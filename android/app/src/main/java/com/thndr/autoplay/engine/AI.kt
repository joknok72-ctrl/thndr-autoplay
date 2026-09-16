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
    // probes = pieces that actually occur in the real game (no 3x3 / 5-long bars — they are never dealt)
    private val PROBES: List<Pair<Piece, Double>> = listOf(
        Engine.shape(0 to 0, 0 to 1, 0 to 2, 1 to 1, 2 to 1) to 2.0,   // T5a
        Engine.shape(0 to 0, 0 to 1, 0 to 2, 1 to 2, 2 to 2) to 1.5,   // Lbig3
        Engine.shape(0 to 0, 0 to 2, 1 to 0, 1 to 1, 1 to 2) to 1.5,   // U1
        Engine.shape(0 to 1, 1 to 0, 1 to 1, 1 to 2, 2 to 1) to 1.5,   // plus
        Engine.shape(0 to 0, 0 to 1, 0 to 2, 1 to 1) to 1.0,           // T4a
        Engine.shape(0 to 1, 1 to 1, 2 to 0, 2 to 1) to 1.0,           // J4a
        Engine.shape(0 to 0, 0 to 1, 0 to 2, 0 to 3) to 1.0,           // i4
        Engine.shape(0 to 1, 0 to 2, 1 to 0, 1 to 1) to 1.0,           // S4a
        Engine.shape(0 to 0, 0 to 1, 1 to 0, 1 to 1) to 1.0,           // sq2
        Engine.shape(0 to 0, 1 to 0, 2 to 0, 3 to 0) to 1.0,           // v4
        Engine.shape(0 to 0, 1 to 0, 1 to 1) to 0.6,                   // l3a
        Engine.shape(0 to 0, 0 to 1, 0 to 2) to 0.6,                   // i3
    )
    // board-quality weights
    private const val W_EMPTY = 1.0; private const val W_HOLES = 5.0; private const val W_TRANS = 0.9; private const val W_NEAR = 1.6
    private const val W_EDGE = 0.25; private const val W_FIT = 6.0; private const val W_ISL = 2.5; private const val W_SQ3 = 1.5; private const val W_DEAD = 15.0
    // score-max weights (tuned by simulation)
    @JvmField var K_MULT = 18.0        // value of +1 multiplier per remaining move
    @JvmField var W_SURV = 4.0         // survival weight (re-tuned on the REAL piece distribution: 0 deaths / 20 games)
    @JvmField var W_ORANGE = 0.3       // oranges parked in near-complete lines
    @JvmField var W_BONUS_KEEP = 0.4   // keep uncovered bonus cells coverable (legacy, used when W_FARM = 0)
    @JvmField var W_FARM = 1.2         // bonus farming weight (measured: 77.8K → 126K–140K on the realistic sim, 0 deaths)
    @JvmField var FARM_RATE = 0.33     // expected tier steps per round while 3 cells are farmed
    private val TIER = intArrayOf(50, 150, 300, 500, 750, 1000, 1500, 2000, 3000, 5000, 7500, 10000)
    @JvmField var W_COVER = 1.0        // real-piece survivability: penalty ∝ P(next piece has no place) × stake
    @JvmField var W_TIGHT = 0.3
    @JvmField var STAKE = 12.0         // ≈ points per remaining move per multiplier unit
    @JvmField var W_CLEAN = 0.0        // clean-board style: reward per empty cell before the fill phase (player's strategy)
    @JvmField var END_CUBE = 1000.0    // REAL RULE: every cube still on the board after level 25 pays 1000 (only if the game is completed)
    @JvmField var END_FADE = 9         // the end-bonus fades in over the last N moves
    @JvmField var END_BEAM = 64        // wider beam in the last END_BEAM_REM moves (deeper end-game search)
    @JvmField var END_BEAM_REM = 12
    @JvmField var ROLL_ROUNDS = 3      // end-game rollouts in the last N rounds
    @JvmField var ROLL_K = 5           // candidates re-ranked by rollout
    @JvmField var ROLL_K_FILL = 3      // + best board-filling candidates
    @JvmField var ROLL_K_PTS = 2       // + best raw-points candidates
    @JvmField var ROLL_M = 6           // random futures per candidate
    @JvmField var ROLL_LEVEL = 1       // planner level used inside rollouts (fast)

    data class Cfg(val beam: Int, val probes: Int)
    val LEVELS = mapOf(1 to Cfg(6, 6), 2 to Cfg(14, 9), 3 to Cfg(32, 12))

    /** Real THNDR scoring. */
    fun realPoints(res: PlaceResult, mult: Int): Pair<Int, Int> {
        val nm = mult + res.orangeCleared
        return Pair((res.cells.size + 20 * res.lines + res.bonusHit) * nm, nm)
    }

    private class BQ(val q: Double, val rf: IntArray, val cf: IntArray, val bf: IntArray, val dead: Int, val cover: Double, val tight: Double, val empty: Int)

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
        // REAL-PIECE SURVIVABILITY: weighted fraction of the pieces the game actually deals that still have a place
        var cover = 0.0; var tight = 0.0
        if (W_COVER > 0) {
            for (lp in LIBP) { val n = Engine.countPlacements(board, lp.first); if (n > 0) cover += lp.second; if (n < 3) tight += lp.second * (3 - n) / 3.0 }
            cover /= LIBW; tight /= LIBW
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
        return BQ(q, rf, cf, bf, dead, cover, tight, empty)
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
        // BONUS CELLS — REAL RULE (measured on 6 logged games): while 3 cells sit on the board and none is covered during a
        // round, one of them grows a tier each level (50→150→300→500→1K→2K). "Farming": value uncovered cells by their
        // expected FUTURE value (they grow, and the multiplier grows) as long as enough moves remain to cash them in.
        var bonusPot = 0.0
        var nB = 0; for (i in 0 until N * N) if (node.bonus[i] != 0 && node.board[i] == 0) nB++
        for (i in 0 until N * N) if (node.bonus[i] != 0 && node.board[i] == 0) {
            if (W_FARM > 0) {
                var t = TIER.indexOf(node.bonus[i]); if (t < 0) { t = 0; while (t < TIER.size - 1 && TIER[t + 1] <= node.bonus[i]) t++ }
                val roundsLeft = rem / 3.0
                val steps = if (nB >= 3) minOf((TIER.size - 1 - t).toDouble(), roundsLeft * FARM_RATE) else 0.0
                val fut = t + steps; val lo = fut.toInt().coerceIn(0, TIER.size - 1); val hi = minOf(TIER.size - 1, lo + 1)
                val fv = TIER[lo] + (TIER[hi] - TIER[lo]) * (fut - lo)
                val multFut = node.mult + minOf(roundsLeft, 25.0) * 0.8
                if (rem >= 3) bonusPot += fv * multFut * W_FARM * 0.3
            } else if (rem > 3) bonusPot += node.bonus[i] * node.mult * W_BONUS_KEEP * 0.3
        }
        val survW = W_SURV * minOf(1.0, rem / 15.0) * (1 + node.mult * 0.15) * 4
        // END BONUS: in the last moves keep as many cubes as possible on the board (1000 each at the end) —
        // but never at the price of dying: the dead-board penalty is 6× stronger in that phase.
        var endVal = 0.0
        if (rem < END_FADE) { var cubes = 0; for (i in 0 until N * N) if (node.board[i] != 0) cubes++; val w = 1.0 - rem.toDouble() / END_FADE; endVal = cubes * END_CUBE * w * w }
        val deadPen = if (bq.dead > 0 && rem > 0) 400.0 * (1 + node.mult * 0.2) * (if (rem < END_FADE) 6 else 1) else 0.0
        // DYING = losing everything still to come (remaining × mult × STAKE) — (1-cover) ≈ chance the next piece has no place
        // CLEAN-BOARD PHASE (player's strategy): before the fill phase, reward an empty board (flexibility + every clear pays 20×mult)
        val cleanVal = if (rem >= END_FADE) bq.empty * W_CLEAN * (1 + node.mult * 0.15) else 0.0
        val stake = if (rem > 0) rem * node.mult * STAKE else 0.0
        val surviv = if (rem > 0 && W_COVER > 0) -(1 - bq.cover) * stake * W_COVER - bq.tight * stake * W_TIGHT else 0.0
        return node.pts + multGain + orangePot + bonusPot + bq.q * survW + endVal - deadPen + surviv + cleanVal
    }

    // ---- piece library with the REAL deal distribution (96 pieces observed across two full games; big 3x3 / 5-bars never appear) ----
    private val LIB: List<Triple<String, Double, IntArray>> = listOf(
        Triple("dot", 1.0, intArrayOf(0,0)),
        Triple("i2", 4.0, intArrayOf(0,0, 0,1)),
        Triple("v2", 2.5, intArrayOf(0,0, 1,0)),
        Triple("i3", 3.0, intArrayOf(0,0, 0,1, 0,2)),
        Triple("v3", 3.0, intArrayOf(0,0, 1,0, 2,0)),
        Triple("i4", 3.0, intArrayOf(0,0, 0,1, 0,2, 0,3)),
        Triple("v4", 1.5, intArrayOf(0,0, 1,0, 2,0, 3,0)),
        Triple("i5", 0.0, intArrayOf(0,0, 0,1, 0,2, 0,3, 0,4)),
        Triple("v5", 0.0, intArrayOf(0,0, 1,0, 2,0, 3,0, 4,0)),
        Triple("sq2", 3.0, intArrayOf(0,0, 0,1, 1,0, 1,1)),
        Triple("l3a", 2.0, intArrayOf(0,0, 1,0, 1,1)),
        Triple("l3b", 2.0, intArrayOf(0,0, 0,1, 1,0)),
        Triple("l3c", 2.0, intArrayOf(0,0, 0,1, 1,1)),
        Triple("l3d", 1.5, intArrayOf(0,1, 1,0, 1,1)),
        Triple("L4a", 1.0, intArrayOf(0,0, 1,0, 2,0, 2,1)),
        Triple("L4b", 2.0, intArrayOf(0,0, 0,1, 1,0, 2,0)),
        Triple("L4c", 1.0, intArrayOf(0,0, 0,1, 0,2, 1,0)),
        Triple("L4d", 1.0, intArrayOf(0,2, 1,0, 1,1, 1,2)),
        Triple("J4a", 5.0, intArrayOf(0,1, 1,1, 2,0, 2,1)),
        Triple("J4b", 1.0, intArrayOf(0,0, 1,0, 1,1, 1,2)),
        Triple("J4c", 1.0, intArrayOf(0,0, 0,1, 1,1, 2,1)),
        Triple("J4d", 3.0, intArrayOf(0,0, 0,1, 0,2, 1,2)),
        Triple("T4a", 5.0, intArrayOf(0,0, 0,1, 0,2, 1,1)),
        Triple("T4b", 1.5, intArrayOf(0,1, 1,0, 1,1, 1,2)),
        Triple("T4c", 1.0, intArrayOf(0,0, 1,0, 1,1, 2,0)),
        Triple("T4d", 2.0, intArrayOf(0,1, 1,0, 1,1, 2,1)),
        Triple("S4a", 4.0, intArrayOf(0,1, 0,2, 1,0, 1,1)),
        Triple("S4b", 1.0, intArrayOf(0,0, 1,0, 1,1, 2,1)),
        Triple("Z4a", 2.0, intArrayOf(0,0, 0,1, 1,1, 1,2)),
        Triple("Z4b", 3.0, intArrayOf(0,1, 1,0, 1,1, 2,0)),
        Triple("Lbig1", 1.0, intArrayOf(0,0, 1,0, 2,0, 2,1, 2,2)),
        Triple("Lbig2", 2.0, intArrayOf(0,0, 0,1, 0,2, 1,0, 2,0)),
        Triple("Lbig3", 3.0, intArrayOf(0,0, 0,1, 0,2, 1,2, 2,2)),
        Triple("Lbig4", 2.0, intArrayOf(0,2, 1,2, 2,0, 2,1, 2,2)),
        Triple("plus", 3.0, intArrayOf(0,1, 1,0, 1,1, 1,2, 2,1)),
        Triple("U1", 4.0, intArrayOf(0,0, 0,2, 1,0, 1,1, 1,2)),
        Triple("U2", 1.0, intArrayOf(0,0, 0,1, 0,2, 1,0, 1,2)),
        Triple("U3", 1.0, intArrayOf(0,0, 0,1, 1,0, 2,0, 2,1)),
        Triple("U4", 1.0, intArrayOf(0,0, 0,1, 1,1, 2,0, 2,1)),
        Triple("d2a", 1.0, intArrayOf(0,1, 1,0)),
        Triple("d2b", 4.0, intArrayOf(0,0, 1,1)),
        Triple("d3a", 2.0, intArrayOf(0,2, 1,1, 2,0)),
        Triple("d3b", 2.0, intArrayOf(0,0, 1,1, 2,2)),
        Triple("stair", 0.0, intArrayOf(0,2, 1,1, 1,2, 2,0, 2,1)),
        Triple("rect23", 0.0, intArrayOf(0,0, 0,1, 0,2, 1,0, 1,1, 1,2)),
        Triple("rect32", 0.0, intArrayOf(0,0, 0,1, 1,0, 1,1, 2,0, 2,1)),
        Triple("sq3", 0.0, intArrayOf(0,0, 0,1, 0,2, 1,0, 1,1, 1,2, 2,0, 2,1, 2,2)),
        Triple("T5a", 3.0, intArrayOf(0,0, 0,1, 0,2, 1,1, 2,1)),
        Triple("T5b", 3.0, intArrayOf(0,2, 1,0, 1,1, 1,2, 2,2)),
        Triple("T5c", 2.0, intArrayOf(0,0, 1,0, 1,1, 1,2, 2,0)),
        Triple("T5d", 2.0, intArrayOf(0,1, 1,1, 2,0, 2,1, 2,2)),
        Triple("S5a", 1.0, intArrayOf(0,1, 0,2, 1,1, 2,0, 2,1)),
        Triple("S5b", 1.0, intArrayOf(0,0, 1,0, 1,1, 1,2, 2,2)),
        Triple("d4", 1.0, intArrayOf(0,0, 1,1, 2,2, 3,3)),
    )
    fun randomPiece(rng: java.util.Random): Piece {
        var total = 0.0; for (t in LIB) total += t.second
        var x = rng.nextDouble() * total; var pick = LIB[0]
        for (t in LIB) { x -= t.second; if (x <= 0 && t.second > 0) { pick = t; break } }
        val a = pick.third
        return Piece((0 until a.size / 2).map { Cell(a[it * 2], a[it * 2 + 1], 1) })
    }

    private val LIBP: List<Pair<Piece, Double>> = LIB.filter { it.second > 0 }.map { t -> Piece((0 until t.third.size / 2).map { Cell(t.third[it * 2], t.third[it * 2 + 1], 1) }) to t.second }
    private val LIBW: Double = LIBP.sumOf { it.second }.coerceAtLeast(1.0)

    /** Shared thread pool. Its size is re-tuned before every plan from the RAM that is actually free (see [setThreads] / [tuneThreads]). */
    val CORES: Int = maxOf(1, Runtime.getRuntime().availableProcessors())
    @Volatile var threads: Int = CORES
    private val POOL: java.util.concurrent.ThreadPoolExecutor by lazy {
        java.util.concurrent.ThreadPoolExecutor(CORES, CORES, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue()).also { it.allowCoreThreadTimeOut(true) }
    }
    /** Resize the pool to [n] worker threads (1..CORES). */
    fun setThreads(n: Int) {
        val t = n.coerceIn(1, CORES)
        threads = t
        try {
            if (t >= POOL.maximumPoolSize) { POOL.maximumPoolSize = t; POOL.corePoolSize = t } else { POOL.corePoolSize = t; POOL.maximumPoolSize = t }
        } catch (_: Throwable) {}
    }
    /**
     * Choose the thread count from the memory that is really free right now:
     *  - [availSys]  = free system RAM in bytes (ActivityManager.MemoryInfo.availMem − threshold), or -1 if unknown
     *  - [totalSys]  = total system RAM in bytes, or -1 if unknown
     * Each planning thread needs roughly 96 MB of system RAM and ~24 MB of Java heap. If the system is mostly free (≥ 45 % of RAM
     * available) we take every core; otherwise we use only what the free RAM affords — never fewer than one thread.
     */
    fun tuneThreads(availSys: Long, totalSys: Long): Int {
        val rt = Runtime.getRuntime()
        val heapFree = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        var n = CORES
        if (availSys >= 0) {
            val mostlyFree = totalSys > 0 && availSys >= totalSys * 0.45
            if (!mostlyFree) n = minOf(n, (availSys / (96L shl 20)).toInt())
        }
        n = minOf(n, (heapFree / (24L shl 20)).toInt())
        setThreads(maxOf(1, n))
        return threads
    }
    fun <T, R> parallelMap(items: List<T>, f: (T) -> R): List<R> {
        if (items.size <= 1 || threads <= 1) return items.map(f)
        return try {
            val futs = items.map { it -> POOL.submit(java.util.concurrent.Callable { f(it) }) }
            futs.map { it.get() }
        } catch (_: Throwable) { items.map(f) }
    }

    private fun perms(a: List<Int>): List<List<Int>> = if (a.size <= 1) listOf(a) else a.flatMap { x -> perms(a - x).map { listOf(x) + it } }

    /**
     * @param mult   current multiplier (from the "NX" pill)
     * @param gameLevel current level 1..25 (from the "LEVEL n/25" pill)
     * @param deep   deeper end-game search (wider beam in the last moves + rollouts). ON by default — identical to the web site.
     * @param rollout end-game rollouts (last ROLL_ROUNDS rounds): re-rank the best candidates by simulating the
     *                remaining rounds with random pieces — same futures for every candidate.
     */
    fun plan(board: IntArray, bonus: IntArray, pieces: List<Piece?>, mult: Int, gameLevel: Int, level: Int = 3, deep: Boolean = true, rollout: Boolean = true): Plan {
        val cfg = LEVELS[level.coerceIn(1, 3)]!!
        val slots = pieces.indices.filter { pieces[it] != null }
        if (slots.isEmpty()) return Plan(emptyList(), 0, false, mult)
        val mult0 = maxOf(1, mult); val lvl = gameLevel.coerceIn(1, 25)
        val remAfterRound = (25 - lvl) * 3
        val roundsLeft = 25 - lvl
        val rollActive = deep && rollout && ROLL_ROUNDS > 0 && roundsLeft in 0 until ROLL_ROUNDS
        var best: Node? = null
        val cands = ArrayList<Node>()
        // MULTI-CORE: the 6 piece orderings are independent → one task per ordering on all available cores
        val orderResults = parallelMap(perms(slots)) { order ->
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
                val keep = if (step == order.size - 1) (if (rollActive) ROLL_K else 1)
                           else if (deep && remaining <= END_BEAM_REM) maxOf(cfg.beam, END_BEAM) else cfg.beam
                beam = next.take(keep)
            }
            beam
        }
        for (beam in orderResults) if (beam.isNotEmpty()) { cands.addAll(beam); if (best == null || beam[0].score > best.score) best = beam[0] }
        var b = best ?: return Plan(emptyList(), 0, true, mult0)
        if (rollActive && cands.size > 1) {
            cands.sortByDescending { it.score }
            // candidate diversity: heuristic top-K + best "fill" plans (pts + cubes×1000) + best raw-points plans
            val top = ArrayList(cands.take(ROLL_K))
            for (n in cands.sortedByDescending { it.pts + it.board.count { v -> v != 0 } * END_CUBE }) { if (top.size >= ROLL_K + ROLL_K_FILL) break; if (n !in top) top.add(n) }
            for (n in cands.sortedByDescending { it.pts }) { if (top.size >= ROLL_K + ROLL_K_FILL + ROLL_K_PTS) break; if (n !in top) top.add(n) }
            val rng = java.util.Random(12345L + lvl * 7919L)
            val futures = (0 until ROLL_M).map {
                (0 until roundsLeft).map {
                    val ps = (0 until 3).map { randomPiece(rng) }
                    val oi = rng.nextInt(3); val op = ps[oi]; val ci = rng.nextInt(op.cells.size)
                    ps.mapIndexed { i, p -> if (i == oi) Piece(p.cells.mapIndexed { j, c -> if (j == ci) Cell(c.r, c.c, 2) else c }) else p }
                }
            }
            // every candidate is evaluated on ALL futures — (candidate × future) pairs run in parallel on all cores
            val jobs = ArrayList<Pair<Int, Int>>(); for (ci in top.indices) for (fi in futures.indices) jobs.add(ci to fi)
            val vals = parallelMap(jobs) { (ci, fi) ->
                val cand = top[ci]; val f = futures[fi]
                var bd = cand.board; var bo = cand.bonus; var mu = cand.mult; var pts = cand.pts.toDouble(); var dead = false
                for (k in 0 until roundsLeft) {
                    val pl = planFull(bd, bo, f[k], mu, lvl + 1 + k, ROLL_LEVEL)
                    if (pl == null) { dead = true; break }
                    bd = pl.board; bo = pl.bonus; mu = pl.mult; pts += pl.pts
                }
                if (!dead) pts += bd.count { it != 0 } * END_CUBE
                pts
            }
            val sums = DoubleArray(top.size); for ((j, v) in vals.withIndex()) sums[jobs[j].first] += v
            val used = futures.size
            if (used > 0) {
                var bestAvg = Double.NEGATIVE_INFINITY
                for (ci in top.indices) if (sums[ci] / used > bestAvg) { bestAvg = sums[ci] / used; b = top[ci] }
            }
        }
        return Plan(b.moves, b.pts, false, b.mult)
    }

    /** plan() variant used inside rollouts: returns the resulting node (board/bonus/mult/pts) or null if dead. */
    private fun planFull(board: IntArray, bonus: IntArray, pieces: List<Piece>, mult: Int, gameLevel: Int, level: Int): Node? {
        val cfg = LEVELS[level.coerceIn(1, 3)]!!
        val lvl = gameLevel.coerceIn(1, 25); val remAfterRound = (25 - lvl) * 3
        var best: Node? = null
        for (order in perms(pieces.indices.toList())) {
            var beam = listOf(Node(board, bonus, mult, 0, emptyList()))
            for ((step, slot) in order.withIndex()) {
                val piece = pieces[slot]; val next = ArrayList<Node>()
                val remaining = remAfterRound + (order.size - 1 - step)
                for (node in beam) for (rc in Engine.allPlacements(node.board, piece)) {
                    val res = Engine.place(node.board, node.bonus, piece, rc[0], rc[1])
                    val (pts, nm) = realPoints(res, node.mult)
                    val nn = Node(res.board, res.bonus, nm, node.pts + pts, emptyList())
                    nn.score = evaluate(nn, mult, remaining, cfg)
                    next.add(nn)
                }
                if (next.isEmpty()) { beam = emptyList(); break }
                next.sortByDescending { it.score }
                beam = next.take(if (step == order.size - 1) 1 else cfg.beam)
            }
            if (beam.isNotEmpty() && (best == null || beam[0].score > best.score)) best = beam[0]
        }
        return best
    }
}
