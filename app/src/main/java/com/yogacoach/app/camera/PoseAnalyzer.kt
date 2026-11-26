package com.yogacoach.app.camera

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.yogacoach.app.model.Joint
import com.yogacoach.app.model.Pt

/** 한 프레임의 인식 결과 (이미지 좌표, 회전 보정된 세로 기준) */
data class PoseFrame(
    val points: Map<Joint, Pt>,
    val visibility: Map<Joint, Float>,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    val visibleCount: Int get() = visibility.values.count { it >= 0.5f }
}

/**
 * CameraX 프레임을 Google ML Kit 자세 인식기로 분석합니다.
 * 모든 처리는 기기 안에서 이루어지며 영상은 저장·전송되지 않습니다.
 */
class PoseAnalyzer(private val onResult: (PoseFrame) -> Unit) : ImageAnalysis.Analyzer {

    private val detector = PoseDetection.getClient(
        AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()
    )

    @Volatile
    private var closed = false

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || closed) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val w = if (rotation % 180 == 0) proxy.width else proxy.height
        val h = if (rotation % 180 == 0) proxy.height else proxy.width
        detector.process(InputImage.fromMediaImage(media, rotation))
            .addOnSuccessListener { pose -> if (!closed) onResult(toFrame(pose, w, h)) }
            .addOnFailureListener { if (!closed) onResult(PoseFrame(emptyMap(), emptyMap(), w, h)) }
            .addOnCompleteListener { proxy.close() }
    }

    fun close() {
        closed = true
        detector.close()
    }

    private fun toFrame(pose: Pose, w: Int, h: Int): PoseFrame {
        if (pose.allPoseLandmarks.isEmpty()) return PoseFrame(emptyMap(), emptyMap(), w, h)
        val pts = HashMap<Joint, Pt>()
        val vis = HashMap<Joint, Float>()
        for ((joint, type) in LANDMARKS) {
            val lm = pose.getPoseLandmark(type) ?: continue
            pts[joint] = Pt(lm.position.x, lm.position.y)
            vis[joint] = lm.inFrameLikelihood
        }
        return if (pts.size == Joint.REAL.size) PoseFrame(pts, vis, w, h) else PoseFrame(emptyMap(), emptyMap(), w, h)
    }

    companion object {
        private val LANDMARKS = mapOf(
            Joint.NOSE to PoseLandmark.NOSE,
            Joint.LS to PoseLandmark.LEFT_SHOULDER, Joint.RS to PoseLandmark.RIGHT_SHOULDER,
            Joint.LE to PoseLandmark.LEFT_ELBOW, Joint.RE to PoseLandmark.RIGHT_ELBOW,
            Joint.LW to PoseLandmark.LEFT_WRIST, Joint.RW to PoseLandmark.RIGHT_WRIST,
            Joint.LH to PoseLandmark.LEFT_HIP, Joint.RH to PoseLandmark.RIGHT_HIP,
            Joint.LK to PoseLandmark.LEFT_KNEE, Joint.RK to PoseLandmark.RIGHT_KNEE,
            Joint.LA to PoseLandmark.LEFT_ANKLE, Joint.RA to PoseLandmark.RIGHT_ANKLE,
        )
    }
}
