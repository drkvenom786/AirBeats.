package com.darkxvenom.airbeats.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import timber.log.Timber
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Single Owner of the AudioRecord hardware pipeline for AirBeats.
 * 
 * Strict Production Rules:
 * 1. ONE AudioRecord instance initialized once on service startup.
 * 2. ONE capture loop on a dedicated audio thread.
 * 3. Never restarted during state changes.
 * 4. Implements AudioFrameBuffer (ring buffer) to decouple hardware reads from ML inference windows.
 */
class VoiceAudioCapture(
    private val onAudioFrame: (ShortArray, Int) -> Unit,
    private val onRmsCalculated: (Float) -> Unit
) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val READ_CHUNK_SIZE = 1024
        private const val RING_BUFFER_CAPACITY = 16000 * 2 // 2.0 seconds capacity
    }

    private var audioRecord: AudioRecord? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @Volatile
    private var isCapturing = false
    private var captureThread: Thread? = null

    // Ring buffer
    private val ringBuffer = ShortArray(RING_BUFFER_CAPACITY)
    private var writeHead = 0
    private var readHead = 0
    private var availableSamples = 0
    private val bufferLock = Object()

    @SuppressLint("MissingPermission")
    fun start() {
        if (isCapturing) return
        isCapturing = true

        captureThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = (minBufSize * 2).coerceAtLeast(READ_CHUNK_SIZE * 4)

            while (isCapturing) {
                try {
                    val record = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                    )

                    if (record.state != AudioRecord.STATE_INITIALIZED) {
                        record.release()
                        Timber.w("VoiceAudioCapture: AudioRecord not initialized, retrying in 500ms...")
                        try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                        continue
                    }

                    // Attach Hardware Acoustic Effects
                    try {
                        if (AcousticEchoCanceler.isAvailable()) {
                            acousticEchoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                        }
                        if (AutomaticGainControl.isAvailable()) {
                            automaticGainControl = AutomaticGainControl.create(record.audioSessionId)?.apply { enabled = true }
                        }
                        if (NoiseSuppressor.isAvailable()) {
                            noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
                        }
                    } catch (_: Exception) {}

                    audioRecord = record
                    record.startRecording()
                    Timber.i("VoiceAudioCapture: Hardware 16kHz microphone capture active")

                    val readBuffer = ShortArray(READ_CHUNK_SIZE)

                    while (isCapturing) {
                        val readCount = record.read(readBuffer, 0, READ_CHUNK_SIZE)
                        if (readCount > 0) {
                            // Compute RMS
                            var sum = 0.0
                            for (i in 0 until readCount) {
                                val s = readBuffer[i].toInt()
                                sum += s * s
                            }
                            val rms = sqrt(sum / readCount)
                            val db = if (rms > 0) (20 * log10(rms / 32767.0) + 90.0).coerceAtLeast(0.0).toFloat() else 0.0f
                            onRmsCalculated(db)

                            // Dispatch audio chunk to inference dispatcher
                            onAudioFrame(readBuffer, readCount)

                            // Push to ring buffer
                            pushToRingBuffer(readBuffer, readCount)
                        } else if (readCount < 0) {
                            Timber.w("VoiceAudioCapture: AudioRecord read error: %d", readCount)
                        }
                    }

                    releaseEffects()
                    try {
                        record.stop()
                        record.release()
                    } catch (_: Exception) {}
                    audioRecord = null
                } catch (_: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    Timber.e(e, "VoiceAudioCapture: Capture loop exception, recovering...")
                    releaseEffects()
                    try { audioRecord?.release() } catch (_: Exception) {}
                    audioRecord = null
                    if (!isCapturing) break
                    try { Thread.sleep(300) } catch (_: InterruptedException) { break }
                }
            }
        }, "AirBeats-VoiceAudioCapture-Thread").apply {
            start()
        }
    }

    private fun pushToRingBuffer(data: ShortArray, count: Int) {
        synchronized(bufferLock) {
            for (i in 0 until count) {
                ringBuffer[writeHead] = data[i]
                writeHead = (writeHead + 1) % RING_BUFFER_CAPACITY
                if (availableSamples < RING_BUFFER_CAPACITY) {
                    availableSamples++
                } else {
                    readHead = (readHead + 1) % RING_BUFFER_CAPACITY
                }
            }
        }
    }

    /**
     * Reads a snapshot of the most recent [sampleCount] samples from the ring buffer.
     */
    fun getRecentAudioSnapshot(sampleCount: Int): ShortArray {
        val out = ShortArray(sampleCount)
        synchronized(bufferLock) {
            val count = sampleCount.coerceAtMost(availableSamples)
            var startIdx = (writeHead - count + RING_BUFFER_CAPACITY) % RING_BUFFER_CAPACITY
            for (i in 0 until count) {
                out[i] = ringBuffer[startIdx]
                startIdx = (startIdx + 1) % RING_BUFFER_CAPACITY
            }
        }
        return out
    }

    private fun releaseEffects() {
        try {
            acousticEchoCanceler?.release()
            acousticEchoCanceler = null
            automaticGainControl?.release()
            automaticGainControl = null
            noiseSuppressor?.release()
            noiseSuppressor = null
        } catch (_: Exception) {}
    }

    fun stop() {
        isCapturing = false
        releaseEffects()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        try {
            captureThread?.interrupt()
        } catch (_: Exception) {}
        captureThread = null
        Timber.i("VoiceAudioCapture: Hardware capture stopped")
    }
}
