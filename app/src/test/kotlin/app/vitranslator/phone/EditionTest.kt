package app.vitranslator.phone

import app.vitranslator.phone.core.EditionDefaults
import app.vitranslator.phone.core.RustCore
import app.vitranslator.phone.ps.Decoder
import app.vitranslator.phone.ps.VideoCodecs
import app.vitranslator.phone.ps.chiaki.Codec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** This edition's own choices: the PS5 picture's codec, the reading's speed, the TV page's port. */
class EditionTest {
    private val fitsFullHd = { w: Int, h: Int -> w <= 1920 && h <= 1080 }
    private val upTo720 = { w: Int, h: Int -> w <= 1280 && h <= 720 }

    @Test
    fun h265_when_the_phone_has_a_hardware_decoder_for_the_picture() {
        val phone = listOf(
            Decoder("c2.qti.avc.decoder", listOf("video/avc"), hardware = true, fits = fitsFullHd),
            Decoder("c2.qti.hevc.decoder", listOf("video/hevc"), hardware = true, fits = fitsFullHd),
        )
        assertEquals(Codec.CODEC_H265, VideoCodecs.pick(phone, 1920, 1080))
    }

    @Test
    fun h264_when_only_a_software_decoder_plays_h265() {
        val phone = listOf(
            Decoder("c2.mtk.avc.decoder", listOf("video/avc"), hardware = true, fits = fitsFullHd),
            Decoder("c2.android.hevc.decoder", listOf("video/hevc"), hardware = false, fits = fitsFullHd),
        )
        assertEquals(Codec.CODEC_H264, VideoCodecs.pick(phone, 1920, 1080))
    }

    @Test
    fun h264_when_the_h265_decoder_is_too_small_for_the_picture() {
        val phone = listOf(Decoder("OMX.hisi.video.decoder.hevc", listOf("video/hevc"), hardware = true, fits = upTo720))
        assertEquals(Codec.CODEC_H264, VideoCodecs.pick(phone, 1920, 1080))
        assertEquals(Codec.CODEC_H265, VideoCodecs.pick(phone, 1280, 720))
    }

    @Test
    fun h264_when_the_phone_has_no_h265_decoder_or_it_failed_here_before() {
        assertEquals(Codec.CODEC_H264, VideoCodecs.pick(emptyList(), 1920, 1080))
        val phone = listOf(Decoder("c2.exynos.hevc.decoder", listOf("VIDEO/HEVC"), hardware = true, fits = fitsFullHd))
        assertEquals(Codec.CODEC_H265, VideoCodecs.pick(phone, 1920, 1080))
        assertEquals(Codec.CODEC_H264, VideoCodecs.pick(phone, 1920, 1080, allowed = false))
    }

    @Test
    fun the_reading_moves_to_this_editions_faster_defaults() {
        val patch = EditionDefaults.patch(JSONObject("""{"interval_ms": 250, "stable_ms": 300, "clear_after_ms": 800, "timing_custom": false}"""))!!
        assertEquals(EditionDefaults.INTERVAL_MS, patch.getInt("interval_ms"))
        assertEquals(EditionDefaults.STABLE_MS, patch.getInt("stable_ms"))
        assertEquals(setOf("interval_ms", "stable_ms"), patch.keys().asSequence().toSet())
        // (as fast as the camera app reads, and settled in about two readings)
        assertEquals(120, EditionDefaults.INTERVAL_MS)
        assertEquals(150, EditionDefaults.STABLE_MS)
    }

    @Test
    fun a_timing_the_player_chose_stays() {
        assertNull(EditionDefaults.patch(JSONObject("""{"interval_ms": 250, "stable_ms": 300, "timing_custom": true}""")))
        // (one value moved off the old default: only the other moves)
        val patch = EditionDefaults.patch(JSONObject("""{"interval_ms": 400, "stable_ms": 300}"""))!!
        assertEquals(setOf("stable_ms"), patch.keys().asSequence().toSet())
        assertNull(EditionDefaults.patch(JSONObject("""{"interval_ms": 400, "stable_ms": 600}""")))
    }

    @Test
    fun the_tv_page_is_not_on_the_camera_apps_port() {
        // (the camera app ซับไทยขึ้นทีวี serves its TV page on 8791 while it runs: both on one phone)
        assertNotEquals(8791, RustCore.PORT)
        assertEquals(8795, RustCore.PORT)
    }
}
