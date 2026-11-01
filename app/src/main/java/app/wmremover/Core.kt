package app.wmremover

import kotlin.math.max
import kotlin.math.min

/** Pure-Kotlin helpers (no Android classes) so they can be unit-tested on a plain JVM. */

const val LAMA_SIZE = 512

/** One group of watermark pixels plus the square crop that is sent to the AI model. */
class Region(
    val x0: Int, val y0: Int, val side: Int,
    val alpha: FloatArray,      // side*side blend weights 0..1 (mask, feathered)
    val mask512: FloatArray     // 512*512 binary mask for the model
)

class Components(val labels: IntArray, val count: Int, val bboxes: Array<IntArray>, val areas: IntArray)

object Morph {
    /** Square dilation, window (2r+1). Values 0/1. */
    fun dilate(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        if (r <= 0) return src.copyOf()
        val tmp = ByteArray(w * h)
        val out = ByteArray(w * h)
        val pre = IntArray(max(w, h) + 1)
        for (y in 0 until h) {
            val o = y * w
            pre[0] = 0
            for (x in 0 until w) pre[x + 1] = pre[x] + src[o + x]
            for (x in 0 until w) {
                val a = max(0, x - r)
                val b = min(w, x + r + 1)
                tmp[o + x] = if (pre[b] - pre[a] > 0) 1 else 0
            }
        }
        for (x in 0 until w) {
            pre[0] = 0
            for (y in 0 until h) pre[y + 1] = pre[y] + tmp[y * w + x]
            for (y in 0 until h) {
                val a = max(0, y - r)
                val b = min(h, y + r + 1)
                out[y * w + x] = if (pre[b] - pre[a] > 0) 1 else 0
            }
        }
        return out
    }

    fun erode(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val inv = ByteArray(src.size) { (1 - src[it]).toByte() }
        val d = dilate(inv, w, h, r)
        for (i in d.indices) d[i] = (1 - d[i]).toByte()
        return d
    }

    fun close(src: ByteArray, w: Int, h: Int, r: Int): ByteArray =
        erode(dilate(src, w, h, r), w, h, r)
}

fun components(mask: ByteArray, w: Int, h: Int): Components {
    val labels = IntArray(w * h)
    val queue = IntArray(w * h)
    val boxes = ArrayList<IntArray>()
    val areas = ArrayList<Int>()
    var n = 0
    for (s in 0 until w * h) {
        if (mask[s].toInt() == 0 || labels[s] != 0) continue
        n++
        var qh = 0
        var qt = 0
        queue[qt++] = s
        labels[s] = n
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1; var area = 0
        while (qh < qt) {
            val p = queue[qh++]
            val px = p % w
            val py = p / w
            area++
            if (px < x0) x0 = px
            if (px > x1) x1 = px
            if (py < y0) y0 = py
            if (py > y1) y1 = py
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = px + dx
                val ny = py + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val q = ny * w + nx
                if (mask[q].toInt() != 0 && labels[q] == 0) {
                    labels[q] = n
                    queue[qt++] = q
                }
            }
        }
        boxes.add(intArrayOf(x0, y0, x1, y1))
        areas.add(area)
    }
    return Components(labels, n, boxes.toTypedArray(), areas.toIntArray())
}

private fun boxBlur3(a: FloatArray, s: Int): FloatArray {
    val o = FloatArray(a.size)
    for (y in 0 until s) for (x in 0 until s) {
        var sum = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val yy = (y + dy).coerceIn(0, s - 1)
            val xx = (x + dx).coerceIn(0, s - 1)
            sum += a[yy * s + xx]
        }
        o[y * s + x] = sum / 9f
    }
    return o
}

private fun feather(m: ByteArray, s: Int): FloatArray {
    var a = FloatArray(s * s) { m[it].toFloat() }
    repeat(2) { a = boxBlur3(a, s) }
    for (i in a.indices) a[i] = max(a[i], m[i].toFloat())
    return a
}

