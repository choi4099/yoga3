package com.yogacoach.app.scoring

import com.yogacoach.app.model.Joint
import com.yogacoach.app.model.Joint.*
import com.yogacoach.app.model.Pt
import com.yogacoach.app.model.YogaPose
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max

/** 점수 부위 구분 (가중치 합 100) */
enum class Part(val ko: String, val weight: Float) {
    HAND("손", 15f), ARM("팔", 25f), LEG("다리", 30f), BODY("몸", 30f)
}

enum class Kind {
    /** 세 관절 사이의 굽힘 각도 (예: 어깨-팔꿈치-손목) */
    ANGLE,
    /** 뼈(두 관절을 잇는 선)의 방향 */
    DIR,
    /** 상체(골반 중심→어깨 중심) 기울기 */
    TORSO,
    /** 몸 중심 기준 관절 위치 */
    POINT,
    /** 두 관절 사이 거리 (두 손 모으기 등) */
    DIST,
}

data class Check(val part: Part, val label: String, val kind: Kind, val joints: List<Joint>)

data class CheckResult(
    val check: Check,
    val score: Float,
    val error: Float,
    val problem: String,
    val fix: String,
)

data class ScoreResult(
    val total: Float,
    val parts: Map<Part, Float>,
    val checks: List<CheckResult>,
    /** 좌우를 바꾼 기준 자세가 더 잘 맞았는지 */
    val mirrored: Boolean,
    /** 사용자 몸 위치·크기에 맞춰 옮긴 기준 자세 (화면 겹쳐 그리기용, 이미지 좌표) */
    val alignedTemplate: Map<Joint, Pt>,
    /** 뼈별 점수 (사용자 골격 색칠용) */
    val boneScores: Map<Pair<Joint, Joint>, Float>,
) {
    /** 점수가 낮은 순서의 문제 항목 */
    fun worst(limit: Int = 3, below: Float = 75f): List<CheckResult> =
        checks.filter { it.score < below }.sortedBy { it.score }.take(limit)
}

/**
 * 사람 자세와 요가 기준 자세를 비교해 부위별 점수, 문제점, 개선 방법을 만듭니다.
 * 안드로이드 API 를 쓰지 않아 단위 테스트가 가능합니다.
 */
object PoseScorer {
    /** 이 각도(°) 이내 오차는 만점 */
    const val TOLERANCE = 10f

    /** 허용 오차를 넘으면 1° 당 감점 (허용치 + 35° 에서 0점) */
    private const val SLOPE = 100f / 35f

    /** 화면 밖으로 나갔다고 볼 관절 신뢰도 */
    const val MIN_VISIBLE = 0.3f

    val CHECKS: List<Check> = listOf(
        Check(Part.ARM, "왼쪽 팔꿈치", Kind.ANGLE, listOf(LS, LE, LW)),
        Check(Part.ARM, "오른쪽 팔꿈치", Kind.ANGLE, listOf(RS, RE, RW)),
        Check(Part.ARM, "왼쪽 위팔", Kind.DIR, listOf(LS, LE)),
        Check(Part.ARM, "오른쪽 위팔", Kind.DIR, listOf(RS, RE)),
        Check(Part.ARM, "왼쪽 아래팔", Kind.DIR, listOf(LE, LW)),
        Check(Part.ARM, "오른쪽 아래팔", Kind.DIR, listOf(RE, RW)),

        Check(Part.HAND, "왼손", Kind.POINT, listOf(LW)),
        Check(Part.HAND, "오른손", Kind.POINT, listOf(RW)),
        Check(Part.HAND, "두 손 간격", Kind.DIST, listOf(LW, RW)),

        Check(Part.LEG, "왼쪽 무릎", Kind.ANGLE, listOf(LH, LK, LA)),
        Check(Part.LEG, "오른쪽 무릎", Kind.ANGLE, listOf(RH, RK, RA)),
        Check(Part.LEG, "왼쪽 허벅지", Kind.DIR, listOf(LH, LK)),
        Check(Part.LEG, "오른쪽 허벅지", Kind.DIR, listOf(RH, RK)),
        Check(Part.LEG, "왼쪽 정강이", Kind.DIR, listOf(LK, LA)),
        Check(Part.LEG, "오른쪽 정강이", Kind.DIR, listOf(RK, RA)),

        Check(Part.BODY, "상체", Kind.TORSO, listOf(HM, SM)),
        Check(Part.BODY, "왼쪽 고관절", Kind.ANGLE, listOf(LS, LH, LK)),
        Check(Part.BODY, "오른쪽 고관절", Kind.ANGLE, listOf(RS, RH, RK)),
        Check(Part.BODY, "머리", Kind.POINT, listOf(NOSE)),
        Check(Part.BODY, "어깨 라인", Kind.DIR, listOf(RS, LS)),
    )

