package com.blockblast.helper.solver

import com.blockblast.helper.model.BlockShape
import com.blockblast.helper.model.PlacementStep
import com.blockblast.helper.model.SolveResult

/**
 * 8x8 비트마스크(Long 64bit) 기반 Block Blast 3-블록 순열 탐색 솔버.
 * - 평상시: 다중 줄 제거 및 콤보 스트릭 유지를 통한 고득점 지향
 * - 위기/난제 시: 3x3 대형 블록 수용 공간 확보 및 고립 구멍 제거를 통한 생존 최우선
 */
class BlockBlastSolver {

    companion object {
        private const val BOARD_SIZE = 8
        private const val CRISIS_OCCUPIED_THRESHOLD = 36

        private val ROW_MASKS = LongArray(BOARD_SIZE) { r ->
            0xFFL shl (r * BOARD_SIZE)
        }

        private val COL_MASKS = LongArray(BOARD_SIZE) { c ->
            var mask = 0L
            for (r in 0 until BOARD_SIZE) {
                mask = mask or (1L shl (r * BOARD_SIZE + c))
            }
            mask
        }

        private val PERMUTATIONS_3 = arrayOf(
            intArrayOf(0, 1, 2),
            intArrayOf(0, 2, 1),
            intArrayOf(1, 0, 2),
            intArrayOf(1, 2, 0),
            intArrayOf(2, 0, 1),
            intArrayOf(2, 1, 0)
        )
    }

    fun boardFromGrid(grid: Array<BooleanArray>): Long {
        var mask = 0L
        for (r in 0 until BOARD_SIZE) {
            for (c in 0 until BOARD_SIZE) {
                if (grid[r][c]) {
                    mask = mask or (1L shl (r * BOARD_SIZE + c))
                }
            }
        }
        return mask
    }

    fun solve(
        initialBoard: Long,
        availableBlocks: Map<Int, BlockShape>
    ): SolveResult? {
        if (availableBlocks.isEmpty()) return null

        val occupiedCount = java.lang.Long.bitCount(initialBoard)
        val isCrisisMode = occupiedCount >= CRISIS_OCCUPIED_THRESHOLD

        val slots = availableBlocks.keys.sorted()
        val orders = generateOrders(slots)

        var bestResult: SolveResult? = null

        for (order in orders) {
            val steps = mutableListOf<PlacementStep>()
            val candidate = searchRecursive(
                board = initialBoard,
                order = order,
                depth = 0,
                availableBlocks = availableBlocks,
                currentSteps = steps,
                accumulatedCleared = 0,
                consecutiveClears = 0,
                isCrisisMode = isCrisisMode
            )
            if (candidate != null) {
                if (bestResult == null ||
                    candidate.steps.size > bestResult.steps.size ||
                    (candidate.steps.size == bestResult.steps.size &&
                        candidate.evaluationScore > bestResult.evaluationScore)
                ) {
                    bestResult = candidate
                }
            }
        }

        return bestResult
    }

    private fun generateOrders(slots: List<Int>): List<IntArray> {
        return when (slots.size) {
            1 -> listOf(intArrayOf(slots[0]))
            2 -> listOf(
                intArrayOf(slots[0], slots[1]),
                intArrayOf(slots[1], slots[0])
            )
            3 -> PERMUTATIONS_3.map { perm ->
                intArrayOf(slots[perm[0]], slots[perm[1]], slots[perm[2]])
            }
            else -> emptyList()
        }
    }

