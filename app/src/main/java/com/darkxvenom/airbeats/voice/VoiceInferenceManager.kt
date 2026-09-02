package com.darkxvenom.airbeats.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * High-Performance, Persistent Inference Manager for AirBeats Voice.
 * 
 * Rules:
 * 1. Persistent ML Engines: WakeWordEngine & CommandEngine are initialized once and kept alive.
 * 2. State-Based Routing: Routes audio stream to the appropriate engine without allocating/destroying sessions.
 * 3. Decoupled Event Flow: Emits VoiceEvents via SharedFlow so audio thread is never blocked.
 */
class VoiceInferenceManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    // Persistent Engines
    private var wakeWordEngine: OnnxWakeWordEngine? = null

    // Command Capture State (during COMMAND_LISTEN)
    private val commandMaxSamples = 16000 * 3 // 3.0 seconds max
    private val commandBuffer = ShortArray(commandMaxSamples)
    private var commandSamplesCount = 0
    private var commandListeningStartTime = 0L

    init {
        initializeEngines()
    }

    private fun initializeEngines() {
        wakeWordEngine = OnnxWakeWordEngine(context)
        Timber.i("VoiceInferenceManager: Initialized persistent ONNX wake-word and acoustic command engines")
    }

    /**
     * Called when the State Machine transitions to COMMAND_LISTEN.
     */
    fun startCommandListening() {
        commandSamplesCount = 0
        commandListeningStartTime = System.currentTimeMillis()
        Timber.d("VoiceInferenceManager: Command listening window open")
    }

    /**
     * Feeds 16kHz PCM audio frame from VoiceAudioCapture into the state-routed inference pipeline.
     */
    fun onAudioFrame(buffer: ShortArray, count: Int, currentState: VoiceState) {
        when (currentState) {
            VoiceState.WAKE_WORD -> {
                val detected = wakeWordEngine?.process(buffer, count) == true
                if (detected) {
                    Timber.i("VoiceInferenceManager: 'Hey AirBeats' Wake Word Detected!")
                    scope.launch(Dispatchers.Default) {
                        _events.emit(VoiceEvent.WakeWordDetected)
                    }
                }
            }

            VoiceState.COMMAND_LISTEN -> {
                // Accumulate audio in memory
                val toCopy = count.coerceAtMost(commandMaxSamples - commandSamplesCount)
                if (toCopy > 0) {
                    System.arraycopy(buffer, 0, commandBuffer, commandSamplesCount, toCopy)
                    commandSamplesCount += toCopy
                }

                val elapsed = System.currentTimeMillis() - commandListeningStartTime

                // Evaluate command after capturing 2.0s of audio or buffer full
                if (commandSamplesCount >= 16000 * 2 || elapsed >= 2500L) {
                    evaluateCommand()
                }
            }

            VoiceState.EXECUTING, VoiceState.INITIALIZING, VoiceState.DISABLED -> {
                // No inference needed, drop audio to save CPU/battery
            }
        }
    }

    private fun evaluateCommand() {
        if (commandSamplesCount <= 0) return

        val result = OfflineCommandClassifier.classify(commandBuffer, commandSamplesCount)
        commandSamplesCount = 0

        if (result != null) {
            val (command, label) = result
            val airBeatsCmd = when (command) {
                is VoiceCommand.NextTrack -> AirBeatsCommand.NEXT
                is VoiceCommand.PreviousTrack -> AirBeatsCommand.PREVIOUS
                is VoiceCommand.Pause -> AirBeatsCommand.PAUSE
                is VoiceCommand.Resume -> AirBeatsCommand.RESUME
                is VoiceCommand.VolumeUp -> AirBeatsCommand.VOLUME_UP
                is VoiceCommand.VolumeDown -> AirBeatsCommand.VOLUME_DOWN
                is VoiceCommand.ToggleLike -> AirBeatsCommand.TOGGLE_LIKE
                is VoiceCommand.StartRadio -> AirBeatsCommand.START_RADIO
                is VoiceCommand.PlayGenericMusic -> AirBeatsCommand.PLAY_GENERIC
                is VoiceCommand.PlayCachedSongs -> AirBeatsCommand.PLAY_CACHED
                is VoiceCommand.PlayLikedSongs -> AirBeatsCommand.PLAY_LIKED
                else -> AirBeatsCommand.PLAY
            }

            Timber.i("VoiceInferenceManager: Classified Command: %s (%s)", airBeatsCmd, label)
            scope.launch(Dispatchers.Default) {
                _events.emit(VoiceEvent.CommandDetected(airBeatsCmd, rawText = label))
            }
        } else {
            // Default to resume if speech energy was present
            Timber.d("VoiceInferenceManager: Command window completed without high-confidence match")
            scope.launch(Dispatchers.Default) {
                _events.emit(VoiceEvent.CommandDetected(AirBeatsCommand.NONE))
            }
        }
    }

    fun release() {
        wakeWordEngine?.release()
        wakeWordEngine = null
        Timber.i("VoiceInferenceManager: Released persistent inference engines")
    }
}
