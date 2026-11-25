package app.vitranslator.phone.ps

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import app.vitranslator.phone.ps.chiaki.Codec

/** A video decoder on this phone (MediaCodecList), as far as choosing the PS5 stream's codec goes. */
class Decoder(val name: String, val types: List<String>, val hardware: Boolean, val fits: (width: Int, height: Int) -> Boolean)

/**
 * The PS5 picture's codec (this edition): H.265 where the phone has a hardware decoder for it at the
 * stream's size, as it is sharper than H.264 at the same bitrate (small subtitles read better), else
 * H.264, which every phone plays. A software H.265 decoder is too slow for a 1080p stream on most phones.
 */
object VideoCodecs {
    private const val HEVC = "video/hevc"

    fun pick(decoders: List<Decoder>, width: Int, height: Int, allowed: Boolean = true): Codec {
        if (!allowed) return Codec.CODEC_H264
        val h265 = decoders.any { d -> d.hardware && d.types.any { it.equals(HEVC, ignoreCase = true) } && d.fits(width, height) }
        return if (h265) Codec.CODEC_H265 else Codec.CODEC_H264
    }

    /** This phone's video decoders (none when Android won't list them). */
    fun onThisPhone(): List<Decoder> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }.map { info ->
            Decoder(info.name, info.supportedTypes.toList(), hardware(info)) { w, h -> fits(info, w, h) }
        }
    } catch (e: RuntimeException) {
        emptyList()
    }

    private fun hardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated && !info.isSoftwareOnly
        // (before Android 10: Google's own decoders are the software ones)
        val n = info.name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."))
    }

    private fun fits(info: MediaCodecInfo, width: Int, height: Int): Boolean = try {
        info.getCapabilitiesForType(HEVC).videoCapabilities?.areSizeAndRateSupported(width, height, 30.0) == true
    } catch (e: IllegalArgumentException) {
        false
    }
}
