package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统内录的纯逻辑（格式归一化 / 秒数裁剪 / 文件命名 / PCM 时长换算）。
 * 真正的采集依赖 MediaProjection + 真机，只能端到端验证。
 */
class SystemAudioCaptureRulesTest {
    @Test fun formatNormalization() {
        assertEquals("m4a", SystemAudioCaptureRules.normalizeFormat(null))
        assertEquals("m4a", SystemAudioCaptureRules.normalizeFormat(""))
        assertEquals("m4a", SystemAudioCaptureRules.normalizeFormat("M4A"))
        assertEquals("m4a", SystemAudioCaptureRules.normalizeFormat(" aac "))
        assertEquals("wav", SystemAudioCaptureRules.normalizeFormat("WAV"))
        assertEquals("wav", SystemAudioCaptureRules.normalizeFormat("pcm"))
        assertNull(SystemAudioCaptureRules.normalizeFormat("mp3"))
    }

    @Test fun secondsClampedToRange() {
        assertEquals(SystemAudioCaptureRules.DEFAULT_SECONDS, SystemAudioCaptureRules.clampSeconds(null))
        assertEquals(1, SystemAudioCaptureRules.clampSeconds(0))
        assertEquals(1, SystemAudioCaptureRules.clampSeconds(-5))
        assertEquals(60, SystemAudioCaptureRules.clampSeconds(60))
        assertEquals(SystemAudioCaptureRules.MAX_SECONDS, SystemAudioCaptureRules.clampSeconds(99_999))
    }

    @Test fun fileNameCarriesPrefixTimestampAndExtension() {
        val name = SystemAudioCaptureRules.fileName("m4a", 0L)
        assertTrue(name, Regex("""^sys_\d{8}_\d{6}_\d{3}\.m4a$""").matches(name))
        assertEquals("wav", SystemAudioCaptureRules.fileName("wav", 1_700_000_000_000L).substringAfterLast('.'))
        assertEquals(SystemAudioCaptureRules.FILE_PREFIX, name.substringBefore('_') + "_")
    }

    @Test fun pcmDurationFollowsWavSampleRate() {
        // 44.1kHz × 立体声 × 16bit = 176400 B/s
        assertEquals(176_400, SystemAudioCaptureRules.BYTES_PER_SECOND)
        assertEquals(1_000L, SystemAudioCaptureRules.pcmDurationMs(176_400))
        assertEquals(500L, SystemAudioCaptureRules.pcmDurationMs(88_200))
        assertEquals(0L, SystemAudioCaptureRules.pcmDurationMs(0))
    }

    @Test fun formatGateIsAndroid10() {
        assertEquals(29, SystemAudioCaptureRules.MIN_SDK)
        assertTrue(SystemAudioCaptureRules.SILENCE_DBFS <= -60.0)
    }
}
