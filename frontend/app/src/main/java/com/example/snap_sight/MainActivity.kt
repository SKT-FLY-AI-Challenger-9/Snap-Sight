// 이 파일: 앱의 시작점이자 모든 부품을 연결하는 본부.
// 카메라·AI 탐지·음성 인식·편차 안내·업로드를 만들어 서로 이어주고,
// 볼륨 버튼 입력과 권한 요청도 여기서 처리한다.
package com.example.snap_sight

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.media.AudioManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.snap_sight.camera.AutoZoomController
import com.example.snap_sight.camera.CameraController
import com.example.snap_sight.camera.CameraMotionEstimator
import com.example.snap_sight.camera.CaptureSessionManager
import com.example.snap_sight.camera.FrameScorer
import com.example.snap_sight.camera.GalleryPhoto
import com.example.snap_sight.camera.PhotoLibrary
import com.example.snap_sight.camera.MissingSubjectNotice
import com.example.snap_sight.camera.RingFrameBuffer
import com.example.snap_sight.camera.SessionState
import com.example.snap_sight.cv.ByteTrackLiteConfig
import com.example.snap_sight.cv.CvFrameOutput
import com.example.snap_sight.cv.CvFrame
import com.example.snap_sight.cv.FaceFrameAnalyzer
import com.example.snap_sight.cv.FrameResult
import com.example.snap_sight.face.FaceIdentifier
import com.example.snap_sight.face.FaceRegistry
import com.example.snap_sight.face.SelfieGazeMonitor
import com.example.snap_sight.face.TfLiteFaceEmbedder
import com.example.snap_sight.cv.DeviationJudgment
import com.example.snap_sight.cv.DeviationListener
import com.example.snap_sight.cv.DeviationResult
import com.example.snap_sight.cv.SnapSightFrameProcessor
import com.example.snap_sight.cv.SpecDeviationCalculator
import com.example.snap_sight.cv.TrackedObject
import com.example.snap_sight.cv.TargetLockStats
import com.example.snap_sight.cv.TargetSpec
import com.example.snap_sight.network.BackendConfig
import com.example.snap_sight.network.CaptureResultClient
import com.example.snap_sight.network.DescriptionLookup
import com.example.snap_sight.network.FrameUploader
import com.example.snap_sight.network.MetadataClient
import com.example.snap_sight.network.PhotoDescriptionClient
import com.example.snap_sight.network.TtsClient
import com.example.snap_sight.network.UtteranceClient
import com.example.snap_sight.search.PhotoIndexEntry
import com.example.snap_sight.search.PhotoIndexStore
import com.example.snap_sight.search.PhotoLabelDictionary
import com.example.snap_sight.search.PhotoQuery
import com.example.snap_sight.search.PhotoQueryParser
import com.example.snap_sight.search.PhotoSearchEngine
import com.example.snap_sight.stt.SpeechToTextRecognizer
import com.example.snap_sight.tts.TtsPlayer
import com.example.snap_sight.ux.CaptureScreen
import com.example.snap_sight.ux.GalleryScreen
import com.example.snap_sight.ux.GuidanceFeedback
import com.example.snap_sight.ux.HomeScreen
import com.example.snap_sight.ux.ResultScreen
import com.example.snap_sight.ux.OnboardingPermissionState
import com.example.snap_sight.ux.OnboardingScreen
import com.example.snap_sight.ux.SettingsRepository
import com.example.snap_sight.ux.SettingsScreen
import com.example.snap_sight.ux.SettingsUiState
import com.example.snap_sight.ui.theme.SnapSightTheme

