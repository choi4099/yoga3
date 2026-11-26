package com.yogacoach.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.yogacoach.app.model.Joint
import com.yogacoach.app.model.Pt
import com.yogacoach.app.scoring.PoseScorer
import kotlin.math.min

/** 요가 기준 자세를 막대 인형으로 그려 주는 작은 그림 (목록, 연습 화면 모서리) */
class PoseThumbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var template: Map<Joint, Pt>? = null
    private val dp = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 4 * dp; color = Color.rgb(94, 53, 177)
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(94, 53, 177) }

    var lineColor: Int
        get() = line.color
        set(v) { line.color = v; head.color = v; invalidate() }

    /** 화면이 거울처럼 보이므로 기준 그림도 좌우 반전해 보여 줍니다 */
    fun setPose(t: Map<Joint, Pt>) {
        template = PoseScorer.mirrorTemplate(t)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = template ?: return
        val pad = 8 * dp
        val minX = t.values.minOf { it.x } - 0.25f; val maxX = t.values.maxOf { it.x } + 0.25f
        val minY = t.values.minOf { it.y } - 0.3f; val maxY = t.values.maxOf { it.y } + 0.1f
        val s = min((width - 2 * pad) / (maxX - minX), (height - 2 * pad) / (maxY - minY))
        val ox = (width - (maxX - minX) * s) / 2f
        val oy = (height - (maxY - minY) * s) / 2f
        fun m(p: Pt) = Pt(ox + (p.x - minX) * s, oy + (p.y - minY) * s)
        line.strokeWidth = (s * 0.09f).coerceIn(2 * dp, 6 * dp)
        for ((a, b) in PoseScorer.BONES) {
            val pa = m(t.getValue(a)); val pb = m(t.getValue(b))
            canvas.drawLine(pa.x, pa.y, pb.x, pb.y, line)
        }
        val n = m(t.getValue(Joint.NOSE))
        canvas.drawCircle(n.x, n.y, s * 0.17f, head)
    }
}
