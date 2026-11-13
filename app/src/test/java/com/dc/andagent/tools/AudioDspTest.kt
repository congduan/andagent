package com.dc.andagent.tools

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDspTest {

    private fun approx(a: Double, b: Double, tol: Double) = abs(a - b) <= tol

    /** 频谱窗长取 fftLength(n16)，正弦频率未必精确落在 bin 中心，容忍 ±2 bin。 */
    private fun twoBins(spectrum: AudioDsp.Spectrum) = 2 * spectrum.binHz

    @Test
    fun `pure sine dominates at its frequency`() {
        val pcm = AudioDsp.sinePcm(16_000, 1000.0, 0.5)
        val spec = AudioDsp.spectrum(pcm, 16_000)!!
        val dominant = AudioDsp.dominantFrequency(spec)
        assertTrue("dominant=$dominant", approx(dominant, 1000.0, twoBins(spec)))
    }

    @Test
    fun `mixed sines expose both peaks`() {
        val pcm = AudioDsp.mixPcm(
            AudioDsp.sinePcm(16_000, 500.0, 0.5, 15000),
            AudioDsp.sinePcm(16_000, 3000.0, 0.5, 12000),
        )
        val spec = AudioDsp.spectrum(pcm, 16_000)!!
        val peaks = AudioDsp.topPeaks(spec, 8).map { it.frequencyHz }
        assertTrue("500Hz missing: $peaks", peaks.any { approx(it, 500.0, twoBins(spec)) })
        assertTrue("3000Hz missing: $peaks", peaks.any { approx(it, 3000.0, twoBins(spec)) })
    }

    @Test
    fun `silence yields flat tiny magnitudes`() {
        val pcm = ByteArray(16_000) // 0.5s of zeros
        val spec = AudioDsp.spectrum(pcm, 16_000)!!
        assertTrue(AudioDsp.dominantFrequency(spec) >= 0.0)
        val peaks = AudioDsp.topPeaks(spec, 8)
        peaks.forEach { assertTrue("silence peak too large: ${it.magnitude}", it.magnitude < 1e-3) }
    }

    @Test
    fun `rms of full scale square-ish sine is near 0 dBFS region`() {
        val pcm = AudioDsp.sinePcm(16_000, 1000.0, 0.1, 32767)
        val rms = AudioDsp.rmsDbfs(pcm)
        assertTrue("rms=$rms", rms > -6.0 && rms <= 0.0)
    }

    @Test
    fun `too short input returns null`() {
        assertNull(AudioDsp.spectrum(ByteArray(40), 16_000))
    }

    @Test
    fun `fft length snaps to power of two`() {
        assertEquals(4096, AudioDsp.fftLength(8000))
        assertEquals(64, AudioDsp.fftLength(64))
        assertEquals(64, AudioDsp.fftLength(65))
        assertEquals(1, AudioDsp.fftLength(1))
    }

    @Test
    fun `phase free magnitude of known bin`() {
        // 采样 256 点 @1024Hz 采样率 → 1 秒；信号 64Hz → 精确 bin 16
        val n = 256
        val sampleRate = 1024
        val pcm = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (16000 * sin(2.0 * PI * 64.0 * i / sampleRate)).toInt()
            pcm[i * 2] = (v and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val spec = AudioDsp.spectrum(pcm, sampleRate)!!
        assertEquals(64.0, AudioDsp.dominantFrequency(spec), twoBins(spec))
        assertTrue(AudioDsp.magnitudeAt(spec, 64.0) > 0.1)
    }
}
