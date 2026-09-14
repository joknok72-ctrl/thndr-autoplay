package com.thndr.autoplay.engine

/**
 * SCORE-MAX planner. Scoring rules reverse-engineered from 26 consecutive real screenshots (one per placed piece):
 *
 *   points(move) = (cubes + 20 × linesCleared + bonusCovered) × multAfter
 *   multAfter    = mult + orangeCubesCleared         ← permanent, multiplies EVERYTHING that follows
 *   bonus cells (50/150/…) pay when COVERED by a piece and then vanish; new ones spawn (max 3); values grow with level
 *   a round of 3 pieces = 1 level; 25 levels = 75 pieces; exactly one piece per round carries an orange cube
 *
 * Strategy: maximise the total over the remaining game, not just this move:
 *   immediate points + (multiplier gained × remaining moves × avg base) + oranges parked in nearly complete lines
 *   + board quality/survival weighted by how much is still to be earned
 *   + END BONUS: 1000 per cube left on the board when level 25 is completed (so the last moves FILL the board).
 * Weights tuned by self-play simulation under the real rules.
 */
data class Move(val slot: Int, val r: Int, val c: Int, val points: Int, val lines: Int)
data class Plan(val moves: List<Move>, val total: Int, val gameOver: Boolean, val finalMult: Int = 1)

object AI {
    private val PROBES: List<Pair<Piece, Double>> = listOf(
        Engine.shape(0 to 0, 0 to 1, 0 to 2, 1 to 0, 1 to 1, 1 to 2, 2 to 0, 2 to 1, 2 to 2) to 3.5,
        Engine.shape(0 to 0, 0 to 1, 0 to 2, 0 to 3, 0 to 4) to 2.0,
        Engine.shape(0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0) to 2.0,
        Engine.shape(0 to 0, 0 to 1, 1 to 0, 1 to 1) to 1.5,
        Engine.shape(0 to 0, 1 to 0, 2 to 0, 2 to 1, 2 to 2) to 1.5,
        Engine.shape(0 to 0, 0 to 1, 0 to 2, 1 to 1) to 1.0,
        Engine.shape(0 to 0, 0 to 1, 0 to 2, 0 to 3) to 1.0,
        Engine.shape(0 to 0, 1 to 0, 2 to 0, 3 to 0) to 1.0,
        Engine.shape(0 to 0, 1 to 0, 1 to 1) to 0.6,
        Engine.shape(0 to 0, 0 to 1, 0 to 2) to 0.6,
        Engine.shape(0 to 0, 1 to 0, 2 to 0) to 0.6,
        Engine.shape(0 to 1, 1 to 0, 1 to 1, 1 to 2, 2 to 1) to 1.0,
    )
    // board-quality weights
    private const val W_EMPTY = 1.0; private const val W_HOLES = 5.0; private const val W_TRANS = 0.9; private const val W_NEAR = 1.6
    private const val W_EDGE = 0.25; private const val W_FIT = 6.0; private const val W_ISL = 2.5; private const val W_SQ3 = 1.5; private const val W_DEAD = 15.0
    // score-max weights (tuned by simulation)
    @JvmField var K_MULT = 18.0        // value of +1 multiplier per remaining move
    @JvmField var W_SURV = 2.0         // survival weight
    @JvmField var W_ORANGE = 0.3       // oranges parked in near-complete lines
    @JvmField var W_BONUS_KEEP = 0.4   // keep uncovered bonus cells coverable
    @JvmField var END_CUBE = 1000.0    // REAL RULE: every cube still on the board after level 25 pays 1000 (only if the game is completed)
    @JvmField var END_FADE = 6         // the end-bonus fades in over the last N moves

    data class Cfg(val beam: Int, val probes: Int)
    val LEVELS = mapOf(1 to Cfg(6, 6), 2 to Cfg(14, 9), 3 to Cfg(32, 12))

