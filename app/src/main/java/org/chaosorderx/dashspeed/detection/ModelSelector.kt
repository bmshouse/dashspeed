package org.chaosorderx.dashspeed.detection

/** Measured inference speed of one candidate model on this device. */
data class ModelBenchmark(
    val file: String,
    val width: Int,
    val height: Int,
    val backend: String,
    val medianMs: Float,
    val p90Ms: Float,
    /**
     * Detail the model gets per pixel of a 640-px-wide camera frame (see [ModelSelector.referenceFrameWidth]):
     * 1.0 = native 640 px resolution, 0.65 = a 416 px square model, 1.5 = a 960 px-wide band model fed 960 px frames.
     * The proxy for how small a vehicle the model can still see.
     */
    val scale: Float = 1f,
    /** True for a band model, which sees a horizontal strip of the frame instead of all of it. */
    val band: Boolean = false,
    /** Width of the camera frames this benchmark assumed (and the model wants for full detail). */
    val frameWidth: Int = ModelSelector.REFERENCE_FRAME_WIDTH,
)

/** Picks which bundled model to run, given how fast each one is on this device. */
object ModelSelector {
    const val REFERENCE_FRAME_WIDTH = 640

    /** Models below this detail level are never chosen by the runtime governor (too weak for far vehicles). */
    const val GOVERNOR_MIN_SCALE = 0.75f

    /**
     * The model with the most detail whose p90 latency is within [budgetP90Ms]; when none is fast
     * enough, the fastest one. Ties in detail go to the faster model (so a band model beats a
     * full-frame model of the same resolution). Null for no candidates.
     */
    fun choose(
        results: List<ModelBenchmark>,
        budgetP90Ms: Float = ModelConfig.MODEL_BUDGET_P90_MS,
    ): ModelBenchmark? {
        val affordable = results.filter { it.p90Ms <= budgetP90Ms }
        return if (affordable.isNotEmpty()) {
            affordable.maxWithOrNull(compareBy<ModelBenchmark> { it.scale }.thenByDescending { it.p90Ms })
        } else {
            results.minByOrNull { it.p90Ms }
        }
    }

    /** Best-first ladder for the runtime governor: [start] and everything weaker that is still strong enough. */
    fun governorLadder(
        results: List<ModelBenchmark>,
        start: ModelBenchmark,
    ): List<ModelBenchmark> =
        results
            .filter { it.scale >= GOVERNOR_MIN_SCALE || it.file == start.file }
            .sortedWith(compareByDescending<ModelBenchmark> { it.scale }.thenBy { it.p90Ms })
}
