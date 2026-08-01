package org.chaosorderx.dashspeed.tracking

class HungarianAlgorithm {
    /**
     * Solves the linear assignment problem for a cost matrix.
     *
     * Returns an IntArray where result[i] is the column assigned to row i,
     * or -1 if row i is unmatched (only possible when rows > cols).
     *
     * Uses the Jonker-Volgenant potential-based algorithm (O(n³)).
     * Non-square inputs are padded to square with zero-cost dummy rows/cols.
     * All costs must be non-negative.
     */
    @Suppress("LongMethod", "ComplexMethod", "NestedBlockDepth")
    fun solve(costMatrix: Array<FloatArray>): IntArray {
        val rows = costMatrix.size
        if (rows == 0) return intArrayOf()
        val cols = costMatrix[0].size
        val n = maxOf(rows, cols)
        val inf = Float.MAX_VALUE / 2f

        // Pad to square; dummy entries cost 0 so they never block real assignments
        val c = Array(n) { i -> FloatArray(n) { j -> if (i < rows && j < cols) costMatrix[i][j] else 0f } }

        val u = FloatArray(n + 1) // row potentials (1-indexed)
        val v = FloatArray(n + 1) // column potentials (1-indexed)
        val p = IntArray(n + 1) // p[j] = row assigned to column j; 0 = unassigned
        val way = IntArray(n + 1) // predecessor column in augmenting path

        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minDist = FloatArray(n + 1) { inf }
            val used = BooleanArray(n + 1)

            // Find shortest augmenting path from row i to a free column
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = inf
                var j1 = 0
                for (j in 1..n) {
                    if (!used[j]) {
                        val cur = c[i0 - 1][j - 1] - u[i0] - v[j]
                        if (cur < minDist[j]) {
                            minDist[j] = cur
                            way[j] = j0
                        }
                        if (minDist[j] < delta) {
                            delta = minDist[j]
                            j1 = j
                        }
                    }
                }
                // Update potentials to maintain feasibility
                for (j in 0..n) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minDist[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)

            // Augment along the path
            do {
                p[j0] = p[way[j0]]
                j0 = way[j0]
            } while (j0 != 0)
        }

        // p[j] = row (1-indexed) assigned to column j (1-indexed)
        val rowAssign = IntArray(n) { -1 }
        for (j in 1..n) {
            if (p[j] != 0) rowAssign[p[j] - 1] = j - 1
        }

        // Strip dummy-column assignments (col >= original cols → unmatched)
        return IntArray(rows) { i -> if (rowAssign[i] in 0 until cols) rowAssign[i] else -1 }
    }
}