    private fun searchRecursive(
        board: Long,
        order: IntArray,
        depth: Int,
        availableBlocks: Map<Int, BlockShape>,
        currentSteps: MutableList<PlacementStep>,
        accumulatedCleared: Int,
        consecutiveClears: Int,
        isCrisisMode: Boolean
    ): SolveResult? {
        if (depth == order.size) {
            val finalEval = evaluateBoard(
                board = board,
                totalCleared = accumulatedCleared,
                consecutiveClears = consecutiveClears,
                isCrisisMode = isCrisisMode
            )
            return SolveResult(
                steps = currentSteps.toList(),
                totalLinesCleared = accumulatedCleared,
                isCrisisMode = isCrisisMode,
                evaluationScore = finalEval
            )
        }

        val slotIndex = order[depth]
        val block = availableBlocks[slotIndex] ?: return null

        val placements = findAllValidPlacements(board, block)
        if (placements.isEmpty()) {
            return null
        }

        // 분기 폭발 방지: 1차 휴리스틱 점수로 상위 후보만 깊게 탐색
        val scoredPlacements = placements.map { (r, c) ->
            val placed = placeBlock(board, block, r, c)
            val (nextBoard, cleared) = clearLines(placed)
            val quickScore = quickHeuristic(nextBoard, cleared, isCrisisMode)
            Triple(r, c, quickScore)
        }.sortedByDescending { it.third }

        val beamWidth = if (order.size - depth >= 2) 10 else scoredPlacements.size
        var bestSubResult: SolveResult? = null

        for (i in 0 until minOf(beamWidth, scoredPlacements.size)) {
            val (r, c, _) = scoredPlacements[i]
            val placed = placeBlock(board, block, r, c)
            val (nextBoard, cleared) = clearLines(placed)

            currentSteps.add(
                PlacementStep(
                    slotIndex = slotIndex,
                    order = depth + 1,
                    block = block,
                    boardRow = r,
                    boardCol = c,
                    clearedLines = cleared
                )
            )

            val subResult = searchRecursive(
                board = nextBoard,
                order = order,
                depth = depth + 1,
                availableBlocks = availableBlocks,
                currentSteps = currentSteps,
                accumulatedCleared = accumulatedCleared + cleared,
                consecutiveClears = if (cleared > 0) consecutiveClears + 1 else consecutiveClears,
                isCrisisMode = isCrisisMode
            )

            currentSteps.removeAt(currentSteps.lastIndex)

            if (subResult != null) {
                if (bestSubResult == null || subResult.evaluationScore > bestSubResult.evaluationScore) {
                    bestSubResult = subResult
                }
            }
        }

        return bestSubResult
    }

