package com.dc.andagent.tools

import android.media.AudioManager
import android.media.audiofx.PresetReverb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R114: pure-logic coverage for the audio effect/focus/spatial helpers.
 * No framework instances, no playback, no permissions — JVM only.
 */
class AudioFxToolsTest {

    /* ------------------------------ effects ----------------------------- */

    @Test
    fun effectsDefaultAndParsing() {
        assertEquals(AudioFxRules.DEFAULT_EFFECTS, AudioFxRules.parseEffects(null))
        assertEquals(AudioFxRules.DEFAULT_EFFECTS, AudioFxRules.parseEffects("  "))
        assertEquals(setOf("eq", "bass"), AudioFxRules.parseEffects("eq, bass"))
        assertEquals(setOf("reverb", "virtualizer"), AudioFxRules.parseEffects("REVERB;virtualizer"))
        assertNull(AudioFxRules.parseEffects("eq,chorus"))
    }

    @Test
    fun bandsParseAndValidate() {
        val bands = AudioFxRules.parseBands("60:4, 230:-2")
        assertEquals(listOf(60 to 400, 230 to -200), bands)
        assertNull(AudioFxRules.parseBands(null))
        assertNull(AudioFxRules.parseBands("60"))          // 缺少 :dB
        assertNull(AudioFxRules.parseBands("10:3"))        // 频率过低
        assertNull(AudioFxRules.parseBands("60:16"))       // 增益越界
        assertNull(AudioFxRules.parseBands("60:abc"))
    }

    @Test
    fun strengthAndVolumeAreClamped() {
        assertEquals(500, AudioFxRules.clampStrength(null))
        assertEquals(0, AudioFxRules.clampStrength(-20))
        assertEquals(1000, AudioFxRules.clampStrength(5_000))
        assertEquals(1f, AudioFxRules.clampVolume(null), 0f)
        assertEquals(0f, AudioFxRules.clampVolume(-1), 0f)
        assertEquals(0.5f, AudioFxRules.clampVolume(50), 0f)
    }

    @Test
    fun presetResolutionSupportsAliasesAndIndex() {
        val names = AudioFxRules.DEFAULT_EQ_PRESETS
        assertEquals(names.indexOf("Rock"), AudioFxRules.presetIndex("rock", names))
        assertEquals(names.indexOf("Heavy Metal"), AudioFxRules.presetIndex("heavy_metal", names))
        assertEquals(names.indexOf("Hip Hop"), AudioFxRules.presetIndex("hiphop", names))
        assertEquals(3, AudioFxRules.presetIndex("3", names))
        assertNull(AudioFxRules.presetIndex("99", names))
        assertNull(AudioFxRules.presetIndex("chorus", names))
        assertNull(AudioFxRules.presetIndex(null, names))
    }

    @Test
    fun nearestBandPicksClosestCenter() {
        val centers = listOf(60, 230, 910, 3_600, 14_000)
        assertEquals(1, AudioFxRules.nearestBand(centers, 200))
        assertEquals(4, AudioFxRules.nearestBand(centers, 20_000))
        assertEquals(0, AudioFxRules.nearestBand(emptyList(), 500))
    }

    @Test
    fun reverbPresetRoundTrip() {
        AudioFxRules.REVERB_PRESETS.forEach { name ->
            val id = AudioFxRules.reverbPresetId(name)
            assertTrue("preset $name", id != null)
            assertEquals(name, AudioFxRules.reverbPresetName(id!!))
        }
        assertEquals(PresetReverb.PRESET_MEDIUMROOM, AudioFxRules.reverbPresetId("medium_room"))
        assertNull(AudioFxRules.reverbPresetId("cathedral"))
    }

    /* ------------------------------- focus ------------------------------ */

    @Test
    fun focusGainMappingAndLabels() {
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, AudioFxRules.focusGainType(null))
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, AudioFxRules.focusGainType("gain_transient"))
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            AudioFxRules.focusGainType("may_duck"),
        )
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            AudioFxRules.focusGainType("exclusive"),
        )
        assertNull(AudioFxRules.focusGainType("forever"))

        listOf(
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
        ).forEach { gain ->
            val name = AudioFxRules.gainTypeName(gain)
            assertEquals(gain, AudioFxRules.focusGainType(name))
        }
    }

    @Test
    fun focusCallbackAndRequestLabels() {
        assertEquals("gain", AudioFxRules.focusChangeLabel(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals("loss", AudioFxRules.focusChangeLabel(AudioManager.AUDIOFOCUS_LOSS))
        assertEquals(
            "loss_transient",
            AudioFxRules.focusChangeLabel(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT),
        )
        assertEquals(
            "loss_transient_can_duck",
            AudioFxRules.focusChangeLabel(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),
        )
        assertEquals(
            "granted",
            AudioFxRules.focusRequestResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED),
        )
        assertEquals(
            "delayed",
            AudioFxRules.focusRequestResult(AudioManager.AUDIOFOCUS_REQUEST_DELAYED),
        )
        assertEquals("change_42", AudioFxRules.focusChangeLabel(42))
    }

    @Test
    fun usageRoundTrip() {
        listOf("media", "voice_communication", "assistant", "game", "notification", "alarm", "navigation")
            .forEach { name ->
                val value = AudioFxRules.usageValue(name)
                assertEquals(name, AudioFxRules.usageName(name))
                assertEquals(value, AudioFxRules.usageValue(AudioFxRules.usageName(name)))
            }
        assertEquals(android.media.AudioAttributes.USAGE_MEDIA, AudioFxRules.usageValue("unknown"))
    }
}
