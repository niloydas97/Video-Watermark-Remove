package app.wmremover

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.EnumSet

enum class ModelKind(val file: String, val urls: List<String>, val minBytes: Long, val label: String, val mb: Int) {
    MIGAN(
        "migan_pipeline_v2.onnx",
        listOf(
            "https://huggingface.co/andraniksargsyan/migan/resolve/main/migan_pipeline_v2.onnx",
            "https://huggingface.co/CardAnvilModels/MI-GAN-ONNX/resolve/main/migan_pipeline_v2.onnx",
            "https://huggingface.co/edgetools/migan/resolve/main/migan_pipeline_v2.onnx"
        ),
        40_000_000L, "Fast (MI-GAN)", 80
    ),
    LAMA(
        "lama_fp32.onnx",
        listOf("https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"),
        100_000_000L, "Detail (LaMa)", 208
    )
}

/**
 * On-device inpainting. MI-GAN is a mobile-first GAN (fast, ~80 MB); LaMa is heavier but more detailed.
 * Picks the fastest runtime for this phone (CPU / XNNPACK / NNAPI-NPU) with a one-time speed test.
 */
class Engine {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var imgName = "image"
    private var maskName = "mask"
    var kind: ModelKind = ModelKind.MIGAN
        private set
    var accel: String = "cpu4"
        private set

    val loaded: Boolean get() = session != null
    val minSide: Int get() = if (kind == ModelKind.MIGAN) 256 else 320
    val ctxMul: Float get() = if (kind == ModelKind.MIGAN) 1.6f else 1.8f

    fun modelFile(ctx: Context, k: ModelKind) = File(ctx.filesDir, k.file)
    fun present(ctx: Context, k: ModelKind) = modelFile(ctx, k).let { it.exists() && it.length() > k.minBytes }

