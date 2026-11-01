package app.wmremover

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.roundToInt

class CancelledException : RuntimeException("Cancelled")

/**
 * Hardware decode -> patch only the watermark pixels (in YUV, via LaMa) -> hardware encode.
 * Untouched pixels are copied byte-for-byte from the decoder, so only the encode step costs quality
 * (use a high bitrate multiplier to keep that invisible). Audio is copied without re-encoding.
 */
class Transcoder(private val ctx: Context, private val lama: Lama) {
    @Volatile var cancelled = false

    private fun fpsOf(f: MediaFormat): Float = try {
        f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
    } catch (e: Exception) {
        try { f.getFloat(MediaFormat.KEY_FRAME_RATE) } catch (e2: Exception) { 30f }
    }

    private fun rd(p: Image.Plane, row: Int, col: Int): Int =
        p.buffer.get(row * p.rowStride + col * p.pixelStride).toInt() and 255

    private fun wr(p: Image.Plane, row: Int, col: Int, v: Int) {
        p.buffer.put(row * p.rowStride + col * p.pixelStride, v.toByte())
    }

    private fun copyPlane(s: Image.Plane, d: Image.Plane, w: Int, h: Int, sx0: Int, sy0: Int) {
        val sb = s.buffer.duplicate()
        val db = d.buffer.duplicate()
        val srs = s.rowStride; val sps = s.pixelStride
        val drs = d.rowStride; val dps = d.pixelStride
        if (sps == 1 && dps == 1) {
            for (y in 0 until h) {
                val so = (sy0 + y) * srs + sx0
                sb.limit(so + w)
                sb.position(so)
                db.position(y * drs)
                db.put(sb)
            }
        } else {
            for (y in 0 until h) {
                var so = (sy0 + y) * srs + sx0 * sps
                var dO = y * drs
                for (x in 0 until w) {
                    db.put(dO, sb.get(so))
                    so += sps
                    dO += dps
                }
            }
        }
    }

    private fun copyFrame(src: Image, dst: Image, w: Int, h: Int) {
        val cl = src.cropRect.left
        val ct = src.cropRect.top
        copyPlane(src.planes[0], dst.planes[0], w, h, cl, ct)
        copyPlane(src.planes[1], dst.planes[1], w / 2, h / 2, cl / 2, ct / 2)
        copyPlane(src.planes[2], dst.planes[2], w / 2, h / 2, cl / 2, ct / 2)
    }

    private fun patch(src: Image, dst: Image, rg: Region, yuv: YuvMath) {
        val sp = src.planes
        val dp = dst.planes
        val cl = src.cropRect.left
        val ct = src.cropRect.top
        val side = rg.side
        val rgb = IntArray(side * side)
        for (yy in 0 until side) {
            val y = rg.y0 + yy
            for (xx in 0 until side) {
                val x = rg.x0 + xx
                rgb[yy * side + xx] = yuv.toRgb(
                    rd(sp[0], ct + y, cl + x),
                    rd(sp[1], (ct + y) / 2, (cl + x) / 2),
                    rd(sp[2], (ct + y) / 2, (cl + x) / 2)
                )
            }
        }
        val out = inpaintRegion(rgb, rg) { a, b -> lama.infer(a, b) }
        for (yy in 0 until side) {
            val y = rg.y0 + yy
            for (xx in 0 until side) {
                val i = yy * side + xx
                if (rg.alpha[i] <= 0.001f) continue
                val x = rg.x0 + xx
                val p = out[i]
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                wr(dp[0], y, x, yuv.yOf(r, g, b))
                if ((x and 1) == 0 && (y and 1) == 0) {
                    wr(dp[1], y / 2, x / 2, yuv.uOf(r, g, b))
                    wr(dp[2], y / 2, x / 2, yuv.vOf(r, g, b))
                }
            }
        }
    }

