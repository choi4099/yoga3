package com.yogacoach.app.model

import kotlin.math.sqrt

/** 2D 좌표 (이미지 좌표계: x 오른쪽, y 아래쪽) */
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Float) = Pt(x * k, y * k)
    operator fun div(k: Float) = Pt(x / k, y / k)
    fun len(): Float = sqrt(x * x + y * y)
    fun unit(): Pt { val l = len(); return if (l < 1e-6f) Pt(0f, 0f) else Pt(x / l, y / l) }
}

/** 채점에 쓰는 관절. HM/SM 은 골반·어깨 중심(계산값) */
enum class Joint(val ko: String) {
    NOSE("머리"),
    LS("왼쪽 어깨"), RS("오른쪽 어깨"),
    LE("왼쪽 팔꿈치"), RE("오른쪽 팔꿈치"),
    LW("왼쪽 손목"), RW("오른쪽 손목"),
    LH("왼쪽 골반"), RH("오른쪽 골반"),
    LK("왼쪽 무릎"), RK("오른쪽 무릎"),
    LA("왼쪽 발목"), RA("오른쪽 발목"),
    HM("골반 중심"), SM("어깨 중심");

    val mirror: Joint
        get() = when (this) {
            LS -> RS; RS -> LS; LE -> RE; RE -> LE; LW -> RW; RW -> LW
            LH -> RH; RH -> LH; LK -> RK; RK -> LK; LA -> RA; RA -> LA
            else -> this
        }

    /** 화면에 보이는지 판단할 때 쓰는 실제 관절 */
    val sources: List<Joint>
        get() = when (this) {
            HM -> listOf(LH, RH)
            SM -> listOf(LS, RS)
            else -> listOf(this)
        }

    companion object {
        val REAL = listOf(NOSE, LS, RS, LE, RE, LW, RW, LH, RH, LK, RK, LA, RA)
    }
}

enum class Level(val ko: String) { INTRO("입문"), BEGINNER("초급"), INTERMEDIATE("중급"), ADVANCED("고급") }

data class YogaPose(
    val id: String,
    val name: String,
    val nameEn: String,
    val level: Level,
    /** true = 몸 옆면이 카메라를 향하는 자세 */
    val sideView: Boolean,
    val description: String,
    val tips: List<String>,
    val holdSeconds: Int,
    /** 기준 자세 좌표 (골반 중심 0,0 / 몸통 길이 1 기준, 카메라에서 본 모습) */
    val template: Map<Joint, Pt>,
)