/** Split the mask into separate groups (e.g. logos in two corners) and prepare a crop for each. */
fun planRegions(mask: ByteArray, w: Int, h: Int, grow: Int): List<Region> {
    if (mask.none { it.toInt() != 0 }) return emptyList()
    val m = Morph.dilate(mask, w, h, grow)
    val grouped = Morph.dilate(m, w, h, 20)
    val comp = components(grouped, w, h)
    val bb = Array(comp.count) { intArrayOf(w, h, -1, -1) }
    for (i in 0 until w * h) {
        if (m[i].toInt() == 0) continue
        val b = bb[comp.labels[i] - 1]
        val x = i % w
        val y = i / w
        if (x < b[0]) b[0] = x
        if (y < b[1]) b[1] = y
        if (x > b[2]) b[2] = x
        if (y > b[3]) b[3] = y
    }
    val regions = ArrayList<Region>()
    for (l in 0 until comp.count) {
        val b = bb[l]
        if (b[2] < 0) continue
        val bw = b[2] - b[0] + 1
        val bh = b[3] - b[1] + 1
        var side = (max(bw, bh) * 1.8f).toInt()
        side = max(side, 320)
        side = min(side, min(w, h))
        side = side and 1.inv()
        val cx = (b[0] + b[2]) / 2
        val cy = (b[1] + b[3]) / 2
        var rx = (cx - side / 2).coerceIn(0, max(0, w - side))
        var ry = (cy - side / 2).coerceIn(0, max(0, h - side))
        rx = rx and 1.inv()
        ry = ry and 1.inv()
        val crop = ByteArray(side * side)
        for (yy in 0 until side) for (xx in 0 until side) {
            val i = (ry + yy) * w + rx + xx
            if (m[i].toInt() != 0 && comp.labels[i] == l + 1) crop[yy * side + xx] = 1
        }
        val alpha = feather(crop, side)
        val m512 = FloatArray(LAMA_SIZE * LAMA_SIZE)
        for (y in 0 until LAMA_SIZE) {
            val sy = min(side - 1, (y.toLong() * side / LAMA_SIZE).toInt())
            for (x in 0 until LAMA_SIZE) {
                val sx = min(side - 1, (x.toLong() * side / LAMA_SIZE).toInt())
                m512[y * LAMA_SIZE + x] = crop[sy * side + sx].toFloat()
            }
        }
        regions.add(Region(rx, ry, side, alpha, m512))
    }
    return regions
}

/** side x side ARGB crop -> planar float 3x512x512 (0..1). */
fun toChw512(rgb: IntArray, side: Int): FloatArray {
    val n = LAMA_SIZE
    val out = FloatArray(3 * n * n)
    val scale = side.toFloat() / n
    val plane = n * n
    for (y in 0 until n) {
        val sy = ((y + 0.5f) * scale - 0.5f).coerceIn(0f, (side - 1).toFloat())
        val y0 = sy.toInt()
        val y1 = min(y0 + 1, side - 1)
        val fy = sy - y0
        for (x in 0 until n) {
            val sx = ((x + 0.5f) * scale - 0.5f).coerceIn(0f, (side - 1).toFloat())
            val x0 = sx.toInt()
            val x1 = min(x0 + 1, side - 1)
            val fx = sx - x0
            val p00 = rgb[y0 * side + x0]; val p10 = rgb[y0 * side + x1]
            val p01 = rgb[y1 * side + x0]; val p11 = rgb[y1 * side + x1]
            for (c in 0 until 3) {
                val sh = 16 - 8 * c
                val a = (p00 shr sh) and 255; val b = (p10 shr sh) and 255
                val d = (p01 shr sh) and 255; val e = (p11 shr sh) and 255
                val top = a + (b - a) * fx
                val bot = d + (e - d) * fx
                out[c * plane + y * n + x] = (top + (bot - top) * fy) / 255f
            }
        }
    }
    return out
}

/** Blend the model output (CHW 512, values 0..255) into the original crop, only where alpha > 0. */
fun compositeAlpha(rgb: IntArray, region: Region, out512: FloatArray): IntArray {
    val side = region.side
    val n = LAMA_SIZE
    val plane = n * n
    val scale = n.toFloat() / side
    val res = rgb.copyOf()
    for (y in 0 until side) {
        val sy = ((y + 0.5f) * scale - 0.5f).coerceIn(0f, (n - 1).toFloat())
        val y0 = sy.toInt()
        val y1 = min(y0 + 1, n - 1)
        val fy = sy - y0
        for (x in 0 until side) {
            val a = region.alpha[y * side + x]
            if (a <= 0.001f) continue
            val sx = ((x + 0.5f) * scale - 0.5f).coerceIn(0f, (n - 1).toFloat())
            val x0 = sx.toInt()
            val x1 = min(x0 + 1, n - 1)
            val fx = sx - x0
            val orig = rgb[y * side + x]
            var px = 0xFF shl 24
            for (c in 0 until 3) {
                val o = c * plane
                val v00 = out512[o + y0 * n + x0]; val v10 = out512[o + y0 * n + x1]
                val v01 = out512[o + y1 * n + x0]; val v11 = out512[o + y1 * n + x1]
                val top = v00 + (v10 - v00) * fx
                val bot = v01 + (v11 - v01) * fx
                val v = top + (bot - top) * fy
                val sh = 16 - 8 * c
                val ov = (orig shr sh) and 255
                val mixed = v * a + ov * (1f - a)
                px = px or (mixed.toInt().coerceIn(0, 255) shl sh)
            }
            res[y * side + x] = px
        }
    }
    return res
}

/** Full pipeline for one crop: original ARGB crop in, patched ARGB crop out. */
fun inpaintRegion(rgb: IntArray, region: Region, infer: (FloatArray, FloatArray) -> FloatArray): IntArray {
    val chw = toChw512(rgb, region.side)
    val out = infer(chw, region.mask512)
    return compositeAlpha(rgb, region, out)
}

/** YUV <-> RGB for a given matrix (BT.709 / BT.601) and range (full / limited). */
class YuvMath(bt709: Boolean, private val full: Boolean) {
    private val kr = if (bt709) 0.2126f else 0.299f
    private val kb = if (bt709) 0.0722f else 0.114f
    private val kg = 1f - kr - kb
    private val yOff = if (full) 0f else 16f
    private val yScale = if (full) 255f else 219f
    private val cScale = if (full) 255f else 224f

