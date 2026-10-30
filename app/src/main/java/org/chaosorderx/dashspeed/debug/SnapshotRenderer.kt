package org.chaosorderx.dashspeed.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.chaosorderx.dashspeed.detection.Detection
import org.chaosorderx.dashspeed.detection.ModelConfig
import org.chaosorderx.dashspeed.detection.StaticMask

/** Everything needed to draw one annotated debug snapshot (frame-normalised boxes). */
class SnapshotRequest(
    val reason: String,
    val frame: Bitmap,
    val kept: List<Detection>,
    val droppedByMask: List<Detection>,
    val mask: StaticMask,
    val caption: String,
    /** Rows (of the frame bitmap) the band model looked at, when one is active. */
    val bandRows: Pair<Int, Int>? = null,
)

/** Draws detections, dropped detections and the static mask over a camera frame. */
internal object SnapshotRenderer {
    private val keptPaint = boxPaint(Color.GREEN)
    private val droppedPaint = boxPaint(Color.RED)
    private val bandPaint = boxPaint(Color.CYAN)
    private val maskPaint = Paint().apply { color = Color.argb(90, 255, 59, 48) }
    private val textPaint =
        Paint().apply {
            color = Color.WHITE
            textSize = 22f
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }

    private fun boxPaint(argb: Int) =
        Paint().apply {
            color = argb
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

    /** Draws onto [request].frame, which must be a mutable copy. */
    fun draw(request: SnapshotRequest) {
        val canvas = Canvas(request.frame)
        val w = request.frame.width.toFloat()
        val h = request.frame.height.toFloat()
        drawMask(canvas, request.mask, w, h)
        request.kept.forEach { drawBox(canvas, it, keptPaint, w, h) }
        request.droppedByMask.forEach { drawBox(canvas, it, droppedPaint, w, h) }
        request.bandRows?.let { (top, bottom) ->
            canvas.drawLine(0f, top.toFloat(), w, top.toFloat(), bandPaint)
            canvas.drawLine(0f, bottom.toFloat(), w, bottom.toFloat(), bandPaint)
        }
        canvas.drawText("${request.reason}  ${request.caption}", 12f, 28f, textPaint)
    }

    private fun drawMask(
        canvas: Canvas,
        mask: StaticMask,
        w: Float,
        h: Float,
    ) {
        val cellW = w / StaticMask.COLS
        val cellH = h / StaticMask.ROWS
        for (row in 0 until StaticMask.ROWS) {
            for (col in 0 until StaticMask.COLS) {
                if (mask.isStatic(col, row)) {
                    canvas.drawRect(col * cellW, row * cellH, (col + 1) * cellW, (row + 1) * cellH, maskPaint)
                }
            }
        }
    }

    private fun drawBox(
        canvas: Canvas,
        detection: Detection,
        paint: Paint,
        w: Float,
        h: Float,
    ) {
        val s = ModelConfig.FRAME_SPACE.toFloat()
        val b = detection.boundingBox
        val box = RectF(b.left / s * w, b.top / s * h, b.right / s * w, b.bottom / s * h)
        canvas.drawRect(box, paint)
        val label = "%d %.2f".format(detection.classId, detection.confidence)
        canvas.drawText(label, box.left + 4f, box.top + 22f, textPaint)
    }
}
