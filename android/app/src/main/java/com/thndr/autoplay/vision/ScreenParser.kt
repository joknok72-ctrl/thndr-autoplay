package com.thndr.autoplay.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.thndr.autoplay.engine.Cell
import com.thndr.autoplay.engine.N
import com.thndr.autoplay.engine.Piece
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * THEME-AGNOSTIC screen reader for THNDR Block.
 *  - page background  : most common color on the right margin
 *  - board            : largest square whose rows/cols are 70–97% "non-page-bg" (the 8 thin gaps keep it < 100%)
 *  - empty-cell color : most common low-saturation color at the 81 cell centers
 *  - cube             : saturated & bright pixels (any hue: blue, green, pink, …); icons inside cubes are ignored
 *  - SPECIAL cube     : hue learned live from the small multiplier icon next to "1X" under the board
 *  - bonus cell (50…) : low-saturation light text on an empty cell
 * Verified on: blue theme, dark/green+yellow theme, dark/blue-swords+orange theme.
 */
data class TrayPiece(val piece: Piece, val cx: Float, val cy: Float, val cubePx: Float, val x0: Int, val y0: Int, val x1: Int, val y1: Int)
data class Screen(
    val board: IntArray, val bonus: IntArray,
    val bx0: Int, val by0: Int, val bx1: Int, val by1: Int, val pitch: Float,
    val tray: List<TrayPiece?>, val width: Int, val height: Int
) {
    fun cellCenter(r: Int, c: Int): Pair<Float, Float> = Pair(bx0 + (c + 0.5f) * pitch, by0 + (r + 0.5f) * pitch)
    val piecesFound get() = tray.count { it != null }
    fun boardString(): String { val sb = StringBuilder(); for (r in 0 until N) { for (c in 0 until N) { val i = r * N + c; sb.append(when { board[i] == 1 -> '#'; board[i] == 2 -> 'O'; bonus[i] != 0 -> '$'; else -> '.' }) }; sb.append('\n') }; return sb.toString() }
}

object ScreenParser {
    class ParseException(msg: String) : Exception(msg)

    private fun r(p: Int) = (p shr 16) and 0xff
    private fun g(p: Int) = (p shr 8) and 0xff
    private fun b(p: Int) = p and 0xff
    private fun mx(p: Int) = maxOf(r(p), g(p), b(p))
    private fun mn(p: Int) = minOf(r(p), g(p), b(p))
    private fun sat(p: Int) = mx(p) - mn(p)
    private fun dist(p: Int, q: Int) = abs(r(p) - r(q)) + abs(g(p) - g(q)) + abs(b(p) - b(q))
    private fun hue(p: Int): Float {
        val r = r(p); val g = g(p); val b = b(p); val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = (mx - mn).toFloat()
        if (d <= 0f) return 0f
        var h = when (mx) { r -> ((g - b) / d) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f } * 60f
        if (h < 0f) h += 360f
        return h
    }
    private fun hueDist(a: Float, b: Float): Float { val d = abs(a - b); return minOf(d, 360f - d) }

    private fun clusters(idx: List<Int>, gap: Int): List<IntArray> {
        if (idx.isEmpty()) return emptyList()
        val out = ArrayList<IntArray>(); var s = idx[0]; var p = idx[0]
        for (k in 1 until idx.size) { val x = idx[k]; if (x - p > gap) { out.add(intArrayOf(s, p)); s = x }; p = x }
        out.add(intArrayOf(s, p)); return out
    }
    private fun mode(colors: IntArray): Int {
        val m = HashMap<Int, Int>(); for (c in colors) { val q = (c and 0xfcfcfc); m[q] = (m[q] ?: 0) + 1 }
        return m.maxByOrNull { it.value }?.key ?: 0
    }

