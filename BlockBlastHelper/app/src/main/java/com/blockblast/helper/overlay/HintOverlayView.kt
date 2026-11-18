package com.blockblast.helper.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.blockblast.helper.model.BoardCalibration
import com.blockblast.helper.model.SolveResult

/**
 * 게임 화면 위에 투과형(Non-touchable)으로 띄워지는 추천 배치 오버레이 뷰.
 * - 보드의 목표 칸: 연한 노란색 하이라이트 + 순서 숫자(1, 2, 3)
 * - 하단 남은 블록 위: 배치 순서 번호 배지(1, 2, 3)
 */
class HintOverlayView(context: Context) : View(context) {

    private var solveResult: SolveResult? = null
    private var calibration: BoardCalibration? = null

    private val cellFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFF176") // 연한 노란색 반투명
        style = Paint.Style.FILL
    }

    private val cellStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DDFDD835")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val numberTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = 36f
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EEFFF176")
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = 40f
    }

    fun updateHint(result: SolveResult?, boardCalibration: BoardCalibration) {
        solveResult = result
        calibration = boardCalibration
        invalidate()
    }

    fun clearHint() {
        solveResult = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val result = solveResult ?: return
        val cal = calibration ?: return

        val boardRect = cal.boardRect
        val cellW = boardRect.width() / 8f
        val cellH = boardRect.height() / 8f
        numberTextPaint.textSize = cellH * 0.48f

        // 1. 8x8 보드 빈 칸에 연한 노란색 + 배치 순서 숫자 표시
        for (step in result.steps) {
            val orderText = step.order.toString()
            for (cell in step.block.cells) {
                val r = step.boardRow + cell.dRow
                val c = step.boardCol + cell.dCol
                if (r !in 0 until 8 || c !in 0 until 8) continue

                val left = boardRect.left + c * cellW + 3f
                val top = boardRect.top + r * cellH + 3f
                val right = left + cellW - 6f
                val bottom = top + cellH - 6f
                val rect = RectF(left, top, right, bottom)

                canvas.drawRoundRect(rect, 10f, 10f, cellFillPaint)
                canvas.drawRoundRect(rect, 10f, 10f, cellStrokePaint)

                val textY = rect.centerY() - (numberTextPaint.descent() + numberTextPaint.ascent()) / 2f
                canvas.drawText(orderText, rect.centerX(), textY, numberTextPaint)
            }

            // 2. 하단 남은 블록 위에도 순서 번호 배지 표시
            if (step.slotIndex in cal.slotRects.indices) {
                val slotRect = cal.slotRects[step.slotIndex]
                val badgeCx = slotRect.centerX()
                val badgeCy = slotRect.top + 28f
                canvas.drawCircle(badgeCx, badgeCy, 26f, badgeBgPaint)
                canvas.drawCircle(badgeCx, badgeCy, 26f, cellStrokePaint)

                val badgeTextY = badgeCy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
                canvas.drawText(orderText, badgeCx, badgeTextY, badgeTextPaint)
            }
        }
    }
}
