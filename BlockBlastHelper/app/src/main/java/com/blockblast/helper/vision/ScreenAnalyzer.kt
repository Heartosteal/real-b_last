package com.blockblast.helper.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import com.blockblast.helper.model.BlockShape
import com.blockblast.helper.model.BoardCalibration
import com.blockblast.helper.model.CellOffset
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 27장의 실제 Block Blast 테마(나무, 털실, 케이크, 수박, 치즈, 벽돌, 모피 등)를 기반으로
 * 상대 색차(Color Distance)와 그림자 필터링을 적용한 고정밀 화면 인식기.
 */
class ScreenAnalyzer {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun extractBoardGrid(bitmap: Bitmap, calibration: BoardCalibration): Array<BooleanArray> {
        val grid = Array(8) { BooleanArray(8) }
        val rect = calibration.boardRect
        val cellW = rect.width() / 8f
        val cellH = rect.height() / 8f

        val samples = Array(64) { IntArray(3) }
        val lums = FloatArray(64)

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val idx = r * 8 + c
                val cx = (rect.left + (c + 0.5f) * cellW).toInt().coerceIn(0, bitmap.width - 1)
                val cy = (rect.top + (r + 0.5f) * cellH).toInt().coerceIn(0, bitmap.height - 1)
                val px = bitmap.getPixel(cx, cy)
                val rr = Color.red(px)
                val gg = Color.green(px)
                val bb = Color.blue(px)
                samples[idx][0] = rr
                samples[idx][1] = gg
                samples[idx][2] = bb
                lums[idx] = (rr + gg + bb) / 3f
            }
        }

        // 보드의 빈 칸은 항상 채워진 블록보다 어둡다. 하위 25%(16개) 가장 어두운 셀의 중앙값을 빈 칸 기준색으로 사용.
        val sortedIndices = (0 until 64).sortedBy { lums[it] }
        val emptySampleCount = 16
        val refR = medianOf(sortedIndices.take(emptySampleCount).map { samples[it][0] })
        val refG = medianOf(sortedIndices.take(emptySampleCount).map { samples[it][1] })
        val refB = medianOf(sortedIndices.take(emptySampleCount).map { samples[it][2] })

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val idx = r * 8 + c
                val dr = samples[idx][0] - refR
                val dg = samples[idx][1] - refG
                val db = samples[idx][2] - refB
                val dist = sqrt((dr * dr + dg * dg + db * db).toDouble())
                grid[r][c] = dist > 32.0
            }
        }
        return grid
    }

    fun extractAvailableBlocks(
        bitmap: Bitmap,
        calibration: BoardCalibration
    ): Map<Int, BlockShape> {
        val result = mutableMapOf<Int, BlockShape>()
        val miniCellSize = calibration.boardCellSize * 0.435f
        calibration.slotRects.forEachIndexed { slotIndex, slotRect ->
            val shape = detectBlockInSlot(bitmap, slotRect, miniCellSize)
            if (shape != null && shape.cells.isNotEmpty()) {
                result[slotIndex] = shape
            }
        }
        return result
    }

    private fun detectBlockInSlot(
        bitmap: Bitmap,
        slotRect: RectF,
        miniCellSize: Float
    ): BlockShape? {
        val left = slotRect.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = slotRect.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = slotRect.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = slotRect.bottom.toInt().coerceIn(top + 1, bitmap.height)

        val w = right - left
        val h = bottom - top
        if (w <= 4 || h <= 4) return null

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, left, top, w, h)

        // 슬롯 4면 테두리 픽셀들의 중앙값을 배경색으로 추출
        val edgeR = ArrayList<Int>(w * 2 + h * 2)
        val edgeG = ArrayList<Int>(w * 2 + h * 2)
        val edgeB = ArrayList<Int>(w * 2 + h * 2)

        for (x in 0 until w) {
            addRgb(pixels[x], edgeR, edgeG, edgeB)
            addRgb(pixels[(h - 1) * w + x], edgeR, edgeG, edgeB)
        }
        for (y in 0 until h) {
            addRgb(pixels[y * w], edgeR, edgeG, edgeB)
            addRgb(pixels[y * w + (w - 1)], edgeR, edgeG, edgeB)
        }

        val bgR = medianOf(edgeR)
        val bgG = medianOf(edgeG)
        val bgB = medianOf(edgeB)

        val mask = BooleanArray(w * h)
        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1
        var fgCount = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = pixels[y * w + x]
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)

                val dr = r - bgR
                val dg = g - bgG
                val db = b - bgB
                val dist = sqrt((dr * dr + dg * dg + db * db).toDouble())

                // 드롭 섀도우 제외: 배경보다 3채널 모두 어둡고 색차가 작은 그림자 픽셀 필터링
                val isShadow = (r < bgR - 8) && (g < bgG - 8) && (b < bgB - 8) && (dist < 55.0)
                if (dist > 24.0 && !isShadow) {
                    mask[y * w + x] = true
                    fgCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (fgCount < 20 || maxX < minX || maxY < minY) return null

        val bw = (maxX - minX + 1).toFloat()
        val bh = (maxY - minY + 1).toFloat()
        val cols = (bw / miniCellSize).roundToInt().coerceIn(1, 5)
        val rows = (bh / miniCellSize).roundToInt().coerceIn(1, 5)

        val cellW = bw / cols
        val cellH = bh / rows
        val cells = mutableListOf<CellOffset>()

        for (rr in 0 until rows) {
            for (cc in 0 until cols) {
                val cy = (minY + (rr + 0.5f) * cellH).toInt().coerceIn(0, h - 1)
                val cx = (minX + (cc + 0.5f) * cellW).toInt().coerceIn(0, w - 1)

                var hit = 0
                var total = 0
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val ny = (cy + dy).coerceIn(0, h - 1)
                        val nx = (cx + dx).coerceIn(0, w - 1)
                        total++
                        if (mask[ny * w + nx]) hit++
                    }
                }
                if (hit.toFloat() / total >= 0.35f) {
                    cells.add(CellOffset(rr, cc))
                }
            }
        }

        if (cells.isEmpty()) return null
        return BlockShape(cells).normalized()
    }

    suspend fun extractScore(bitmap: Bitmap, calibration: BoardCalibration): Int? {
        val sRect = calibration.scoreRect
        val cropRect = Rect(
            sRect.left.toInt().coerceAtLeast(0),
            sRect.top.toInt().coerceAtLeast(0),
            sRect.right.toInt().coerceAtMost(bitmap.width),
            sRect.bottom.toInt().coerceAtMost(bitmap.height)
        )
        if (cropRect.width() <= 0 || cropRect.height() <= 0) return null

        val cropped = Bitmap.createBitmap(
            bitmap,
            cropRect.left,
            cropRect.top,
            cropRect.width(),
            cropRect.height()
        )
        val inputImage = InputImage.fromBitmap(cropped, 0)

        return suspendCoroutine { cont ->
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val digits = visionText.text
                        .replace(",", "")
                        .replace(" ", "")
                        .filter { it.isDigit() }
                    cont.resume(digits.toIntOrNull())
                }
                .addOnFailureListener {
                    cont.resume(null)
                }
        }
    }

    private fun addRgb(px: Int, rList: MutableList<Int>, gList: MutableList<Int>, bList: MutableList<Int>) {
        rList.add(Color.red(px))
        gList.add(Color.green(px))
        bList.add(Color.blue(px))
    }

    private fun medianOf(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