    /**
     * Bonus text is light, low-saturation glyphs on an empty cell. We count glyph columns in the cell:
     *  "50" -> 2 glyphs, "150"/"300"/"500" -> 3 glyphs (150 is the most common), "1K"/"2K" -> 2 glyphs but narrower total.
     * The exact value matters less than "how big" — the AI just needs the ranking.
     */
    private fun estimateBonus(px: IntArray, W: Int, H: Int, cx: Int, cy: Int, pitch: Float, cellBg: Int, cellLum: Int): Int {
        val k = (pitch * 0.44f).toInt()
        val x0 = maxOf(0, cx - k); val x1 = minOf(W - 1, cx + k); val y0 = maxOf(0, cy - k); val y1 = minOf(H - 1, cy + k)
        val colP = IntArray(x1 - x0 + 1); var total = 0
        for (y in y0..y1) for (x in x0..x1) { val p = px[y * W + x]; if (mx(p) > cellLum + 50 && sat(p) < 90 && dist(p, cellBg) > 90) { colP[x - x0]++; total++ } }
        var runs = 0; var on = false; var width = 0; var first = -1; var last = -1
        for (i in colP.indices) { val v = colP[i] > 0; if (v) { if (first < 0) first = i; last = i; width++ }; if (v && !on) runs++; on = v }
        if (total < 6) return 50
        val span = if (first >= 0) (last - first + 1) / pitch else 0f
        return when {
            runs >= 3 -> if (span > 0.62f) 300 else 150     // 3 digits
            span < 0.36f -> 1000                              // "1K" is narrow (thin 1 + K)
            else -> 50
        }
    }

