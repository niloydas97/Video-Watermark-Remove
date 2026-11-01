package app.wmremover

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val REQ_VIDEO = 11
    private val REQ_MODEL = 12

    private lateinit var maskView: MaskView
    private lateinit var status: TextView
    private lateinit var frameSeek: SeekBar
    private lateinit var brushSeek: SeekBar
    private lateinit var growSeek: SeekBar
    private lateinit var sensSeek: SeekBar
    private lateinit var quality: Spinner
    private lateinit var startEt: EditText
    private lateinit var endEt: EditText
    private lateinit var bar: ProgressBar
    private lateinit var btnOpen: Button
    private lateinit var btnScan: Button
    private lateinit var btnPreview: Button
    private lateinit var btnGo: Button
    private lateinit var btnCancel: Button
    private lateinit var btnDownload: Button
    private lateinit var btnImport: Button
    private lateinit var modelRow: LinearLayout

    private val lama = Lama()
    private val bg = Executors.newSingleThreadExecutor()
    private var uri: Uri? = null
    private var retriever: MediaMetadataRetriever? = null
    private var durationUs = 0L
    private var uw = 0
    private var uh = 0
    private var busy = false
    private var transcoder: Transcoder? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(f: () -> Unit) = runOnUiThread(f)
    private fun say(s: String) = ui { status.text = s }

    // ------------------------------------------------------------------ UI
    private fun button(text: String, w: Float = 1f, click: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 13f
        b.setOnClickListener { click() }
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
        return b
    }

    private fun row(vararg v: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        v.forEach { r.addView(it) }
        return r
    }

    private fun label(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 13f
        tv.setPadding(dp(6), 0, dp(6), 0)
        tv.minWidth = dp(72)
        return tv
    }

    private fun seek(max: Int, value: Int, onChange: (Int) -> Unit): SeekBar {
        val s = SeekBar(this)
        s.max = max
        s.progress = value
        s.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { onChange(p) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        return s
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#15171C")
        window.navigationBarColor = Color.parseColor("#15171C")

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#15171C"))

        status = TextView(this)
        status.setPadding(dp(10), dp(8), dp(10), dp(6))
        status.textSize = 12f
        status.text = "Open a video to begin."
        root.addView(status)

        maskView = MaskView(this)
        maskView.onMaskEdited = { }
        root.addView(maskView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        frameSeek = SeekBar(this)
        frameSeek.max = 1000
        frameSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { loadFrame(sb.progress) }
        })
        root.addView(frameSeek)

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(6), 0, dp(6), dp(6))

        btnOpen = button("Open video") { pickVideo() }
        btnScan = button("Auto-scan") { scan() }
        btnPreview = button("Preview frame") { preview() }
        panel.addView(row(btnOpen, btnScan, btnPreview))

        val rg = RadioGroup(this)
        rg.orientation = RadioGroup.HORIZONTAL
        rg.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        for ((i, t) in listOf("Brush", "Box", "Erase").withIndex()) {
            val rb = RadioButton(this)
            rb.text = t
            rb.id = 100 + i
            rb.textSize = 13f
            rg.addView(rb)
        }
        rg.check(100)
        rg.setOnCheckedChangeListener { _, id -> maskView.tool = id - 100 }
        panel.addView(row(rg, button("Undo") { maskView.undo() }, button("Clear") { maskView.clearMask() }))

        brushSeek = seek(200, 48) { maskView.brushPx = maxOf(8, it).toFloat() }
        panel.addView(row(label("Brush"), brushSeek))
        growSeek = seek(30, 4) { }
        panel.addView(row(label("Mask grow"), growSeek))
        sensSeek = seek(100, 50) { }
        panel.addView(row(label("Scan sens."), sensSeek))

        quality = Spinner(this)
        quality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("High quality (1.6x bitrate)", "Maximum (2.5x bitrate)", "Balanced (1.1x bitrate)"))
        quality.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        panel.addView(row(label("Output"), quality))

        startEt = EditText(this)
        startEt.hint = "start s"
        startEt.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        startEt.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        endEt = EditText(this)
        endEt.hint = "end s (blank=all)"
        endEt.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        endEt.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        panel.addView(row(label("Trim"), startEt, endEt))

        btnGo = button("Remove watermark & save") { process() }
        btnGo.setBackgroundColor(Color.parseColor("#1FA463"))
        btnCancel = button("Cancel", 0.5f) { transcoder?.cancelled = true }
        btnCancel.visibility = View.GONE
        panel.addView(row(btnGo, btnCancel))

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.visibility = View.GONE
        panel.addView(bar)

        btnDownload = button("Download AI model (208 MB)") { downloadModel() }
        btnImport = button("Import model file") { importModel() }
        modelRow = row(btnDownload, btnImport)
        modelRow.visibility = View.GONE
        panel.addView(modelRow)

        val sv = ScrollView(this)
        sv.addView(panel)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)))
        setContentView(root)

        setBusy(true)
        if (Lama.modelPresent(this)) loadModel() else {
            say("The AI model is not installed yet. Download it once (Wi-Fi recommended).")
            ui { modelRow.visibility = View.VISIBLE; setBusy(false) }
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        for (v in listOf(btnOpen, btnScan, btnPreview, btnGo, btnDownload, btnImport)) v.isEnabled = !b
    }

    // ------------------------------------------------------------------ model
    private fun loadModel() {
        say("Loading AI model...")
        bg.execute {
            try {
                lama.load(this)
                say("AI model ready. Open a video.")
                ui { modelRow.visibility = View.GONE; setBusy(false) }
            } catch (e: Throwable) {
                say("Could not load model: ${e.message}")
                ui { modelRow.visibility = View.VISIBLE; setBusy(false) }
            }
        }
    }

    private fun downloadModel() {
        setBusy(true)
        Thread {
            try {
                lama.download(this) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    say("Downloading AI model... $pct%  (${done / 1_000_000} MB)")
                }
                loadModel()
            } catch (e: Throwable) {
                say("Download failed: ${e.message}. Try again, or use Import model file.")
                ui { setBusy(false) }
            }
        }.start()
    }

    private fun importModel() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(i, REQ_MODEL)
    }

    // ------------------------------------------------------------------ video
    private fun pickVideo() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(i, REQ_VIDEO)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val u = data?.data ?: return
        if (resultCode != RESULT_OK) return
        if (requestCode == REQ_VIDEO) openVideo(u)
        if (requestCode == REQ_MODEL) {
            setBusy(true)
            Thread {
                try {
                    say("Importing model...")
                    contentResolver.openInputStream(u)!!.use { inp ->
                        Lama.modelFile(this).outputStream().use { inp.copyTo(it) }
                    }
                    loadModel()
                } catch (e: Throwable) {
                    say("Import failed: ${e.message}")
                    ui { setBusy(false) }
                }
            }.start()
        }
    }

    private fun openVideo(u: Uri) {
        bg.execute {
            try {
                retriever?.release()
                val r = MediaMetadataRetriever()
                r.setDataSource(this, u)
                retriever = r
                durationUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
                val bmp = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: throw IllegalStateException("Could not decode this video")
                ui {
                    uri = u
                    uw = bmp.width
                    uh = bmp.height
                    maskView.setVideoSize(uw, uh)
                    maskView.setFrame(bmp)
                    frameSeek.progress = 0
                    status.text = "${uw}x${uh}, ${durationUs / 1_000_000}s.  Auto-scan, or paint over the watermark (2 fingers = zoom/pan)."
                }
            } catch (e: Throwable) {
                say("Cannot open video: ${e.message}")
            }
        }
    }

    private fun loadFrame(progress: Int) {
        val r = retriever ?: return
        bg.execute {
            try {
                val t = durationUs * progress / 1000
                val bmp = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@execute
                if (bmp.width == uw && bmp.height == uh) ui { maskView.setFrame(bmp) }
            } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------------ scan / preview
    private fun scan() {
        val u = uri ?: return say("Open a video first.")
        setBusy(true)
        val sens = sensSeek.progress / 100f
        bg.execute {
            try {
                val m = Detector.scan(this, u, uw, uh, sens, { a, b -> say("Scanning video... $a/$b") }, { false })
                ui {
                    setBusy(false)
                    if (m == null || m.none { it.toInt() != 0 }) {
                        status.text = "No static logo found. Raise sensitivity or paint it manually."
                    } else {
                        maskView.mergeMask(m)
                        status.text = "Scan done: red = detected. Fix with Brush / Erase if needed."
                    }
                }
            } catch (e: Throwable) {
                ui { setBusy(false); status.text = "Scan failed: ${e.message}" }
            }
        }
    }

    private fun preview() {
        val frame = maskView.frame ?: return say("Open a video first.")
        if (maskView.isMaskEmpty()) return say("Mark the watermark first.")
        if (!lama.loaded) return say("AI model not loaded.")
        setBusy(true)
        say("Running AI on this frame...")
        val mask = maskView.mask.copyOf()
        val grow = growSeek.progress
        bg.execute {
            try {
                val t0 = System.currentTimeMillis()
                val px = IntArray(uw * uh)
                frame.getPixels(px, 0, uw, 0, 0, uw, uh)
                for (rg in planRegions(mask, uw, uh, grow)) {
                    val side = rg.side
                    val crop = IntArray(side * side)
                    for (yy in 0 until side) System.arraycopy(px, (rg.y0 + yy) * uw + rg.x0, crop, yy * side, side)
                    val out = inpaintRegion(crop, rg) { a, b -> lama.infer(a, b) }
                    for (yy in 0 until side) for (xx in 0 until side) {
                        if (rg.alpha[yy * side + xx] > 0.001f) px[(rg.y0 + yy) * uw + rg.x0 + xx] = out[yy * side + xx]
                    }
                }
                val res = Bitmap.createBitmap(px, uw, uh, Bitmap.Config.ARGB_8888)
                val sec = (System.currentTimeMillis() - t0) / 1000.0
                ui {
                    maskView.setPreview(res)
                    setBusy(false)
                    status.text = "Preview done in %.1fs per region-frame. Touch the image to return to editing.".format(sec)
                }
            } catch (e: Throwable) {
                ui { setBusy(false); status.text = "Preview failed: ${e.message}" }
            }
        }
    }

    // ------------------------------------------------------------------ process
    private fun displayName(u: Uri): String {
        contentResolver.query(u, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i).substringBeforeLast('.')
        }
        return "video"
    }

    private fun process() {
        val u = uri ?: return say("Open a video first.")
        if (maskView.isMaskEmpty()) return say("Mark the watermark first.")
        if (!lama.loaded) return say("AI model not loaded.")
        val startUs = ((startEt.text.toString().toDoubleOrNull() ?: 0.0) * 1_000_000).toLong()
        val endUs = ((endEt.text.toString().toDoubleOrNull() ?: 0.0) * 1_000_000).toLong()
        val mul = when (quality.selectedItemPosition) { 1 -> 2.5f; 2 -> 1.1f; else -> 1.6f }
        val mask = maskView.mask.copyOf()
        val grow = growSeek.progress
        val outFile = File(cacheDir, "wm_out.mp4")
        val baseName = displayName(u)
        val tc = Transcoder(this, lama)
        transcoder = tc

        setBusy(true)
        btnCancel.visibility = View.VISIBLE
        bar.visibility = View.VISIBLE
        bar.progress = 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wmremover:process").also { it.acquire(6 * 60 * 60 * 1000L) }

        Thread {
            try {
                outFile.delete()
                tc.run(u, outFile.absolutePath, mask, uw, uh, grow, mul, startUs, endUs) { done, total, msPer ->
                    val etaS = (total - done) * msPer / 1000
                    ui {
                        bar.max = total
                        bar.progress = done
                        status.text = "Frame $done / $total  |  %.1f s per frame  |  about %d min %d s left\nKeep this screen on and the app open."
                            .format(msPer / 1000.0, etaS / 60, etaS % 60)
                    }
                }
                say("Saving to gallery...")
                val saved = saveToGallery(outFile, "${baseName}_clean.mp4")
                ui { finishProcessing(); AlertDialog.Builder(this).setTitle("Done").setMessage("Saved to Movies/WatermarkRemover:\n$saved").setPositiveButton("OK", null).show() }
            } catch (e: CancelledException) {
                outFile.delete()
                ui { finishProcessing(); status.text = "Cancelled." }
            } catch (e: Throwable) {
                outFile.delete()
                ui { finishProcessing(); AlertDialog.Builder(this).setTitle("Failed").setMessage(e.toString()).setPositiveButton("OK", null).show() }
            }
        }.start()
    }

    private fun finishProcessing() {
        btnCancel.visibility = View.GONE
        bar.visibility = View.GONE
        setBusy(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun saveToGallery(file: File, name: String): String {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/WatermarkRemover")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val out = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            ?: throw IllegalStateException("Could not create gallery entry")
        contentResolver.openOutputStream(out)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
        cv.clear()
        cv.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(out, cv, null, null)
        file.delete()
        return name
    }

    override fun onDestroy() {
        super.onDestroy()
        retriever?.release()
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
