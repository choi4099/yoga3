package com.yogacoach.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.yogacoach.app.camera.PoseAnalyzer
import com.yogacoach.app.camera.PoseFrame
import com.yogacoach.app.databinding.ActivityPracticeBinding
import com.yogacoach.app.model.PoseRepository
import com.yogacoach.app.model.YogaPose
import com.yogacoach.app.scoring.Part
import com.yogacoach.app.scoring.PoseScorer
import com.yogacoach.app.scoring.ScoreResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 연습 화면: 카메라 + 기준 자세 겹쳐 보기 + 부위별 점수 + 문제점 + 개선 방법 */
class PracticeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_POSE_ID = "pose_id"
        private const val PASS_SCORE = 80f
        private const val UI_INTERVAL_MS = 300L
        private const val VOICE_INTERVAL_MS = 6000L
    }

    private lateinit var b: ActivityPracticeBinding
    private lateinit var pose: YogaPose
    private lateinit var executor: ExecutorService
    private var analyzer: PoseAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var useFront = true

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voiceOn = true
    private var lastSpokenAt = 0L
    private var lastSpoken = ""

    // 점수 흔들림을 줄이기 위한 평활화 값
    private var smoothTotal = -1f
    private val smoothParts = HashMap<Part, Float>()
    private var best = 0f
    private var holdMs = 0L
    private var lastFrameAt = 0L
    private var lastUiAt = 0L
    private var completed = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            b.status.text = "카메라 권한이 필요합니다. 설정 > 애플리케이션 > 요가 > 권한에서 허용해 주세요."
            b.status.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPracticeBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = intent.getStringExtra(EXTRA_POSE_ID)
        pose = id?.let { PoseRepository.byId(this, it) } ?: run { finish(); return }
        executor = Executors.newSingleThreadExecutor()

        b.title.text = "${pose.name}  ${pose.nameEn}"
        b.thumb.setPose(pose.template)
        b.thumb.lineColor = 0xFFFFFFFF.toInt()
        b.btnBack.setOnClickListener { finish() }
        b.btnSwitch.setOnClickListener { useFront = !useFront; resetScores(); startCamera() }
        b.btnVoice.setOnClickListener {
            voiceOn = !voiceOn
            b.btnVoice.text = if (voiceOn) "음성 켬" else "음성 끔"
            if (!voiceOn) tts?.stop()
        }
        b.fixes.text = tipsText()
        b.status.text = if (pose.sideView) "몸 옆면이 카메라를 향하게 서 주세요" else "카메라를 정면으로 보고 서 주세요"
        b.status.visibility = View.VISIBLE

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                ttsReady = true
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            provider.unbindAll()
            analyzer?.close()

            val preview = Preview.Builder().build().also { it.setSurfaceProvider(b.preview.surfaceProvider) }
            val newAnalyzer = PoseAnalyzer { frame -> onFrame(frame) }
            analyzer = newAnalyzer
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, newAnalyzer) }
            var selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val hasIt = try { provider.hasCamera(selector) } catch (e: Exception) { false }
            if (!hasIt) {
                useFront = !useFront
                selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            }
            try {
                provider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "카메라를 시작할 수 없습니다: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun resetScores() {
        smoothTotal = -1f; smoothParts.clear(); holdMs = 0L; best = 0f; completed = false
    }

    /** 메인 스레드에서 호출됨 (ML Kit 콜백) */
    private fun onFrame(frame: PoseFrame) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameAt == 0L) 0L else (now - lastFrameAt).coerceAtMost(500L)
        lastFrameAt = now

        if (frame.points.isEmpty() || frame.visibleCount < 9) {
            b.overlay.update(frame, null, useFront)
            if (now - lastUiAt > UI_INTERVAL_MS) {
                lastUiAt = now
                b.status.visibility = View.VISIBLE
                b.status.text = if (frame.points.isEmpty()) "사람을 찾는 중입니다… 전신이 보이게 서 주세요"
                else "몸 전체(머리~발목)가 화면에 들어오도록 2~3m 뒤로 물러나 주세요"
            }
            return
        }

        val r = PoseScorer.evaluateBest(frame.points, frame.visibility, pose)
        b.overlay.update(frame, r, useFront)

        smoothTotal = if (smoothTotal < 0) r.total else smoothTotal * 0.75f + r.total * 0.25f
        for ((p, s) in r.parts) smoothParts[p] = smoothParts[p]?.let { it * 0.75f + s * 0.25f } ?: s
        best = maxOf(best, smoothTotal)
        if (smoothTotal >= PASS_SCORE) holdMs += dt

        if (now - lastUiAt < UI_INTERVAL_MS) return
        lastUiAt = now
        renderPanel(r)
        speak(r, now)
    }

    private fun renderPanel(r: ScoreResult) {
        b.status.visibility = View.GONE
        val total = smoothTotal.toInt()
        b.totalScore.text = total.toString()
        b.totalScore.setTextColor(OverlayView.scoreColor(smoothTotal))
        b.grade.text = when {
            total >= 90 -> "훌륭해요!"
            total >= 80 -> "좋아요"
            total >= 60 -> "조금만 더"
            else -> "자세를 맞춰 보세요"
        }
        setPart(b.pbHand, b.txtHand, Part.HAND)
        setPart(b.pbArm, b.txtArm, Part.ARM)
        setPart(b.pbLeg, b.txtLeg, Part.LEG)
        setPart(b.pbBody, b.txtBody, Part.BODY)

        val sec = holdMs / 1000
        b.hold.text = "유지 ${sec}초 / 목표 ${pose.holdSeconds}초   ·   최고 ${best.toInt()}점"
        if (!completed && sec >= pose.holdSeconds) {
            completed = true
            Toast.makeText(this, "${pose.name} 완료! 수고하셨어요", Toast.LENGTH_LONG).show()
            say("${pose.name} 완료! 수고하셨어요", force = true)
        }

        val worst = r.worst(3)
        if (worst.isEmpty()) {
            b.problems.text = "• 큰 문제가 없습니다. 지금 자세를 유지하세요 👍"
            b.fixes.text = tipsText()
        } else {
            b.problems.text = worst.joinToString("\n") { "• [${it.check.part.ko}] ${it.problem}" }
            b.fixes.text = worst.mapIndexed { i, c -> "${i + 1}. ${c.fix}" }.joinToString("\n")
        }
    }

    private fun setPart(pb: ProgressBar, tv: TextView, part: Part) {
        val v = smoothParts[part]
        if (v == null) {
            pb.progress = 0; tv.text = "-"
        } else {
            pb.progress = v.toInt()
            tv.text = v.toInt().toString()
            tv.setTextColor(OverlayView.scoreColor(v))
        }
    }

    private fun tipsText() = pose.tips.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")

    private fun speak(r: ScoreResult, now: Long) {
        if (!voiceOn || !ttsReady || now - lastSpokenAt < VOICE_INTERVAL_MS) return
        val msg = r.worst(1).firstOrNull()?.fix ?: if (smoothTotal >= PASS_SCORE) "좋아요, 그대로 유지하세요" else return
        if (msg == lastSpoken && now - lastSpokenAt < VOICE_INTERVAL_MS * 2) return
        say(msg)
        lastSpoken = msg
        lastSpokenAt = now
    }

    private fun say(msg: String, force: Boolean = false) {
        if ((!voiceOn && !force) || !ttsReady) return
        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "yoga")
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzer?.close()
        cameraProvider?.unbindAll()
        if (::executor.isInitialized) executor.shutdown()
        tts?.shutdown()
    }
}