    fun parse(bmp: Bitmap): Screen {
        val W = bmp.width; val H = bmp.height
        val px = IntArray(W * H); bmp.getPixels(px, 0, W, 0, 0, W, H)

        // ---- 1) page background from the right margin strip ----
        val strip = ArrayList<Int>()
        for (y in (H * 0.2).toInt() until (H * 0.7).toInt()) for (x in W - 8 until W - 2) strip.add(px[y * W + x])
        val pg = mode(strip.toIntArray())

        // ---- 2) board rectangle ----
        val nonbg = BooleanArray(W * H) { dist(px[it], pg) > 9 }
        val gapPx = maxOf(6, W / 30)
        val rowFrac = FloatArray(H); for (y in 0 until H) { var n = 0; val off = y * W; for (x in 0 until W) if (nonbg[off + x]) n++; rowFrac[y] = n / W.toFloat() }
        val rows = (0 until H).filter { rowFrac[it] > 0.7f && rowFrac[it] < 0.975f }
        var best: IntArray? = null
        for (rc in clusters(rows, gapPx)) {
            val ry0 = rc[0]; val ry1 = rc[1]
            if (ry1 - ry0 < W * 0.5) continue
            val cf = FloatArray(W); for (x in 0 until W) { var n = 0; for (y in ry0..ry1) if (nonbg[y * W + x]) n++; cf[x] = n / maxOf(1, ry1 - ry0).toFloat() }
            val cols = (0 until W).filter { cf[it] > 0.7f && cf[it] < 0.985f }
            if (cols.isEmpty()) continue
            val cc = clusters(cols, gapPx).maxByOrNull { it[1] - it[0] }!!
            val cx0 = cc[0]; val cx1 = cc[1]; val side = cx1 - cx0
            if (side < W * 0.6) continue
            val rows2 = (ry0..ry1).filter { y -> var n = 0; for (x in cx0..cx1) if (nonbg[y * W + x]) n++; n > 0.75 * side }
            val cands = clusters(rows2, gapPx).map { intArrayOf(abs((it[1] - it[0]) - side), it[0], it[1]) }.sortedBy { it[0] }
            var found = false
            for (cd in cands) if (cd[0] <= side * 0.1) { if (best == null || side > best!![4]) best = intArrayOf(cx0, cd[1], cx1, cd[2], side); found = true; break }
            if (!found && rows2.isNotEmpty()) {
                val last = clusters(rows2, gapPx).last()
                if (last[1] - last[0] > side * 1.1) best = intArrayOf(cx0, last[1] - side, cx1, last[1], side)
            }
        }
        val bb = best ?: throw ParseException("board not found")
        val bx0 = bb[0]; val by0 = bb[1]; val bx1 = bb[2]; val by1 = bb[3]
        val pitch = ((bx1 - bx0 + 1) / 9f + (by1 - by0 + 1) / 9f) / 2f
        val k = maxOf(2, (pitch * 0.3f).toInt())

        // ---- 3) empty-cell color = mode of low-saturation cell centers ----
        val cents = ArrayList<Int>()
        for (i in 0 until N) for (j in 0 until N) {
            val cy = (by0 + (i + 0.5f) * pitch).toInt(); val cx = (bx0 + (j + 0.5f) * pitch).toInt()
            val p = px[cy * W + cx]; if (sat(p) < 100) cents.add(p)
        }
        val cellBg = if (cents.isNotEmpty()) mode(cents.toIntArray()) else pg
        val cellLum = mx(cellBg)

        // ---- 4) special (multiplier) hue from the small icon next to "1X" under the board ----
        var specialHue = -1f
        run {
            val ay0 = (by1 + pitch * 0.4f).toInt(); val ay1 = minOf(H - 1, (by1 + pitch * 1.6f).toInt())
            val ax0 = (W * 0.03f).toInt(); val ax1 = (W * 0.3f).toInt()
            val hs = ArrayList<Float>()
            for (y in ay0..ay1) for (x in ax0..ax1) { val p = px[y * W + x]; if (sat(p) >= 105 && mx(p) >= 140) hs.add(hue(p)) }
            if (hs.size >= 20) { hs.sort(); specialHue = hs[hs.size / 2] }
        }
        fun isSpecial(h: Float) = if (specialHue < 0) h in 15f..70f else hueDist(h, specialHue) < 35f

        // ---- 5) cells ----
        val board = IntArray(N * N); val bonus = IntArray(N * N)
        for (i in 0 until N) for (j in 0 until N) {
            val cy = (by0 + (i + 0.5f) * pitch).toInt(); val cx = (bx0 + (j + 0.5f) * pitch).toInt()
            var tot = 0; var nBlock = 0; var nText = 0; var nSpecial = 0; var nHue = 0
            for (y in cy - k until cy + k) for (x in cx - k until cx + k) {
                if (y < 0 || y >= H || x < 0 || x >= W) continue
                val p = px[y * W + x]; tot++
                val s = sat(p); val l = mx(p)
                val white = s < 40 && l > 200
                if (s >= 100 && l >= 170 && !white) { nBlock++; nHue++; if (isSpecial(hue(p))) nSpecial++ }
                else if (s < 100 && l > cellLum + 50 && !white && dist(p, cellBg) > 90) nText++
            }
            val idx = i * N + j
            if (nBlock > 0.3 * tot) board[idx] = if (nHue > 0 && nSpecial > nHue / 2) 2 else 1
            else if (nText > 0.02 * tot) bonus[idx] = estimateBonus(px, W, H, cx, cy, pitch, cellBg, cellLum)
        }

        // ---- 6) tray: read the 3 pieces EXACTLY as drawn ----
        // (a) cube mask = saturated & bright pixels whose hue is the MAIN cube hue (learned from the tray itself) or the special hue.
        //     Anything else (overlay rings, green numbers, text, icons) is ignored.
        // (b) cube size self-calibrated from horizontal run lengths (robust even if the board pitch is slightly off).
        // (c) erosion cuts thin bridges, components must have grid-compatible dimensions (n*P - gap).
        // (d) each slot → bounding box → nr×nc grid by the known pitch → a cell is a cube if >50% of its center patch is mask.
        val ty0 = minOf(H - 1, (by1 + pitch * 2.9f).toInt())
        val th = H - ty0
        val raw = BooleanArray(W * th) { val y = it / W + ty0; val x = it % W; val p = px[y * W + x]; sat(p) >= 90 && mx(p) >= 120 }
        val hueArr = FloatArray(W * th) { if (raw[it]) hue(px[(it / W + ty0) * W + it % W]) else -1f }
        // main hue = dominant 10° bin among non-special saturated tray pixels, refined to the median of its ±20° neighbourhood
        val bins = IntArray(36)
        for (i in 0 until W * th) if (raw[i]) { val h = hueArr[i]; if (!isSpecial(h)) bins[(h / 10f).toInt().coerceIn(0, 35)]++ }
        var mainHue = -1f
        if (bins.sum() >= 20) {
            var bi = 0; for (i in 1 until 36) if (bins[i] > bins[bi]) bi = i
            val center = bi * 10f + 5f
            val near = ArrayList<Float>()
            for (i in 0 until W * th) if (raw[i] && hueDist(hueArr[i], center) < 20f) near.add(hueArr[i])
            mainHue = if (near.isNotEmpty()) { near.sort(); near[near.size / 2] } else center
        }
        val m = BooleanArray(W * th) { raw[it] && mainHue >= 0 && (hueDist(hueArr[it], mainHue) < 28f || isSpecial(hueArr[it])) }
        // cube geometry: prior from the board pitch, then self-calibrated from run lengths
        var P = pitch * 0.514f; var cube = P * 0.885f
        run {
            val rl = ArrayList<Int>()
            var y = 0
            while (y < th) {
                var x = 0
                while (x < W) { if (m[y * W + x]) { val s = x; while (x < W && m[y * W + x]) x++; rl.add(x - s) } else x++ }
                y += 2
            }
            val cand = rl.filter { it > cube * 0.6f && it < cube * 1.4f }.sorted()
            if (cand.size >= 10) { cube = cand[cand.size / 2].toFloat(); P = cube / 0.885f }
        }
        val gap = P - cube
        val ek = maxOf(1, (cube * 0.12f).toInt())
        val er = BooleanArray(W * th) { i ->
            val y = i / W; val x = i % W
            m[i] && y - ek >= 0 && y + ek < th && x - ek >= 0 && x + ek < W && m[(y - ek) * W + x] && m[(y + ek) * W + x] && m[y * W + x - ek] && m[y * W + x + ek]
        }
        val lab = IntArray(W * th)
        class Comp(var y0: Int, var y1: Int, var x0: Int, var x1: Int, var n: Int)
        val comps = ArrayList<Comp>(); val stack = IntArray(W * th + 1); var labN = 0
        for (s in 0 until W * th) {
            if (!er[s] || lab[s] != 0) continue
            labN++; var sp = 0; stack[sp++] = s; lab[s] = labN
            val comp = Comp(s / W, s / W, s % W, s % W, 0)
            while (sp > 0) {
                val j = stack[--sp]; comp.n++
                val jy = j / W; val jx = j % W
                if (jy < comp.y0) comp.y0 = jy; if (jy > comp.y1) comp.y1 = jy; if (jx < comp.x0) comp.x0 = jx; if (jx > comp.x1) comp.x1 = jx
                if (jy > 0) { val n2 = j - W; if (er[n2] && lab[n2] == 0) { lab[n2] = labN; stack[sp++] = n2 } }
                if (jy < th - 1) { val n2 = j + W; if (er[n2] && lab[n2] == 0) { lab[n2] = labN; stack[sp++] = n2 } }
                if (jx > 0) { val n2 = j - 1; if (er[n2] && lab[n2] == 0) { lab[n2] = labN; stack[sp++] = n2 } }
                if (jx < W - 1) { val n2 = j + 1; if (er[n2] && lab[n2] == 0) { lab[n2] = labN; stack[sp++] = n2 } }
            }
            comp.y0 -= ek; comp.y1 += ek; comp.x0 -= ek; comp.x1 += ek   // undo the erosion
            comps.add(comp)
        }
        fun fits(d: Int): Boolean { for (n in 1..5) if (abs(d - (n * P - gap)) <= P * 0.3f) return true; return false }
        val good = comps.filter { c -> val w = c.x1 - c.x0 + 1; val h = c.y1 - c.y0 + 1; fits(w) && fits(h) && w < W * 0.4f && c.n > cube * cube * 0.3f }
        val slots = listOf(ArrayList<Comp>(), ArrayList<Comp>(), ArrayList<Comp>())
        for (cp in good) slots[minOf(2, (((cp.x0 + cp.x1) / 2f) / (W / 3f)).toInt())].add(cp)
        val kk = maxOf(2, (cube * 0.3f).toInt())
        val tray = ArrayList<TrayPiece?>()
        for (sl in slots) {
            if (sl.isEmpty()) { tray.add(null); continue }
            val minx = sl.minOf { it.x0 }; val miny = sl.minOf { it.y0 }; val maxx = sl.maxOf { it.x1 }; val maxy = sl.maxOf { it.y1 }
            val w = maxx - minx + 1; val h = maxy - miny + 1
            val nc = ((w + gap) / P).roundToInt().coerceIn(1, 5); val nr = ((h + gap) / P).roundToInt().coerceIn(1, 5)
            val Px = (w + gap) / nc; val Py = (h + gap) / nr
            val cells = ArrayList<Cell>()
            for (ri in 0 until nr) for (ci in 0 until nc) {
                val cy = (miny + ri * Py + cube / 2f).toInt(); val cx = (minx + ci * Px + cube / 2f).toInt()
                var tot = 0; var on = 0; var nH = 0; var nS = 0
                for (y in cy - kk..cy + kk) for (x in cx - kk..cx + kk) {
                    if (y !in 0 until th || x !in 0 until W) continue
                    tot++
                    val gi = y * W + x
                    if (m[gi]) on++
                    if (raw[gi]) { nH++; if (isSpecial(hueArr[gi])) nS++ }
                }
                if (tot > 0 && on > 0.5f * tot) cells.add(Cell(ri, ci, if (nH > 0 && nS > nH / 2) 2 else 1))
            }
            if (cells.isEmpty()) { tray.add(null); continue }
            tray.add(TrayPiece(Piece(cells), (minx + maxx) / 2f, (miny + maxy) / 2f + ty0, cube, minx, miny + ty0, maxx, maxy + ty0))
        }
        return Screen(board, bonus, bx0, by0, bx1, by1, pitch, tray, W, H)
    }
}