    /** 화면에 그릴 뼈 목록 */
    val BONES: List<Pair<Joint, Joint>> = listOf(
        LS to LE, LE to LW, RS to RE, RE to RW,
        LS to RS, LH to RH, LS to LH, RS to RH,
        LH to LK, LK to LA, RH to RK, RK to RA,
    )

    // ------------------------------------------------------------------ 공개 함수

    /** 기준 자세와 좌우 반전 자세 둘 다 비교해 더 잘 맞는 쪽 결과를 반환 */
    fun evaluateBest(user: Map<Joint, Pt>, visibility: Map<Joint, Float>, pose: YogaPose): ScoreResult {
        val a = evaluate(user, visibility, pose.template, pose.sideView, false)
        val b = evaluate(user, visibility, mirrorTemplate(pose.template), pose.sideView, true)
        return if (a.total >= b.total) a else b
    }

    /** 좌우 반전: 왼쪽↔오른쪽 관절을 바꾸고 x 를 뒤집음 */
    fun mirrorTemplate(t: Map<Joint, Pt>): Map<Joint, Pt> =
        Joint.REAL.associateWith { j -> val p = t.getValue(j.mirror); Pt(-p.x, p.y) }

    fun evaluate(
        userRaw: Map<Joint, Pt>,
        visibility: Map<Joint, Float>,
        templateRaw: Map<Joint, Pt>,
        sideView: Boolean,
        mirrored: Boolean,
    ): ScoreResult {
        val userFull = withCenters(userRaw)
        val tplFull = withCenters(templateRaw)
        val uHm = userFull.getValue(HM)
        val uTorso = max(1e-3f, (userFull.getValue(SM) - uHm).len())
        val tHm = tplFull.getValue(HM)
        val tTorso = max(1e-3f, (tplFull.getValue(SM) - tHm).len())
        val u = userFull.mapValues { (_, p) -> (p - uHm) / uTorso }
        val t = tplFull.mapValues { (_, p) -> (p - tHm) / tTorso }
        val facing = if (u.getValue(NOSE).x - u.getValue(SM).x >= 0f) 1 else -1

        val results = ArrayList<CheckResult>()
        for (c in CHECKS) {
            if (c.joints.any { j -> j.sources.any { (visibility[it] ?: 1f) < MIN_VISIBLE } }) continue
            evaluateCheck(c, u, t, sideView, facing)?.let { results += it }
        }

        val parts = LinkedHashMap<Part, Float>()
        for (p in Part.values()) {
            val s = results.filter { it.check.part == p }.map { it.score }
            if (s.isNotEmpty()) parts[p] = 0.6f * s.average().toFloat() + 0.4f * s.min()
        }
        val total = if (parts.isEmpty()) 0f else {
            val weighted = parts.entries.sumOf { (p, s) -> (s * p.weight).toDouble() } /
                parts.keys.sumOf { it.weight.toDouble() }
            (0.7f * weighted.toFloat() + 0.3f * parts.values.min()).coerceIn(0f, 100f)
        }

        val aligned = Joint.REAL.associateWith { j -> uHm + (tplFull.getValue(j) - tHm) * (uTorso / tTorso) }

        val bone = HashMap<Pair<Joint, Joint>, Float>()
        for (r in results) {
            val js = r.check.joints
            if (r.check.kind == Kind.DIR) bone[js[0] to js[1]] = r.score
        }
        results.firstOrNull { it.check.kind == Kind.TORSO }?.let { tr ->
            bone[LS to LH] = tr.score; bone[RS to RH] = tr.score; bone[LH to RH] = tr.score
        }
        results.firstOrNull { it.check.label == "어깨 라인" }?.let { bone[LS to RS] = it.score }

        return ScoreResult(total, parts, results, mirrored, aligned, bone)
    }

    // ------------------------------------------------------------------ 내부 계산

    fun scoreOf(err: Float): Float = (100f - max(0f, err - TOLERANCE) * SLOPE).coerceIn(0f, 100f)

    private fun withCenters(m: Map<Joint, Pt>): Map<Joint, Pt> {
        val r = HashMap(m)
        r[HM] = (m.getValue(LH) + m.getValue(RH)) / 2f
        r[SM] = (m.getValue(LS) + m.getValue(RS)) / 2f
        return r
    }

