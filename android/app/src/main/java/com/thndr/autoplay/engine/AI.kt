package com.thndr.autoplay.engine

/**
 * Strongest-move planner: all 6 orderings x beam search x board-quality heuristic.
 * Ported from the web version (weights tuned by self-play: 10/10 survival over 25 levels).
 */
data class Move(val slot: Int, val r: Int, val c: Int, val points: Int, val lines: Int)
data class Plan(val moves: List<Move>, val total: Int, val gameOver: Boolean)

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
    // weights
    private const val W_EMPTY = 1.0; private const val W_HOLES = 5.0; private const val W_TRANS = 0.9; private const val W_NEAR = 1.6
    private const val W_EDGE = 0.25; private const val W_FIT = 6.0; private const val W_ISL = 2.5; private const val W_SQ3 = 1.5
    private const val W_DEAD = 15.0; private const val W_PTS = 0.5; private const val W_MULT = 45.0

    data class Cfg(val beam: Int, val probes: Int)
    val LEVELS = mapOf(1 to Cfg(6, 6), 2 to Cfg(14, 9), 3 to Cfg(32, 9), 4 to Cfg(48, 12))
    // Level 4 ("خارق"): after beam search, re-rank the top candidates by a 1-round LOOKAHEAD against
    // hard future rounds (big/awkward pieces). Chooses the plan that stays alive AND scores next round too.
    private val HARD_ROUNDS: List<List<Piece>> = listOf(
        listOf(Engine.shape(0 to 0, 0 to 1, 0 to 2, 1 to 0, 1 to 1, 1 to 2, 2 to 0, 2 to 1, 2 to 2), Engine.shape(0 to 0, 0 to 1, 0 to 2, 0 to 3, 0 to 4), Engine.shape(0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0)),
        listOf(Engine.shape(0 to 0, 1 to 0, 2 to 0, 2 to 1, 2 to 2), Engine.shape(0 to 0, 0 to 1, 0 to 2, 1 to 0, 1 to 1, 1 to 2, 2 to 0, 2 to 1, 2 to 2), Engine.shape(0 to 0, 0 to 1, 0 to 2, 0 to 3)),
        listOf(Engine.shape(0 to 0, 0 to 1, 1 to 0, 1 to 1), Engine.shape(0 to 0, 0 to 1, 1 to 0, 1 to 1), Engine.shape(0 to 0, 0 to 1, 0 to 2, 0 to 3, 0 to 4)),
        listOf(Engine.shape(0 to 0, 0 to 1, 0 to 2, 1 to 1), Engine.shape(0 to 0, 0 to 1, 0 to 2, 1 to 2, 2 to 2), Engine.shape(0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0)),
    )

    fun evaluate(board: IntArray, bonus: IntArray, cfg: Cfg): Double {
        var empty = 0; var holes = 0; var trans = 0; var nearFull = 0; var edge = 0
        val rf = IntArray(N); val cf = IntArray(N)
        for (r in 0 until N) for (c in 0 until N) { if (board[Engine.idx(r, c)] != 0) { rf[r]++; cf[c]++ } else empty++ }
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
            val br = (k / 3) * 3; val bc = (k % 3) * 3; var bf = 0
            for (r in br until br + 3) for (c in bc until bc + 3) if (board[Engine.idx(r, c)] != 0) bf++
            if (bf >= 7) nearFull += bf - 6
        }
        var fit = 0.0; var dead = 0
        for (k in 0 until minOf(cfg.probes, PROBES.size)) {
            val (p, w) = PROBES[k]; val n = Engine.countPlacements(board, p)
            fit += minOf(n, 12) * w / 12.0
            if (n == 0) { fit -= w * 2; dead++ }
        }
        var sq3 = 0
        for (r in 0..6) for (c in 0..6) { var ok = true; loop@ for (a in 0 until 3) for (b in 0 until 3) if (board[Engine.idx(r + a, c + b)] != 0) { ok = false; break@loop }; if (ok) sq3++ }
        // small islands
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
        // Bonus cells (50..2K) pay out when their row/col/box is CLEARED — and they vanish once covered.
        // Reward boards where the lines through a bonus cell are close to completion (cell itself still empty),
        // scaled by the value. This makes the AI actively build toward clearing them.
        var bonusPot = 0.0
        for (i in 0 until N * N) if (bonus[i] != 0 && board[i] == 0) {
            val r = i / N; val c = i % N; val br = (r / 3) * 3; val bc = (c / 3) * 3; var bf = 0
            for (rr in br until br + 3) for (cc in bc until bc + 3) if (board[Engine.idx(rr, cc)] != 0) bf++
            val prog = maxOf(rf[r], cf[c], bf) / 8.0          // 8 = all other cells filled, ready to complete
            val v = Math.log10(bonus[i].toDouble()) - 1.0      // 50 -> 0.7, 150 -> 1.18, 1K -> 2, 2K -> 2.3
            bonusPot += v * (4.0 + 14.0 * prog * prog)
        }
        return empty * W_EMPTY - holes * W_HOLES - trans * W_TRANS + nearFull * W_NEAR + edge * W_EDGE + fit * W_FIT - islands * W_ISL + bonusPot + sq3 * W_SQ3 - dead * W_DEAD
    }

    private class Node(val board: IntArray, val bonus: IntArray, val streak: Int, val mult: Int, val pts: Int, val score: Double, val moves: List<Move>)

    private fun perms(a: List<Int>): List<List<Int>> = if (a.size <= 1) listOf(a) else a.flatMap { x -> perms(a - x).map { listOf(x) + it } }

    fun plan(board: IntArray, bonus: IntArray, pieces: List<Piece?>, streak: Int, mult: Int, level: Int = 3): Plan {
        val cfg = LEVELS[level] ?: LEVELS[3]!!
        val slots = pieces.indices.filter { pieces[it] != null }
        if (slots.isEmpty()) return Plan(emptyList(), 0, false)
        val cands = ArrayList<Node>()
        val keepFinal = if (level >= 4) 4 else 1
        for (order in perms(slots)) {
            var beam = listOf(Node(board, bonus, streak, mult, 0, 0.0, emptyList()))
            for ((step, slot) in order.withIndex()) {
                val piece = pieces[slot]!!; val next = ArrayList<Node>()
                for (node in beam) for (rc in Engine.allPlacements(node.board, piece)) {
                    val res = Engine.place(node.board, node.bonus, piece, rc[0], rc[1])
                    val sc = Engine.score(res, node.streak, node.mult)
                    val pts = node.pts + sc.points
                    // combo bonus: clearing 2+ lines/boxes in one drop is worth extra (streak & multiplier synergy)
                    val combo = if (sc.lines >= 2) 25.0 * (sc.lines - 1) else 0.0
                    val bonusHit = res.bonusHit * 0.35 * node.mult                    // collecting a bonus cell is a priority
                    val heur = evaluate(res.board, res.bonus, cfg) + (sc.mult - node.mult) * W_MULT + combo + bonusHit
                    next.add(Node(res.board, res.bonus, sc.streak, sc.mult, pts, pts * W_PTS + heur, node.moves + Move(slot, rc[0], rc[1], sc.points, sc.lines)))
                }
                if (next.isEmpty()) { beam = emptyList(); break }
                next.sortByDescending { it.score }
                beam = next.take(if (step == order.size - 1) keepFinal else cfg.beam)
            }
            cands.addAll(beam)
        }
        if (cands.isEmpty()) return Plan(emptyList(), 0, true)
        cands.sortByDescending { it.score }
        var best: Node? = cands[0]
        if (level >= 4) {
            // lookahead re-rank of the top 8 final boards
            var bestVal = Double.NEGATIVE_INFINITY
            for (c in cands.take(8)) {
                var la = 0.0
                for (hr in HARD_ROUNDS) {
                    val p = plan(c.board, c.bonus, hr, c.streak, c.mult, 1)
                    la += if (p.gameOver) -400.0 else p.total * 0.25 + evaluate(planBoard(c, hr, p), c.bonus, LEVELS[1]!!) * 0.15
                }
                val v = c.score + la / HARD_ROUNDS.size
                if (v > bestVal) { bestVal = v; best = c }
            }
        }
        val b = best ?: return Plan(emptyList(), 0, true)
        return Plan(b.moves, b.pts, false)
    }

    /** Apply a plan's moves to a candidate board (used for lookahead evaluation). */
    private fun planBoard(c: Node, pieces: List<Piece>, p: Plan): IntArray {
        var b = c.board; var bo = c.bonus
        for (m in p.moves) { val r = Engine.place(b, bo, pieces[m.slot], m.r, m.c); b = r.board; bo = r.bonus }
        return b
    }
}