    fun run(
        src: Uri, outPath: String, uprightMask: ByteArray, uw: Int, uh: Int, grow: Int,
        bitrateMul: Float, startUs: Long, endUs: Long,
        onProgress: (done: Int, total: Int, msPerFrame: Long) -> Unit
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(ctx, src, null)
        var vIdx = -1
        var aIdx = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && vIdx < 0) vIdx = i
            else if (mime.startsWith("audio/") && aIdx < 0) aIdx = i
        }
        if (vIdx < 0) throw IOException("No video track found")
        extractor.selectTrack(vIdx)
        val vf = extractor.getTrackFormat(vIdx)
        val mime = vf.getString(MediaFormat.KEY_MIME)!!
        val W = vf.getInteger(MediaFormat.KEY_WIDTH)
        val H = vf.getInteger(MediaFormat.KEY_HEIGHT)
        val rotation = if (vf.containsKey(MediaFormat.KEY_ROTATION)) vf.getInteger(MediaFormat.KEY_ROTATION) else 0
        val fps = fpsOf(vf)
        val durationUs = if (vf.containsKey(MediaFormat.KEY_DURATION)) vf.getLong(MediaFormat.KEY_DURATION) else 0L
        if (W % 2 != 0 || H % 2 != 0) throw IOException("Odd video dimensions are not supported")

        // mask: upright (what the user painted) -> raw frame orientation
        var m = uprightMask
        var mw = uw
        var mh = uh
        repeat(((360 - rotation) / 90) % 4) {
            m = rotateMaskCw(m, mw, mh)
            val t = mw; mw = mh; mh = t
        }
        m = resizeMaskNearest(m, mw, mh, W, H)
        val regions = planRegions(m, W, H, grow)
        if (regions.isEmpty()) throw IOException("Nothing is marked")

        val retr = MediaMetadataRetriever()
        retr.setDataSource(ctx, src)
        val srcBr = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
            ?: (W.toLong() * H * fps * 0.1).toInt()
        retr.release()
        val bitrate = (srcBr * bitrateMul).toInt().coerceIn(2_000_000, 100_000_000)

        val effDur = (if (endUs > 0 && durationUs > 0) minOf(endUs, durationUs) else if (endUs > 0) endUs else durationUs) - startUs
        val total = maxOf(1, (effDur / 1_000_000.0 * fps).toInt())

        var yuv = YuvMath(H >= 720, false)
        fun updateColor(f: MediaFormat) {
            val std = if (f.containsKey(MediaFormat.KEY_COLOR_STANDARD)) f.getInteger(MediaFormat.KEY_COLOR_STANDARD) else 0
            val rng = if (f.containsKey(MediaFormat.KEY_COLOR_RANGE)) f.getInteger(MediaFormat.KEY_COLOR_RANGE) else 0
            val bt709 = when (std) {
                MediaFormat.COLOR_STANDARD_BT709 -> true
                MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> false
                else -> H >= 720
            }
            yuv = YuvMath(bt709, rng == MediaFormat.COLOR_RANGE_FULL)
        }
        updateColor(vf)

        val decoder = MediaCodec.createDecoderByType(mime)
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        decoder.configure(vf, null, null, 0)
        decoder.start()

        val outMime = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val ef = MediaFormat.createVideoFormat(outMime, W, H)
        ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        ef.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt().coerceAtLeast(1))
        ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        ef.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        if (vf.containsKey(MediaFormat.KEY_COLOR_STANDARD)) ef.setInteger(MediaFormat.KEY_COLOR_STANDARD, vf.getInteger(MediaFormat.KEY_COLOR_STANDARD))
        else if (H >= 720) ef.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        if (vf.containsKey(MediaFormat.KEY_COLOR_RANGE)) ef.setInteger(MediaFormat.KEY_COLOR_RANGE, vf.getInteger(MediaFormat.KEY_COLOR_RANGE))
        else ef.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        val encoder = MediaCodec.createEncoderByType(outMime)
        encoder.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)
        val audioFormat = if (aIdx >= 0) extractor.getTrackFormat(aIdx) else null
        var videoTrack = -1
        var audioTrack = -1
        var muxerStarted = false
        var encDone = false
        val encInfo = MediaCodec.BufferInfo()

        fun drainEncoder(timeoutUs: Long) {
            var t = timeoutUs
            while (true) {
                val idx = encoder.dequeueOutputBuffer(encInfo, t)
                t = 0
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = muxer.addTrack(encoder.outputFormat)
                    if (audioFormat != null) {
                        audioTrack = try { muxer.addTrack(audioFormat) } catch (e: Exception) { -1 }
                    }
                    muxer.start()
                    muxerStarted = true
                } else if (idx >= 0) {
                    val buf = encoder.getOutputBuffer(idx)!!
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                    if (encInfo.size > 0 && muxerStarted) {
                        buf.position(encInfo.offset)
                        buf.limit(encInfo.offset + encInfo.size)
                        muxer.writeSampleData(videoTrack, buf, encInfo)
                    }
                    encoder.releaseOutputBuffer(idx, false)
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encDone = true
                        return
                    }
                }
            }
        }

        fun waitEncoderInput(): Int {
            while (true) {
                if (cancelled) throw CancelledException()
                val i = encoder.dequeueInputBuffer(5_000)
                if (i >= 0) return i
                drainEncoder(0)
            }
        }

        try {
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var decoderDone = false
            var frames = 0
            val t0 = System.currentTimeMillis()

            while (!decoderDone) {
                if (cancelled) throw CancelledException()
                if (!inputEos) {
                    val ii = decoder.dequeueInputBuffer(0)
                    if (ii >= 0) {
                        val ib = decoder.getInputBuffer(ii)!!
                        val n = extractor.readSampleData(ib, 0)
                        val st = extractor.sampleTime
                        if (n < 0 || (endUs > 0 && st > endUs)) {
                            decoder.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(ii, 0, n, st, 0)
                            extractor.advance()
                        }
                    }
                }
                val oi = decoder.dequeueOutputBuffer(decInfo, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    updateColor(decoder.outputFormat)
                } else if (oi >= 0) {
                    val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (decInfo.size > 0 && decInfo.presentationTimeUs >= startUs) {
                        val img = decoder.getOutputImage(oi) ?: throw IOException("Decoder gave no image buffer")
                        val ei = waitEncoderInput()
                        val dst = encoder.getInputImage(ei) ?: throw IOException("Encoder does not accept raw image input")
                        copyFrame(img, dst, W, H)
                        for (rg in regions) {
                            if (cancelled) throw CancelledException()
                            patch(img, dst, rg, yuv)
                        }
                        encoder.queueInputBuffer(ei, 0, W * H * 3 / 2, decInfo.presentationTimeUs - startUs, 0)
                        frames++
                        onProgress(frames, maxOf(total, frames), (System.currentTimeMillis() - t0) / frames)
                    }
                    decoder.releaseOutputBuffer(oi, false)
                    if (eos) {
                        val ei = waitEncoderInput()
                        encoder.queueInputBuffer(ei, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        decoderDone = true
                    }
                }
                drainEncoder(0)
            }
            while (!encDone) {
                if (cancelled) throw CancelledException()
                drainEncoder(10_000)
            }

            // audio: copy as-is
            if (audioFormat != null && audioTrack >= 0 && muxerStarted) {
                val ae = MediaExtractor()
                ae.setDataSource(ctx, src, null)
                ae.selectTrack(aIdx)
                if (startUs > 0) ae.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val buf = ByteBuffer.allocate(1 shl 20)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    buf.clear()
                    val n = ae.readSampleData(buf, 0)
                    if (n < 0) break
                    val t = ae.sampleTime
                    if (endUs > 0 && t > endUs) break
                    if (t >= startUs) {
                        val fl = if (ae.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        info.set(0, n, t - startUs, fl)
                        muxer.writeSampleData(audioTrack, buf, info)
                    }
                    ae.advance()
                }
                ae.release()
            }
        } finally {
            try { decoder.stop() } catch (e: Exception) {}
            try { decoder.release() } catch (e: Exception) {}
            try { encoder.stop() } catch (e: Exception) {}
            try { encoder.release() } catch (e: Exception) {}
            try { if (muxerStarted) muxer.stop() } catch (e: Exception) {}
            try { muxer.release() } catch (e: Exception) {}
            extractor.release()
        }
    }
}
