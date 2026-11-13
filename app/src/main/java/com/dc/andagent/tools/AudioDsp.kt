package com.dc.andagent.tools

import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R152d: audio DSP toolkit — pure-JVM radix-2 FFT + peak extraction over PCM16LE
 * captured by HardwareStatusTools.recordWavEx (16k mono pipeline, reused as-is).
 *
 * Kotlin implementation instead of native C: at 16 kHz a 2 s capture is 32k
 * samples; a 512-point windowed FFT runs in low single-digit ms on big cores,
 * so a JNI round-trip buys nothing and costs APK weight + build complexity.
 * Honesty note: if later use cases need real-time streaming DSP (continuous
 * monitoring), revisit native.
 */
object AudioDsp {

    /** Nearest power-of-two length ≤ n used for analysis windows. */
    fun fftLength(n: Int): Int {
        var p = 1
        while (p * 2 <= n) p *= 2
        return p
    }

    /**
     * In-place iterative radix-2 Cooley-Tukey FFT over re/im pairs of length
     * powerOfTwo. No bit-reversal table allocation per call (computed inline).
     */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && n >= 1 && (n and (n - 1)) == 0) { "FFT 长度必须是 2 的幂" }
        // bit reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * PCM16LE mono → per-bin magnitudes of the largest power-of-two window
     * (Hann-windowed), bins 0..n/2. Returns null when too few samples.
     */
    fun spectrum(pcm: ByteArray, sampleRate: Int): Spectrum? {
        val n16 = pcm.size / 2
        if (n16 < 64) return null
        val n = fftLength(n16)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        // Hann window + little-endian sample decode, take the first n samples
        for (i in 0 until n) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            val w = 0.5 * (1 - cos(2.0 * PI * i / (n - 1)))
            re[i] = sample * w
        }
        fft(re, im)
        val bins = n / 2
        val mags = DoubleArray(bins)
        for (k in 0 until bins) mags[k] = hypot(re[k], im[k]) / n
        val binHz = sampleRate.toDouble() / n
        return Spectrum(binHz, mags)
    }

    data class Spectrum(val binHz: Double, val magnitudes: DoubleArray)

    data class Peak(val frequencyHz: Double, val magnitude: Double)

    /** Top-N non-DC peaks with ±2-bin local-maximum filter (peaks, not noise). */
    fun topPeaks(spectrum: Spectrum, topN: Int = 8): List<Peak> {
        val m = spectrum.magnitudes
        val candidates = mutableListOf<Peak>()
        for (k in 2 until m.size - 2) {
            val v = m[k]
            if (v > m[k - 1] && v > m[k + 1] && v >= m[k - 2] && v >= m[k + 2]) {
                candidates.add(Peak(k * spectrum.binHz, v))
            }
        }
        candidates.sortByDescending { it.magnitude }
        // 邻近合并：峰值频率差 < 2 bin 视为同一峰
        val picked = mutableListOf<Peak>()
        for (c in candidates) {
            if (picked.none { abs(it.frequencyHz - c.frequencyHz) < spectrum.binHz * 2 }) {
                picked.add(c)
                if (picked.size >= topN) break
            }
        }
        return picked
    }

    fun dominantFrequency(spectrum: Spectrum): Double =
        topPeaks(spectrum, 1).firstOrNull()?.frequencyHz ?: 0.0

    fun magnitudeAt(spectrum: Spectrum, frequencyHz: Double): Double {
        val k = (frequencyHz / spectrum.binHz).toInt().coerceIn(0, spectrum.magnitudes.size - 1)
        return spectrum.magnitudes[k]
    }

    fun rmsDbfs(pcm: ByteArray): Double {
        var sum = 0.0
        var count = 0L
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        if (count == 0L) return -120.0
        val rms = sqrt(sum / count)
        return if (rms <= 0.0) -120.0 else maxOf(-120.0, 20.0 * log10(rms / 32768.0))
    }

    // ------- test-friendly generators (also used by unit tests) -------

    fun sinePcm(sampleRate: Int, freqHz: Double, seconds: Double, amplitude: Int = 20000): ByteArray {
        val count = (sampleRate * seconds).toInt()
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = (amplitude * sin(2.0 * PI * freqHz * i / sampleRate)).toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun mixPcm(vararg pcms: ByteArray): ByteArray {
        val maxLen = pcms.maxOf { it.size }
        val out = ByteArray(maxLen)
        // 简单饱和相加（16bit LE 成对处理）
        val acc = IntArray(maxLen / 2)
        for (pcm in pcms) {
            for (i in 0 until pcm.size / 2) {
                val lo = pcm[i * 2].toInt() and 0xFF
                val hi = pcm[i * 2 + 1].toInt()
                acc[i] += (hi shl 8) or lo
            }
        }
        for (i in 0 until out.size / 2) {
            val v = acc[i].coerceIn(-32768, 32767)
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
