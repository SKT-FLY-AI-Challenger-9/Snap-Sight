package com.example.snap_sight

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.snap_sight.camera.CameraController
import com.example.snap_sight.camera.CaptureSessionManager
import com.example.snap_sight.camera.FrameScorer
import com.example.snap_sight.camera.RingFrameBuffer
import com.example.snap_sight.camera.SessionState
import com.example.snap_sight.cv.CvFrameOutput
import com.example.snap_sight.cv.DeviationJudgment
import com.example.snap_sight.cv.DeviationListener
import com.example.snap_sight.cv.DeviationResult
import com.example.snap_sight.cv.SnapSightFrameProcessor
import com.example.snap_sight.cv.SpecDeviationCalculator
import com.example.snap_sight.cv.TrackedObject
import com.example.snap_sight.cv.TargetSpec
import com.example.snap_sight.network.FrameUploader
import com.example.snap_sight.network.TtsClient
import com.example.snap_sight.network.UtteranceClient
import com.example.snap_sight.tts.TtsPlayer
import com.example.snap_sight.ux.CaptureScreen
import com.example.snap_sight.ux.GuidanceFeedback
import com.example.snap_sight.ui.theme.SnapSightTheme

/** S1(온보딩)/S2(홈·조준, [CaptureScreen]이 겸함)/S5(설정) — `docs/screen-design.md` 화면 목록 기준. */
private enum class AppScreen { ONBOARDING, MAIN, SETTINGS }

/**
 * 모듈 배선 호스트.
 *
 * 소유·연결하는 것:
 *  - 카메라(⑤): [CameraController] + 세션 상태 머신 [CaptureSessionManager] (볼륨 버튼 트리거)
 *  - CV(②): [SnapSightFrameProcessor] — 탐지·추적 결과를 디버그 오버레이와 성능 로그로 소비
 *  - STT(①): 발화 인식 결과를 [UtteranceClient]로 보내 타겟 스펙 수신
 *  - TTS(①): 재질문·에러 안내를 [TtsClient]로 요청해 [TtsPlayer]로 재생
 *  - 업로드(⑤→④): 대표 컷 + 후보 프레임을 [FrameUploader]로 백엔드 전송
 *  - 화면 전환(⑥): [AppScreen] 상태로 [OnboardingScreen]/[CaptureScreen]/[SettingsScreen] 전환
 *
 * 화면 자체(⑥)는 각 Screen Composable이 담당하고, 이 클래스는 상태 배선만 한다.
 */
class MainActivity : ComponentActivity() {

    private val cameraController by lazy { CameraController(this) }
    private val sessionManager by lazy { CaptureSessionManager(this, cameraController) }
    private val frameUploader = FrameUploader()
    private val utteranceClient = UtteranceClient()
    private val ttsClient = TtsClient()
    private val ttsPlayer by lazy { TtsPlayer(cacheDir) }

    /** ② 온디바이스 CV. 결과는 분석 스레드에서 도착 — 오버레이 갱신·성능 집계는 [onCvFrameResult] 참고. */
    private val cvProcessor by lazy {
        SnapSightFrameProcessor.create(
            this,
            listener = { output -> onCvFrameResult(output) },
            deviationCalculator = SpecDeviationCalculator(), // ④ 기하 편차 — 파이프라인 안에서 계산
        )
    }
    private var lastCvLogMs = 0L

    // 대표 컷(MediaStore)과 후보 프레임(링 버퍼)은 비동기로 따로 도착하므로
    // 둘 다 모이면 업로드한다.
    private var pendingSessionId: String? = null
    private var pendingRepresentative: Uri? = null
    private var pendingCandidates: List<RingFrameBuffer.Frame>? = null

    // 업로드에 동봉할 발화 원문 (#36 계약의 raw_text 필수 필드).
    // AIMING 진입 시 초기화, 타겟 스펙이 도착하면 그 rawText 로 갱신된다.
    private var currentRawText = ""

    private var permissionsGranted by mutableStateOf(false)
    // 권한을 한 번이라도 거부당했는지 — OnboardingScreen의 "재안내" 문구/버튼 노출 여부를 가른다.
    private var permissionDenied by mutableStateOf(false)
    private var statusText by mutableStateOf(SessionState.IDLE.description)
    private var buttonLabel by mutableStateOf("세션 시작")