    fun findAllValidPlacements(board: Long, block: BlockShape): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>(32)
        val maxR = BOARD_SIZE - block.height
        val maxC = BOARD_SIZE - block.width
        for (r in 0..maxR) {
            for (c in 0..maxC) {
                if (canPlace(board, block, r, c)) {
                    result.add(r to c)
                }
            }
        }
        return result
    }

    fun canPlace(board: Long, block: BlockShape, startRow: Int, startCol: Int): Boolean {
        for (cell in block.cells) {
            val r = startRow + cell.dRow
            val c = startCol + cell.dCol
            if (r !in 0 until BOARD_SIZE || c !in 0 until BOARD_SIZE) return false
            val bit = 1L shl (r * BOARD_SIZE + c)
            if ((board and bit) != 0L) return false
        }
        return true
    }

    fun placeBlock(board: Long, block: BlockShape, startRow: Int, startCol: Int): Long {
        var next = board
        for (cell in block.cells) {
            val r = startRow + cell.dRow
            val c = startCol + cell.dCol
            next = next or (1L shl (r * BOARD_SIZE + c))
        }
        return next
    }

    fun clearLines(board: Long): Pair<Long, Int> {
        var clearMask = 0L
        var clearedCount = 0

        for (r in 0 until BOARD_SIZE) {
            if ((board and ROW_MASKS[r]) == ROW_MASKS[r]) {
                clearMask = clearMask or ROW_MASKS[r]
                clearedCount++
            }
        }
        for (c in 0 until BOARD_SIZE) {
            if ((board and COL_MASKS[c]) == COL_MASKS[c]) {
                clearMask = clearMask or COL_MASKS[c]
                clearedCount++
            }
        }

        val nextBoard = board and clearMask.inv()
        return nextBoard to clearedCount
    }

    private fun quickHeuristic(board: Long, cleared: Int, isCrisisMode: Boolean): Double {
        val emptyCells = 64 - java.lang.Long.bitCount(board)
        val holes = countIsolatedHoles(board)
        return if (isCrisisMode) {
            emptyCells * 3.0 + cleared * 25.0 - holes * 18.0
        } else {
            emptyCells * 1.5 + (cleared * cleared) * 20.0 - holes * 10.0
        }
    }

    private fun evaluateBoard(
        board: Long,
        totalCleared: Int,
        consecutiveClears: Int,
        isCrisisMode: Boolean
    ): Double {
        val occupied = java.lang.Long.bitCount(board)
        val emptyCells = 64 - occupied
        val isolatedHoles = countIsolatedHoles(board)
        val transitions = countRowColTransitions(board)
        val bigBlockSpots = count3x3FitSpots(board)
        val centerEmpty = countCenterEmpty(board)

        return if (isCrisisMode) {
            // 생존 최우선: 빈 칸 확보 + 3x3 대형 블록 자리 + 고립 구멍 제거
            (emptyCells * 4.5) +
                (bigBlockSpots * 16.0) +
                (totalCleared * 35.0) -
                (isolatedHoles * 28.0) -
                (transitions * 2.2) +
                (centerEmpty * 2.0)
        } else {
            // 고득점 모드: 다중 줄 제거 + 연속 콤보 + 중앙 공간 유지
            (totalCleared * totalCleared * 28.0) +
                (consecutiveClears * 24.0) +
                (emptyCells * 2.0) +
                (bigBlockSpots * 10.0) +
                (centerEmpty * 2.5) -
                (isolatedHoles * 16.0) -
                (transitions * 1.5)
        }
    }

    private fun countIsolatedHoles(board: Long): Int {
        var holes = 0
        for (r in 0 until BOARD_SIZE) {
            for (c in 0 until BOARD_SIZE) {
                if (!isOccupied(board, r, c)) {
                    val up = r == 0 || isOccupied(board, r - 1, c)
                    val down = r == BOARD_SIZE - 1 || isOccupied(board, r + 1, c)
                    val left = c == 0 || isOccupied(board, r, c - 1)
                    val right = c == BOARD_SIZE - 1 || isOccupied(board, r, c + 1)
                    if (up && down && left && right) {
                        holes++
                    }
                }
            }
        }
        return holes
    }

    private fun countRowColTransitions(board: Long): Int {
        var transitions = 0
        for (r in 0 until BOARD_SIZE) {
            var prev = true
            for (c in 0 until BOARD_SIZE) {
                val curr = isOccupied(board, r, c)
                if (curr != prev) transitions++
                prev = curr
            }
            if (!prev) transitions++
        }
        for (c in 0 until BOARD_SIZE) {
            var prev = true
            for (r in 0 until BOARD_SIZE) {
                val curr = isOccupied(board, r, c)
                if (curr != prev) transitions++
                prev = curr
            }
            if (!prev) transitions++
        }
        return transitions
    }

    private fun count3x3FitSpots(board: Long): Int {
        var count = 0
        for (r in 0..5) {
            for (c in 0..5) {
                var fits = true
                loop@ for (dr in 0 until 3) {
                    for (dc in 0 until 3) {
                        if (isOccupied(board, r + dr, c + dc)) {
                            fits = false
                            break@loop
                        }
                    }
                }
                if (fits) count++
            }
        }
        return count
    }

    private fun countCenterEmpty(board: Long): Int {
        var count = 0
        for (r in 2..5) {
            for (c in 2..5) {
                if (!isOccupied(board, r, c)) count++
            }
        }
        return count
    }

    private fun isOccupied(board: Long, r: Int, c: Int): Boolean {
        return (board and (1L shl (r * BOARD_SIZE + c))) != 0L
    }
}
