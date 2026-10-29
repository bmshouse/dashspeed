package org.chaosorderx.dashspeed.detection

/**
 * Keeps per-frame time sustainable while driving. A phone running a large model in sun heats up and
 * throttles: the benchmark made on a cool phone stops being true after a few minutes. The governor
 * watches how long each frame really takes and moves along the ladder of bundled models (best first):
 * down one rung when the typical (median) frame is too slow, back up one rung after a long calm spell when the larger
 * model, scaled from its benchmark, would fit again.
 *
 * Pure logic with an injected clock (all times are milliseconds from any monotonic source).
 */
class ModelGovernor(
    private val ladder: List<ModelBenchmark>,
    startIndex: Int,
    private val downLimitMs: Float = ModelConfig.GOVERNOR_DOWN_MEDIAN_MS,
    private val upLimitMs: Float = ModelConfig.MODEL_BUDGET_P90_MS,
) {
    /** Index into [ladder] of the model that should be running. */
    var index: Int = startIndex
        private set

    /** The model first chosen by the benchmark; the governor never climbs above it. */
    private val bestIndex = startIndex
    private val samples = ArrayDeque<Sample>()
    private var lastSwitchMs: Long? = null
    private var lastUpgradeMs: Long? = null
    private var upDwellMs = BASE_UP_DWELL_MS

    private class Sample(val timeMs: Long, val frameMs: Float)

    val current: ModelBenchmark get() = ladder[index]

    /** Asset name of the model at ladder position [i]. */
    fun fileAt(i: Int): String = ladder[i].file

    /** Records one frame's processing time; returns the ladder index to switch to, or null to stay. */
    fun record(
        frameMs: Float,
        nowMs: Long,
    ): Int? {
        samples.addLast(Sample(nowMs, frameMs))
        while (samples.isNotEmpty() && nowMs - samples.first().timeMs > LONG_WINDOW_MS) samples.removeFirst()
        val sinceSwitch = nowMs - (lastSwitchMs ?: nowMs.also { lastSwitchMs = it })
        val recent = samples.filter { nowMs - it.timeMs <= SHORT_WINDOW_MS }
        if (recent.size < MIN_SAMPLES) return null
        val median = median(recent)
        val canGoDown = index < ladder.lastIndex && sinceSwitch >= DOWN_DWELL_MS && median > downLimitMs
        return when {
            canGoDown -> index + 1
            canGoUp(sinceSwitch, median) -> index - 1
            else -> null
        }
    }

    /** Call after actually switching to [newIndex]. */
    fun onSwitched(
        newIndex: Int,
        nowMs: Long,
    ) {
        if (newIndex > index) {
            // Stepping back down shortly after climbing means the climb was too optimistic: wait longer next time.
            val upgraded = lastUpgradeMs
            if (upgraded != null && nowMs - upgraded < FAILED_UPGRADE_WINDOW_MS) {
                upDwellMs = minOf(upDwellMs * 2, MAX_UP_DWELL_MS)
            }
        } else {
            lastUpgradeMs = nowMs
        }
        index = newIndex
        lastSwitchMs = nowMs
        samples.clear()
    }

    private fun canGoUp(
        sinceSwitch: Long,
        currentMedian: Float,
    ): Boolean {
        if (index <= bestIndex || sinceSwitch < upDwellMs) return false
        // What the larger model would cost now: today's time, scaled by the benchmark ratio between the two.
        val larger = ladder[index - 1]
        val estimate = currentMedian * larger.medianMs / current.medianMs
        return estimate <= upLimitMs
    }

    /** The typical frame time: a brief hiccup (GC, a model switch) must not look like sustained slowness. */
    private fun median(window: List<Sample>): Float = window.map { it.frameMs }.sorted()[window.size / 2]

    companion object {
        const val SHORT_WINDOW_MS = 8_000L
        const val LONG_WINDOW_MS = 30_000L
        const val MIN_SAMPLES = 30
        const val DOWN_DWELL_MS = 15_000L
        const val BASE_UP_DWELL_MS = 90_000L
        const val MAX_UP_DWELL_MS = 600_000L
        const val FAILED_UPGRADE_WINDOW_MS = 120_000L
    }
}