    /** Real THNDR scoring. */
    fun realPoints(res: PlaceResult, mult: Int): Pair<Int, Int> {
        val nm = mult + res.orangeCleared
        return Pair((res.cells.size + 20 * res.lines + res.bonusHit) * nm, nm)
    }

    private class BQ(val q: Double, val rf: IntArray, val cf: IntArray, val bf: IntArray, val dead: Int)

    private fun boardQuality(board: IntArray, cfg: Cfg): BQ {
        var empty = 0; var holes = 0; var trans = 0; var nearFull = 0; var edge = 0
        val rf = IntArray(N); val cf = IntArray(N); val bf = IntArray(9)
        for (r in 0 until N) for (c in 0 until N) { if (board[Engine.idx(r, c)] != 0) { rf[r]++; cf[c]++; bf[(r / 3) * 3 + c / 3]++ } else empty++ }
        for (r in 0 until N) for (c in 0 until N) {
            val i = Engine.idx(r, c); val v = board[i] != 0
            if (c < N - 1 && v != (board[Engine.idx(r, c + 1)] != 0)) trans++
            if (r < N - 1 && v != (board[Engine.idx(r + 1, c)] != 0)) trans++
            if (!v) {
                val up = r == 0 || board[Engine.idx(r - 1, c)] != 0; val dn = r == N - 1 || board[Engine.idx(r + 1, c)] != 0
                val lf = c == 0 || board[Engine.idx(r, c - 1)] != 0; val rt = c == N - 1 || board[Engine.idx(r, c + 1)] != 0
                val n = (if (up) 1 else 0) + (if (dn) 1 else 0) + (if (lf) 1 else 0) + (if (rt) 1 else 0)
                if (n == 4) holes += 3 else if (n == 3) holes += 1
            } else if (r == 0 || r == N - 1 || c == 0 || c == N - 1) edge++
        }
        for (k in 0 until N) {
            if (rf[k] >= 7) nearFull += rf[k] - 6
            if (cf[k] >= 7) nearFull += cf[k] - 6
            if (bf[k] >= 7) nearFull += bf[k] - 6
        }
        var fit = 0.0; var dead = 0
        for (k in 0 until minOf(cfg.probes, PROBES.size)) {
            val (p, w) = PROBES[k]; val n = Engine.countPlacements(board, p)
            fit += minOf(n, 12) * w / 12.0
            if (n == 0) { fit -= w * 2; dead++ }
        }
        var sq3 = 0
        for (r in 0..6) for (c in 0..6) { var ok = true; loop@ for (a in 0 until 3) for (b in 0 until 3) if (board[Engine.idx(r + a, c + b)] != 0) { ok = false; break@loop }; if (ok) sq3++ }
        var islands = 0; val seen = BooleanArray(N * N); val st = ArrayDeque<Int>()
        for (i in 0 until N * N) if (board[i] != 0 && !seen[i]) {
            var size = 0; st.clear(); st.add(i); seen[i] = true
            while (st.isNotEmpty()) {
                val j = st.removeLast(); size++; val r = j / N; val c = j % N
                if (r > 0 && board[j - N] != 0 && !seen[j - N]) { seen[j - N] = true; st.add(j - N) }
                if (r < N - 1 && board[j + N] != 0 && !seen[j + N]) { seen[j + N] = true; st.add(j + N) }
                if (c > 0 && board[j - 1] != 0 && !seen[j - 1]) { seen[j - 1] = true; st.add(j - 1) }
                if (c < N - 1 && board[j + 1] != 0 && !seen[j + 1]) { seen[j + 1] = true; st.add(j + 1) }
            }
            if (size <= 2) islands++
        }
        val q = empty * W_EMPTY - holes * W_HOLES - trans * W_TRANS + nearFull * W_NEAR + edge * W_EDGE + fit * W_FIT - islands * W_ISL + sq3 * W_SQ3 - dead * W_DEAD
        return BQ(q, rf, cf, bf, dead)
    }

    private class Node(val board: IntArray, val bonus: IntArray, val mult: Int, val pts: Int, val moves: List<Move>) { var score = 0.0 }

