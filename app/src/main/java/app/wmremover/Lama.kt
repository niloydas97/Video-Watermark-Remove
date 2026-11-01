package app.wmremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer

/** LaMa inpainting model running on-device with ONNX Runtime. */
class Lama {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var imgName = "image"
    private var maskName = "mask"

    val loaded: Boolean get() = session != null

    companion object {
        const val MODEL_URL = "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"
        fun modelFile(ctx: Context) = File(ctx.filesDir, "lama_fp32.onnx")
        fun modelPresent(ctx: Context) = modelFile(ctx).let { it.exists() && it.length() > 100_000_000L }
    }

    fun download(ctx: Context, onProgress: (Long, Long) -> Unit) {
        val dst = modelFile(ctx)
        val tmp = File(dst.path + ".part")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        if (conn.responseCode !in 200..299) throw IOException("Download failed: HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
        }
        if (tmp.length() < 100_000_000L) throw IOException("Model download incomplete")
        dst.delete()
        if (!tmp.renameTo(dst)) throw IOException("Could not save model")
    }

    fun load(ctx: Context) {
        session?.close()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(4)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        val s = env.createSession(modelFile(ctx).absolutePath, opts)
        val names = s.inputNames.toList()
        imgName = names.firstOrNull { it.contains("image", true) } ?: names[0]
        maskName = names.firstOrNull { it.contains("mask", true) } ?: names[1]
        session = s
    }

    /** chw: 3x512x512 floats 0..1, mask: 512x512 floats 0/1. Returns 3x512x512 floats in 0..255. */
    fun infer(chw: FloatArray, mask: FloatArray): FloatArray {
        val s = session ?: error("Model not loaded")
        val n = LAMA_SIZE.toLong()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, n, n)).use { t1 ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, n, n)).use { t2 ->
                s.run(mapOf(imgName to t1, maskName to t2)).use { res ->
                    val out = res.get(0) as OnnxTensor
                    val fb = out.floatBuffer
                    val arr = FloatArray(fb.remaining())
                    fb.get(arr)
                    var mx = 0f
                    for (v in arr) if (v > mx) mx = v
                    // some exports output 0..1, others 0..255
                    if (mx <= 2f) for (i in arr.indices) arr[i] = arr[i] * 255f
                    return arr
                }
            }
        }
    }
}