    // ------------------------------------------------------------ download
    fun download(ctx: Context, k: ModelKind, onProgress: (Long, Long) -> Unit) {
        var last: Exception? = null
        for (u in k.urls) {
            try {
                downloadOne(u, modelFile(ctx, k), k.minBytes, onProgress)
                return
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Download failed")
    }

    private fun downloadOne(url: String, dst: File, minBytes: Long, onProgress: (Long, Long) -> Unit) {
        val tmp = File(dst.path + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
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
        if (tmp.length() < minBytes) throw IOException("Download incomplete")
        dst.delete()
        if (!tmp.renameTo(dst)) throw IOException("Could not save model")
    }

    // ------------------------------------------------------------ sessions
    private fun createSession(ctx: Context, k: ModelKind, accel: String): OrtSession {
        val o = OrtSession.SessionOptions()
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        when (accel) {
            "cpu6" -> o.setIntraOpNumThreads(6)
            "xnn4" -> {
                o.setIntraOpNumThreads(1)
                o.addXnnpack(mapOf("intra_op_num_threads" to "4"))
            }
            "nnapi" -> {
                o.setIntraOpNumThreads(4)
                o.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
            }
            else -> o.setIntraOpNumThreads(4)
        }
        return env.createSession(modelFile(ctx, k).absolutePath, o)
    }

    private fun bind(s: OrtSession) {
        val names = s.inputNames.toList()
        imgName = names.firstOrNull { it.contains("image", true) } ?: names[0]
        maskName = names.firstOrNull { it.contains("mask", true) } ?: names[1]
    }

    fun load(ctx: Context, k: ModelKind, accel: String) {
        session?.close()
        session = null
        kind = k
        this.accel = accel
        val s = try { createSession(ctx, k, accel) } catch (e: Throwable) { createSession(ctx, k, "cpu4") }
        bind(s)
        session = s
    }

    // ------------------------------------------------------------ inference
    fun inpaint(rgb: IntArray, rg: Region): IntArray =
        if (kind == ModelKind.LAMA) inpaintRegion(rgb, rg) { a, b -> lamaInfer(a, b) } else miganInfer(rgb, rg)

    private fun lamaInfer(chw: FloatArray, mask: FloatArray): FloatArray {
        val s = session ?: error("Model not loaded")
        val n = LAMA_SIZE.toLong()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, n, n)).use { t1 ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, n, n)).use { t2 ->
                s.run(mapOf(imgName to t1, maskName to t2)).use { res ->
                    val fb = (res.get(0) as OnnxTensor).floatBuffer
                    val arr = FloatArray(fb.remaining())
                    fb.get(arr)
                    var mx = 0f
                    for (v in arr) if (v > mx) mx = v
                    if (mx <= 2f) for (i in arr.indices) arr[i] = arr[i] * 255f
                    return arr
                }
            }
        }
    }

    /** MI-GAN pipeline: uint8 image [1,3,H,W] + uint8 mask [1,1,H,W] (0 = hole, 255 = keep) -> uint8 result. */
    private fun miganInfer(rgb: IntArray, rg: Region): IntArray {
        val s = session ?: error("Model not loaded")
        val side = rg.side
        val plane = side * side
        val img = ByteBuffer.allocateDirect(3 * plane)
        for (c in 0 until 3) {
            val sh = 16 - 8 * c
            for (i in 0 until plane) img.put((rgb[i] shr sh).toByte())
        }
        img.rewind()
        val m = ByteBuffer.allocateDirect(plane)
        for (i in 0 until plane) m.put(if (rg.hole[i].toInt() != 0) 0 else (-1).toByte())
        m.rewind()
        val sd = side.toLong()
        OnnxTensor.createTensor(env, img, longArrayOf(1, 3, sd, sd), OnnxJavaType.UINT8).use { t1 ->
            OnnxTensor.createTensor(env, m, longArrayOf(1, 1, sd, sd), OnnxJavaType.UINT8).use { t2 ->
                s.run(mapOf(imgName to t1, maskName to t2)).use { res ->
                    val bb = (res.get(0) as OnnxTensor).byteBuffer
                    val out = ByteArray(bb.remaining())
                    bb.get(out)
                    if (out.size != 3 * plane) throw IOException("Unexpected model output size ${out.size}")
                    return compositeSame(rgb, rg, out)
                }
            }
        }
    }

    // ------------------------------------------------------------ speed test
    private fun meanDiff(a: IntArray, b: IntArray, rg: Region): Float {
        var sum = 0L
        var n = 0
        for (i in a.indices) {
            if (rg.alpha[i] <= 0.5f) continue
            for (sh in intArrayOf(0, 8, 16)) sum += kotlin.math.abs(((a[i] shr sh) and 255) - ((b[i] shr sh) and 255))
            n += 3
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    /** Tries CPU / XNNPACK / NPU (NNAPI) and returns the fastest valid one with its ms per inference. */
    fun autoTune(ctx: Context, k: ModelKind, onStatus: (String) -> Unit): Pair<String, Long> {
        session?.close()
        session = null
        kind = k
        val mask = ByteArray(640 * 640)
        for (y in 300..340) for (x in 250..380) mask[y * 640 + x] = 1
        val rg = planRegions(mask, 640, 640, 4, minSide, ctxMul)[0]
        val rgb = IntArray(rg.side * rg.side) {
            val x = it % rg.side
            val y = it / rg.side
            (0xFF shl 24) or ((x * 255 / rg.side) shl 16) or ((y * 255 / rg.side) shl 8) or (90 + ((x / 8 + y / 8) % 2) * 60)
        }
        var best = "cpu4"
        var bestMs = Long.MAX_VALUE
        var ref: IntArray? = null
        for (cand in listOf("cpu4", "cpu6", "xnn4", "nnapi")) {
            onStatus("Speed test: $cand")
            try {
                val s = createSession(ctx, k, cand)
                bind(s)
                session = s
                accel = cand
                inpaint(rgb, rg)
                val t0 = System.nanoTime()
                var out = inpaint(rgb, rg)
                repeat(2) { out = inpaint(rgb, rg) }
                val ms = (System.nanoTime() - t0) / 1_000_000 / 3
                session = null
                s.close()
                val r = ref
                if (r == null) ref = out
                else if (meanDiff(r, out, rg) > 10f) continue
                if (ms < bestMs) { bestMs = ms; best = cand }
            } catch (e: Throwable) {
                try { session?.close() } catch (_: Throwable) {}
                session = null
            }
        }
        return best to (if (bestMs == Long.MAX_VALUE) 0L else bestMs)
    }
}
