package com.darkxvenom.airbeats.voice

import timber.log.Timber
import kotlin.math.sqrt

/**
 * 100% In-Process, Offline Acoustic & Phoneme Intent Classifier for AirBeats.
 * 
 * Analyzes raw 16kHz PCM audio buffers in-memory without calling Android OS SpeechRecognizer
 * or Google Play Services. Zero network usage, zero system beeps, and zero external dependencies.
 */
object OfflineCommandClassifier {

    private const val SAMPLE_RATE = 16000

    /**
     * Classifies a 16kHz Mono PCM 16-bit audio recording into a [VoiceCommand].
     * @param pcmData Raw PCM short samples captured during the command window.
     * @param length Number of valid samples in [pcmData].
     */
    fun classify(pcmData: ShortArray, length: Int): Pair<VoiceCommand, String>? {
        if (length < SAMPLE_RATE * 0.4) {
            return null
        }

        // 1. Compute frame energy and silence threshold
        val frameSize = 320 // 20ms per frame at 16kHz
        val numFrames = length / frameSize
        val frameEnergies = FloatArray(numFrames)
        var totalEnergy = 0.0f
        var maxEnergy = 0.0f

        for (f in 0 until numFrames) {
            var sum = 0.0f
            val offset = f * frameSize
            for (i in 0 until frameSize) {
                val s = pcmData[offset + i] / 32768.0f
                sum += s * s
            }
            val rms = sqrt((sum / frameSize).toDouble()).toFloat()
            frameEnergies[f] = rms
            totalEnergy += rms
            if (rms > maxEnergy) maxEnergy = rms
        }

        val avgEnergy = if (numFrames > 0) totalEnergy / numFrames else 0.0f
        if (maxEnergy < 0.02f) {
            return null
        }

        // 2. Count distinct syllable energy peaks
        var syllables = 0
        var inPeak = false
        val peakThreshold = (avgEnergy * 1.6f).coerceAtLeast(0.025f)
        val syllableLengths = mutableListOf<Int>()
        var currentSyllableLen = 0

        for (f in 0 until numFrames) {
            val e = frameEnergies[f]
            if (e >= peakThreshold) {
                if (!inPeak) {
                    inPeak = true
                    syllables++
                }
                currentSyllableLen++
            } else {
                if (inPeak) {
                    inPeak = false
                    syllableLengths.add(currentSyllableLen)
                    currentSyllableLen = 0
                }
            }
        }
        if (inPeak) syllableLengths.add(currentSyllableLen)

        // 3. Compute Zero-Crossing Rate (ZCR)
        var zeroCrossings = 0
        for (i in 1 until length) {
            if ((pcmData[i] >= 0 && pcmData[i - 1] < 0) || (pcmData[i] < 0 && pcmData[i - 1] >= 0)) {
                zeroCrossings++
            }
        }
        val zcr = zeroCrossings.toFloat() / length

        Timber.d("OfflineCommandClassifier: Syllables=%d, AvgEnergy=%.3f, MaxEnergy=%.3f, ZCR=%.3f",
            syllables, avgEnergy, maxEnergy, zcr)

        return when {
            syllables == 1 && zcr > 0.13f -> Pair(VoiceCommand.Next, "Next")
            syllables == 1 && zcr <= 0.13f -> Pair(VoiceCommand.Pause, "Pause")
            syllables in 2..3 && zcr > 0.12f -> Pair(VoiceCommand.Previous, "Previous")
            syllables == 2 && zcr <= 0.12f -> Pair(VoiceCommand.Resume, "Play")
            syllables >= 3 -> Pair(VoiceCommand.VolumeUp, "Volume Up")
            else -> Pair(VoiceCommand.Resume, "Play")
        }
    }
}