    fun toRgb(y: Int, u: Int, v: Int): Int {
        val yy = (y - yOff) / yScale
        val cb = (u - 128) / cScale
        val cr = (v - 128) / cScale
        val r = yy + 2f * (1f - kr) * cr
        val b = yy + 2f * (1f - kb) * cb
        val g = (yy - kr * r - kb * b) / kg
        val ri = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
        val gi = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bi = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    fun yOf(r: Int, g: Int, b: Int): Int {
        val yy = (kr * r + kg * g + kb * b) / 255f
        return (yOff + yScale * yy + 0.5f).toInt().coerceIn(0, 255)
    }

    fun uOf(r: Int, g: Int, b: Int): Int {
        val yy = (kr * r + kg * g + kb * b) / 255f
        val cb = (b / 255f - yy) / (2f * (1f - kb))
        return (128f + cScale * cb + 0.5f).toInt().coerceIn(0, 255)
    }

    fun vOf(r: Int, g: Int, b: Int): Int {
        val yy = (kr * r + kg * g + kb * b) / 255f
        val cr = (r / 255f - yy) / (2f * (1f - kr))
        return (128f + cScale * cr + 0.5f).toInt().coerceIn(0, 255)
    }
}

/** Rotate a w*h 0/1 mask 90 degrees clockwise -> (h x w) mask. */
fun rotateMaskCw(m: ByteArray, w: Int, h: Int): ByteArray {
    val o = ByteArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val nx = h - 1 - y
        val ny = x
        o[ny * h + nx] = m[y * w + x]
    }
    return o
}

fun resizeMaskNearest(m: ByteArray, w: Int, h: Int, nw: Int, nh: Int): ByteArray {
    if (w == nw && h == nh) return m
    val o = ByteArray(nw * nh)
    for (y in 0 until nh) {
        val sy = min(h - 1, (y.toLong() * h / nh).toInt())
        for (x in 0 until nw) {
            val sx = min(w - 1, (x.toLong() * w / nw).toInt())
            o[y * nw + x] = m[sy * w + sx]
        }
    }
    return o
}

// ---------------------------------------------------------------- auto detect

private fun sobelMag(g: ByteArray, w: Int, h: Int): FloatArray {
    val f = FloatArray(w * h) { (g[it].toInt() and 255).toFloat() }
    val b = FloatArray(w * h)
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        var s = 0f
        for (dy in -1..1) for (dx in -1..1) s += f[(y + dy) * w + x + dx]
        b[y * w + x] = s / 9f
    }
    val o = FloatArray(w * h)
    for (y in 2 until h - 2) for (x in 2 until w - 2) {
        val i = y * w + x
        val gx = -b[i - w - 1] + b[i - w + 1] - 2 * b[i - 1] + 2 * b[i + 1] - b[i + w - 1] + b[i + w + 1]
        val gy = -b[i - w - 1] - 2 * b[i - w] - b[i - w + 1] + b[i + w - 1] + 2 * b[i + w] + b[i + w + 1]
        o[i] = kotlin.math.sqrt(gx * gx + gy * gy)
    }
    return o
}

/**
 * Finds things that stay in the same place with strong edges in (almost) every sampled frame.
 * grays: gray frames (w*h, one unsigned byte per pixel). Returns a 0/1 mask, or null if not enough frames.
 */
fun detectStatic(grays: List<ByteArray>, w: Int, h: Int, sensitivity: Float): ByteArray? {
    val n = grays.size
    if (n < 4) return null
    val mags = Array(n) { sobelMag(grays[it], w, h) }
    val persist = FloatArray(w * h)
    val tmp = FloatArray(n)
    val k = (n * 0.25f).toInt()
    for (i in 0 until w * h) {
        for (j in 0 until n) tmp[j] = mags[j][i]
        java.util.Arrays.sort(tmp)
        persist[i] = tmp[k]
    }
    val sorted = persist.copyOf()
    sorted.sort()
    val top = sorted[min(sorted.size - 1, (sorted.size * 0.998f).toInt())]
    if (top < 10f) return ByteArray(w * h)
    val frac = 0.50f - 0.38f * sensitivity
    var bin = ByteArray(w * h) { if (persist[it] > top * frac) 1 else 0 }
    bin = Morph.close(bin, w, h, 2)
    bin = Morph.dilate(bin, w, h, 2)
    val merged = Morph.close(bin, w, h, 12)
    val comp = components(merged, w, h)
    val keep = BooleanArray(comp.count + 1)
    for (l in 1..comp.count) {
        val b = comp.bboxes[l - 1]
        val bw = b[2] - b[0] + 1
        val bh = b[3] - b[1] + 1
        keep[l] = comp.areas[l - 1] >= 60 && bw <= 0.7f * w && bh <= 0.7f * h && bw.toLong() * bh <= 0.35f * w * h
    }
    val out = ByteArray(w * h)
    for (i in 0 until w * h) {
        val l = comp.labels[i]
        if (l > 0 && keep[l]) out[i] = 1
    }
    return Morph.dilate(out, w, h, 2)
}