    // 현재 표시 중인 화면. onCreate에서 온보딩 완료 여부에 따라 초기값을 정한다.
    // permissionsGranted(카메라 권한 전용 플래그)를 화면 전환 상태로 재사용하지 않는다.
    private var currentScreen by mutableStateOf(AppScreen.ONBOARDING)
    private val appPrefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val onboardingPermissionState: OnboardingPermissionState
        get() = when {
            permissionsGranted -> OnboardingPermissionState.GRANTED
            permissionDenied -> OnboardingPermissionState.DENIED
            else -> OnboardingPermissionState.NOT_REQUESTED
        }

    // S5 설정값 — 기본값(최대 강도·기본 속도)으로 초기화, onCreate에서 appPrefs 저장값으로 덮어쓴다.
    // 볼륨 버튼으로 슬라이더를 조절하는 것은 범위 밖(TODO) — 이슈 #43 "남은 연동 작업" 참고.
    private var settingsUiState by mutableStateOf(
        SettingsUiState(vibrationIntensity = 1f, soundVolume = 1f, speechRate = 1f)
    )

    // 디버그 오버레이용 최신 추적 객체 (정식 화면에서는 음성·햅틱으로 대체)
    private var cvObjects by mutableStateOf<List<TrackedObject>>(emptyList())

    // CV 성능 측정용 롤링 창 (분석 스레드에서 갱신, 로그 시점에 집계)
    private val cvLatencies = ArrayDeque<Long>()
    private var cvAnalyzedCount = 0

    /**
     * ⑥ 연결 지점 — AIMING 중 매 분석 프레임의 편차 결과가 이 리스너로 전달된다.
     * CV 분석 스레드에서 호출되므로 UI/햅틱 접근 시 스레드 전환은 구현체 책임.
     */
    var deviationListener: DeviationListener? = null

