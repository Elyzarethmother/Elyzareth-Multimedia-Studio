package com.example.media

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Generates real playable 44.1kHz 16-bit PCM WAV audio files and raw PCM buffers
 * ONLY for built-in studio demonstration presets.
 * Never substituted for imported user MP3/audio files.
 */
object SynthAudioGenerator {
    const val SAMPLE_RATE = 44100

    /**
     * Generates 16-bit mono PCM samples for a built-in demonstration preset and duration in seconds.
     */
    fun generatePcmSamples(presetIndex: Int, durationSec: Int = 12): ShortArray {
        val totalSamples = SAMPLE_RATE * durationSec
        val samples = ShortArray(totalSamples)

        val bpm = when (presetIndex % 3) {
            0 -> 124.0
            1 -> 110.0
            else -> 132.0
        }
        val beatDurationSec = 60.0 / bpm

        val bassNotes = when (presetIndex % 3) {
            0 -> doubleArrayOf(110.0, 87.31, 130.81, 98.0)
            1 -> doubleArrayOf(98.0, 116.54, 77.78, 87.31)
            else -> doubleArrayOf(130.81, 146.83, 110.0, 123.47)
        }

        val leadNotes = when (presetIndex % 3) {
            0 -> doubleArrayOf(440.0, 523.25, 659.25, 587.33, 440.0, 659.25, 523.25, 392.0)
            1 -> doubleArrayOf(392.0, 466.16, 587.33, 523.25, 392.0, 349.23, 311.13, 293.66)
            else -> doubleArrayOf(523.25, 659.25, 783.99, 659.25, 587.33, 783.99, 880.0, 783.99)
        }

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            val beatPos = (t / beatDurationSec)
            val beatIndex = beatPos.toInt()
            val beatFrac = beatPos - beatIndex

            val kickFreq = 52.0 + 110.0 * exp(-beatFrac * 24.0)
            val kickEnv = exp(-beatFrac * 7.5)
            val kick = sin(2.0 * PI * kickFreq * beatFrac * beatDurationSec) * kickEnv * 0.48

            val barIndex = (beatIndex / 4) % bassNotes.size
            val bassFreq = bassNotes[barIndex]
            val subBass = sin(2.0 * PI * bassFreq * t) * 0.25
            val sawHarmonic = sin(2.0 * PI * bassFreq * 2.0 * t) * 0.12

            val arpIndex = ((beatPos * 2.0).toInt()) % leadNotes.size
            val arpFrac = (beatPos * 2.0) - (beatPos * 2.0).toInt()
            val arpEnv = exp(-arpFrac * 4.2)
            val leadFreq = leadNotes[arpIndex]
            val lead = (sin(2.0 * PI * leadFreq * t) + 0.3 * sin(2.0 * PI * leadFreq * 1.5 * t)) * arpEnv * 0.22

            val offBeatFrac = (beatFrac + 0.5) % 1.0
            val hatEnv = exp(-offBeatFrac * 28.0)
            val pseudoNoise = sin(i * 1.73205) * sin(i * 2.64575)
            val hat = pseudoNoise * hatEnv * 0.08

            val fade = when {
                t < 0.4 -> (t / 0.4)
                t > durationSec - 0.8 -> ((durationSec - t) / 0.8).coerceAtLeast(0.0)
                else -> 1.0
            }

            val mixed = ((kick + subBass + sawHarmonic + lead + hat) * fade).coerceIn(-0.95, 0.95)
            samples[i] = (mixed * Short.MAX_VALUE).toInt().toShort()
        }
        return samples
    }

    /**
     * Writes a valid RIFF WAVE file from 16-bit mono PCM samples so MediaPlayer can play it natively.
     */
    fun writeWavFile(outputFile: File, samples: ShortArray) {
        val byteDataSize = samples.size * 2
        val totalHeaderSize = 36 + byteDataSize

        FileOutputStream(outputFile).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(totalHeaderSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1)
            header.putShort(1)
            header.putInt(SAMPLE_RATE)
            header.putInt(SAMPLE_RATE * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(byteDataSize)
            fos.write(header.array())

            val pcmBuffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            var idx = 0
            while (idx < samples.size) {
                pcmBuffer.clear()
                val chunk = minOf(4096, samples.size - idx)
                for (j in 0 until chunk) {
                    pcmBuffer.putShort(samples[idx + j])
                }
                fos.write(pcmBuffer.array(), 0, chunk * 2)
                idx += chunk
            }
        }
    }
}