    /** remaining = moves left in the game AFTER this node. */
    private fun evaluate(node: Node, mult0: Int, remaining: Int, cfg: Cfg): Double {
        val bq = boardQuality(node.board, cfg)
        val rem = maxOf(0, remaining)
        val multGain = (node.mult - mult0) * rem * K_MULT
        var orangePot = 0.0
        for (i in 0 until N * N) if (node.board[i] == 2) {
            val r = i / N; val c = i % N; val b = (r / 3) * 3 + c / 3
            val prog = maxOf(bq.rf[r], bq.cf[c], bq.bf[b]) / 9.0
            orangePot += rem * K_MULT * W_ORANGE * prog * prog
        }
        var bonusPot = 0.0
        if (rem > 3) for (i in 0 until N * N) if (node.bonus[i] != 0 && node.board[i] == 0) bonusPot += node.bonus[i] * node.mult * W_BONUS_KEEP * 0.3
        val survW = W_SURV * minOf(1.0, rem / 15.0) * (1 + node.mult * 0.15) * 4
        // END BONUS: in the last moves keep as many cubes as possible on the board (1000 each at the end) —
        // but never at the price of dying: the dead-board penalty is 6× stronger in that phase.
        var endVal = 0.0
        if (rem < END_FADE) { var cubes = 0; for (i in 0 until N * N) if (node.board[i] != 0) cubes++; val w = 1.0 - rem.toDouble() / END_FADE; endVal = cubes * END_CUBE * w * w }
        val deadPen = if (bq.dead > 0 && rem > 0) 400.0 * (1 + node.mult * 0.2) * (if (rem < END_FADE) 6 else 1) else 0.0
        return node.pts + multGain + orangePot + bonusPot + bq.q * survW + endVal - deadPen
    }

    private fun perms(a: List<Int>): List<List<Int>> = if (a.size <= 1) listOf(a) else a.flatMap { x -> perms(a - x).map { listOf(x) + it } }

    /**
     * @param mult   current multiplier (from the "NX" pill)
     * @param gameLevel current level 1..25 (from the "LEVEL n/25" pill)
     */
    fun plan(board: IntArray, bonus: IntArray, pieces: List<Piece?>, mult: Int, gameLevel: Int, level: Int = 3): Plan {
        val cfg = LEVELS[level.coerceIn(1, 3)]!!
        val slots = pieces.indices.filter { pieces[it] != null }
        if (slots.isEmpty()) return Plan(emptyList(), 0, false, mult)
        val mult0 = maxOf(1, mult); val lvl = gameLevel.coerceIn(1, 25)
        val remAfterRound = (25 - lvl) * 3
        var best: Node? = null
        for (order in perms(slots)) {
            var beam = listOf(Node(board, bonus, mult0, 0, emptyList()))
            for ((step, slot) in order.withIndex()) {
                val piece = pieces[slot]!!; val next = ArrayList<Node>()
                val remaining = remAfterRound + (order.size - 1 - step)
                for (node in beam) for (rc in Engine.allPlacements(node.board, piece)) {
                    val res = Engine.place(node.board, node.bonus, piece, rc[0], rc[1])
                    val (pts, nm) = realPoints(res, node.mult)
                    val nn = Node(res.board, res.bonus, nm, node.pts + pts, node.moves + Move(slot, rc[0], rc[1], pts, res.lines))
                    nn.score = evaluate(nn, mult0, remaining, cfg)
                    next.add(nn)
                }
                if (next.isEmpty()) { beam = emptyList(); break }
                next.sortByDescending { it.score }
                beam = next.take(if (step == order.size - 1) 1 else cfg.beam)
            }
            if (beam.isNotEmpty() && (best == null || beam[0].score > best.score)) best = beam[0]
        }
        val b = best ?: return Plan(emptyList(), 0, true, mult0)
        return Plan(b.moves, b.pts, false, b.mult)
    }
}