    /** ⑥ 실제 사운드·햅틱·TTS 렌더러 — [deviationListener]로 등록해서 쓴다 (아래 [onCreate]). */
    private val guidanceFeedback by lazy { GuidanceFeedback(this) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            permissionsGranted = results[Manifest.permission.CAMERA] == true
            if (!permissionsGranted) {
                permissionDenied = true
                statusText = "카메라 권한이 필요합니다"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        deviationListener = guidanceFeedback // ⑥ 판정 결과를 실제 사운드/햅틱/TTS로 렌더링

        settingsUiState = loadSettingsUiState()
        deviationListener = guidanceFeedback // ⑥ 판정 결과를 실제 사운드/햅틱/TTS로 렌더링
        guidanceFeedback.applySettings(settingsUiState) // 저장된 설정값을 시작부터 반영

        cameraController.setFrameProcessor(cvProcessor)
        sessionManager.listener = object : CaptureSessionManager.Listener {
            override fun onStateChanged(state: SessionState) {
                statusText = state.description
                // 조준 시작 = 새 추적 세션. track_id 가 이전 세션과 섞이지 않도록 초기화한다.
                // TargetSpec은 아직 백엔드 응답 전이라 일단 null로 시작 — onUtteranceRecognized의
                // UtteranceClient 콜백이 도착하면 같은 세션이 아직 AIMING일 때 한 번 더 갱신한다.
                if (state == SessionState.AIMING) {
                    currentRawText = "" // 스펙 도착 전 기본값 (발화 없는 세션은 이대로 업로드)
                    cvProcessor.startNewSession(spec = null)
                }
                buttonLabel = when (state) {
                    SessionState.IDLE -> "세션 시작"
                    SessionState.LISTENING -> "발화 종료"
                    SessionState.PARSING, SessionState.CAPTURING, SessionState.SAVED -> "잠시만요"
                    SessionState.AIMING -> "촬영"
                    SessionState.ERROR -> "처음으로"
                }
            }

            override fun onRecognitionRetry(sessionId: String) {
                Log.i(TAG, "발화 인식 재시도 [$sessionId]")
                speak("다시 한번 말씀해주세요")
            }

            override fun onUtteranceRecognized(sessionId: String, text: String?) {
                if (text == null) {
                    // 재시도까지 소진한 뒤 호출됨(CaptureSessionManager 참고). "의도 자체가
                    // 없었던" 경우(spec=null, 마이크 권한 없음 등)와 구분하기 위해
                    // status=FAILED인 TargetSpec을 직접 만든다 — 백엔드에 보낼 텍스트가
                    // 애초에 없으므로 UtteranceClient 왕복 없이 로컬에서 바로 처리한다.
                    Log.w(TAG, "발화 인식 실패(재시도 포함) [$sessionId] — FAILED 스펙으로 진행")
                    val failedSpec = TargetSpec(
                        sessionId = sessionId,
                        rawText = "",
                        source = "ondevice",
                        schemaVersion = "0.2",
                        status = TargetSpec.Status.FAILED,
                    )
                    applyTargetSpecIfStillAiming(sessionId, failedSpec)
                    return
                }
                Log.i(TAG, "발화 인식 완료 [$sessionId]: $text")
                utteranceClient.sendUtterance(
                    sessionId = sessionId,
                    rawText = text,
                    callback = object : UtteranceClient.Callback {
                        override fun onSuccess(spec: TargetSpec?) {
                            Log.i(TAG, "타겟 스펙 수신 [$sessionId]: $spec")
                            applyTargetSpecIfStillAiming(sessionId, spec)
                        }

                        override fun onFailure(error: Throwable) {
                            // 타겟 스펙 요청 실패해도 촬영 흐름은 계속 진행 (일반 촬영 모드로 대체)
                            Log.w(TAG, "타겟 스펙 요청 실패 [$sessionId]", error)
                        }
                    },
                )
            }

            override fun onPhotoCaptured(sessionId: String, uri: Uri) {
                Log.i(TAG, "대표 컷 저장 [$sessionId]: $uri")
                pendingSessionId = sessionId
                pendingRepresentative = uri
                maybeUploadFrames()
            }

            override fun onCandidatesCollected(
                sessionId: String,
                candidates: List<RingFrameBuffer.Frame>,
            ) {
                Log.i(TAG, "후보 프레임 ${candidates.size}장 [$sessionId]")
                pendingCandidates = candidates
                maybeUploadFrames()
            }
        }

        setContent {
            SnapSightTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (currentScreen) {
                        AppScreen.ONBOARDING -> OnboardingScreen(
                            permissionState = onboardingPermissionState,
                            onRequestPermissions = { checkOrRequestPermissions() },
                            onOpenAppSettings = { openAppSettings() },
                            onContinue = {
                                appPrefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
                                currentScreen = AppScreen.MAIN
                            },
                        )

                        AppScreen.MAIN -> if (permissionsGranted) {
                            CaptureScreen(
                                controller = cameraController,
                                statusText = statusText,
                                sessionButtonLabel = buttonLabel,
                                onSessionButton = { sessionManager.onVolumePressed() },
                                cvObjects = cvObjects,
                                onOpenSettings = { currentScreen = AppScreen.SETTINGS },
                            )
                        } else {
                            // 온보딩 완료 후 시스템 설정에서 권한이 취소된 경우의 안전망.
                            Text(
                                text = statusText,
                                modifier = Modifier.padding(innerPadding).padding(24.dp),
                            )
                        }

                        AppScreen.SETTINGS -> SettingsScreen(
                            state = settingsUiState,
                            onVibrationIntensityChange = {
                                updateSettings(settingsUiState.copy(vibrationIntensity = it))
                            },
                            onSoundVolumeChange = {
                                updateSettings(settingsUiState.copy(soundVolume = it))
                            },
                            onSpeechRateChange = {
                                updateSettings(settingsUiState.copy(speechRate = it))
                            },
                            onBack = { currentScreen = AppScreen.MAIN },
                        )
                    }
                }
            }
        }

        // 최초 실행(온보딩 미완료)은 OnboardingScreen 자체 버튼이 권한을 요청하므로 여기서
        // 자동 요청하지 않는다. 재실행 시(온보딩 완료됨)는 시스템 설정에서 권한이 취소됐을 수
        // 있어 기존과 동일하게 확인한다.
        if (currentScreen == AppScreen.MAIN) {
            checkOrRequestPermissions()
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
    }

