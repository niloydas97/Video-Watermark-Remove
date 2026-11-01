package app.wmremover

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

object Detector {
    /** Samples frames across the video and finds static logos. Returns a 0/1 mask in upright (uw x uh) space. */
    fun scan(
        ctx: Context, uri: Uri, uw: Int, uh: Int, sensitivity: Float,
        onProgress: (Int, Int) -> Unit, cancelled: () -> Boolean
    ): ByteArray? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val s = minOf(1f, 640f / maxOf(uw, uh))
            val tw = maxOf(16, (uw * s).toInt()) and 1.inv()
            val th = maxOf(16, (uh * s).toInt()) and 1.inv()
            val samples = 24
            val grays = ArrayList<ByteArray>()
            var sw = 0
            var sh = 0
            for (i in 0 until samples) {
                if (cancelled()) return null
                val tUs = ((i + 0.5) / samples * durMs * 1000).toLong()
                val bmp = r.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
                onProgress(i + 1, samples)
                if (bmp == null) continue
                if (sw == 0) { sw = bmp.width; sh = bmp.height }
                if (bmp.width != sw || bmp.height != sh) { bmp.recycle(); continue }
                val px = IntArray(sw * sh)
                bmp.getPixels(px, 0, sw, 0, 0, sw, sh)
                bmp.recycle()
                grays.add(ByteArray(sw * sh) {
                    val p = px[it]
                    ((((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8).toByte()
                })
            }
            val small = detectStatic(grays, sw, sh, sensitivity) ?: return null
            return resizeMaskNearest(small, sw, sh, uw, uh)
        } finally {
            r.release()
        }
    }
}
