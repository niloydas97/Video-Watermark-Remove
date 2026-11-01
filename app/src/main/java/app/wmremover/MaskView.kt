package app.wmremover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Shows a video frame with a red mask overlay. 1 finger = draw, 2 fingers = pinch-zoom / pan. */
class MaskView(ctx: Context) : View(ctx) {
    companion object { const val BRUSH = 0; const val BOX = 1; const val ERASE = 2 }

    var frame: Bitmap? = null
        private set
    var mask: ByteArray = ByteArray(0)
        private set
    var mw = 0
        private set
    var mh = 0
        private set
    var tool = BRUSH
    var brushPx = 48f
    var showMask = true
    var onMaskEdited: (() -> Unit)? = null
    var onInteract: (() -> Unit)? = null
    var sizeRing = false
        set(v) { field = v; invalidate() }
    private var touching = false
    private var touchX = 0f
    private var touchY = 0f
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3f
    }
    private val ringShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xAA000000.toInt(); strokeWidth = 7f
    }
    private var preview: Bitmap? = null

    private var overlayPix = IntArray(0)
    private var overlayBmp: Bitmap? = null
    private var scale = 1f
    private var minScale = 1f
    private var tx = 0f
    private var ty = 0f
    private var fitted = true
    private val undoStack = ArrayDeque<ByteArray>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE; color = Color.YELLOW; strokeWidth = 3f
    }
    private val overlayColor = Color.argb(140, 255, 40, 60)

    private var multi = false
    private var strokeActive = false
    private var snapshot: ByteArray? = null
    private var lastIx = 0f
    private var lastIy = 0f
    private var boxX0 = 0f
    private var boxY0 = 0f
    private var boxX1 = 0f
    private var boxY1 = 0f
    private var panX = 0f
    private var panY = 0f

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = (scale * d.scaleFactor).coerceIn(minScale, minScale * 30f)
            val r = ns / scale
            tx = d.focusX - (d.focusX - tx) * r
            ty = d.focusY - (d.focusY - ty) * r
            scale = ns
            fitted = false
            invalidate()
            return true
        }
    })

    fun setVideoSize(w: Int, h: Int) {
        mw = w; mh = h
        mask = ByteArray(w * h)
        overlayPix = IntArray(w * h)
        overlayBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        undoStack.clear()
        fitted = true
    }

    fun setFrame(b: Bitmap) {
        frame = b
        preview = null
        if (fitted) fit()
        invalidate()
    }

    fun setPreview(b: Bitmap?) { preview = b; invalidate() }
    val hasPreview: Boolean get() = preview != null

    fun isMaskEmpty(): Boolean = mask.none { it.toInt() != 0 }

    fun setMask(newMask: ByteArray, pushUndo: Boolean = true) {
        if (pushUndo) pushUndoSnapshot(mask.copyOf())
        System.arraycopy(newMask, 0, mask, 0, min(mask.size, newMask.size))
        rebuildOverlay()
        preview = null
        invalidate()
        onMaskEdited?.invoke()
    }

    fun mergeMask(add: ByteArray) {
        val m = mask.copyOf()
        for (i in m.indices) if (i < add.size && add[i].toInt() != 0) m[i] = 1
        setMask(m)
    }

    fun clearMask() = setMask(ByteArray(mask.size))

    fun undo() {
        if (undoStack.isEmpty()) return
        setMask(undoStack.removeLast(), pushUndo = false)
    }

    private fun pushUndoSnapshot(s: ByteArray) {
        undoStack.addLast(s)
        while (undoStack.size > 8) undoStack.removeFirst()
    }

    private fun rebuildOverlay() {
        for (i in mask.indices) overlayPix[i] = if (mask[i].toInt() != 0) overlayColor else 0
        overlayBmp?.setPixels(overlayPix, 0, mw, 0, 0, mw, mh)
    }

    private fun refreshOverlay(x0: Int, y0: Int, x1: Int, y1: Int) {
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0) return
        overlayBmp?.setPixels(overlayPix, y0 * mw + x0, mw, x0, y0, w, h)
    }

    private fun fit() {
        val f = frame ?: return
        if (width == 0 || height == 0) return
        scale = min(width.toFloat() / f.width, height.toFloat() / f.height)
        minScale = scale
        tx = (width - f.width * scale) / 2f
        ty = (height - f.height * scale) / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (fitted) fit()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF15171C.toInt())
        val shown = preview ?: frame ?: return
        c.save()
        c.translate(tx, ty)
        c.scale(scale, scale)
        c.drawBitmap(shown, 0f, 0f, paint)
        val ov = overlayBmp
        if (preview == null && showMask && ov != null) c.drawBitmap(ov, 0f, 0f, paint)
        c.restore()
        if (strokeActive && tool == BOX) {
            c.drawRect(
                min(boxX0, boxX1) * scale + tx, min(boxY0, boxY1) * scale + ty,
                max(boxX0, boxX1) * scale + tx, max(boxY0, boxY1) * scale + ty, boxPaint
            )
        }
        if (sizeRing) {
            c.drawCircle(width / 2f, height / 2f, brushPx / 2f, ringShadow)
            c.drawCircle(width / 2f, height / 2f, brushPx / 2f, ringPaint)
        } else if (touching && tool != BOX) {
            c.drawCircle(touchX, touchY, brushPx / 2f, ringShadow)
            c.drawCircle(touchX, touchY, brushPx / 2f, ringPaint)
        }
    }

    private fun paintCircle(cx: Float, cy: Float, r: Float, v: Byte, dirty: IntArray) {
        val ri = max(1, r.toInt())
        val x0 = max(0, (cx - ri).toInt()); val x1 = min(mw, (cx + ri).toInt() + 1)
        val y0 = max(0, (cy - ri).toInt()); val y1 = min(mh, (cy + ri).toInt() + 1)
        val r2 = ri.toFloat() * ri
        for (y in y0 until y1) for (x in x0 until x1) {
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy <= r2) {
                val i = y * mw + x
                mask[i] = v
                overlayPix[i] = if (v.toInt() != 0) overlayColor else 0
            }
        }
        dirty[0] = min(dirty[0], x0); dirty[1] = min(dirty[1], y0)
        dirty[2] = max(dirty[2], x1); dirty[3] = max(dirty[3], y1)
    }

    private fun stroke(ax: Float, ay: Float, bx: Float, by: Float) {
        val r = brushPx / 2f / scale
        val v: Byte = if (tool == ERASE) 0 else 1
        val dirty = intArrayOf(mw, mh, 0, 0)
        val dist = kotlin.math.hypot(bx - ax, by - ay)
        val steps = max(1, (dist / max(1f, r / 2f)).toInt())
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            paintCircle(ax + (bx - ax) * t, ay + (by - ay) * t, r, v, dirty)
        }
        refreshOverlay(dirty[0], dirty[1], dirty[2], dirty[3])
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (frame == null) return false
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multi = false
                onInteract?.invoke()
                touching = true
                touchX = e.x; touchY = e.y
                if (preview != null) { preview = null; invalidate() }
                snapshot = mask.copyOf()
                strokeActive = true
                lastIx = (e.x - tx) / scale; lastIy = (e.y - ty) / scale
                if (tool == BOX) { boxX0 = lastIx; boxY0 = lastIy; boxX1 = lastIx; boxY1 = lastIy }
                else stroke(lastIx, lastIy, lastIx, lastIy)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                touching = false
                if (strokeActive && !multi) {
                    snapshot?.let { System.arraycopy(it, 0, mask, 0, mask.size); rebuildOverlay() }
                    snapshot = null
                    strokeActive = false
                    invalidate()
                }
                multi = true
                panX = (e.getX(0) + e.getX(1)) / 2f
                panY = (e.getY(0) + e.getY(1)) / 2f
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) {
                    if (e.pointerCount >= 2) {
                        val mx = (e.getX(0) + e.getX(1)) / 2f
                        val my = (e.getY(0) + e.getY(1)) / 2f
                        tx += mx - panX; ty += my - panY
                        panX = mx; panY = my
                        fitted = false
                        invalidate()
                    }
                } else if (strokeActive) {
                    touchX = e.x; touchY = e.y
                    val ix = (e.x - tx) / scale
                    val iy = (e.y - ty) / scale
                    if (tool == BOX) { boxX1 = ix; boxY1 = iy; invalidate() }
                    else { stroke(lastIx, lastIy, ix, iy); lastIx = ix; lastIy = iy }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (strokeActive && !multi) {
                    if (tool == BOX && e.actionMasked == MotionEvent.ACTION_UP) {
                        val x0 = max(0, min(boxX0, boxX1).toInt()); val x1 = min(mw, max(boxX0, boxX1).toInt())
                        val y0 = max(0, min(boxY0, boxY1).toInt()); val y1 = min(mh, max(boxY0, boxY1).toInt())
                        for (y in y0 until y1) for (x in x0 until x1) {
                            mask[y * mw + x] = 1
                            overlayPix[y * mw + x] = overlayColor
                        }
                        refreshOverlay(x0, y0, x1, y1)
                    }
                    snapshot?.let { pushUndoSnapshot(it) }
                    snapshot = null
                    onMaskEdited?.invoke()
                }
                strokeActive = false
                multi = false
                touching = false
                invalidate()
            }
        }
        return true
    }
}
