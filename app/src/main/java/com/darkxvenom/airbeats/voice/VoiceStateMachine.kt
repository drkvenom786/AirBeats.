package com.darkxvenom.airbeats.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Authoritative Serialized State Machine for the AirBeats Voice Assistant.
 * 
 * States:
 * DISABLED -> INITIALIZING -> WAKE_WORD <-> COMMAND_LISTEN -> EXECUTING -> WAKE_WORD
 */
class VoiceStateMachine(
    private val scope: CoroutineScope,
    private val onStateChanged: (VoiceState) -> Unit
) {

    private val _state = MutableStateFlow(VoiceState.DISABLED)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var timeoutJob: Job? = null

    companion object {
        private const val COMMAND_TIMEOUT_MS = 3200L
        private const val EXECUTE_COOLDOWN_MS = 800L
    }

    @Synchronized
    fun transitionTo(newState: VoiceState) {
        val oldState = _state.value
        if (oldState == newState) return

        Timber.i("VoiceStateMachine: Transition [%s] -> [%s]", oldState, newState)
        _state.value = newState
        onStateChanged(newState)

        timeoutJob?.cancel()
        timeoutJob = null

        if (newState == VoiceState.COMMAND_LISTEN) {
            // Start timeout countdown to return to WAKE_WORD if no command is heard
            timeoutJob = scope.launch(Dispatchers.Default) {
                delay(COMMAND_TIMEOUT_MS)
                if (_state.value == VoiceState.COMMAND_LISTEN) {
                    Timber.d("VoiceStateMachine: Command listen timeout reached, reverting to WAKE_WORD")
                    transitionTo(VoiceState.WAKE_WORD)
                }
            }
        }
    }

    fun onWakeWordDetected() {
        if (_state.value == VoiceState.WAKE_WORD) {
            transitionTo(VoiceState.COMMAND_LISTEN)
        }
    }

    fun onCommandDetected(command: AirBeatsCommand) {
        if (_state.value == VoiceState.COMMAND_LISTEN) {
            if (command == AirBeatsCommand.NONE) {
                transitionTo(VoiceState.WAKE_WORD)
            } else {
                transitionTo(VoiceState.EXECUTING)
                scope.launch(Dispatchers.Default) {
                    delay(EXECUTE_COOLDOWN_MS)
                    if (_state.value == VoiceState.EXECUTING) {
                        transitionTo(VoiceState.WAKE_WORD)
                    }
                }
            }
        }
    }

    fun reset() {
        timeoutJob?.cancel()
        timeoutJob = null
        _state.value = VoiceState.DISABLED
    }
}