/** S1(온보딩)/S2(홈·조준, [CaptureScreen]이 겸함)/S5(설정) — `docs/screen-design.md` 화면 목록 기준. */
private enum class AppScreen { ONBOARDING, MAIN, SETTINGS, GALLERY }

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
    private val resultClient = CaptureResultClient()
    private val descriptionClient = PhotoDescriptionClient()
    private val descriptionLookup by lazy { DescriptionLookup(this) }
    private val metadataClient = MetadataClient()

    // 검색용 로컬 사진 인덱스 + 고정 라벨 사전 (기능 3) — DB 접근은 항상 백그라운드 스레드에서
    private val photoIndexStore by lazy { PhotoIndexStore(this) }
    private val photoLabelDictionary: PhotoLabelDictionary by lazy {
        try {
            val raw = assets.open("photo_labels.json").use { it.readBytes().toString(Charsets.UTF_8) }
            PhotoLabelDictionary.fromJson(raw)
        } catch (t: Throwable) {
            Log.w(TAG, "라벨 사전 로드 실패 — 시간·본문 검색만 동작", t)
            PhotoLabelDictionary(version = 0, labels = emptyList())
        }
    }

    /** 커스텀 라벨 사전의 메모리 사본 — 업로드·질의 파싱이 메인 스레드에서 읽는다. */
    @Volatile
    private var customLabelsCache: List<String> = emptyList()

    /** 갤러리 음성 검색 전용 인식기 — 촬영 세션의 인식기와 분리 (동시 사용 없음). */
    private val searchRecognizer by lazy { SpeechToTextRecognizer(this) }

    // 기능 2: 얼굴 인식 — 임베딩·이름은 기기 로컬 전용 (서버로 절대 안 나감)
    private val faceRegistry by lazy { FaceRegistry(this) }
    private val faceEmbedder by lazy { TfLiteFaceEmbedder(this) }
    private val faceIdentifier by lazy { FaceIdentifier(faceRegistry, faceEmbedder) }

    // 셀카 모드 시선 판정 — 전면 카메라일 때만 켜진다 (CaptureScreen onLensChanged 로 토글)
    private val selfieGaze = SelfieGazeMonitor()

    /** 인물 인식 + 셀카 시선 판정을 한 훅으로 묶는다 (분석 스레드에서 순차 실행). */
    private val faceAnalyzers = object : FaceFrameAnalyzer {
        override fun analyze(frame: CvFrame, frameResult: FrameResult): Map<Int, String> {
            selfieGaze.onFrame(frame)
            return faceIdentifier.analyze(frame, frameResult)
        }

        override fun reset() {
            selfieGaze.reset()
            faceIdentifier.reset()
        }
    }

    // 셀카 모드 상태 + "카메라를 보고 있어요" 안내 중복 방지 (메인 스레드에서만 접근)
    private var isSelfieMode = false
    private var lastAnnouncedGaze: SelfieGazeMonitor.GazeState? = null

    /** 등록 인물 이름 캐시 — 검색 파서·설정 화면이 메인 스레드에서 읽는다. */
    private var registeredPeople by mutableStateOf<List<String>>(emptyList())

    // 조준 중 마지막 신원 결과 + 세션 중 이미 안내한 이름 (같은 사람을 반복 안내하지 않게)
    @Volatile
    private var currentIdentities: Map<Int, String> = emptyMap()
    private val announcedIdentities = mutableSetOf<String>()

    /** ④ 기하 편차 계산기 — 세션마다 [SpecDeviationCalculator.reset] 으로 타겟 기억을 지운다. */
    private val deviationCalculator = SpecDeviationCalculator()

    /** 자이로 기반 카메라 모션 보정 (기능 1-C) — CameraMotionEstimator.ENABLED 로 게이트. */
    private val motionEstimator by lazy { CameraMotionEstimator(this) }

    /** ② 온디바이스 CV. 결과는 분석 스레드에서 도착 — 오버레이 갱신·성능 집계는 [onCvFrameResult] 참고. */
    private val cvProcessor by lazy {
        SnapSightFrameProcessor.create(
            this,
            listener = { output -> onCvFrameResult(output) },
            deviationCalculator = deviationCalculator, // ④ 기하 편차 — 파이프라인 안에서 계산
            // selector 생략 = detector 와 같은 라벨 자산으로 Objects365TargetSelector 생성
            // (person/object/landscape 구분, canonical objectLabel class 매칭, 못 찾으면 후보 없음)
            // 트래킹 안정화 (기능 1-D): track 유지 시간을 프레임 수 대신 초로 고정하고,
            // 놓친 시간에 비례해 매칭 bbox 를 확장한다. 값은 docs/feature-expansion-plan.md 파라미터 표.
            trackerConfig = ByteTrackLiteConfig(
                lostTrackBufferSeconds = 2.0,
                matchExpansionRatePerSecond = 0.5,
            ),
            motionHintProvider = { motionEstimator.consumeHint() },
            faceAnalyzer = faceAnalyzers, // 기능 2(인물 인식) + 셀카 시선 판정
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

    // 세션 상태(Compose 반영용) — 홈 오버레이(IDLE/LISTENING/PARSING)와 조준 UI 전환을 가른다 (#80)
    private var sessionState by mutableStateOf(SessionState.IDLE)

    // 현재 세션 발화 원문 (홈 "듣는 중" 표시·조준 화면 요청 카드·결과 화면에 공유)
    private var sessionRawText by mutableStateOf("")

    // 조준 중 하단 안내 카드 문구 — 음성·햅틱(⑥)과 같은 판정을 화면 텍스트로도 보여준다 (#80)
    private var guidanceText by mutableStateOf("")

    // 촬영 결과 화면(S4) 상태 — 대표 컷 저장 직후 표시, 새 세션 시작 시 해제
    private var showResult by mutableStateOf(false)
    private var resultPhoto by mutableStateOf<Bitmap?>(null)
    private var lastResultDescription by mutableStateOf<String?>(null)

    // 셔터 순간의 탐지 객체 스냅샷 — 즉시 상황 안내(instantCaptureSummary)의 입력 (#80)
    private var shutterObjects: List<TrackedObject> = emptyList()

    // 마지막으로 촬영된 세션 — 결과 화면의 "라벨 붙이기"가 어느 사진에 붙일지의 기준
    private var lastCapturedSessionId: String? = null

    // 셔터 순간 화면에 있던 등록 인물 이름 — 로컬 인덱스의 people 태그로만 쓰인다 (서버 안 감)
    private var shutterIdentities: List<String> = emptyList()

    // S5 서버 주소 입력칸 상태 — 돌아가기 시점에 BackendConfig 로 저장·정규화된다
    private var backendUrlInput by mutableStateOf("")

    // 현재 표시 중인 화면. onCreate에서 온보딩 완료 여부에 따라 초기값을 정한다.
    // permissionsGranted(카메라 권한 전용 플래그)를 화면 전환 상태로 재사용하지 않는다.
    private var currentScreen by mutableStateOf(AppScreen.ONBOARDING)
    private val appPrefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val settingsRepository by lazy { SettingsRepository(appPrefs) }

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

    // 사진 찾기 화면 데이터 — GALLERY 진입 시 백그라운드로 로드 (#78)
    private var galleryPhotos by mutableStateOf<List<GalleryPhoto>?>(null)

    // 검색 인덱스(세션 ID → 항목)와 음성 필터 스택 (기능 3-C 점진 좁히기)
    private var galleryIndex by mutableStateOf<Map<String, PhotoIndexEntry>>(emptyMap())
    private var galleryFilterStack by mutableStateOf<List<PhotoQuery>>(emptyList())

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

    // 자동 줌인 (이슈 #67) — AIMING 중 타겟 점유율 기반, 세션 시작 시 리셋
    private val autoZoom by lazy { AutoZoomController(cameraController) }

    // 조준 중 최근 CV 판정 스냅샷 — "요청한 피사체 없이 찍힘" 안내의 근거 (분석 스레드에서 갱신)
    @Volatile
    private var lastAimingVerdictAtMs = 0L
    @Volatile
    private var lastAimingSubjectDetected = false

    // 셔터 순간 요청 피사체가 화면에 없었으면 그 한글 호칭 — 결과 안내·헤드라인에 덧붙인다
    private var shutterMissingTarget: String? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            permissionsGranted = results[Manifest.permission.CAMERA] == true
            if (permissionsGranted) announceWelcomeOnce()
            if (!permissionsGranted) {
                permissionDenied = true
                statusText = "카메라 권한이 필요합니다"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        currentScreen = if (appPrefs.getBoolean(KEY_ONBOARDING_DONE, false)) {
            AppScreen.MAIN
        } else {
            AppScreen.ONBOARDING
        }

        settingsUiState = settingsRepository.load()
        BackendConfig.load(appPrefs) // 저장된 서버 주소 재정의 복원 (없으면 빌드 주입값)
        backendUrlInput = BackendConfig.storedOverride(appPrefs)
        deviationListener = guidanceFeedback // ⑥ 판정 결과를 실제 사운드/햅틱/TTS로 렌더링
        // 줌인 여유가 있으면 "가까이" 대신 자동 줌이 처리한다 — 음성과 줌이 서로 싸우지 않게
        guidanceFeedback.zoomHandlesDistance = { sessionManager.state == SessionState.AIMING && autoZoom.canZoomIn }
        // 셀카 모드: 구도가 맞아도 시선이 카메라를 벗어나 있으면 "지금 촬영하세요"를 보류하고 사유를 말한다
        guidanceFeedback.readyGate = { selfieGaze.readyBlockReason() }
        guidanceFeedback.applySettings(settingsUiState) // 저장된 설정값을 시작부터 반영

        // 커스텀 라벨·등록 인물을 메모리에 올린다 — 업로드/검색 파서가 메인 스레드에서 읽는다 (기능 2·3)
        Thread({
            customLabelsCache = photoIndexStore.allCustomLabels()
            val people = faceRegistry.peopleNames()
            runOnUiThread { registeredPeople = people }
        }, "SnapSight-LabelCache").start()

        cameraController.setFrameProcessor(cvProcessor)
        sessionManager.listener = object : CaptureSessionManager.Listener {
            override fun onStateChanged(state: SessionState) {
                sessionState = state
                statusText = state.description
                if (state == SessionState.LISTENING) {
                    showResult = false
                    sessionRawText = ""
                    guidanceText = ""
                }
                // 조준 시작 = 새 추적 세션. track_id 가 이전 세션과 섞이지 않도록 초기화한다.
                // TargetSpec은 아직 백엔드 응답 전이라 일단 null로 시작 — onUtteranceRecognized의
                // UtteranceClient 콜백이 도착하면 같은 세션이 아직 AIMING일 때 한 번 더 갱신한다.
                if (state == SessionState.AIMING) {
                    currentRawText = "" // 스펙 도착 전 기본값 (발화 없는 세션은 이대로 업로드)
                    cvProcessor.startNewSession(spec = null)
                    autoZoom.reset() // 넓게(0.6배, 기기 최소 배율) 시작해 피사체를 먼저 찾는다
                    guidanceFeedback.resetSession() // 이전 세션의 READY/LOST "이미 말했음" 상태 초기화
                    deviationCalculator.reset() // 이전 세션의 타겟(track_id)·안정성 지표 초기화
                    motionEstimator.start() // 조준 중에만 자이로 적분 (기능 1-C)
                    announcedIdentities.clear() // 이 세션에서 인물 안내를 다시 할 수 있게 (기능 2)
                    currentIdentities = emptyMap()
                    shutterMissingTarget = null
                    lastAimingVerdictAtMs = 0L
                    lastAimingSubjectDetected = false
                    lastAnnouncedGaze = null // 셀카 시선 안내를 세션마다 새로 시작
                } else {
                    // 조준 종료 — 이번 세션의 트래킹 안정성 지표를 남긴다 (기능 1-A, 캘리브레이션 근거)
                    motionEstimator.stop()
                    val lockStats = deviationCalculator.stats()
                    if (lockStats != TargetLockStats()) {
                        Log.i(CV_TAG, "타겟 락 지표: 갈아탐 ${lockStats.targetSwitches}, " +
                            "재획득 ${lockStats.reacquisitions}, LOST ${lockStats.lostEpisodes}, " +
                            "hold ${lockStats.heldFrames}프레임")
                    }
                }
                // ⑥ 세션 이벤트 안내 — 즉시성이 중요해 내장 TTS 로 바로 말한다 (실사용 피드백 #2·#3)
                when (state) {
                    SessionState.LISTENING -> guidanceFeedback.announce("무엇을 찍을지 말씀해 주세요")
                    // #84 탭 우선: 탭을 먼저, 볼륨은 병행 수단으로 나중에 말한다
                    SessionState.AIMING -> guidanceFeedback.announce("카메라를 비춰 주세요. 화면을 두 번 탭하면 촬영합니다")
                    SessionState.CAPTURING -> {
                        shutterObjects = cvObjects // 즉시 상황 안내용 스냅샷 (#80)
                        shutterIdentities = currentIdentities.values.distinct() // 기능 2 — 인물 태그용
                        // 요청한 피사체(예: 노트북)가 셔터 순간 화면에 없었는지 — 결과 안내에 쓴다
                        shutterMissingTarget = MissingSubjectNotice.targetNameIfMissing(
                            spec = cvProcessor.currentTargetSpec(),
                            subjectDetected = lastAimingSubjectDetected,
                            hasFreshVerdict = System.currentTimeMillis() -
                                lastAimingVerdictAtMs <= VERDICT_FRESH_MS,
                            koreanLabels = KOREAN_LABELS,
                        )
                        guidanceFeedback.playShutter()
                    }
                    SessionState.SAVED -> {
                        guidanceFeedback.announce(captureHeadline())
                        autoZoom.reset() // 촬영이 끝나면 다시 0.6배 광각으로
                    }
                    SessionState.ERROR -> {
                        guidanceFeedback.announce("촬영에 실패했습니다. 화면을 두 번 탭해 처음으로 돌아갑니다")
                        autoZoom.reset()
                    }
                    else -> Unit
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
                sessionRawText = text // 시안(v31): 해석하는 동안 홈 마이크 아래에 발화 원문을 보여준다
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
                lastCapturedSessionId = sessionId
                pendingRepresentative = uri
                // 검색 인덱스 기본 행 — 메타데이터 도착 전에도 시간 검색이 되게 한다 (기능 3-B)
                val peopleAtShutter = shutterIdentities
                Thread({
                    photoIndexStore.insertCapture(
                        sessionId = sessionId,
                        takenAtMs = System.currentTimeMillis(),
                        locationText = null, // TODO 위치 권한 온보딩 후 Geocoder 연동 (기획 문서 3-B)
                    )
                    // 기능 2 연동: 셔터 순간 인식된 인물을 로컬 인덱스에만 기록 → "민수 나온 사진" 검색
                    if (peopleAtShutter.isNotEmpty()) {
                        photoIndexStore.applyPeople(sessionId, peopleAtShutter)
                    }
                }, "SnapSight-IndexInsert").start()
                showResultScreen(uri)
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
                            // 카메라는 항상 아래에 깔려 있고, 세션 단계에 따라 홈/결과 화면이 위에 뜬다 (v31 #80).
                            val homeVisible = !showResult && sessionState in setOf(
                                SessionState.IDLE, SessionState.LISTENING, SessionState.PARSING,
                            )
                            // #84: 뒤로가기 = 복귀 문법 — 결과 닫기 → 세션 취소 → (홈) 2회 종료 확인
                            BackHandler {
                                when {
                                    showResult -> closeResultToHome()
                                    sessionState != SessionState.IDLE -> sessionManager.cancel()
                                    else -> onHomeBackPressed()
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // #84: 화면 아무 곳 두 번 탭 = 진행(시작/발화 종료/셔터/다시 촬영),
                                    // 길게 누르기 = 복귀. 버튼 단일 탭은 자식이 소비하므로 공존한다.
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onDoubleTap = { onMainDoubleTap() },
                                            onLongPress = { onMainLongPress() },
                                        )
                                    },
                            ) {
                                CaptureScreen(
                                    controller = cameraController,
                                    statusText = statusText,
                                    rawText = sessionRawText,
                                    guidanceText = guidanceText,
                                    onCancel = { sessionManager.cancel() },
                                    cvObjects = cvObjects,
                                    showOverlays = !homeVisible && !showResult,
                                    onLensChanged = { isFront -> onLensChanged(isFront) },
                                    // 조준 중엔 미리보기 전체가 "촬영" 접근성 노드 — TalkBack 두 번 탭도 셔터
                                    onShutterTap = if (sessionState == SessionState.AIMING) {
                                        { sessionManager.onVolumePressed() }
                                    } else null,
                                )
                                if (homeVisible) {
                                    HomeScreen(
                                        onStartSession = { sessionManager.onVolumePressed() },
                                        onOpenGallery = { openGallery() },
                                        onOpenSettings = { enterScreen(AppScreen.SETTINGS) },
                                        isListening = sessionState == SessionState.LISTENING ||
                                            sessionState == SessionState.PARSING,
                                        recognizedText = sessionRawText,
                                    )
                                }
                                if (showResult) {
                                    ResultScreen(
                                        photo = resultPhoto,
                                        rawText = sessionRawText,
                                        description = lastResultDescription,
                                        headline = captureHeadline(),
                                        onReplayDescription = {
                                            lastResultDescription?.let(::speak)
                                                ?: speak("설명을 만드는 중이에요")
                                        },
                                        onConfirm = { showResult = false }, // 사진은 이미 저장됨
                                        onRetake = {
                                            showResult = false
                                            if (sessionManager.state == SessionState.IDLE) {
                                                sessionManager.onVolumePressed()
                                            }
                                        },
                                        onAddLabel = { startResultLabeling() },
                                    )
                                }
                            }
                        } else {
                            // 온보딩 완료 후 시스템 설정에서 권한이 취소된 경우의 안전망.
                            Text(
                                text = statusText,
                                modifier = Modifier.padding(innerPadding).padding(24.dp),
                            )
                        }

                        AppScreen.SETTINGS -> {
                            // #84: 뒤로 제스처로 나가도 서버 주소 적용을 건너뛰지 않는다
                            BackHandler { leaveSettingsToHome() }
                            SettingsScreen(
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
                            serverUrl = backendUrlInput,
                            onServerUrlChange = { backendUrlInput = it },
                            registeredPeople = registeredPeople,
                            onEnrollFace = { startFaceEnrollment() },
                            onDeletePerson = { name -> deleteRegisteredPerson(name) },
                            // 돌아가기 = 서버 주소 적용 시점 — 뒤로 제스처와 같은 공통 경로 (#84)
                            onBack = { leaveSettingsToHome() },
                            )
                        }

                        AppScreen.GALLERY -> {
                            BackHandler { leaveGalleryToHome() }
                            GalleryScreen(
                            photos = applyGalleryFilters(galleryPhotos),
                            onBack = { leaveGalleryToHome() },
                            onVoiceSearch = { startGalleryVoiceSearch() },
                            filterSummaries = galleryFilterStack.map {
                                it.summary(photoLabelDictionary)
                            },
                            onResetFilters = {
                                galleryFilterStack = emptyList()
                                speak("검색 조건을 지웠어요")
                            },
                            onPhotoClick = { photo -> speakPhotoDetails(photo) },
                            onReadResults = { speakCurrentResults() },
                            )
                        }
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

    /** S5에서 값이 바뀔 때마다 호출 — 화면 상태 갱신 + 영속화 + GuidanceFeedback 반영을 한 번에 한다. */
    private fun updateSettings(newState: SettingsUiState) {
        settingsUiState = newState
        settingsRepository.save(newState)
        guidanceFeedback.applySettings(newState)
    }

    /**
     * ⑧ 촬영 완료 후 MLLM 비교 결과를 폴링해 교체 여부를 음성으로 알린다.
     *
     * 파이프라인 마지막 단계 — 실패·타임아웃은 로그만 남기고 조용히 넘어간다
     * (사진은 이미 기기와 서버에 저장돼 있어 사용자 손해가 없다).
     */
    /** 촬영 직후 대표 컷 한 줄 설명을 폴링해 먼저 낭독한다 — 비교 결과(수십 초)보다 빨리 도착한다 (#76). */
    private fun pollPhotoDescription(sessionId: String) {
        descriptionClient.pollDescription(sessionId, object : PhotoDescriptionClient.Callback {
            override fun onDone(description: String?) {
                Log.i(TAG, "사진 설명 도착 [$sessionId]: $description")
                description?.let {
                    lastResultDescription = it
                    speak(it)
                    // 검색 인덱스에도 저장 — 상세 설명 도착 전의 낭독·본문 검색 폴백 (기능 3)
                    Thread({ photoIndexStore.applyShortDescription(sessionId, it) },
                        "SnapSight-IndexShortDesc").start()
                }
            }

            override fun onGaveUp(reason: String) {
                Log.w(TAG, "사진 설명 폴링 중단 [$sessionId]: $reason")
            }
        })
    }

    /** 검색용 상세 메타데이터를 폴링해 로컬 인덱스에 기록한다 (기능 3-B) — 이후 검색은 오프라인. */
    private fun pollSearchMetadata(sessionId: String) {
        metadataClient.pollMetadata(sessionId, object : MetadataClient.Callback {
            override fun onDone(metadata: MetadataClient.Metadata) {
                Log.i(TAG, "검색 메타데이터 도착 [$sessionId] 라벨 ${metadata.labels}")
                Thread({
                    photoIndexStore.applyMetadata(
                        sessionId = sessionId,
                        longDescription = metadata.longDescription,
                        fixedLabels = metadata.labels,
                        customAuto = metadata.customLabels,
                        taxonomyVersion = metadata.taxonomyVersion,
                    )
                }, "SnapSight-IndexUpdate").start()
            }

            override fun onGaveUp(reason: String) {
                Log.w(TAG, "검색 메타데이터 폴링 중단 [$sessionId]: $reason")
            }
        })
    }

    private fun pollComparisonResult(sessionId: String) {
        resultClient.pollResult(sessionId, object : CaptureResultClient.Callback {
            override fun onDone(result: CaptureResultClient.ComparisonResult) {
                Log.i(TAG, "MLLM 비교 완료 [$sessionId] improved=${result.improved} (${result.reason})")
                val headline =
                    if (result.improved) "더 나은 순간의 사진으로 교체했어요"
                    else "사진 저장이 완료됐어요"
                // 판정 근거(reason)는 "명시적 요구사항…" 같은 기술 문구라 낭독하지 않는다 —
                // 사용자에게는 저장/교체 결과와 사진 설명(1문장)만 들려준다 (실사용 피드백)
                speak(headline)
            }

            override fun onGaveUp(reason: String) {
                Log.w(TAG, "MLLM 결과 폴링 중단 [$sessionId]: $reason")
            }
        })
    }

    private var welcomed = false
    private var wentToBackground = false
    private var sessionCancelledInBackground = false

    // ---- 화면 전환·조작 문법 (#84 P1) — 두 번 탭=진행, 길게·뒤로가기=복귀, 전환 확인 3채널 ----

    /** 앱 실행 단위로 "첫 진입 상세 안내"를 했는지 — 재진입부터는 화면 이름만 말한다. */
    private val visitedScreens = mutableSetOf<AppScreen>()
    private var lastHomeBackPressMs = 0L

    /** 위성 화면 진입 — 상승 earcon + 진입 TTS 1회 (첫 진입에만 버튼 위치를 덧붙인다). */
    private fun enterScreen(screen: AppScreen) {
        currentScreen = screen
        guidanceFeedback.playScreenEnter()
        val firstVisit = visitedScreens.add(screen)
        val message = when (screen) {
            AppScreen.SETTINGS ->
                if (firstVisit) "설정 화면입니다. 진동, 사운드, 음성 속도를 조절할 수 있어요"
                else "설정 화면입니다"
            AppScreen.GALLERY ->
                if (firstVisit) "사진 찾기 화면입니다. 아래에 말해서 찾기 버튼이 있어요"
                else "사진 찾기 화면입니다"
            else -> null
        }
        message?.let { guidanceFeedback.announce(it) }
    }

    /** 홈 복귀 — 하강 earcon + "홈입니다" 1회. */
    private fun returnHome(prefix: String? = null) {
        currentScreen = AppScreen.MAIN
        guidanceFeedback.playScreenExit()
        guidanceFeedback.announce(if (prefix != null) "$prefix 홈입니다" else "홈입니다")
    }

    /** 설정 → 홈 공통 복귀 경로 — 뒤로 제스처로 나가도 서버 주소가 적용되게 한 곳에 모은다. */
    private fun leaveSettingsToHome() {
        val applied = BackendConfig.save(appPrefs, backendUrlInput)
        backendUrlInput = BackendConfig.storedOverride(appPrefs)
        Log.i(TAG, "백엔드 주소 적용: $applied")
        returnHome()
    }

    /** 사진 찾기 → 홈 공통 복귀 경로. */
    private fun leaveGalleryToHome() {
        galleryFilterStack = emptyList()
        returnHome()
    }

    /** 결과 화면 닫기 — 자동으로 나타난 화면이라 사진이 저장돼 있음을 함께 말한다. */
    private fun closeResultToHome() {
        showResult = false
        guidanceFeedback.playScreenExit()
        guidanceFeedback.announce("사진은 저장됐어요. 홈입니다")
    }

    /** MAIN 화면 아무 곳 두 번 탭 = 진행 — 볼륨 짧게와 같은 상태별 의미로 수렴한다. */
    private fun onMainDoubleTap() {
        if (showResult) {
            // 결과 화면의 진행 = 다시 촬영
            showResult = false
            if (sessionManager.state == SessionState.IDLE) sessionManager.onVolumePressed()
            return
        }
        sessionManager.onVolumePressed()
    }

    /** MAIN 화면 길게 누르기 = 복귀 — 세션 취소 / 결과 닫기. 홈에서는 할 일이 없다. */
    private fun onMainLongPress() {
        when {
            showResult -> closeResultToHome()
            sessionManager.state != SessionState.IDLE -> sessionManager.cancel()
        }
    }

    /** 홈에서 시스템 뒤로가기 — 1회차는 예고만, 2초 안에 다시 누르면 종료. */
    private fun onHomeBackPressed() {
        val now = System.currentTimeMillis()
        if (now - lastHomeBackPressMs <= HOME_EXIT_CONFIRM_MS) {
            finish()
        } else {
            lastHomeBackPressMs = now
            guidanceFeedback.announce("한 번 더 누르면 앱을 종료합니다")
        }
    }

    /** 사진 찾기 진입 — 목록·검색 인덱스를 매번 새로 읽는다 (촬영 직후 돌아와도 최신이 보이게). */
    private fun openGallery() {
        galleryPhotos = null
        galleryFilterStack = emptyList()
        enterScreen(AppScreen.GALLERY)
        Thread({
            descriptionLookup.beginBatch()
            val photos = PhotoLibrary.loadRecentPhotos(this, describe = descriptionLookup::get)
            val entries = photoIndexStore.allEntries().associateBy { it.sessionId }
            val labels = photoIndexStore.allCustomLabels()
            runOnUiThread {
                galleryPhotos = photos
                galleryIndex = entries
                customLabelsCache = labels
            }
        }, "SnapSight-GalleryLoad").start()
    }

    /** 음성 필터 스택을 사진 목록에 적용한다 (기능 3-C). 스택이 비었으면 전체. */
    private fun applyGalleryFilters(photos: List<GalleryPhoto>?): List<GalleryPhoto>? {
        if (photos == null || galleryFilterStack.isEmpty()) return photos
        return photos.filter { photo ->
            // 인덱스에 있으면 그 항목으로, 없으면(옛 사진) 시간 정보만으로라도 판정한다
            val entry = photo.sessionId?.let { galleryIndex[it] }
                ?: PhotoIndexEntry(
                    sessionId = photo.sessionId ?: photo.uri.toString(),
                    takenAtMs = photo.takenAtMs,
                    shortDescription = photo.description,
                )
            galleryFilterStack.all { PhotoSearchEngine.matches(entry, it, photoLabelDictionary) }
        }
    }

    /** "말해서 찾기" — 발화를 받아 구조화 질의로 바꿔 필터 스택에 누적한다. */
    private fun startGalleryVoiceSearch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            speak("음성 검색에는 마이크 권한이 필요해요")
            return
        }
        if (!searchRecognizer.isAvailable) {
            speak("이 기기는 음성 인식을 지원하지 않아요")
            return
        }
        guidanceFeedback.announce("무엇을 찾을까요?")
        // 안내 음성이 마이크에 섞이지 않도록 잠깐 기다렸다가 듣기 시작한다
        window.decorView.postDelayed({
            searchRecognizer.start(object : SpeechToTextRecognizer.Listener {
                override fun onRecognized(text: String) = handleGalleryQuery(text)
                override fun onError(message: String) = speak("잘 못 들었어요. 다시 눌러 말씀해 주세요")
            })
        }, 1_200L)
    }

    /** 검색 발화 처리 — 초기화/목록 명령을 먼저 보고, 아니면 질의를 쌓아 결과를 낭독한다. */
    private fun handleGalleryQuery(utterance: String) {
        Log.i(TAG, "갤러리 검색 발화: $utterance")
        val compact = utterance.replace(" ", "")
        if (RESET_COMMANDS.any { compact.contains(it) }) {
            galleryFilterStack = emptyList()
            speak("검색 조건을 지웠어요. 전체 사진을 보여드릴게요")
            return
        }
        if (LIST_COMMANDS.any { compact.contains(it) }) {
            speakCurrentResults()
            return
        }

        val parser = PhotoQueryParser(
            dictionary = photoLabelDictionary,
            customLabels = customLabelsCache,
            peopleNames = registeredPeople, // 기능 2 연동 — "민수 나온 사진"
        )
        val query = parser.parse(utterance)
        if (query.isEmpty) {
            speak("무엇을 찾는지 못 알아들었어요. 예를 들어 \"지난주에 찍은 음식 사진\"처럼 말씀해 주세요")
            return
        }

        galleryFilterStack = galleryFilterStack + query
        val results = applyGalleryFilters(galleryPhotos).orEmpty()
        var message = "${query.summary(photoLabelDictionary)} 조건으로 " +
            PhotoSearchEngine.announcement(results.size)
        // 충분히 좁혀졌으면 어떤 사진들인지 바로 훑어 읽어준다 — "그 중 어떤 건지" 확인용
        if (results.size in 1..PhotoSearchEngine.AUTO_ROLL_CALL_MAX) {
            message += ". " + PhotoSearchEngine.rollCall(results.map { it.toRollCallItem() })
        }
        speak(message)
    }

    /** "목록 읽어줘" — 지금 화면에 로딩된(필터 적용된) 사진들을 훑어 낭독한다. */
    private fun speakCurrentResults() {
        val results = applyGalleryFilters(galleryPhotos).orEmpty()
        speak(PhotoSearchEngine.rollCall(results.map { it.toRollCallItem() }))
    }

    /** 훑어 읽기 항목 — 인덱스의 짧은 설명 우선, 없으면 갤러리 카드 설명. */
    private fun GalleryPhoto.toRollCallItem(): PhotoSearchEngine.RollCallItem {
        val entry = sessionId?.let { galleryIndex[it] }
        val description = entry?.shortDescription
            ?: description.takeIf { it.isNotBlank() && it != "설명을 준비 중이에요" }
        return PhotoSearchEngine.RollCallItem(dateText = dateText, description = description)
    }

    /**
     * 결과 화면 "라벨 붙이기" — 말한 이름을 커스텀 라벨로 등록하고 이 사진에 직접 부착한다
     * (기능 3, 두 사전 구조의 사용자 사전 쪽). 이후 촬영부터는 자동 부착 후보로도 쓰인다.
     */
    private fun startResultLabeling() {
        val sessionId = lastCapturedSessionId ?: run {
            speak("라벨을 붙일 사진을 찾지 못했어요")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED || !searchRecognizer.isAvailable
        ) {
            speak("음성 라벨에는 마이크 권한이 필요해요")
            return
        }
        guidanceFeedback.announce("이 사진을 뭐라고 기억할까요?")
        window.decorView.postDelayed({
            searchRecognizer.start(object : SpeechToTextRecognizer.Listener {
                override fun onRecognized(text: String) {
                    val label = text.trim().removeSuffix("이라고 기억해줘").removeSuffix("로 기억해줘").trim()
                    if (label.isBlank()) {
                        speak("라벨 이름을 못 알아들었어요")
                        return
                    }
                    Thread({
                        photoIndexStore.attachUserLabel(sessionId, label)
                        customLabelsCache = photoIndexStore.allCustomLabels()
                    }, "SnapSight-AttachLabel").start()
                    speak("이 사진을 \"$label\"로 기억할게요. 나중에 그렇게 찾을 수 있어요")
                }

                override fun onError(message: String) = speak("잘 못 들었어요. 다시 시도해 주세요")
            })
        }, 1_200L)
    }

    /**
     * 기능 2: 얼굴 등록 흐름 — 카메라 화면(MAIN)으로 이동해 이름을 음성으로 받고,
     * 3초간 프레임에서 얼굴 샘플을 대량 수집한다 (각도 다양성은 안내 음성으로 유도).
     * 동의 확인은 흐름 첫 안내에 포함한다 (기획 문서: 프라이버시 단순화).
     */
    private fun startFaceEnrollment() {
        if (!faceEmbedder.isAvailable) {
            speak("얼굴 인식 모델이 설치되지 않아 등록할 수 없어요")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED || !searchRecognizer.isAvailable
        ) {
            speak("얼굴 등록에는 마이크 권한이 필요해요")
            return
        }
        currentScreen = AppScreen.MAIN // 카메라가 돌아야 프레임에서 얼굴을 딸 수 있다
        guidanceFeedback.announce(
            "얼굴 등록을 시작합니다. 등록할 분의 동의를 받았다면, 그분을 뭐라고 부를지 말씀해 주세요. " +
                "얼굴 정보는 이 기기에만 저장됩니다"
        )
        window.decorView.postDelayed({
            searchRecognizer.start(object : SpeechToTextRecognizer.Listener {
                override fun onRecognized(text: String) {
                    val name = text.trim()
                    if (name.isBlank()) {
                        speak("이름을 못 알아들었어요. 등록을 취소합니다")
                        return
                    }
                    beginFaceScan(name)
                }

                override fun onError(message: String) = speak("이름을 못 알아들었어요. 등록을 취소합니다")
            })
        }, ENROLL_PROMPT_DELAY_MS)
    }

    private fun beginFaceScan(name: String) {
        guidanceFeedback.announce(
            "$name 님의 얼굴을 카메라에 비춰 주세요. 3초 동안 고개를 천천히 좌우로 돌려 주세요"
        )
        window.decorView.postDelayed({
            faceIdentifier.startEnrollment(name, ENROLL_SCAN_MS) { collected ->
                if (collected > 0) {
                    speak("${name}님 얼굴 ${collected}장을 저장했어요. 이제 이름으로 찾을 수 있어요")
                } else {
                    speak("얼굴을 찾지 못했어요. 밝은 곳에서 다시 시도해 주세요")
                }
                Thread({
                    val people = faceRegistry.peopleNames()
                    runOnUiThread { registeredPeople = people }
                }, "SnapSight-FaceNames").start()
            }
        }, ENROLL_PROMPT_DELAY_MS)
    }

    /** 인물 삭제 — 임베딩까지 완전히 지운다 (프라이버시). */
    private fun deleteRegisteredPerson(name: String) {
        Thread({
            faceRegistry.deletePerson(name)
            val people = faceRegistry.peopleNames()
            runOnUiThread {
                registeredPeople = people
                speak("${name}님의 얼굴 정보를 삭제했어요")
            }
        }, "SnapSight-FaceDelete").start()
    }

    /**
     * 전/후면 렌즈 전환 (CaptureScreen 버튼) — 셀카 모드에서는 ML Kit 시선 판정을 켜서
     * "카메라를 보고 있는지"를 조준 안내에 통합한다 (2026-08-21 요청).
     */
    private fun onLensChanged(isFront: Boolean) {
        isSelfieMode = isFront
        selfieGaze.enabled = isFront
        selfieGaze.reset()
        lastAnnouncedGaze = null
        guidanceFeedback.announce(
            if (isFront) "셀카 모드예요. 화면을 향해 얼굴을 보여 주세요"
            else "후면 카메라로 전환했어요"
        )
    }

    /** 사진 카드 탭 — 저장된 상세 설명(long_desc)을 낭독한다. 없으면 짧은 설명으로 폴백. */
    private fun speakPhotoDetails(photo: GalleryPhoto) {
        val entry = photo.sessionId?.let { galleryIndex[it] }
        val detail = entry?.longDescription
            ?: entry?.shortDescription
            ?: photo.description.takeIf { it.isNotBlank() && it != "설명을 준비 중이에요" }
        speak(detail ?: "이 사진의 설명이 아직 준비되지 않았어요")
    }

    /** 앱 진입(권한 확인 완료) 시 1회 — 시각장애 사용자가 첫 화면에서 할 일을 알 수 있게 한다 (실사용 피드백 #2). */
    private fun announceWelcomeOnce() {
        if (welcomed) return
        welcomed = true
        guidanceFeedback.announce(WELCOME_TEXT)
    }

    /**
     * 홈으로 나가면 진행 중이던 세션은 취소한다 — 카메라가 lifecycle 로 이미 해제돼 조준·촬영이 이어질 수 없고,
     * 돌아왔을 때 어디였는지 알 수 없는 상태로 남기는 것보다 처음부터 다시 시작하는 편이 안전하다.
     */
    override fun onStop() {
        super.onStop()
        wentToBackground = true
        if (sessionManager.state != SessionState.IDLE) {
            sessionManager.cancel()
            sessionCancelledInBackground = true
        }
    }

    /**
     * 미디어 볼륨이 무음이면 최소 가청 수준(60%)으로 올린다.
     *
     * 안내 음성(백엔드 TTS mp3·내장 TTS)은 모두 STREAM_MUSIC으로 나가는데, 이 앱은 볼륨
     * 버튼을 세션 제어로 가로채므로 사용자가 앱 안에서 볼륨을 올릴 방법이 없다. 시각장애
     * 사용자는 "무음이라 안 들리는" 상태를 인지하기도 어려워 앱이 직접 보정한다 (실사용
     * 피드백 — 촬영 후 상황 설명이 재생됐지만 볼륨 0이라 들리지 않던 문제).
     */
    private fun ensureAudibleMediaVolume() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val floor = (max * 6) / 10
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) < floor) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, floor, 0)
            Log.i(TAG, "미디어 볼륨이 낮아 $floor/$max 로 보정")
        }
    }

    /** 홈에 갔다 돌아왔을 때도 지금 할 일을 다시 알려준다 (실사용 피드백 — 첫 실행만 안내되던 문제). */
    override fun onResume() {
        super.onResume()
        ensureAudibleMediaVolume()
        if (!wentToBackground) return
        wentToBackground = false
        if (currentScreen != AppScreen.MAIN || !permissionsGranted) return
        guidanceFeedback.announce(
            if (sessionCancelledInBackground) "스냅사이트로 돌아왔습니다. 진행 중이던 촬영은 취소됐어요. 볼륨 버튼을 눌러 다시 시작하세요"
            else "스냅사이트로 돌아왔습니다. 볼륨 버튼을 눌러 시작하세요"
        )
        sessionCancelledInBackground = false
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
                    // 백엔드 TTS 불가(키 없음·네트워크 등) 시 내장 TTS 폴백 — 안내가 아예 침묵하는 것보다 낫다
                    Log.w(TAG, "TTS 요청 실패, 내장 TTS 폴백: $text", error)
                    guidanceFeedback.announce(text)
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
        sessionRawText = currentRawText
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
        // 디버그 오버레이: 항상 전체 탐지 객체를 그린다 — 의도 필터로 후보가 0개여도
        // "탐지 자체는 돌고 있는지"를 눈으로 확인할 수 있어야 한다 (2026-08-21 피드백).
        // 의도 기반 선택 결과는 편차 판정(deviation)·개수 판정에만 쓰인다.
        runOnUiThread { cvObjects = output.objects }

        // 기능 2: 등록 인물이 화면에 들어오면 세션당 1회 음성으로 알린다 ("민수님이 화면에 있어요").
        // announcedIdentities 는 메인 스레드에서만 만진다 (여기서 넘기고, 세션 시작 시 clear).
        currentIdentities = output.identities
        if (sessionManager.state == SessionState.AIMING && output.identities.isNotEmpty()) {
            val names = output.identities.values.toSet()
            runOnUiThread {
                val newNames = names - announcedIdentities
                if (newNames.isNotEmpty()) {
                    announcedIdentities.addAll(newNames)
                    newNames.forEach { guidanceFeedback.announce("${it}님이 화면에 있어요") }
                }
            }
        }

        // ④ 편차 판정 — 파이프라인이 계산한 기하 편차를 계약 형태로 해석. 조준 중에만 의미가 있다.
        val deviation = if (sessionManager.state == SessionState.AIMING) {
            DeviationJudgment.judge(
                deviation = output.deviation,
                framing = output.targetSpec?.framing ?: TargetSpec.Framing.FULL_BODY,
            )
        } else null
        deviation?.let {
            deviationListener?.onDeviation(it)
            // 셔터 게이트 근거 갱신 — "피사체 없이 찍기 직전" 판정에 쓴다
            lastAimingVerdictAtMs = output.timestampMs
            lastAimingSubjectDetected = it.subjectDetected
        }

        // 세션 배율: 0.6배(찾기용)로 시작 → 수평이 5프레임 안정되면 1.0배로 복귀. 면적 기반 자동 줌인은
        // AutoZoomController.ZOOM_IN_ENABLED 로 꺼져 있다 (깊이 판단 부정확 — 후처리 붙인 뒤 재검토).
        if (sessionManager.state == SessionState.AIMING) {
            val framing = output.targetSpec?.framing ?: TargetSpec.Framing.FULL_BODY
            val ready = deviation?.let { DeviationJudgment.isReadyCandidate(it) } ?: false
            val aligned = deviation?.xDeviation?.let {
                kotlin.math.abs(it) <= DeviationJudgment.READY_MAX_ABS_X_DEVIATION
            } ?: false
            val dev = output.deviation
            if (dev != null) {
                autoZoom.onTargetArea(
                    areaRatio = dev.areaRatio,
                    targetArea = DeviationJudgment.TARGET_AREA_RATIO.getValue(framing),
                    aligned = aligned,
                    hold = ready,
                )
            } else {
                autoZoom.onNoTarget() // 광각에서 계속 못 찾으면 1.0배 복귀 (탐색 실패 폴백)
            }

            // 시안 S3 하단 안내 카드 문구 (#80) — 음성·햅틱(⑥)과 같은 판정을 화면 텍스트로도 보여준다.
            // 셀카 모드에서는 구도가 맞아도 시선이 안 맞으면 그 사유를 대신 보여준다.
            val gazeBlock = selfieGaze.readyBlockReason()
            val xDev = deviation?.xDeviation
            val sizeDev = deviation?.sizeDeviation
            val text = when {
                xDev == null || sizeDev == null -> "피사체를 찾고 있어요"
                ready && gazeBlock != null -> "구도는 좋아요. $gazeBlock"
                ready -> "지금이에요! 볼륨 버튼을 누르세요"
                xDev < -DeviationJudgment.READY_MAX_ABS_X_DEVIATION -> "카메라를 조금 왼쪽으로 이동해주세요"
                xDev > DeviationJudgment.READY_MAX_ABS_X_DEVIATION -> "카메라를 조금 오른쪽으로 이동해주세요"
                sizeDev < -DeviationJudgment.READY_MAX_ABS_SIZE_DEVIATION -> "조금 더 가까이 가주세요"
                sizeDev > DeviationJudgment.READY_MAX_ABS_SIZE_DEVIATION -> "조금 뒤로 물러나주세요"
                else -> "좋아요, 그대로 유지해주세요"
            }
            // 셀카 모드: 시선이 카메라로 돌아온 순간을 한 번 알려준다 ("카메라를 보고 있어요")
            val gazeNow = if (isSelfieMode) selfieGaze.state else null
            runOnUiThread {
                guidanceText = text
                if (gazeNow != null && gazeNow != lastAnnouncedGaze) {
                    if (gazeNow == SelfieGazeMonitor.GazeState.LOOKING &&
                        lastAnnouncedGaze != null // 첫 판정은 조용히 — 전환됐을 때만 말한다
                    ) {
                        guidanceFeedback.announce("카메라를 보고 있어요")
                    }
                    lastAnnouncedGaze = gazeNow
                }
            }
        }

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
            // 검색용 메타데이터 재료 (기능 3-B) — 인물 이름은 프라이버시 원칙상 보내지 않는다
            customLabels = customLabelsCache,
            detectedObjects = shutterObjects.map { it.label }.distinct(),
            // ⑦ 휴리스틱 스코어링 — 업로드 스레드에서 계산 (후보 6장 디코딩+라플라시안)
            candidateScoresProvider = {
                candidates.map { FrameScorer.blurScore(it.jpeg) }.also { scores ->
                    // 블러 기준치(SHARPNESS_REF) 캘리브레이션용 분포 데이터 — 이슈 #42
                    Log.d(TAG, "블러 점수 [$sessionId]: " +
                        scores.joinToString(", ") { "%.2f".format(it) })
                }
            },
            callback = object : FrameUploader.Callback {
                override fun onSuccess(result: FrameUploader.UploadResult) {
                    Log.i(TAG, "업로드 완료 [${result.sessionId}] 후보 ${result.receivedCandidateCount}장")
                    pollPhotoDescription(result.sessionId)
                    pollComparisonResult(result.sessionId)
                    pollSearchMetadata(result.sessionId)
                }

                override fun onFailure(error: Throwable) {
                    // 업로드 실패는 촬영 성공과 무관 — 사진은 이미 기기에 저장됨 (재시도는 추후)
                    Log.w(TAG, "업로드 실패 (사진은 기기에 저장됨)", error)
                }
            },
        )
    }

    /**
     * 촬영 결과 화면(S4)을 띄운다 — 대표 컷을 백그라운드에서 디코딩해 표시.
     * 디코딩 실패해도 화면은 띄운다 (사진 없이 요약·설명만이라도 들려주는 게 낫다).
     */
    private fun showResultScreen(uri: Uri) {
        lastResultDescription = null
        showResult = true
        Thread({
            val bitmap = try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(
                        stream, null,
                        BitmapFactory.Options().apply { inSampleSize = 2 },
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "결과 사진 디코딩 실패: $uri", t)
                null
            }
            runOnUiThread { resultPhoto = bitmap }
        }, "SnapSight-ResultDecode").start()
    }

    /**
     * 촬영 직후 안내·결과 화면 헤드라인 — 즉시 요약에, 요청한 피사체(예: 노트북)가
     * 셔터 순간 화면에 없었다면 그 사실을 덧붙인다 (막지 않고 알리기만 — 2026-08-21 결정).
     */
    private fun captureHeadline(): String {
        val summary = instantCaptureSummary(shutterObjects)
        val missing = shutterMissingTarget ?: return summary
        return "$summary. 다만 요청하신 ${missing}은(는) 화면에서 찾지 못했어요"
    }

    /**
     * 셔터 순간의 온디바이스 탐지 결과로 즉시 상황을 요약한다 (#80) —
     * 서버 설명(수 초)보다 먼저 "무엇이 찍혔는지"를 들려주는 용도.
     */
    private fun instantCaptureSummary(objects: List<TrackedObject>): String {
        if (objects.isEmpty()) return "사진을 찍었어요. 어떤 모습인지 곧 알려드릴게요"
        val personCount = objects.count { it.label.trim().equals("person", ignoreCase = true) }
        val others = objects
            .filterNot { it.label.trim().equals("person", ignoreCase = true) }
            .groupingBy { it.label.trim().lowercase() }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(2)
            .map { (label, _) -> KOREAN_LABELS[label] ?: label }

        val parts = buildList {
            if (personCount > 0) add("사람 ${personCount}명")
            addAll(others)
        }
        return "${parts.joinToString(", ")} 사진을 찍었어요"
    }

    private fun checkOrRequestPermissions() {
        val required = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val allGranted = required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            permissionsGranted = true
            announceWelcomeOnce()
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
        const val WELCOME_TEXT = "스냅사이트입니다. 볼륨 버튼을 눌러 시작하세요"
        const val TAG = "SnapSight"

        // 즉시 상황 안내용 자주 나오는 라벨 한글 표기 — 없는 라벨은 영문 그대로 읽는다
        private val KOREAN_LABELS = mapOf(
            "person" to "사람", "laptop" to "노트북", "cup" to "컵", "bottle" to "병",
            "chair" to "의자", "desk" to "책상", "monitor/tv" to "모니터", "cell phone" to "휴대폰",
            "book" to "책", "handbag/satchel" to "가방", "backpack" to "백팩", "cake" to "케이크",
            "dog" to "강아지", "cat" to "고양이", "flower" to "꽃", "glasses" to "안경",
            "keyboard" to "키보드", "mouse" to "마우스", "plate" to "접시", "bowl/basin" to "그릇",
            "wine glass" to "와인잔", "car" to "자동차", "potted plant" to "화분", "bread" to "빵",
        )
        // 대분류 키워드 규칙 (인물 > 음식) — 발화·라벨·설명 텍스트에서 찾는다.
        // '차'는 자동차·기차와 겹쳐 단독으로 쓰지 않고 녹차·홍차·찻잔으로 한정한다.
        private val PERSON_KEYWORDS = listOf(
            "사람", "아들", "딸", "아이", "아기", "가족", "친구", "남성", "여성",
            "남자", "여자", "인물", "얼굴", "커플", "부모", "엄마", "아빠",
        )
        private val FOOD_KEYWORDS = listOf(
            "음식", "커피", "라떼", "아메리카노", "녹차", "홍차", "찻잔", "음료", "주스",
            "케이크", "빵", "디저트", "밥", "식사", "요리", "접시", "식탁", "메뉴",
            "과일", "파스타", "피자", "치킨", "샐러드", "맥주", "와인",
        )
        const val CV_TAG = "SnapSightCV"
        const val PREFS_NAME = "snap_sight_prefs"
        const val KEY_ONBOARDING_DONE = "onboarding_done"

        // 갤러리 음성 검색의 "필터 전체 해제" 명령 (공백 제거 후 포함 비교)
        private val RESET_COMMANDS = listOf("처음부터", "조건지워", "조건취소", "전체보여", "다시검색")

        // "지금 목록에 뭐가 있는지 읽어줘" 명령 (공백 제거 후 포함 비교)
        private val LIST_COMMANDS = listOf("목록", "뭐있어", "뭐가있", "어떤사진", "읽어줘", "훑어")

        // 얼굴 등록 흐름 타이밍 (기능 2) — 안내 음성이 마이크에 섞이지 않을 정도의 지연
        private const val ENROLL_PROMPT_DELAY_MS = 4_000L
        private const val ENROLL_SCAN_MS = 3_000L

        // 셔터 게이트가 신뢰하는 CV 판정의 최대 나이 — CV 가 멈춰 있으면 막지 않는다 (fail-open)
        private const val VERDICT_FRESH_MS = 1_500L

        /** 홈 뒤로가기 2회 종료 확인 창 (#84) — 1회차 예고 후 이 시간 안에 다시 누르면 종료. */
        private const val HOME_EXIT_CONFIRM_MS = 2_000L
    }
}
