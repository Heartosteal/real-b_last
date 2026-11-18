package com.blockblast.helper

import com.blockblast.helper.model.BlockShape
import com.blockblast.helper.model.CellOffset
import com.blockblast.helper.solver.BlockBlastSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockBlastSolverTest {

    private val solver = BlockBlastSolver()

    @Test
    fun `선행 블록으로 줄을 지워야만 후속 3x3 블록을 놓을 수 있는 난제를 해결한다`() {
        // 보드 상단 0행의 0..6열이 채워져 있고, (0,7)만 비어 있음.
        // 나머지 1..7행도 중간중간 채워져 있어 3x3이 바로 들어갈 자리가 없으나,
        // 0행이 지워지면 (0..2, 0..2)에 3x3 공간이 열리도록 구성.
        val grid = Array(8) { BooleanArray(8) }
        for (c in 0..6) grid[0][c] = true
        // 1~2행의 3..7열을 채우고 0..2열은 비워둠 -> 0행이 지워지면 (0..2, 0..2) 3x3 확보!
        for (r in 1..2) {
            for (c in 3..7) grid[r][c] = true
        }
        // 3~7행은 3x3이 들어가지 못하도록 2칸 간격으로 채움
        for (r in 3..7) {
            for (c in 0..7 step 2) grid[r][c] = true
        }

        val board = solver.boardFromGrid(grid)

        val singleDot = BlockShape(listOf(CellOffset(0, 0)))
        val bigSquare3x3 = BlockShape(
            (0 until 3).flatMap { r -> (0 until 3).map { c -> CellOffset(r, c) } }
        )
        val line1x2 = BlockShape(listOf(CellOffset(0, 0), CellOffset(0, 1)))

        val blocks = mapOf(
            0 to bigSquare3x3,
            1 to singleDot,
            2 to line1x2
        )

        val result = solver.solve(board, blocks)
        assertNotNull("3블록을 모두 배치할 수 있는 해를 찾아야 함", result)
        assertEquals(3, result!!.steps.size)
        // 반드시 1x1 점(slot 1)을 먼저 놓아 0행을 지운 뒤 3x3(slot 0)을 배치해야 함
        assertEquals(1, result.steps[0].slotIndex)
        assertTrue(result.totalLinesCleared >= 1)
    }
}
