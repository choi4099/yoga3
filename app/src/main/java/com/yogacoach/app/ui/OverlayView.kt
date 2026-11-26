package com.yogacoach.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.yogacoach.app.camera.PoseFrame
import com.yogacoach.app.model.Joint
import com.yogacoach.app.model.Pt
import com.yogacoach.app.scoring.PoseScorer
import com.yogacoach.app.scoring.ScoreResult
import kotlin.math.max

/**
 * 카메라 화면 위에 그리는 층.
 *  - 흰색 반투명 굵은 선: 사용자 몸 위치·크기에 맞춘 '기준 요가 자세'
 *  - 색깔 선: 인식된 사용자 골격 (초록=좋음, 노랑=보통, 빨강=교정 필요)
 */
class OverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var frame: PoseFrame? = null
    private var result: ScoreResult? = null
    private var mirrorX = true

    private val dp = resources.displayMetrics.density

    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 16 * dp; strokeCap = Paint.Cap.ROUND
        color = Color.argb(110, 255, 255, 255)
    }
    private val ghostHead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5 * dp; color = Color.argb(140, 255, 255, 255)
    }
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6 * dp; strokeCap = Paint.Cap.ROUND
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }

    fun update(frame: PoseFrame?, result: ScoreResult?, mirror: Boolean) {
        this.frame = frame
        this.result = result
        this.mirrorX = mirror
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = frame ?: return
        if (f.imageWidth <= 0 || f.imageHeight <= 0) return
        // PreviewView 기본값(FILL_CENTER)과 동일한 변환
        val scale = max(width / f.imageWidth.toFloat(), height / f.imageHeight.toFloat())
        val offX = (width - f.imageWidth * scale) / 2f
        val offY = (height - f.imageHeight * scale) / 2f
        fun toView(p: Pt): Pt {
            var x = p.x * scale + offX
            if (mirrorX) x = width - x
            return Pt(x, p.y * scale + offY)
        }

        val r = result
        // 1) 기준 자세 (겹쳐 보기)
        if (r != null) {
            val g = r.alignedTemplate.mapValues { toView(it.value) }
            for ((a, b) in PoseScorer.BONES) {
                val pa = g[a] ?: continue; val pb = g[b] ?: continue
                canvas.drawLine(pa.x, pa.y, pb.x, pb.y, ghostPaint)
            }
            g[Joint.NOSE]?.let { canvas.drawCircle(it.x, it.y, 18 * dp, ghostHead) }
        }

        // 2) 사용자 골격
        if (f.points.isEmpty()) return
        val u = f.points.mapValues { toView(it.value) }
        for (bone in PoseScorer.BONES) {
            val pa = u[bone.first] ?: continue; val pb = u[bone.second] ?: continue
            if ((f.visibility[bone.first] ?: 0f) < 0.3f || (f.visibility[bone.second] ?: 0f) < 0.3f) continue
            val s = r?.boneScores?.get(bone) ?: r?.boneScores?.get(bone.second to bone.first)
            bonePaint.color = scoreColor(s)
            canvas.drawLine(pa.x, pa.y, pb.x, pb.y, bonePaint)
        }
        for ((j, p) in u) {
            if ((f.visibility[j] ?: 0f) < 0.3f) continue
            canvas.drawCircle(p.x, p.y, 5 * dp, jointPaint)
        }
    }

    companion object {
        fun scoreColor(s: Float?): Int = when {
            s == null -> Color.rgb(144, 202, 249)
            s >= 80f -> Color.rgb(76, 175, 80)
            s >= 60f -> Color.rgb(255, 193, 7)
            else -> Color.rgb(244, 67, 54)
        }
    }
}