    /** appPrefs에 저장된 S5 설정값을 읽는다. 저장된 적 없으면 기본값(최대 강도·기본 속도). */
    private fun loadSettingsUiState() = SettingsUiState(
        vibrationIntensity = appPrefs.getFloat(KEY_VIBRATION_INTENSITY, 1f),
        soundVolume = appPrefs.getFloat(KEY_SOUND_VOLUME, 1f),
        speechRate = appPrefs.getFloat(KEY_SPEECH_RATE, 1f),
    )

    /** S5에서 값이 바뀔 때마다 호출 — 화면 상태 갱신 + 영속화 + GuidanceFeedback 반영을 한 번에 한다. */
    private fun updateSettings(newState: SettingsUiState) {
        settingsUiState = newState
        appPrefs.edit()
            .putFloat(KEY_VIBRATION_INTENSITY, newState.vibrationIntensity)
            .putFloat(KEY_SOUND_VOLUME, newState.soundVolume)
            .putFloat(KEY_SPEECH_RATE, newState.speechRate)
            .apply()
        guidanceFeedback.applySettings(newState)
    }

    /**
     * 짧은 안내 문구를 TTS로 재생한다 (재질문·에러 안내 등, 이슈 TTS-1).
     *
     * 실패해도 세션 흐름은 절대 막지 않는다 — 안내 음성 하나 때문에 촬영이 멈추면 안 되므로
     * 실패 시 로그만 남기고 조용히 넘어간다.
     */
    private fun speak(text: String) {
        ttsClient.synthesize(
            text = text,
            callback = object : TtsClient.Callback {
                override fun onSuccess(audioBytes: ByteArray) {
                    ttsPlayer.play(audioBytes)
                }

                override fun onFailure(error: Throwable) {
                    Log.w(TAG, "TTS 요청 실패: $text", error)
                }
            },
        )
    }

    /**
     * 타겟 스펙이 (네트워크 지연으로) AIMING 시작보다 늦게 도착했을 때 CV 세션에 반영한다.
     *
     * [SpeechToTextRecognizer][com.example.snap_sight.stt.SpeechToTextRecognizer] 인식 →
     * [UtteranceClient] 응답은 비동기라 AIMING 진입 시점엔 아직 없는 게 보통이다(spec=null로 시작).
     * 응답이 왔을 때 사용자가 이미 촬영을 마쳤거나 세션을 취소·재시작했다면(다른 sessionId,
     * 또는 더 이상 AIMING이 아님) 엉뚱한 세션에 적용하면 안 되므로 여기서 막는다.
     */
    private fun applyTargetSpecIfStillAiming(sessionId: String, spec: TargetSpec?) {
        if (sessionManager.sessionId != sessionId || sessionManager.state != SessionState.AIMING) {
            Log.i(TAG, "타겟 스펙 도착했지만 세션이 이미 지나감 [$sessionId] — 무시")
            return
        }
        currentRawText = spec?.rawText.orEmpty()
        cvProcessor.startNewSession(spec = spec)
    }

    /**
     * ② CV 결과 수신 지점 (분석 스레드).
     *
     * 여기서 나오는 `output.objectsJson()` 이 ③ 편차 계산 / ⑥ 햅틱·사운드 렌더링의 입력이다.
     * 매 프레임 로그는 너무 많으므로 1초에 한 번만 남긴다.
     * Logcat 필터: `tag:SnapSightCV`
     */
    private fun onCvFrameResult(output: CvFrameOutput) {
        if (!output.analyzed) return
        runOnUiThread { cvObjects = output.objects } // 디버그 오버레이 갱신

        // ④ 편차 판정 — 파이프라인이 계산한 기하 편차를 계약 형태로 해석. 조준 중에만 의미가 있다.
        val deviation = if (sessionManager.state == SessionState.AIMING) {
            DeviationJudgment.judge(
                deviation = output.deviation,
                framing = output.targetSpec?.framing ?: TargetSpec.Framing.FULL_BODY,
            )
        } else null
        deviation?.let { deviationListener?.onDeviation(it) }

        // 성능 측정: emit 은 처리 종료 직후 동기 호출이므로 now - timestampMs = 파이프라인 지연
        val latencyMs = System.currentTimeMillis() - output.timestampMs
        synchronized(cvLatencies) {
            cvLatencies.addLast(latencyMs)
            if (cvLatencies.size > 120) cvLatencies.removeFirst()
            cvAnalyzedCount++
        }

        val now = output.timestampMs
        if (now - lastCvLogMs < 1000) return
        val windowMs = now - lastCvLogMs
        lastCvLogMs = now

        val (p50, p95, fps) = synchronized(cvLatencies) {
            val sorted = cvLatencies.sorted()
            val p50 = sorted[sorted.size / 2]
            val p95 = sorted[minOf(sorted.size - 1, sorted.size * 95 / 100)]
            val fps = cvAnalyzedCount * 1000f / windowMs.coerceAtLeast(1)
            cvAnalyzedCount = 0
            Triple(p50, p95, fps)
        }
        Log.d(CV_TAG, "성능: %.1f fps, 지연 %dms (p50 %d / p95 %d, 최근 %d프레임 창)".format(
            fps, latencyMs, p50, p95, cvLatencies.size))
        Log.d(CV_TAG, "객체 ${output.objects.size}개 ${output.objectsJson()}")
        deviation?.let {
            if (it.subjectDetected) {
                Log.d(CV_TAG, "편차: x=%+.3f size=%+.3f ready후보=%b".format(
                    it.xDeviation, it.sizeDeviation, DeviationJudgment.isReadyCandidate(it)))
            } else {
                Log.d(CV_TAG, "편차: 피사체 없음 (LOST 후보)")
            }
        }
    }

