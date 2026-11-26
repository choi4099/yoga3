package com.yogacoach.app

import com.yogacoach.app.model.Joint
import com.yogacoach.app.model.Joint.*
import com.yogacoach.app.model.Level
import com.yogacoach.app.model.Pt
import com.yogacoach.app.model.YogaPose
import com.yogacoach.app.scoring.PoseScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android Studio 에서 이 파일을 우클릭 → Run 으로 채점 로직을 확인할 수 있습니다. */
class PoseScorerTest {
    private val tree = mapOf(
        NOSE to Pt(0f, -1.35f), LS to Pt(.4f, -1f), RS to Pt(-.4f, -1f),
        LE to Pt(.35f, -1.55f), RE to Pt(-.35f, -1.55f), LW to Pt(.03f, -2.1f), RW to Pt(-.03f, -2.1f),
        LH to Pt(.2f, 0f), RH to Pt(-.2f, 0f), LK to Pt(.15f, .95f), RK to Pt(-.75f, .55f),
        LA to Pt(.12f, 1.9f), RA to Pt(.05f, .75f),
    )
    private val pose = YogaPose("tree", "나무 자세", "Vrksasana", Level.BEGINNER, false, "", emptyList(), 30, tree)
    private val allVisible = Joint.REAL.associateWith { 1f }

    /** 기준 좌표를 카메라 이미지 크기로 키운 것 */
    private fun toImage(m: Map<Joint, Pt>) = m.mapValues { (_, p) -> Pt(500f + p.x * 200f, 600f + p.y * 200f) }

    @Test
    fun samePose_scores100() {
        val r = PoseScorer.evaluateBest(toImage(tree), allVisible, pose)
        assertTrue("total=${r.total}", r.total > 99.9f)
    }

    @Test
    fun mirroredPose_scores100() {
        val r = PoseScorer.evaluateBest(toImage(PoseScorer.mirrorTemplate(tree)), allVisible, pose)
        assertTrue(r.total > 99.9f)
        assertTrue(r.mirrored)
    }

    @Test
    fun bentStandingKnee_isReported() {
        val u = tree.toMutableMap()
        u[LK] = Pt(.35f, .9f); u[LA] = Pt(.1f, 1.75f)
        val r = PoseScorer.evaluateBest(toImage(u), allVisible, pose)
        val worst = r.worst(1).first()
        assertEquals("왼쪽 무릎", worst.check.label)
        assertEquals("왼쪽 무릎을 더 펴 주세요", worst.fix)
        assertTrue(r.total < 95f)
    }

    @Test
    fun josa() {
        assertEquals("을", PoseScorer.josa("무릎", "을", "를"))
        assertEquals("를", PoseScorer.josa("팔꿈치", "을", "를"))
    }
}
