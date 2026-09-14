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
    val tray: List<TrayPiece?>, val width: Int, val height: Int,
    val mult: Int = 1, val level: Int = 1
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
    /**
     * Bonus-cell value OCR. Legal values in THNDR are 50 / 150 / 300 / 500 / 1K / 2K, so the reading is
     * structural: connected components of grey text inside the cell → digit-shaped ones sharing a baseline.
     *   3 glyphs → first ∈ {1,3,5} → 150/300/500
     *   2 glyphs → "50" if the second glyph has a hollow centre (0) or the first is a 5; else "1K"/"2K"
     *   otherwise → 50 (safe default: never over-estimate an unreadable cell).
     */
    private fun estimateBonus(px: IntArray, W: Int, H: Int, cx: Int, cy: Int, pitch: Float, cellBg: Int, cellLum: Int): Int {
        val half = (pitch * 0.5f).toInt()
        val x0 = maxOf(0, cx - half); val y0 = maxOf(0, cy - half)
        val x1 = minOf(W - 1, cx + half); val y1 = minOf(H - 1, cy + half)
        val w = x1 - x0 + 1; val h = y1 - y0 + 1
        if (w < 12 || h < 12) return 50
        val pad = (w * 0.08f).toInt()
        val m = BooleanArray(w * h) { i ->
            val yy = i / w; val xx = i % w
            if (yy < pad || yy >= h - pad || xx < pad || xx >= w - pad) false else {
                val p = px[(y0 + yy) * W + x0 + xx]
                mx(p) > cellLum + 50 && sat(p) < 90 && dist(p, cellBg) > 90
            }
        }
        // connected components (4-neighbour)
        val lab = IntArray(w * h); val stack = IntArray(w * h + 1); var n = 0
        class C(val id: Int, var x0: Int, var y0: Int, var x1: Int, var y1: Int)
        val comps = ArrayList<C>()
        for (s in 0 until w * h) {
            if (!m[s] || lab[s] != 0) continue
            n++; var sp = 0; stack[sp++] = s; lab[s] = n
            val c = C(n, s % w, s / w, s % w, s / w)
            while (sp > 0) {
                val j = stack[--sp]; val jy = j / w; val jx = j % w
                if (jx < c.x0) c.x0 = jx; if (jx > c.x1) c.x1 = jx; if (jy < c.y0) c.y0 = jy; if (jy > c.y1) c.y1 = jy
                if (jy > 0 && m[j - w] && lab[j - w] == 0) { lab[j - w] = n; stack[sp++] = j - w }
                if (jy < h - 1 && m[j + w] && lab[j + w] == 0) { lab[j + w] = n; stack[sp++] = j + w }
                if (jx > 0 && m[j - 1] && lab[j - 1] == 0) { lab[j - 1] = n; stack[sp++] = j - 1 }
                if (jx < w - 1 && m[j + 1] && lab[j + 1] == 0) { lab[j + 1] = n; stack[sp++] = j + 1 }
            }
            val gh = c.y1 - c.y0 + 1; val gw = c.x1 - c.x0 + 1; val cyf = (c.y0 + c.y1) / 2f / h
            if (gh >= h * 0.22f && gh <= h * 0.5f && gw >= 3 && gw <= gh * 1.15f && cyf > 0.3f && cyf < 0.7f) comps.add(c)
        }
        if (comps.isEmpty()) return 50
        comps.sortBy { it.x0 }
        var best: List<C> = emptyList()
        for (a in comps) {
            val grp = comps.filter { b -> minOf(a.y1, b.y1) - maxOf(a.y0, b.y0) > 0.6f * (a.y1 - a.y0) }
            if (grp.size > best.size) best = grp
        }
        fun glyphOf(c: C): Glyph {
            val gw = c.x1 - c.x0 + 1; val gh = c.y1 - c.y0 + 1
            return Glyph(BooleanArray(gw * gh) { lab[(c.y0 + it / gw) * w + c.x0 + it % gw] == c.id }, gw, gh)
        }
        fun hollow(g: Glyph): Boolean {
            var on = 0; var tot = 0
            for (yy in (g.gh * 0.35f).toInt()..(g.gh * 0.65f).toInt()) for (xx in (g.gw * 0.3f).toInt()..(g.gw * 0.7f).toInt()) {
                if (yy < g.gh && xx < g.gw) { tot++; if (g.bits[yy * g.gw + xx]) on++ }
            }
            return tot > 0 && on < 0.25f * tot
        }
        return when (best.size) {
            3 -> when (readGlyphAmong(glyphOf(best[0]), "135")) { '1' -> 150; '3' -> 300; else -> 500 }
            2 -> {
                val first = readGlyphAmong(glyphOf(best[0]), "125")
                if (first == '5' || hollow(glyphOf(best[1]))) 50 else if (first == '1') 1000 else 2000
            }
            else -> 50
        }
    }

    // ---------- HUD text (multiplier "NX" pill bottom-left, "LEVEL n/25" pill bottom-right) ----------
    // 7x12 grey-level templates (0..3 per cell) learned from 34 real screenshots; nearest-template matching.
    private const val GW = 7; private const val GH = 12
    private val GLYPHS: Map<Char, FloatArray> = mapOf(
        '/' to "000133300023310003330000333000233100033300003330001332000333000033300023320003331000",
        '0' to "023332013333312332332331013333101333310023331002333100233310133233133213333310233320",
        '1' to "012333323333333333333321333310023330002333000233300023330002333000233300023330002333",
        '2' to "023331023333323332333331023311002330000332000233100233100133200233311133333333333333",
        '3' to "023332013333313332332121023200123320023320002332100123322200233332123333333321333331",
        '4' to "001333100233310033330013333002333301332330232133033223313333333333333322223320001331",
        '5' to "233333233333323322221331000033222103333331233233311102332200133332023333323321333321",
        '6' to "013332003333312332333232012133212103333331333233333201333310133232123313333320233330",
        '7' to "333333333333332222333000033300013320002331000332000133100023200013310002330001332000",
        '8' to "023332013333312331333232023313323320233320123332123323323320133332023323323321333331",
        '9' to "023331013333313332332332023333202333332333233333312333331231233233233323333311333320",
        'E' to "333333333333333320000331000033200003333332333333333200003310000332000033333333333333",
        'L' to "331000033100003310000331000033100003310000331000033100003310000332000033333333333333",
        'V' to "220002333001333300132231013113102311320230022022002313200232310013331000333000033300",
        'X' to "331013323202321332331023332001333200133310003330001333100233320033233013202323310233",
    ).mapValues { e -> FloatArray(GW * GH) { i -> (e.value[i] - '0') / 4f + 0.125f } }
    private class Glyph(val bits: BooleanArray, val gw: Int, val gh: Int)
    private fun glyphs(px: IntArray, W: Int, x0: Int, x1: Int, y0: Int, y1: Int, minH: Int): List<Glyph> {
        val w = x1 - x0; val h = y1 - y0
        val m = BooleanArray(w * h) { val p = px[(y0 + it / w) * W + x0 + it % w]; mx(p) > 170 && sat(p) < 80 }
        val out = ArrayList<Glyph>()
        var x = 0
        while (x < w) {
            var on = false; for (y in 0 until h) if (m[y * w + x]) { on = true; break }
            if (!on) { x++; continue }
            val sx = x
            while (x < w) { var any = false; for (y in 0 until h) if (m[y * w + x]) { any = true; break }; if (!any) break; x++ }
            val ex = x - 1
            var ry0 = h; var ry1 = -1
            for (y in 0 until h) for (xx in sx..ex) if (m[y * w + xx]) { if (y < ry0) ry0 = y; if (y > ry1) ry1 = y }
            val gh = ry1 - ry0 + 1; val gw = ex - sx + 1
            if (gh >= minH && gw >= 3) out.add(Glyph(BooleanArray(gw * gh) { m[(ry0 + it / gw) * w + sx + it % gw] }, gw, gh))
        }
        return out
    }
    private fun readGlyph(g: Glyph): Char {
        val v = FloatArray(GW * GH)
        for (yy in 0 until GH) for (xx in 0 until GW) {
            val sx0 = (xx * g.gw / GW.toFloat()).toInt(); val sx1 = (xx + 1) * g.gw / GW.toFloat()
            val sy0 = (yy * g.gh / GH.toFloat()).toInt(); val sy1 = (yy + 1) * g.gh / GH.toFloat()
            var sum = 0f; var cnt = 0f
            var y = sy0; while (y < sy1 && y < g.gh) { var x = sx0; while (x < sx1 && x < g.gw) { if (g.bits[y * g.gw + x]) sum++; cnt++; x++ }; y++ }
            v[yy * GW + xx] = if (cnt > 0) sum / cnt else 0f
        }
        var best = '?'; var bestD = Float.MAX_VALUE
        for ((ch, t) in GLYPHS) { var d = 0f; for (i in v.indices) d += abs(v[i] - t[i]); if (d < bestD) { bestD = d; best = ch } }
        return best
    }
    private fun glyphVec(g: Glyph): FloatArray {
        val v = FloatArray(GW * GH)
        for (yy in 0 until GH) for (xx in 0 until GW) {
            val sx0 = (xx * g.gw / GW.toFloat()).toInt(); val sx1 = (xx + 1) * g.gw / GW.toFloat()
            val sy0 = (yy * g.gh / GH.toFloat()).toInt(); val sy1 = (yy + 1) * g.gh / GH.toFloat()
            var sum = 0f; var cnt = 0f
            var y = sy0; while (y < sy1 && y < g.gh) { var x = sx0; while (x < sx1 && x < g.gw) { if (g.bits[y * g.gw + x]) sum++; cnt++; x++ }; y++ }
            v[yy * GW + xx] = if (cnt > 0) sum / cnt else 0f
        }
        return v
    }
    /** Nearest template restricted to [chars]. */
    private fun readGlyphAmong(g: Glyph, chars: String): Char {
        val v = glyphVec(g)
        var best = chars[0]; var bestD = Float.MAX_VALUE
        for (ch in chars) { val t = GLYPHS[ch] ?: continue; var d = 0f; for (i in v.indices) d += abs(v[i] - t[i]); if (d < bestD) { bestD = d; best = ch } }
        return best
    }
    /** Returns (mult, level) or (0,0) parts when unreadable. */
    private fun readHud(px: IntArray, W: Int, H: Int, by1: Int, pitch: Float): Pair<Int, Int> {
        val y0 = (by1 + pitch * 0.4f).toInt().coerceIn(0, H - 2); val y1 = (by1 + pitch * 1.6f).toInt().coerceIn(y0 + 1, H - 1)
        val minH = (pitch * 0.2f).toInt()
        var mult = 0; var level = 0
        try {
            val ms = glyphs(px, W, 4, W / 2, y0, y1, minH).map { readGlyph(it) }.joinToString("")
            val digits = ms.substringBefore('X').filter { it.isDigit() }
            if (ms.contains('X') && digits.isNotEmpty()) mult = digits.toInt()
        } catch (_: Throwable) {}
        try {
            val ls = glyphs(px, W, W / 2, W - 4, y0, y1, minH).map { readGlyph(it) }.joinToString("")
            val li = ls.indexOf("LEVEL"); val si = ls.indexOf('/')
            if (li >= 0 && si > li + 5) { val d = ls.substring(li + 5, si).filter { it.isDigit() }; if (d.isNotEmpty()) level = d.toInt() }
        } catch (_: Throwable) {}
        return Pair(mult.coerceIn(0, 99), level.coerceIn(0, 25))
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
        // group cubes into pieces by PROXIMITY (a wide piece may straddle the 1/3 boundaries), then map groups to the 3 slots
        val parent = IntArray(good.size) { it }
        fun find(i: Int): Int { var a = i; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
        val tol = P * 0.6f
        for (i in good.indices) for (j in i + 1 until good.size) {
            val a = good[i]; val b = good[j]
            if (a.x0 - tol <= b.x1 && b.x0 - tol <= a.x1 && a.y0 - tol <= b.y1 && b.y0 - tol <= a.y1) parent[find(i)] = find(j)
        }
        val groupsMap = HashMap<Int, ArrayList<Comp>>()
        for (i in good.indices) groupsMap.getOrPut(find(i)) { ArrayList() }.add(good[i])
        val groups = groupsMap.values.sortedBy { g -> (g.minOf { it.x0 } + g.maxOf { it.x1 }) / 2f }
        val slots = listOf(ArrayList<Comp>(), ArrayList<Comp>(), ArrayList<Comp>())
        if (groups.size <= 3) {
            for (g in groups) {
                val cx = (g.minOf { it.x0 } + g.maxOf { it.x1 }) / 2f
                var si = minOf(2, (cx / (W / 3f)).toInt())
                while (si < 3 && slots[si].isNotEmpty()) si++
                if (si > 2) { si = 2; while (si > 0 && slots[si].isNotEmpty()) si-- }
                slots[si].addAll(g)
            }
        } else for (g in groups) slots[minOf(2, (((g.minOf { it.x0 } + g.maxOf { it.x1 }) / 2f) / (W / 3f)).toInt())].addAll(g)
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
        val (hudMult, hudLevel) = readHud(px, W, H, by1, pitch)
        return Screen(board, bonus, bx0, by0, bx1, by1, pitch, tray, W, H, if (hudMult > 0) hudMult else 1, if (hudLevel > 0) hudLevel else 1)
    }
}