    /** 대표 컷과 후보 프레임이 모두 모이면 백엔드로 업로드 (⑤→④). */
    private fun maybeUploadFrames() {
        val sessionId = pendingSessionId ?: return
        val representative = pendingRepresentative ?: return
        val candidates = pendingCandidates ?: return
        pendingSessionId = null
        pendingRepresentative = null
        pendingCandidates = null

        frameUploader.uploadCaptureFrames(
            sessionId = sessionId,
            rawText = currentRawText,
            representativeJpegProvider = {
                contentResolver.openInputStream(representative)?.use { it.readBytes() }
                    ?: throw IllegalStateException("대표 컷을 읽을 수 없음: $representative")
            },
            candidates = candidates,
            // ⑦ 휴리스틱 스코어링 — 업로드 스레드에서 계산 (후보 6장 디코딩+라플라시안)
            candidateScoresProvider = { candidates.map { FrameScorer.blurScore(it.jpeg) } },
            callback = object : FrameUploader.Callback {
                override fun onSuccess(result: FrameUploader.UploadResult) {
                    Log.i(TAG, "업로드 완료 [${result.sessionId}] 후보 ${result.receivedCandidateCount}장")
                }

                override fun onFailure(error: Throwable) {
                    // 업로드 실패는 촬영 성공과 무관 — 사진은 이미 기기에 저장됨 (재시도는 추후)
                    Log.w(TAG, "업로드 실패 (사진은 기기에 저장됨)", error)
                }
            },
        )
    }

    private fun checkOrRequestPermissions() {
        val required = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val allGranted = required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            permissionsGranted = true
        } else {
            permissionLauncher.launch(required)
        }
    }

    // 볼륨 버튼: 짧게 = 상태별 동작(시작/발화종료/셔터), 길게(≈1초) = 세션 취소.
    // onKeyDown 에서 startTracking() 해야 onKeyLongPress 가 동작한다.

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolumeKey(keyCode) && permissionsGranted && cameraController.isBound) {
            event?.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolumeKey(keyCode) && permissionsGranted && cameraController.isBound) {
            if (event?.isCanceled != true) {
                sessionManager.onVolumePressed()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolumeKey(keyCode)) {
            sessionManager.cancel()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun isVolumeKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP

    override fun onDestroy() {
        super.onDestroy()
        sessionManager.cancel()
        ttsPlayer.stop()
        // 분리 시 onDetached() 가 불려 TFLite 인터프리터가 해제된다.
        cameraController.setFrameProcessor(null)
        guidanceFeedback.release()
    }

    private companion object {
        const val TAG = "SnapSight"
        const val CV_TAG = "SnapSightCV"
        const val PREFS_NAME = "snap_sight_prefs"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        const val KEY_VIBRATION_INTENSITY = "vibration_intensity"
        const val KEY_SOUND_VOLUME = "sound_volume"
        const val KEY_SPEECH_RATE = "speech_rate"
    }
}
