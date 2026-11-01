package app.wmremover

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val REQ_VIDEO = 11
    private val REQ_MODEL = 12

    // palette
    private val cBg = Color.parseColor("#0F1115")
    private val cSurf = Color.parseColor("#1A1D24")
    private val cSurf2 = Color.parseColor("#262A33")
    private val cAcc = Color.parseColor("#4C8DFF")
    private val cAccDim = Color.parseColor("#2A4A85")
    private val cOk = Color.parseColor("#22C55E")
    private val cTxt = Color.parseColor("#E8EAF0")
    private val cMuted = Color.parseColor("#9AA1B2")
    private val cDanger = Color.parseColor("#FF5C6C")

    // screens
    private lateinit var root: FrameLayout
    private lateinit var emptyView: LinearLayout
    private lateinit var editor: LinearLayout

    // empty screen
    private lateinit var modelStatus: TextView
    private lateinit var modelButtons: LinearLayout

    // editor
    private lateinit var maskView: MaskView
    private lateinit var chip: TextView
    private lateinit var hintTv: TextView
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var timeCur: TextView
    private lateinit var timeTot: TextView
    private lateinit var frameSeek: SeekBar
    private lateinit var sizeRow: LinearLayout
    private lateinit var eyeBtn: TextView
    private lateinit var btnPreview: TextView
    private lateinit var btnGo: TextView
    private val toolBtns = ArrayList<Pair<LinearLayout, Int>>()

    // busy overlay
    private lateinit var busyOverlay: FrameLayout
    private lateinit var busyTitle: TextView
    private lateinit var busyPct: TextView
    private lateinit var busyBar: ProgressBar
    private lateinit var busyDetail: TextView
    private lateinit var busyTip: TextView
    private lateinit var busyCancel: TextView
    private var busyCancelAction: (() -> Unit)? = null

    // state
    private val lama = Lama()
    private val bg = Executors.newSingleThreadExecutor()
    private var uri: Uri? = null
    private var retriever: MediaMetadataRetriever? = null
    private var durationUs = 0L
    private var curUs = 0L
    private var uw = 0
    private var uh = 0
    private var modelLoading = false
    private var modelMsg = ""
    private var scanCancelled = false
    private var wakeLock: PowerManager.WakeLock? = null

    // settings
    private var grow = 4
    private var sens = 50
    private var qualityIdx = 0
    private var startTxt = ""
    private var endTxt = ""
    private val qualityNames = listOf("High", "Maximum", "Balanced")
    private val qualityMul = listOf(1.6f, 2.5f, 1.1f)
    private val qualityDesc = listOf(
        "Re-encoded at 1.6x the original bitrate. Looks identical to the source.",
        "Re-encoded at 2.5x the original bitrate. Biggest file, zero visible loss.",
        "Re-encoded at about the original bitrate. Smaller file."
    )

    // ------------------------------------------------------------------ helpers
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density
    private fun ui(f: () -> Unit) = runOnUiThread(f)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun shape(color: Int, r: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(r)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun ripple(color: Int, r: Int, stroke: Int = 0): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(color, r, stroke), null)

    private fun text(t: String, sp: Float, color: Int = cTxt, bold: Boolean = false): TextView = TextView(this).apply {
        text = t
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun button(t: String, bgc: Int, fg: Int = Color.WHITE, h: Int = 52, stroke: Int = 0, click: () -> Unit): TextView =
        text(t, 15f, fg, true).apply {
            gravity = Gravity.CENTER
            background = ripple(bgc, 14, stroke)
            minHeight = dp(h)
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            setOnClickListener { click() }
        }

    private fun iconBtn(glyph: String, click: () -> Unit): TextView = text(glyph, 21f).apply {
        gravity = Gravity.CENTER
        background = ripple(Color.TRANSPARENT, 24)
        isClickable = true
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    private fun look(v: View, on: Boolean) { v.alpha = if (on) 1f else 0.4f }

    private fun tint(s: SeekBar) {
        s.progressTintList = ColorStateList.valueOf(cAcc)
        s.thumbTintList = ColorStateList.valueOf(cAcc)
        s.progressBackgroundTintList = ColorStateList.valueOf(cSurf2)
    }

    private fun fmt(us: Long): String {
        val s = us / 1_000_000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun sheet(content: View): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val r = dpf(24)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = GradientDrawable().apply { setColor(cSurf); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        box.setPadding(dp(20), dp(10), dp(20), dp(24))
        val grab = View(this)
        grab.background = shape(cSurf2, 3)
        box.addView(grab, lp(dp(40), dp(5), 0f, 0, 0, 0, 14).also { it.gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(content)
        val sv = ScrollView(this)
        sv.addView(box)
        d.setContentView(sv)
        d.window?.apply {
            setLayout(MATCH, WRAP)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        return d
    }

    // ------------------------------------------------------------------ build UI
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        root = FrameLayout(this)
        root.setBackgroundColor(cBg)
        buildEmpty()
        buildEditor()
        buildBusy()
        root.addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))
        editor.visibility = View.GONE
        root.addView(editor, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(busyOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        if (Lama.modelPresent(this)) loadModel() else refreshModelUi()
    }

    private fun buildEmpty() {
        emptyView = LinearLayout(this)
        emptyView.orientation = LinearLayout.VERTICAL
        emptyView.gravity = Gravity.CENTER
        emptyView.setPadding(dp(28), dp(28), dp(28), dp(28))

        val icon = text("🎬", 44f).apply { gravity = Gravity.CENTER; background = shape(cSurf, 36) }
        emptyView.addView(icon, lp(dp(112), dp(112), 0f, 0, 0, 0, 24))
        emptyView.addView(text("Remove video watermarks", 24f, cTxt, true).apply { gravity = Gravity.CENTER })
        emptyView.addView(
            text("AI rebuilds the area behind logos and watermarks, right on your phone. Your videos never leave the device.", 14f, cMuted)
                .apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP, 0f, 0, 10, 0, 28)
        )
        emptyView.addView(button("Choose video", cAcc, h = 58) { pickVideo() }, lp(MATCH, WRAP))

        modelStatus = text("", 13f, cMuted).apply { gravity = Gravity.CENTER }
        emptyView.addView(modelStatus, lp(MATCH, WRAP, 0f, 0, 22, 0, 8))

        modelButtons = LinearLayout(this)
        modelButtons.orientation = LinearLayout.VERTICAL
        modelButtons.addView(button("Download AI model (208 MB)", cSurf2, cTxt, 48) { downloadModel() }, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))
        modelButtons.addView(button("I already have the model file", Color.TRANSPARENT, cMuted, 44) { importModel() }, lp(MATCH, WRAP))
        modelButtons.visibility = View.GONE
        emptyView.addView(modelButtons, lp(MATCH, WRAP))
    }

    private fun buildEditor() {
        editor = LinearLayout(this)
        editor.orientation = LinearLayout.VERTICAL

        // top bar
        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(4), dp(2), dp(4), dp(2))
        top.addView(iconBtn("📂") { pickVideo() })
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titleTv = text("", 15f, cTxt, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        subTv = text("", 11f, cMuted)
        titles.addView(titleTv)
        titles.addView(subTv)
        top.addView(titles, lp(0, WRAP, 1f, 4, 0, 4, 0))
        eyeBtn = iconBtn("👁") {
            maskView.showMask = !maskView.showMask
            look(eyeBtn, maskView.showMask)
            maskView.invalidate()
        }
        top.addView(eyeBtn)
        top.addView(iconBtn("⚙") { openSettings() })
        editor.addView(top, lp(MATCH, WRAP))

        // canvas
        val canvasBox = FrameLayout(this)
        maskView = MaskView(this)
        canvasBox.addView(maskView, FrameLayout.LayoutParams(MATCH, MATCH))
        chip = text("", 12f, Color.WHITE).apply {
            background = shape(0xCC000000.toInt(), 20)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
        }
        canvasBox.addView(chip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12) })
        canvasBox.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dpf(18)) }
        }
        canvasBox.clipToOutline = true
        maskView.onInteract = { chip.visibility = View.GONE }
        maskView.onMaskEdited = { updateHint() }
        editor.addView(canvasBox, lp(MATCH, 0, 1f, 10, 0, 10, 0))

        // timeline
        val tl = LinearLayout(this)
        tl.gravity = Gravity.CENTER_VERTICAL
        tl.setPadding(dp(10), dp(4), dp(10), 0)
        tl.addView(text("−1s", 12f, cMuted, true).apply {
            gravity = Gravity.CENTER; background = ripple(Color.TRANSPARENT, 18); isClickable = true
            setOnClickListener { stepBy(-1_000_000) }
        }, lp(dp(44), dp(40)))
        frameSeek = SeekBar(this)
        frameSeek.max = 1000
        tint(frameSeek)
        frameSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) timeCur.text = fmt(durationUs * p / 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { curUs = durationUs * sb.progress / 1000; loadFrame(curUs) }
        })
        tl.addView(frameSeek, lp(0, dp(40), 1f))
        tl.addView(text("+1s", 12f, cMuted, true).apply {
            gravity = Gravity.CENTER; background = ripple(Color.TRANSPARENT, 18); isClickable = true
            setOnClickListener { stepBy(1_000_000) }
        }, lp(dp(44), dp(40)))
        editor.addView(tl, lp(MATCH, WRAP))
        val times = LinearLayout(this)
        times.setPadding(dp(58), 0, dp(58), 0)
        timeCur = text("0:00", 11f, cMuted)
        timeTot = text("0:00", 11f, cMuted).apply { gravity = Gravity.END }
        times.addView(timeCur, lp(0, WRAP, 1f))
        times.addView(timeTot, lp(0, WRAP, 1f))
        editor.addView(times, lp(MATCH, WRAP))

        // hint
        hintTv = text("", 13f, cTxt).apply {
            gravity = Gravity.CENTER
            background = shape(cSurf, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        editor.addView(hintTv, lp(MATCH, WRAP, 0f, 10, 6, 10, 6))

        // brush size (only for brush / erase)
        sizeRow = LinearLayout(this)
        sizeRow.gravity = Gravity.CENTER_VERTICAL
        sizeRow.setPadding(dp(16), 0, dp(16), 0)
        sizeRow.addView(text("Size", 12f, cMuted).apply { minWidth = dp(40) })
        val sizeSeek = SeekBar(this)
        sizeSeek.max = 200
        sizeSeek.progress = 48
        tint(sizeSeek)
        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                maskView.brushPx = maxOf(10, p).toFloat()
                if (fromUser) maskView.sizeRing = true
            }
            override fun onStartTrackingTouch(sb: SeekBar) { maskView.sizeRing = true }
            override fun onStopTrackingTouch(sb: SeekBar) { maskView.sizeRing = false }
        })
        sizeRow.addView(sizeSeek, lp(0, dp(36), 1f))
        editor.addView(sizeRow, lp(MATCH, WRAP))

        // tool dock
        val dock = LinearLayout(this)
        dock.background = shape(cSurf, 20)
        dock.setPadding(dp(6), dp(6), dp(6), dp(6))
        val dockItems = listOf(
            Triple("🖌", "Brush", MaskView.BRUSH), Triple("▭", "Box", MaskView.BOX), Triple("⌫", "Erase", MaskView.ERASE)
        )
        for ((g, l, id) in dockItems) {
            val b = toolButton(g, l) { selectTool(id) }
            toolBtns.add(b to id)
            dock.addView(b, lp(0, dp(58), 1f))
        }
        dock.addView(View(this).apply { setBackgroundColor(cSurf2) }, lp(dp(1), dp(34), 0f, 2, 0, 2, 0).also { it.gravity = Gravity.CENTER_VERTICAL })
        dock.addView(toolButton("↶", "Undo") { maskView.undo() }, lp(0, dp(58), 1f))
        dock.addView(toolButton("🗑", "Clear") { maskView.clearMask() }, lp(0, dp(58), 1f))
        dock.addView(toolButton("✨", "Auto") { scan() }.also { (it.getChildAt(0) as TextView).setTextColor(cAcc) }, lp(0, dp(58), 1f))
        editor.addView(dock, lp(MATCH, WRAP, 0f, 10, 4, 10, 8))

        // actions
        val actions = LinearLayout(this)
        btnPreview = button("Preview", cSurf2, cTxt, 54) { preview() }
        btnGo = button("Remove watermark", cOk, Color.WHITE, 54) { process() }
        actions.addView(btnPreview, lp(0, WRAP, 1f, 0, 0, 8, 0))
        actions.addView(btnGo, lp(0, WRAP, 2f))
        editor.addView(actions, lp(MATCH, WRAP, 0f, 10, 0, 10, 12))

        selectTool(MaskView.BRUSH)
    }

    private fun toolButton(glyph: String, label: String, click: () -> Unit): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.gravity = Gravity.CENTER
        l.background = ripple(Color.TRANSPARENT, 14)
        l.isClickable = true
        l.addView(text(glyph, 20f).apply { gravity = Gravity.CENTER })
        l.addView(text(label, 11f, cMuted).apply { gravity = Gravity.CENTER })
        l.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            click()
        }
        return l
    }

    private fun selectTool(id: Int) {
        maskView.tool = id
        for ((v, t) in toolBtns) v.background = ripple(if (t == id) cAccDim else Color.TRANSPARENT, 14)
        sizeRow.visibility = if (id == MaskView.BOX) View.GONE else View.VISIBLE
    }

    private fun updateHint() {
        val empty = maskView.isMaskEmpty()
        hintTv.text = if (empty) "1  Tap ✨ Auto to find the logo, or paint over it"
        else "2  Check the red area, tap Preview, then Remove"
        look(btnPreview, !empty)
        look(btnGo, !empty)
    }

    private fun showChip(s: String) {
        chip.text = s
        chip.visibility = View.VISIBLE
    }

    private fun buildBusy() {
        busyOverlay = FrameLayout(this)
        busyOverlay.setBackgroundColor(0xEE0F1115.toInt())
        busyOverlay.isClickable = true
        busyOverlay.visibility = View.GONE
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = shape(cSurf, 24)
        card.setPadding(dp(24), dp(24), dp(24), dp(20))
        busyTitle = text("", 18f, cTxt, true)
        busyPct = text("", 40f, cAcc, true).apply { gravity = Gravity.CENTER }
        busyBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        busyBar.progressTintList = ColorStateList.valueOf(cAcc)
        busyBar.indeterminateTintList = ColorStateList.valueOf(cAcc)
        busyDetail = text("", 13f, cTxt).apply { gravity = Gravity.CENTER }
        busyTip = text("", 12f, cMuted).apply { gravity = Gravity.CENTER }
        busyCancel = button("Cancel", Color.TRANSPARENT, cDanger, 48, cDanger) {
            confirmCancel()
        }
        card.addView(busyTitle, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))
        card.addView(busyPct, lp(MATCH, WRAP, 0f, 0, 4, 0, 4))
        card.addView(busyBar, lp(MATCH, dp(12), 0f, 0, 6, 0, 12))
        card.addView(busyDetail, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
        card.addView(busyTip, lp(MATCH, WRAP, 0f, 0, 0, 0, 16))
        card.addView(busyCancel, lp(MATCH, WRAP))
        busyOverlay.addView(card, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply {
            setMargins(dp(24), 0, dp(24), 0)
        })
    }

    private fun showBusy(title: String, tip: String = "", cancel: (() -> Unit)? = null) {
        busyTitle.text = title
        busyTip.text = tip
        busyDetail.text = ""
        busyPct.visibility = View.GONE
        busyBar.isIndeterminate = true
        busyCancelAction = cancel
        busyCancel.visibility = if (cancel != null) View.VISIBLE else View.GONE
        busyOverlay.visibility = View.VISIBLE
    }

    private fun updateBusy(detail: String, done: Int = -1, total: Int = 0) {
        busyDetail.text = detail
        if (total > 0 && done >= 0) {
            busyBar.isIndeterminate = false
            busyBar.max = total
            busyBar.progress = done
            busyPct.visibility = View.VISIBLE
            busyPct.text = "${done * 100 / total}%"
        }
    }

    private fun hideBusy() {
        busyOverlay.visibility = View.GONE
        busyCancelAction = null
    }

    private fun confirmCancel() {
        val a = busyCancelAction ?: return
        AlertDialog.Builder(this)
            .setTitle("Stop?")
            .setMessage("Progress on this step will be lost.")
            .setPositiveButton("Stop") { _, _ -> a() }
            .setNegativeButton("Keep going", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (busyOverlay.visibility == View.VISIBLE) {
            if (busyCancelAction != null) confirmCancel()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ------------------------------------------------------------------ settings
    private fun seekRow(title: String, desc: String, max: Int, value: Int, label: (Int) -> String, onChange: (Int) -> Unit): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val head = LinearLayout(this)
        head.addView(text(title, 15f, cTxt, true), lp(0, WRAP, 1f))
        val v = text(label(value), 14f, cAcc, true)
        head.addView(v)
        c.addView(head)
        c.addView(text(desc, 12f, cMuted), lp(MATCH, WRAP, 0f, 0, 2, 0, 0))
        val s = SeekBar(this)
        s.max = max
        s.progress = value
        tint(s)
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { v.text = label(p); onChange(p) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        c.addView(s, lp(MATCH, dp(40), 0f, 0, 4, 0, 0))
        return c
    }

    private fun openSettings() {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.addView(text("Settings", 20f, cTxt, true), lp(MATCH, WRAP, 0f, 0, 0, 0, 14))

        c.addView(seekRow("Edge coverage", "Grows the marked area so soft or glowing edges are covered too.", 30, grow, { "$it px" }) { grow = it })
        c.addView(seekRow("Auto-scan sensitivity", "Higher finds fainter logos but may flag more things.", 100, sens, { "$it%" }) { sens = it },
            lp(MATCH, WRAP, 0f, 0, 16, 0, 0))

        c.addView(text("Output quality", 15f, cTxt, true), lp(MATCH, WRAP, 0f, 0, 18, 0, 8))
        val desc = text(qualityDesc[qualityIdx], 12f, cMuted)
        val seg = LinearLayout(this)
        seg.background = shape(cSurf2, 14)
        seg.setPadding(dp(4), dp(4), dp(4), dp(4))
        val segViews = ArrayList<TextView>()
        fun paintSeg() {
            for ((i, tv) in segViews.withIndex()) {
                tv.background = if (i == qualityIdx) shape(cAcc, 11) else shape(Color.TRANSPARENT, 11)
                tv.setTextColor(if (i == qualityIdx) Color.WHITE else cMuted)
            }
            desc.text = qualityDesc[qualityIdx]
        }
        for ((i, n) in qualityNames.withIndex()) {
            val tv = text(n, 14f, cMuted, true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(42)
                isClickable = true
                setOnClickListener { qualityIdx = i; paintSeg() }
            }
            segViews.add(tv)
            seg.addView(tv, lp(0, WRAP, 1f))
        }
        paintSeg()
        c.addView(seg, lp(MATCH, WRAP))
        c.addView(desc, lp(MATCH, WRAP, 0f, 0, 6, 0, 0))

        c.addView(text("Trim (optional)", 15f, cTxt, true), lp(MATCH, WRAP, 0f, 0, 18, 0, 2))
        c.addView(text("Process only part of the video. Try 3 seconds first to check the result.", 12f, cMuted))
        val trim = LinearLayout(this)
        fun field(hint: String, v: String): EditText = EditText(this).apply {
            this.hint = hint
            setText(v)
            setTextColor(cTxt)
            setHintTextColor(cMuted)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = shape(cSurf2, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val sEt = field("Start (sec)", startTxt)
        val eEt = field("End (sec, blank = all)", endTxt)
        trim.addView(sEt, lp(0, WRAP, 1f, 0, 0, 6, 0))
        trim.addView(eEt, lp(0, WRAP, 1f, 6, 0, 0, 0))
        c.addView(trim, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))

        c.addView(text("AI model", 15f, cTxt, true), lp(MATCH, WRAP, 0f, 0, 18, 0, 2))
        c.addView(text(if (lama.loaded) "Loaded and ready ✓" else "Not loaded", 12f, if (lama.loaded) cOk else cDanger))
        val mrow = LinearLayout(this)
        val d = sheet(c)
        mrow.addView(button(if (Lama.modelPresent(this)) "Re-download" else "Download (208 MB)", cSurf2, cTxt, 46) { d.dismiss(); downloadModel() }, lp(0, WRAP, 1f, 0, 0, 6, 0))
        mrow.addView(button("Import file", cSurf2, cTxt, 46) { d.dismiss(); importModel() }, lp(0, WRAP, 1f, 6, 0, 0, 0))
        c.addView(mrow, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))

        c.addView(button("Done", cAcc, h = 52) { d.dismiss() }, lp(MATCH, WRAP, 0f, 0, 20, 0, 0))
        d.setOnDismissListener { startTxt = sEt.text.toString(); endTxt = eEt.text.toString() }
        d.show()
    }

    // ------------------------------------------------------------------ model
    private fun refreshModelUi() {
        modelStatus.text = when {
            lama.loaded -> "● AI model ready"
            modelLoading -> "● Loading AI model..."
            modelMsg.isNotEmpty() -> "● $modelMsg"
            else -> "● AI model not installed yet (one-time download)"
        }
        modelStatus.setTextColor(if (lama.loaded) cOk else cMuted)
        modelButtons.visibility = if (!lama.loaded && !modelLoading) View.VISIBLE else View.GONE
    }

    private fun loadModel() {
        modelLoading = true
        refreshModelUi()
        bg.execute {
            try {
                lama.load(this)
                modelMsg = ""
            } catch (e: Throwable) {
                modelMsg = "Could not load model: ${e.message}"
            }
            modelLoading = false
            ui { refreshModelUi() }
        }
    }

    private fun downloadModel() {
        showBusy("Downloading AI model", "One time only, 208 MB. Wi-Fi recommended.")
        Thread {
            try {
                lama.download(this) { done, total ->
                    ui { updateBusy("${done / 1_000_000} of ${total / 1_000_000} MB", (done / 1000).toInt(), maxOf(1, (total / 1000).toInt())) }
                }
                ui { hideBusy(); toast("Model downloaded") }
                loadModel()
            } catch (e: Throwable) {
                modelMsg = "Download failed: ${e.message}"
                ui { hideBusy(); refreshModelUi(); toast("Download failed. Check your connection and try again.") }
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
            showBusy("Importing model", "Copying the file...")
            Thread {
                try {
                    contentResolver.openInputStream(u)!!.use { inp ->
                        Lama.modelFile(this).outputStream().use { inp.copyTo(it) }
                    }
                    ui { hideBusy() }
                    loadModel()
                } catch (e: Throwable) {
                    modelMsg = "Import failed: ${e.message}"
                    ui { hideBusy(); refreshModelUi(); toast("Import failed") }
                }
            }.start()
        }
    }

    private fun displayName(u: Uri): String {
        contentResolver.query(u, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i).substringBeforeLast('.')
        }
        return "video"
    }

    private fun openVideo(u: Uri) {
        showBusy("Opening video")
        bg.execute {
            try {
                retriever?.release()
                val r = MediaMetadataRetriever()
                r.setDataSource(this, u)
                retriever = r
                durationUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
                val bmp = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: throw IllegalStateException("Could not decode this video")
                val name = displayName(u)
                ui {
                    uri = u
                    uw = bmp.width
                    uh = bmp.height
                    curUs = 0
                    maskView.setVideoSize(uw, uh)
                    maskView.setFrame(bmp)
                    frameSeek.progress = 0
                    titleTv.text = name
                    subTv.text = "${uw} x ${uh}  ·  ${fmt(durationUs)}"
                    timeCur.text = "0:00"
                    timeTot.text = fmt(durationUs)
                    emptyView.visibility = View.GONE
                    editor.visibility = View.VISIBLE
                    updateHint()
                    showChip("Paint over the watermark · pinch to zoom · drag with 2 fingers")
                    hideBusy()
                }
            } catch (e: Throwable) {
                ui { hideBusy(); toast("Cannot open this video: ${e.message}") }
            }
        }
    }

    private fun stepBy(deltaUs: Long) {
        if (retriever == null) return
        curUs = (curUs + deltaUs).coerceIn(0L, maxOf(0L, durationUs - 1))
        frameSeek.progress = if (durationUs > 0) (curUs * 1000 / durationUs).toInt() else 0
        timeCur.text = fmt(curUs)
        loadFrame(curUs)
    }

    private fun loadFrame(us: Long) {
        val r = retriever ?: return
        bg.execute {
            try {
                val bmp = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST) ?: return@execute
                if (bmp.width == uw && bmp.height == uh) ui { maskView.setFrame(bmp) }
            } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------------ scan / preview
    private fun scan() {
        val u = uri ?: return
        scanCancelled = false
        showBusy("Looking for logos", "Checking 24 moments of the video for things that never move.") {
            scanCancelled = true
            updateBusy("Stopping...")
        }
        val s = sens / 100f
        bg.execute {
            try {
                val m = Detector.scan(this, u, uw, uh, s, { a, b -> ui { updateBusy("Frame $a of $b", a, b) } }, { scanCancelled })
                ui {
                    hideBusy()
                    if (scanCancelled) return@ui
                    if (m == null || m.none { it.toInt() != 0 }) {
                        showChip("Nothing found. Raise sensitivity in ⚙ or paint it by hand.")
                    } else {
                        maskView.mergeMask(m)
                        showChip("Found it. Fix the red area with Brush / Erase if needed.")
                    }
                }
            } catch (e: Throwable) {
                ui { hideBusy(); toast("Scan failed: ${e.message}") }
            }
        }
    }

    private fun needModel(): Boolean {
        if (lama.loaded) return false
        toast(if (modelLoading) "AI model is still loading, one moment..." else "The AI model is needed first.")
        if (!modelLoading) openSettings()
        return true
    }

    private fun preview() {
        val frame = maskView.frame ?: return
        if (maskView.isMaskEmpty()) { toast("Mark the watermark first"); return }
        if (needModel()) return
        showBusy("Previewing this frame", "The AI is rebuilding the marked area.")
        val mask = maskView.mask.copyOf()
        val g = grow
        bg.execute {
            try {
                val t0 = System.currentTimeMillis()
                val px = IntArray(uw * uh)
                frame.getPixels(px, 0, uw, 0, 0, uw, uh)
                for (rg in planRegions(mask, uw, uh, g)) {
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
                    hideBusy()
                    maskView.setPreview(res)
                    showChip("Preview · %.1fs per frame · touch to edit".format(sec))
                }
            } catch (e: Throwable) {
                ui { hideBusy(); toast("Preview failed: ${e.message}") }
            }
        }
    }

    // ------------------------------------------------------------------ process
    private fun process() {
        val u = uri ?: return
        if (maskView.isMaskEmpty()) { toast("Mark the watermark first"); return }
        if (needModel()) return
        val startUs = ((startTxt.toDoubleOrNull() ?: 0.0) * 1_000_000).toLong()
        val endUs = ((endTxt.toDoubleOrNull() ?: 0.0) * 1_000_000).toLong()
        val mul = qualityMul[qualityIdx]
        val mask = maskView.mask.copyOf()
        val g = grow
        val outFile = File(cacheDir, "wm_out.mp4")
        val baseName = titleTv.text.toString().ifEmpty { "video" }
        val tc = Transcoder(this, lama)

        showBusy("Removing watermark", "Keep this screen on and the app open. Long videos take a while.") {
            tc.cancelled = true
            updateBusy("Stopping...")
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wmremover:process").also { it.acquire(6 * 60 * 60 * 1000L) }

        Thread {
            try {
                outFile.delete()
                tc.run(u, outFile.absolutePath, mask, uw, uh, g, mul, startUs, endUs) { done, total, msPer ->
                    val etaS = (total - done) * msPer / 1000
                    ui {
                        updateBusy("Frame $done of $total  ·  %.1f s per frame\nabout %d min %d s left".format(msPer / 1000.0, etaS / 60, etaS % 60), done, total)
                    }
                }
                ui { updateBusy("Saving to your gallery...") }
                val saved = saveToGallery(outFile, "${baseName}_clean.mp4")
                ui { endProcessing(); showDone(saved) }
            } catch (e: CancelledException) {
                outFile.delete()
                ui { endProcessing(); toast("Cancelled") }
            } catch (e: Throwable) {
                outFile.delete()
                ui { endProcessing(); showError(e.toString()) }
            }
        }.start()
    }

    private fun endProcessing() {
        hideBusy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun showDone(saved: Uri) {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.addView(text("Done ✓", 22f, cOk, true))
        c.addView(text("Your cleaned video is in the gallery, in Movies / WatermarkRemover.", 14f, cMuted), lp(MATCH, WRAP, 0f, 0, 6, 0, 18))
        val d = sheet(c)
        val row = LinearLayout(this)
        row.addView(button("Close", cSurf2, cTxt, 52) { d.dismiss() }, lp(0, WRAP, 1f, 0, 0, 6, 0))
        row.addView(button("Play", cAcc, h = 52) {
            d.dismiss()
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(saved, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { startActivity(i) } catch (e: Exception) { toast("No video player found") }
        }, lp(0, WRAP, 1f, 6, 0, 0, 0))
        c.addView(row)
        d.show()
    }

    private fun showError(msg: String) {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.addView(text("Something went wrong", 20f, cDanger, true))
        c.addView(text(msg, 12f, cMuted), lp(MATCH, WRAP, 0f, 0, 8, 0, 16))
        val d = sheet(c)
        c.addView(button("OK", cSurf2, cTxt, 50) { d.dismiss() })
        d.show()
    }

    private fun saveToGallery(file: File, name: String): Uri {
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
        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        retriever?.release()
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