    private fun angleBetween(a: Pt, b: Pt): Float {
        val l = a.len() * b.len()
        if (l < 1e-9f) return 0f
        val c = ((a.x * b.x + a.y * b.y) / l).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c.toDouble())).toFloat()
    }

    private fun interior(a: Pt, b: Pt, c: Pt): Float = angleBetween(a - b, c - b)

    /** 받침(조사) 선택: josa("무릎","을","를") → "을" */
    fun josa(word: String, withBatchim: String, without: String): String {
        val ch = word.lastOrNull() ?: return without
        if (ch.code !in 0xAC00..0xD7A3) return without
        return if ((ch.code - 0xAC00) % 28 != 0) withBatchim else without
    }

    private fun horizontal(dx: Float, refX: Float, sideView: Boolean, facing: Int): String =
        if (sideView) {
            if (dx * facing > 0) "앞쪽으로 더" else "뒤쪽으로 더"
        } else {
            val ref = if (abs(refX) > 0.05f) refX else dx
            if (dx * ref > 0) "몸 바깥쪽으로 더" else "몸 안쪽으로 더"
        }

    private fun evaluateCheck(c: Check, u: Map<Joint, Pt>, t: Map<Joint, Pt>, sideView: Boolean, facing: Int): CheckResult? {
        val js = c.joints
        val label = c.label
        val i = josa(label, "이", "가")
        val eul = josa(label, "을", "를")
        return when (c.kind) {
            Kind.ANGLE -> {
                val ta = t.getValue(js[0]); val tb = t.getValue(js[1]); val tc = t.getValue(js[2])
                if ((ta - tb).len() < 0.15f || (tc - tb).len() < 0.15f) return null
                val cur = interior(u.getValue(js[0]), u.getValue(js[1]), u.getValue(js[2]))
                val tgt = interior(ta, tb, tc)
                val err = abs(cur - tgt)
                val detail = "(현재 ${cur.toInt()}° / 목표 ${tgt.toInt()}°)"
                return if (cur < tgt) CheckResult(c, scoreOf(err), err, "$label$i 너무 굽어 있어요 $detail", "$label$eul 더 펴 주세요")
                else CheckResult(c, scoreOf(err), err, "$label$i 너무 펴져 있어요 $detail", "$label$eul 더 굽혀 주세요")
            }
            Kind.DIR, Kind.TORSO -> {
                val vt = t.getValue(js[1]) - t.getValue(js[0])
                val vu = u.getValue(js[1]) - u.getValue(js[0])
                if (vt.len() < 0.15f) return null
                val err = angleBetween(vu, vt)
                if (c.kind == Kind.TORSO) {
                    val up = Pt(0f, -1f)
                    val tiltU = angleBetween(vu, up)
                    val tiltT = angleBetween(vt, up)
                    val fix = when {
                        abs(tiltU - tiltT) < err * 0.5f -> "상체를 반대 방향으로 기울여 주세요"
                        tiltU > tiltT -> "상체를 더 세워 주세요"
                        sideView -> "상체를 더 숙여 주세요"
                        else -> "상체를 옆으로 더 기울여 주세요"
                    }
                    return CheckResult(c, scoreOf(err), err, "상체 기울기가 ${err.toInt()}° 어긋나 있어요", fix)
                }
                val d = vt.unit() - vu.unit()
                val how = if (abs(d.y) >= abs(d.x)) {
                    if (d.y < 0) "더 위로 올려" else "더 아래로 내려"
                } else {
                    val refX = t.getValue(js[1]).x
                    val verb = when {
                        sideView -> "뻗어"
                        d.x * refX > 0 -> "벌려"
                        else -> "모아"
                    }
                    horizontal(d.x, refX, sideView, facing) + " " + verb
                }
                return CheckResult(c, scoreOf(err), err, "$label 방향이 ${err.toInt()}° 어긋나 있어요", "$label$eul $how 주세요")
            }
            Kind.POINT -> {
                val d = t.getValue(js[0]) - u.getValue(js[0])
                val err = d.len() * 57.3f
                val how = if (abs(d.y) >= abs(d.x)) {
                    if (d.y < 0) "더 위로 올려" else "더 아래로 내려"
                } else horizontal(d.x, t.getValue(js[0]).x, sideView, facing) + " 옮겨"
                return CheckResult(c, scoreOf(err), err, "$label 위치가 기준 자세와 달라요", "$label$eul $how 주세요")
            }
            Kind.DIST -> {
                val dt = (t.getValue(js[0]) - t.getValue(js[1])).len()
                if (dt > 0.45f) return null   // 두 손을 모으는 자세에서만 채점
                val du = (u.getValue(js[0]) - u.getValue(js[1])).len()
                val err = abs(du - dt) * 57.3f
                return if (du > dt) CheckResult(c, scoreOf(err), err, "두 손 사이가 너무 멀어요", "두 손을 더 모아 주세요")
                else CheckResult(c, scoreOf(err), err, "두 손 사이가 너무 가까워요", "두 손 간격을 조금 벌려 주세요")
            }
        }
    }

}
