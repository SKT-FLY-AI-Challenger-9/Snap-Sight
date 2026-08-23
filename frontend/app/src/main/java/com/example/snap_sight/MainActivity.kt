// 이 파일: 앱의 시작점이자 모든 부품을 연결하는 본부.
// 카메라·AI 탐지·음성 인식·편차 안내·업로드를 만들어 서로 이어주고,
// 볼륨 버튼 입력과 권한 요청도 여기서 처리한다.
package com.example.snap_sight

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.content.Intent
import android.media.AudioManager
import android.media.ExifInterface
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
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
import com.example.snap_sight.camera.AutoZoomController
import com.example.snap_sight.camera.CameraController
import com.example.snap_sight.camera.CanonicalFrameStore
import com.example.snap_sight.camera.CameraMotionEstimator
import com.example.snap_sight.camera.CaptureSessionManager
import com.example.snap_sight.camera.FrameScorer
import com.example.snap_sight.camera.GalleryPhoto
import com.example.snap_sight.camera.PhotoLibrary
import com.example.snap_sight.camera.MissingSubjectNotice
import com.example.snap_sight.camera.RingFrameBuffer
import com.example.snap_sight.camera.SessionState
import com.example.snap_sight.cv.ByteTrackLiteConfig
import com.example.snap_sight.cv.AimingGuidanceMode
import com.example.snap_sight.cv.AimingGuidanceModeResolver
import com.example.snap_sight.cv.CvFrameOutput
import com.example.snap_sight.cv.CvFrame
import com.example.snap_sight.cv.FaceFrameAnalyzer
import com.example.snap_sight.cv.FrameResult
import com.example.snap_sight.face.FaceDebugSink
import com.example.snap_sight.face.EnrollmentCancelResult
import com.example.snap_sight.face.FaceIdentifier
import com.example.snap_sight.face.FaceMatchConfig
import com.example.snap_sight.face.FaceRegistry
import com.example.snap_sight.face.FileFaceDebugSink
import com.example.snap_sight.face.ObjectIdentifier
import com.example.snap_sight.face.LocalObjectAppearanceEmbedder
import com.example.snap_sight.face.ObjectRegistry
import com.example.snap_sight.face.RegistryReloadGate
import com.example.snap_sight.face.SelfieGazeMonitor
import com.example.snap_sight.face.TfLiteFaceEmbedder
import com.example.snap_sight.cv.DeviationResult
import com.example.snap_sight.cv.DeviationJudgment
import com.example.snap_sight.cv.AnalysisMode
import com.example.snap_sight.cv.FrameProcessorConfig
import com.example.snap_sight.cv.SnapSightFrameProcessor
import com.example.snap_sight.cv.TargetLockConfig
import com.example.snap_sight.cv.TargetSelectionState
import com.example.snap_sight.cv.TfLiteDetectorConfig
import com.example.snap_sight.cv.SpecDeviationCalculator
import com.example.snap_sight.cv.TrackedObject
import com.example.snap_sight.cv.TargetLockStats
import com.example.snap_sight.cv.TargetSpec
import com.example.snap_sight.cv.ReadinessBlocker
import com.example.snap_sight.cv.ReadinessVerdict
import com.example.snap_sight.network.BackendConfig
import com.example.snap_sight.network.DescriptionLookup
import com.example.snap_sight.network.FinalFrameClient
import com.example.snap_sight.network.FrameUploader
import com.example.snap_sight.network.LabelNormalizeClient
import com.example.snap_sight.network.MetadataClient
import com.example.snap_sight.network.MetadataLabelContract
import com.example.snap_sight.network.UtteranceClient
import com.example.snap_sight.privacy.CloudTextRedactor
import com.example.snap_sight.privacy.RegisteredIdentityMatcher
import com.example.snap_sight.search.PhotoIndexEntry
import com.example.snap_sight.search.PhotoIndexStore
import com.example.snap_sight.search.PhotoLabelDictionary
import com.example.snap_sight.search.PhotoQuery
import com.example.snap_sight.search.PhotoQueryParser
import com.example.snap_sight.search.PhotoSearchEngine
import com.example.snap_sight.stt.SpeechToTextRecognizer
import com.example.snap_sight.ux.CaptureScreen
import com.example.snap_sight.ux.CaptureAnnouncementBuilder
import com.example.snap_sight.ux.GalleryScreen
import com.example.snap_sight.ux.GuidanceFeedback
import com.example.snap_sight.ux.GuidanceTextStabilizer
import com.example.snap_sight.ux.HomeScreen
import com.example.snap_sight.ux.ResultScreen
import com.example.snap_sight.ux.OnboardingPermissionState
import com.example.snap_sight.ux.OnboardingScreen
import com.example.snap_sight.ux.SettingsRepository
import com.example.snap_sight.ux.SettingsScreen
import com.example.snap_sight.ux.SettingsUiState
import com.example.snap_sight.ux.appTapGrammar
import kotlin.math.roundToInt
import com.example.snap_sight.ui.theme.SnapSightTheme
import java.util.concurrent.Executors

/** S1(온보딩)/S2(홈·조준, [CaptureScreen]이 겸함)/S5(설정) — `docs/screen-design.md` 화면 목록 기준. */
private enum class AppScreen { ONBOARDING, MAIN, SETTINGS, GALLERY }

/**
 * 모듈 배선 호스트.
 *
 * 소유·연결하는 것:
 *  - 카메라(⑤): [CameraController] + 세션 상태 머신 [CaptureSessionManager] (볼륨 버튼 트리거)
 *  - CV(②): [SnapSightFrameProcessor] — 탐지·추적 결과를 디버그 오버레이와 성능 로그로 소비
 *  - STT(①): 발화 인식 결과를 [UtteranceClient]로 보내 타겟 스펙 수신
 *  - 음성 안내: 하나의 [GuidanceFeedback] TTS 큐에서 우선순위에 따라 재생
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
    private val finalFrameClient = FinalFrameClient()
    private val canonicalFrameStore by lazy { CanonicalFrameStore(this) }
    private val descriptionLookup by lazy { DescriptionLookup(this) }
    private val metadataClient = MetadataClient()

    // 커스텀 라벨 등록 시 "내 개"/"내 강아지" 같은 동의어를 기존 라벨로 병합 (2026-08-22)
    private val labelNormalizeClient = LabelNormalizeClient()

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

    // 디버그 빌드 전용: 등록 크롭·식별 시도 크롭·점수를 Android/data/<패키지>/files/face_debug/ 에 남긴다
    private val faceDebugSink: FaceDebugSink? by lazy {
        if (BuildConfig.DEBUG && BuildConfig.ENABLE_FACE_DEBUG_DUMPS) FileFaceDebugSink(this)
        else null
    }
    // 신원 재시도는 시간 기준 — 열 제한으로 detector FPS가 달라져도 동일한 1초 backoff를 유지한다.
    // 임계값 0.58: 실기기 로그(2026-08-23) 기준 진짜 유재석 0.65~0.75, 다른 사람 최고 0.48~0.53 —
    // 기본 0.5 는 타인이 턱걸이로 통과해 한 프레임에 "유재석"이 셋 잡히는 일이 있었다.
    private val faceIdentifier by lazy {
        FaceIdentifier(
            faceRegistry, faceEmbedder,
            matchConfig = FaceMatchConfig(similarityThreshold = FACE_MATCH_THRESHOLD),
            attemptIntervalMs = IDENTIFY_ATTEMPT_INTERVAL_MS,
            debugSink = faceDebugSink,
        )
    }

    // 사물 등록 — 얼굴 전용 모델과 분리한 경량 외형 임베더를 사용한다.
    private val objectRegistry by lazy { ObjectRegistry(this) }
    private val objectEmbedder by lazy { LocalObjectAppearanceEmbedder() }
    private val objectIdentifier by lazy {
        ObjectIdentifier(
            objectRegistry,
            objectEmbedder,
            attemptIntervalMs = IDENTIFY_ATTEMPT_INTERVAL_MS,
            debugSink = faceDebugSink,
        )
    }

    // 셀카 모드 시선 판정 — 전면 카메라일 때만 켜진다 (CaptureScreen onLensChanged 로 토글)
    private val selfieGaze = SelfieGazeMonitor()

    /** 인물 인식 + 사물 인식 + 셀카 시선 판정을 한 훅으로 묶는다 (분석 스레드에서 순차 실행). */
    private val faceAnalyzers = object : FaceFrameAnalyzer {
        override fun analyze(frame: CvFrame, frameResult: FrameResult): Map<Int, String> {
            selfieGaze.onFrame(frame)
            // person track 은 얼굴, 나머지 track 은 사물 — 대상이 겹치지 않아 합쳐도 안전하다
            val people = faceIdentifier.analyze(frame, frameResult)
            val objects = objectIdentifier.analyze(frame, frameResult)
            return if (objects.isEmpty()) people else people + objects
        }

        override fun reset() {
            selfieGaze.reset()
            faceIdentifier.reset()
            objectIdentifier.reset()
        }
    }

    // 셀카 모드 상태 + "카메라를 보고 있어요" 안내 중복 방지 (메인 스레드에서만 접근)
    private var isSelfieMode = false
    private var lastAnnouncedGaze: SelfieGazeMonitor.GazeState? = null

    /** 등록 인물 이름 캐시 — 검색 파서·설정 화면이 메인 스레드에서 읽는다. */
    private var registeredPeople by mutableStateOf<List<String>>(emptyList())

    /** 등록 사물 이름 캐시 — 인물과 같은 방식으로 검색·설정 화면이 읽는다. */
    private var registeredObjects by mutableStateOf<List<String>>(emptyList())

    /** 둘 중 하나라도 읽지 못하면 원문 대신 일반 촬영 문장만 보내는 fail-closed 게이트. */
    private val identityReloadGate = RegistryReloadGate()
    /** Mutations and paired reads must execute in request order; generations alone are insufficient. */
    private val identityReloadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SnapSight-IdentityRegistry")
    }
    @Volatile
    private var identityReloadClosed = false

    /**
     * 얼굴·사물 등록 흐름 진행 중 — true 인 동안 홈 오버레이를 내려 카메라 미리보기를
     * 보여주고(잔존시력 사용자가 조준을 확인), 탭 문법의 메인/서브 동작을 잠근다.
     * 등록 중에도 IDLE 세션 상태라 이 플래그 없이는 홈이 미리보기를 가린다 (2026-08-22 수정).
     */
    private var enrollmentActive by mutableStateOf(false)
    /** 안내·이름 입력이 끝나고 실제 샘플을 수집하는 구간만 true. */
    private var enrollmentScanning by mutableStateOf(false)

    // 조준 중 마지막 신원 결과 + 세션 중 이미 안내한 이름 (같은 사람을 반복 안내하지 않게)
    @Volatile
    private var currentIdentities: Map<Int, String> = emptyMap()
    private val announcedIdentities = mutableSetOf<String>()

    /**
     * ④ 기하 편차 계산기 — 세션마다 [SpecDeviationCalculator.reset] 으로 타겟 기억을 지운다.
     * hold 는 기준 추론 주기([ANALYSIS_INTERVAL_MS])의 4배 — 관측이 2~3번 비어도 직전 편차를
     * 유지해 잠깐의 미검출이 LOST 로 번지지 않게 한다 (놓침 대책 A, 2026-08-22).
     */
    private val deviationCalculator = SpecDeviationCalculator(TargetLockConfig(holdMs = TARGET_HOLD_MS))

    /** 자이로 기반 카메라 모션 보정 (기능 1-C) — CameraMotionEstimator.ENABLED 로 게이트. */
    private val motionEstimator by lazy { CameraMotionEstimator(this) }

    /** ② 온디바이스 CV. 결과는 분석 스레드에서 도착 — 오버레이 갱신·성능 집계는 [onCvFrameResult] 참고. */
    private val cvProcessor by lazy {
        SnapSightFrameProcessor.create(
            this,
            listener = { output -> onCvFrameResult(output) },
            // 발열 대책 P1: 추론은 최대 5Hz — 건너뛴 프레임은 트래커 결과로 채운다.
            // 조준 안내엔 충분한 주기 (docs/research 연구④ 333ms 이산 갱신)
            config = FrameProcessorConfig(
                minAnalysisIntervalMs = ANALYSIS_INTERVAL_MS,
                enrollAnalysisIntervalMs = ENROLL_SAMPLE_INTERVAL_MS,
            ),
            deviationCalculator = deviationCalculator, // ④ 기하 편차 — 파이프라인 안에서 계산
            // selector 생략 = detector 와 같은 라벨 자산으로 Objects365TargetSelector 생성
            // (person/object/landscape 구분, canonical objectLabel class 매칭, 못 찾으면 후보 없음)
            // 트래킹 안정화 (기능 1-D): track 유지 시간을 프레임 수 대신 초로 고정하고,
            // 놓친 시간에 비례해 매칭 bbox 를 확장한다. 값은 docs/feature-expansion-plan.md 파라미터 표.
            // 놓침 대책 A (2026-08-22): 저신뢰 매칭 하한을 내리고(흔들린 프레임 구제), 최근 0.4초
            // 안에 관측된 track 은 저신뢰 구제 자격을 유지하며, 미검출 track 은 0.7초 동안 예측
            // 위치로 이어서 출력한다(coasting). detector 의 minimumConfidence 와 값을 맞출 것.
            detectorConfig = TfLiteDetectorConfig(minimumConfidence = MATCH_MIN_CONFIDENCE),
            trackerConfig = ByteTrackLiteConfig(
                minimumMatchingConfidence = MATCH_MIN_CONFIDENCE,
                lostTrackBufferSeconds = 2.0,
                // 추론 주기를 낮추면(≈6.7Hz) 프레임 사이 박스 이동이 커져 IoU 매칭이 끊기기 쉽다 —
                // 놓친 시간에 비례하는 매칭 확장을 2배로 (실기기 로그 2026-08-22: 재획득 11회/30초)
                matchExpansionRatePerSecond = 1.0,
                coastSeconds = 0.7,
                lowConfidenceRescueSeconds = 0.4,
            ),
            motionHintProvider = { motionEstimator.consumeHint() },
            faceAnalyzer = faceAnalyzers, // 기능 2(인물 인식) + 셀카 시선 판정
        )
    }
    private var lastCvLogMs = 0L
    /** detector 사이 tracker overlay는 15fps면 충분하다. Compose 갱신을 카메라 30fps로 만들지 않는다. */
    private var lastPropagationUiAtMs = 0L

    private data class PendingCaptureUpload(
        val sessionId: String,
        /** 앱 프로세스 안의 늦은 콜백을 버리기 위한 토큰. 서버 capture_revision과 무관하다. */
        val localGeneration: Long,
        val rawText: String,
        val customLabels: List<String>,
        val detectedObjects: List<String>,
        val knownSubjects: List<FrameUploader.KnownSubject>,
        val peopleAtShutter: List<String>,
        val serverAiEnabled: Boolean,
        var representative: Uri? = null,
        var candidates: List<RingFrameBuffer.Frame>? = null,
    )
    private val pendingCaptureUploads = LinkedHashMap<String, PendingCaptureUpload>()
    private var captureGenerationCounter = 0L
    private var activeCaptureGeneration = 0L
    private var activeServerCaptureRevision: Long? = null

    // 업로드에 동봉할 발화 원문 (#36 계약의 raw_text 필수 필드).
    // AIMING 진입 시 초기화, 타겟 스펙이 도착하면 그 rawText 로 갱신된다.
    private var currentRawText = ""
    /** 일반 발화를 해석하는 동안 임의의 큰 객체로 이동 안내를 시작하지 않는 게이트. */
    @Volatile
    private var targetSpecPending = false
    /** Linearizes target-intent changes with all CV-derived UI, TTS, and zoom effects. */
    private val targetIntentLock = Any()

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
    /** predictOnly/짧은 hold가 직전 실제 관측 안내를 매 프레임 덮지 않게 하는 UI 전용 latch. */
    private val guidanceTextStabilizer = GuidanceTextStabilizer()

    // 촬영 결과 화면(S4) 상태 — 대표 컷 저장 직후 표시, 새 세션 시작 시 해제
    private var showResult by mutableStateOf(false)
    private var resultPhoto by mutableStateOf<Bitmap?>(null)
    private var lastResultDescription by mutableStateOf<String?>(null)
    /** null이면 상세 설명 본문이 도착한 상태, 그 외에는 준비/실패/비활성 상태를 사용자에게 보여준다. */
    private var lastResultDescriptionStatus by mutableStateOf<String?>(null)
    /** 서버 폐쇄형 사전에서 검증된 자동 라벨의 한글 표시명. */
    private var lastResultAutoLabels by mutableStateOf<List<String>>(emptyList())
    private var lastResultHasDetailedDescription = false

    // 셔터 순간의 탐지 객체 스냅샷 — 즉시 상황 안내(instantCaptureSummary)의 입력 (#80)
    private var shutterObjects: List<TrackedObject> = emptyList()
    private var shutterStableFrames: Map<Int, Int> = emptyMap()
    private var shutterSourceAgeMs: Long = Long.MAX_VALUE
    private val cvSnapshotLock = Any()
    private var latestObservedObjects: List<TrackedObject> = emptyList()
    private var latestObservedAtMs: Long = 0L
    private val trackStableFrames = HashMap<Int, Int>()

    // 마지막으로 촬영된 세션 — 결과 화면의 "라벨 붙이기"가 어느 사진에 붙일지의 기준
    private var lastCapturedSessionId: String? = null

    // 셔터 순간 화면에 있던 등록 인물 이름 — 로컬 인덱스의 people 태그로만 쓰인다 (서버 안 감)
    private var shutterIdentities: List<String> = emptyList()
    /** 셔터 순간의 track_id → 이름 — 즉시 상황 안내에서 이름 붙은 track 을 익명 집계에서 빼는 데 쓴다. */
    private var shutterIdentityMap: Map<Int, String> = emptyMap()
    private data class LocalSubjectDisplay(val name: String, val isPerson: Boolean)
    private val subjectNamesBySession = LinkedHashMap<String, Map<String, LocalSubjectDisplay>>()

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

    /**
     * 화면 오버레이용 — 의도가 명확하면 그 대상만 남긴 목록 ([visibleObjectsForOverlay]).
     * [cvObjects]는 전체 탐지(셔터 순간 상황 안내 등)에 계속 쓰므로 분리한다.
     */
    private var overlayObjects by mutableStateOf<List<TrackedObject>>(emptyList())
    private var overlayIdentities by mutableStateOf<Map<Int, String>>(emptyMap())

    // CV 성능 측정용 롤링 창 (분석 스레드에서 갱신, 로그 시점에 집계)
    private val cvLatencies = ArrayDeque<Long>()
    private var cvAnalyzedCount = 0

    /** ⑥ 실제 사운드·햅틱·TTS 렌더러. 화면 문구와 같은 canonical readiness를 공유한다. */
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
            permissionsGranted = hasRequiredPermissions()
            if (permissionsGranted) announceWelcomeOnce()
            if (!permissionsGranted) {
                permissionDenied = true
                statusText = "카메라 권한이 필요합니다"
            }
        }

    private val galleryPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openGalleryContent()
            else guidanceFeedback.announce("사진을 찾으려면 사진 접근 권한이 필요해요")
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
        // 줌인 여유가 있으면 "가까이" 대신 자동 줌이 처리한다 — 음성과 줌이 서로 싸우지 않게
        guidanceFeedback.zoomHandlesDistance = {
            sessionManager.state == SessionState.AIMING && autoZoom.canResolveSmallTarget
        }
        // 셀카 모드: 구도가 맞아도 시선이 카메라를 벗어나 있으면 "지금 촬영하세요"를 보류하고 사유를 말한다
        guidanceFeedback.readyGate = { selfieGaze.readyBlockReason() }
        guidanceFeedback.applySettings(settingsUiState) // 저장된 설정값을 시작부터 반영

        // 이름 가림은 첫 발화부터 준비돼 있어야 한다. 등록 DB는 소규모이므로 UI/세션을 열기 전에
        // 동기 로드하고, 상대적으로 덜 긴급한 사진 라벨 인덱스만 백그라운드에서 읽는다.
        reloadRegisteredIdentities(threadName = "SnapSight-IdentityNames-Initial")
        // 커스텀 라벨은 메타데이터 업로드/검색용이며 이름 가림의 선행 조건이 아니다.
        Thread({
            customLabelsCache = photoIndexStore.allCustomLabels()
        }, "SnapSight-LabelCache").start()

        cameraController.setFrameProcessor(cvProcessor)
        updateAnalysisMode() // 시작은 IDLE — 조준 전까지 추론 OFF
        registerThermalListener()
        // LISTENING 안내 TTS가 끝난 뒤에 음성 인식을 시작한다 — 안내가 마이크로 들어가
        // "말해주세요"가 발화로 인식되던 문제의 수정 (실사용 피드백 2026-08-22)
        sessionManager.listeningPrompt = { isRetry, onDone ->
            guidanceFeedback.announce(
                if (isRetry) "다시 한번 말씀해 주세요" else "무엇을 찍을지 말씀해 주세요",
                onDone = onDone,
            )
        }
        sessionManager.listener = object : CaptureSessionManager.Listener {
            override fun onStateChanged(state: SessionState) {
                sessionState = state
                statusText = state.description
                updateAnalysisMode()
                if (state == SessionState.LISTENING) {
                    cancelCaptureNetworkWork()
                    pendingCaptureUploads.clear()
                    activeCaptureGeneration = 0L
                    activeServerCaptureRevision = null
                    showResult = false
                    sessionRawText = ""
                    guidanceText = ""
                }
                // 조준 시작 = 새 추적 세션. track_id 가 이전 세션과 섞이지 않도록 초기화한다.
                // TargetSpec은 아직 백엔드 응답 전이라 일단 null로 시작 — onUtteranceRecognized의
                // UtteranceClient 콜백이 도착하면 같은 세션이 아직 AIMING일 때 한 번 더 갱신한다.
                if (state == SessionState.AIMING) {
                    synchronized(targetIntentLock) {
                        currentRawText = "" // 발화 없는 세션은 일반 촬영으로 확정된다.
                        targetSpecPending = false
                        resetTargetDerivedStateLocked()
                        cvProcessor.startNewSession(spec = null)
                        autoZoom.reset() // 넓게(0.6배, 기기 최소 배율) 시작해 피사체를 먼저 찾는다
                        guidanceText = GENERAL_CAPTURE_WAITING_GUIDANCE
                    }
                    motionEstimator.start() // 조준 중에만 자이로 적분 (기능 1-C)
                    announcedIdentities.clear() // 이 세션에서 인물 안내를 다시 할 수 있게 (기능 2)
                    shutterMissingTarget = null
                    lastAnnouncedGaze = null // 셀카 시선 안내를 세션마다 새로 시작
                } else {
                    synchronized(targetIntentLock) { targetSpecPending = false }
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
                    // LISTENING 안내는 listeningPrompt 게이트가 담당 — 안내가 끝난 뒤에
                    // 인식이 시작되므로 안내 음성이 발화로 인식되지 않는다 (2026-08-22)
                    // #84 탭 우선: 탭을 먼저, 볼륨은 병행 수단으로 나중에 말한다
                    SessionState.AIMING -> guidanceFeedback.announce("카메라를 비춰 주세요. 화면을 두 번 탭하면 촬영합니다")
                    SessionState.CAPTURING -> {
                        val now = System.currentTimeMillis()
                        synchronized(cvSnapshotLock) {
                            shutterObjects = latestObservedObjects
                            shutterStableFrames = trackStableFrames.toMap()
                            shutterSourceAgeMs = if (latestObservedAtMs > 0L) now - latestObservedAtMs
                            else Long.MAX_VALUE
                        }
                        // 이름은 셔터 순간 최신 관측에 실제로 바인딩된 track만 사용한다.
                        val shutterTrackIds = shutterObjects.mapTo(HashSet()) { it.trackId }
                        shutterIdentityMap = if (shutterSourceAgeMs <= SHUTTER_IDENTITY_MAX_AGE_MS) {
                            currentIdentities.filterKeys { it in shutterTrackIds }
                        } else {
                            emptyMap()
                        }
                        // Photo people tags must never include registered object names.
                        val peopleNames = registeredPeople.toSet()
                        shutterIdentities = shutterIdentityMap.values
                            .filter { it in peopleNames }
                            .distinct()
                        val captureSessionId = sessionManager.sessionId
                        val localGeneration = ++captureGenerationCounter
                        activeCaptureGeneration = localGeneration
                        activeServerCaptureRevision = null
                        val knownSubjects = knownSubjectsAtShutter(
                            captureSessionId,
                            shutterObjects,
                            shutterIdentityMap,
                            registeredPeople.toSet(),
                        )
                        pendingCaptureUploads[captureSessionId] = PendingCaptureUpload(
                            sessionId = captureSessionId,
                            localGeneration = localGeneration,
                            rawText = currentRawText,
                            customLabels = customLabelsCache.toList(),
                            detectedObjects = shutterObjects
                                .filterNot { it.predicted }
                                .map { it.label }
                                .distinct(),
                            knownSubjects = knownSubjects,
                            peopleAtShutter = shutterIdentities,
                            serverAiEnabled = settingsUiState.serverAiDescriptionEnabled,
                        )
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
                        guidanceFeedback.announce(
                            captureHeadline(),
                            priority = GuidanceFeedback.SpeechPriority.CAPTURE,
                        )
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
                // 재시도 안내도 listeningPrompt 게이트(isRetry=true)가 말한다 — 여기서
                // 또 말하면 두 번 겹치고, 인식 시작 전 게이트를 거치지 않게 된다
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
                    applyTargetSpecIfStillAiming(sessionId, failedSpec, localRawText = null)
                    return
                }
                Log.i(TAG, "발화 인식 완료 [$sessionId] (${text.length}자)")
                val serverText = serverSafeUtterance(text)
                sessionRawText = text // 시안(v31): 해석하는 동안 홈 마이크 아래에 발화 원문을 보여준다
                currentRawText = serverText
                // 등록 이름("유재석 찍어줘")은 STT 원문만으로 기기에서 바로 안다 — 백엔드 스펙(수 초)을
                // 기다리는 동안에도 오버레이·조준 대상을 그 이름으로 좁힌다 (2026-08-23)
                if (sessionManager.sessionId == sessionId && sessionManager.state == SessionState.AIMING) {
                    synchronized(targetIntentLock) {
                        if (sessionManager.sessionId == sessionId &&
                            sessionManager.state == SessionState.AIMING
                        ) {
                            targetSpecPending = true
                            // Clear any previous remote spec while the new utterance resolves.
                            // A known local name remains an immediate, generation-safe fast path.
                            val localIdentityName = registeredTargetName(text)
                            resetTargetDerivedStateLocked()
                            cvProcessor.setTargetIntent(
                                spec = null,
                                identityName = localIdentityName,
                                forceNewGeneration = true,
                            )
                            if (localIdentityName == null) {
                                guidanceText = "촬영 요청을 확인하고 있어요"
                            }
                        }
                    }
                }
                utteranceClient.sendUtterance(
                    sessionId = sessionId,
                    rawText = serverText,
                    callback = object : UtteranceClient.Callback {
                        override fun onSuccess(spec: TargetSpec?) {
                            Log.i(
                                TAG,
                                "타겟 스펙 수신 [$sessionId]: " +
                                    "status=${spec?.status?.wire}, " +
                                    "type=${spec?.subjectType?.wire}, " +
                                    "confidence=${spec?.confidence}",
                            )
                            applyTargetSpecIfStillAiming(sessionId, spec, localRawText = text)
                        }

                        override fun onFailure(error: Throwable) {
                            // 타겟 스펙 요청 실패해도 촬영 흐름은 계속 진행 (일반 촬영 모드로 대체)
                            Log.w(TAG, "타겟 스펙 요청 실패 [$sessionId]", error)
                            if (sessionManager.sessionId == sessionId &&
                                sessionManager.state == SessionState.AIMING
                            ) {
                                synchronized(targetIntentLock) {
                                    targetSpecPending = false
                                }
                            }
                        }
                    },
                )
            }

            override fun onPhotoCaptured(sessionId: String, uri: Uri) {
                Log.i(TAG, "대표 컷 저장 [$sessionId]: $uri")
                val pending = pendingCaptureUploads[sessionId] ?: run {
                    Log.w(TAG, "취소됐거나 만료된 세션의 대표 컷 콜백 무시 [$sessionId]")
                    return
                }
                lastCapturedSessionId = sessionId
                activeCaptureGeneration = pending.localGeneration
                pending.representative = uri
                // 검색 인덱스 기본 행 — 메타데이터 도착 전에도 시간 검색이 되게 한다 (기능 3-B)
                val peopleAtShutter = pending.peopleAtShutter
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
                maybeUploadFrames(sessionId)
            }

            override fun onCandidatesCollected(
                sessionId: String,
                candidates: List<RingFrameBuffer.Frame>,
            ) {
                Log.i(TAG, "후보 프레임 ${candidates.size}장 [$sessionId]")
                val pending = pendingCaptureUploads[sessionId] ?: run {
                    Log.w(TAG, "취소됐거나 만료된 세션의 후보 콜백 무시 [$sessionId]")
                    return
                }
                pending.candidates = candidates
                maybeUploadFrames(sessionId)
            }
        }

        setContent {
            SnapSightTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (currentScreen) {
                        AppScreen.ONBOARDING -> Box(
                            // #84 전역 문법: 두 번 탭=권한 허용(메인), 세 번 탭=작동 방식 낭독(서브)
                            modifier = Modifier
                                .fillMaxSize()
                                .appTapGrammar(
                                    onDoubleTap = { checkOrRequestPermissions() },
                                    onTripleTap = {
                                        guidanceFeedback.announce(
                                            "찍고 싶은 장면을 말하면 사운드와 진동으로 방향과 거리를 " +
                                                "안내합니다. 화면을 두 번 탭해 촬영할 수 있어요"
                                        )
                                    },
                                    onLongPress = { /* 첫 화면 — 돌아갈 곳 없음 */ },
                                ),
                        ) {
                            OnboardingScreen(
                                permissionState = onboardingPermissionState,
                                onRequestPermissions = { checkOrRequestPermissions() },
                                onOpenAppSettings = { openAppSettings() },
                                onContinue = {
                                    appPrefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
                                    currentScreen = AppScreen.MAIN
                                },
                            )
                        }

                        AppScreen.MAIN -> if (permissionsGranted) {
                            // 카메라는 항상 아래에 깔려 있고, 세션 단계에 따라 홈/결과 화면이 위에 뜬다 (v31 #80).
                            val homeVisible = !showResult && !enrollmentActive && sessionState in setOf(
                                SessionState.IDLE, SessionState.LISTENING, SessionState.PARSING,
                            )
                            // 대표 컷이 먼저 저장돼 결과 화면이 떠도 CAPTURING 동안은 카메라를
                            // 잠깐 유지해야 셔터 뒤 후보 프레임이 끊기지 않는다.
                            val cameraNeeded = enrollmentActive ||
                                sessionState == SessionState.CAPTURING ||
                                (!showResult && sessionState in setOf(
                                    SessionState.LISTENING,
                                    SessionState.PARSING,
                                    SessionState.AIMING,
                                ))
                            // #84: 뒤로가기 = 복귀 문법 — 결과 닫기 → 세션 취소 → (홈) 2회 종료 확인
                            BackHandler {
                                when {
                                    enrollmentActive -> cancelEnrollmentToHome()
                                    showResult -> closeResultToHome()
                                    sessionState != SessionState.IDLE -> cancelSessionToHome()
                                    else -> onHomeBackPressed()
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // #84: 두 번 탭=메인(시작/발화 종료/셔터/다시 촬영), 길게=뒤로,
                                    // 세 번 탭=서브(홈: 사진 찾기 / 세션: 상태 낭독 / 결과: 설명 듣기).
                                    .appTapGrammar(
                                        onDoubleTap = { onMainMainAction() },
                                        onTripleTap = { onMainSubAction() },
                                        onLongPress = { onMainBackAction() },
                                    ),
                            ) {
                                if (cameraNeeded) {
                                    CaptureScreen(
                                        controller = cameraController,
                                        statusText = statusText,
                                        rawText = sessionRawText,
                                        guidanceText = guidanceText,
                                        onCancel = { cancelSessionToHome() },
                                        cvObjects = overlayObjects,
                                        identities = overlayIdentities,
                                        // 등록 중엔 조준 UI 없이 미리보기+탐지 상자만 보여준다
                                        showOverlays = !homeVisible && !showResult && !enrollmentActive,
                                        onLensChanged = { isFront -> onLensChanged(isFront) },
                                        // 조준 중엔 미리보기 전체가 "촬영" 접근성 노드
                                        onShutterTap = if (sessionState == SessionState.AIMING) {
                                            { sessionManager.onVolumePressed() }
                                        } else null,
                                    )
                                }
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
                                        description = lastResultDescription ?: lastResultDescriptionStatus,
                                        headline = captureHeadline(),
                                        details = if (lastResultAutoLabels.isEmpty()) emptyList() else listOf(
                                            "AI 라벨" to lastResultAutoLabels.joinToString(", ")
                                        ),
                                        onReplayDescription = {
                                            speak(
                                                lastResultDescription
                                                    ?: lastResultDescriptionStatus
                                                    ?: "서버 AI 상세 설명을 준비하고 있어요"
                                            )
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
                            Box(
                                // 두 번 탭=설정값 낭독(메인), 세 번 탭=안내 방식(서브), 길게=홈
                                modifier = Modifier
                                    .fillMaxSize()
                                    .appTapGrammar(
                                        onDoubleTap = { announceSettingsSummary() },
                                        onTripleTap = { announceGuidanceHelp() },
                                        onLongPress = { leaveSettingsToHome() },
                                    ),
                            ) {
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
                            serverAiDescriptionEnabled = settingsUiState.serverAiDescriptionEnabled,
                            onServerAiDescriptionEnabledChange = {
                                updateSettings(settingsUiState.copy(serverAiDescriptionEnabled = it))
                                guidanceFeedback.announce(
                                    if (it) "서버 AI 사진 설명을 켰어요"
                                    else "서버 AI 사진 설명을 껐어요. 촬영 사진은 서버로 보내지 않아요"
                                )
                            },
                            serverUrl = backendUrlInput,
                            onServerUrlChange = { backendUrlInput = it },
                            registeredPeople = registeredPeople,
                            onEnrollFace = { startFaceEnrollment() },
                            onDeletePerson = { name -> deleteRegisteredPerson(name) },
                            registeredObjects = registeredObjects,
                            onEnrollObject = { startObjectEnrollment() },
                            onDeleteObject = { name -> deleteRegisteredObject(name) },
                            // 돌아가기 = 서버 주소 적용 시점 — 뒤로 제스처와 같은 공통 경로 (#84)
                            onBack = { leaveSettingsToHome() },
                            )
                            }
                        }

                        AppScreen.GALLERY -> {
                            BackHandler { leaveGalleryToHome() }
                            Box(
                                // 두 번 탭=말해서 찾기(메인), 세 번 탭=결과 듣기(서브), 길게=홈
                                modifier = Modifier
                                    .fillMaxSize()
                                    .appTapGrammar(
                                        onDoubleTap = { startGalleryVoiceSearch() },
                                        onTripleTap = { speakCurrentResults() },
                                        onLongPress = { leaveGalleryToHome() },
                                    ),
                            ) {
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
     * 비교·짧은 설명·상세 설명·검색 라벨을 한 structured understanding 응답으로 받는다.
     * 세 개 endpoint를 동시에 폴링하던 중복을 없애고 서버가 발급한 revision을 끝까지 검증한다.
     */
    private fun pollCaptureUnderstanding(
        sessionId: String,
        serverRevision: Long,
        allowedCustomLabels: Set<String>,
    ) {
        metadataClient.pollMetadata(sessionId, serverRevision, object : MetadataClient.Callback {
            override fun onDone(metadata: MetadataClient.Metadata) {
                if (!isCurrentServerCapture(sessionId, serverRevision)) return
                val labels = MetadataLabelContract.sanitize(
                    fixed = metadata.labels,
                    custom = metadata.customLabels,
                    allowedFixed = photoLabelDictionary.labels.mapTo(HashSet()) { it.id },
                    allowedCustom = allowedCustomLabels,
                )
                Log.i(TAG, "통합 사진 이해 도착 [$sessionId] 라벨 ${labels.fixed}")
                val localizedBrief = localizeSubjectRefs(sessionId, metadata.briefDescription)
                val localizedDetail = localizeSubjectRefs(sessionId, metadata.longDescription)
                if (isCurrentVisibleResult(sessionId)) {
                    val description = localizedDetail ?: localizedBrief
                    lastResultDescription = description
                    lastResultDescriptionStatus = if (description == null) {
                        "서버가 이 사진의 상세 설명을 만들지 못했어요. 사진은 정상적으로 저장됐어요."
                    } else {
                        null
                    }
                    lastResultHasDetailedDescription = localizedDetail != null
                    val fixedNames = labels.fixed.mapNotNull { labelId ->
                        photoLabelDictionary.labels.firstOrNull { it.id == labelId }?.name
                    }
                    lastResultAutoLabels = fixedNames + labels.custom
                }
                Thread({
                    photoIndexStore.applyMetadata(
                        sessionId = sessionId,
                        longDescription = localizedDetail,
                        fixedLabels = labels.fixed,
                        customAuto = labels.custom,
                        taxonomyVersion = metadata.taxonomyVersion,
                        shortDescription = localizedBrief,
                    )
                }, "SnapSight-IndexUpdate").start()

                if (metadata.finalFrameId == "representative") {
                    announceUnderstandingBrief(sessionId, serverRevision, localizedBrief)
                } else {
                    downloadAndSaveCanonicalFrame(
                        sessionId = sessionId,
                        serverRevision = serverRevision,
                        finalFrameId = metadata.finalFrameId,
                        brief = localizedBrief,
                    )
                }
            }

            override fun onGaveUp(reason: String) {
                Log.w(TAG, "통합 사진 이해 폴링 중단 [$sessionId]: $reason")
                if (isCurrentVisibleResult(sessionId)) {
                    lastResultDescriptionStatus =
                        "서버 AI 상세 설명을 가져오지 못했어요. 서버 연결을 확인해 주세요."
                }
            }
        })
    }

    private fun downloadAndSaveCanonicalFrame(
        sessionId: String,
        serverRevision: Long,
        finalFrameId: String,
        brief: String?,
    ) {
        finalFrameClient.download(
            sessionId = sessionId,
            captureRevision = serverRevision,
            expectedFinalFrameId = finalFrameId,
            callback = object : FinalFrameClient.Callback {
                override fun onSuccess(frame: FinalFrameClient.FinalFrame) {
                    if (!isCurrentServerCapture(sessionId, serverRevision)) return
                    Thread({
                        try {
                            val selectedUri = canonicalFrameStore.save(sessionId, frame.jpeg)
                            val preview = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
                            runOnUiThread {
                                if (!isCurrentServerCapture(sessionId, serverRevision)) return@runOnUiThread
                                resultPhoto = preview
                                if (isCurrentVisibleResult(sessionId)) {
                                    guidanceFeedback.announce(
                                        "촬영 순간 근처에서 더 나은 사진을 찾아 별도로 저장했어요. " +
                                            "고해상도 원본도 그대로 있어요.",
                                        onDone = {
                                            announceUnderstandingBrief(
                                                sessionId, serverRevision, brief,
                                            )
                                        },
                                        priority = GuidanceFeedback.SpeechPriority.CAPTURE,
                                    )
                                }
                                Log.i(TAG, "선택 프레임 저장 완료 [$sessionId]: $selectedUri")
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "선택 프레임 MediaStore 저장 실패 [$sessionId]", t)
                            runOnUiThread {
                                announceUnderstandingBrief(sessionId, serverRevision, brief)
                            }
                        }
                    }, "SnapSight-CanonicalSave").start()
                }

                override fun onFailure(error: Throwable) {
                    Log.w(TAG, "선택 프레임 다운로드 실패 [$sessionId]", error)
                    announceUnderstandingBrief(sessionId, serverRevision, brief)
                }
            }
        )
    }

    private fun announceUnderstandingBrief(
        sessionId: String,
        serverRevision: Long,
        brief: String?,
    ) {
        if (!isCurrentServerCapture(sessionId, serverRevision) ||
            !isCurrentVisibleResult(sessionId) || brief.isNullOrBlank()
        ) return
        guidanceFeedback.announce(brief, priority = GuidanceFeedback.SpeechPriority.STATUS)
    }

    private fun isCurrentVisibleResult(sessionId: String): Boolean =
        currentScreen == AppScreen.MAIN && showResult && lastCapturedSessionId == sessionId

    private fun isCurrentCapture(sessionId: String, localGeneration: Long): Boolean =
        lastCapturedSessionId == sessionId && activeCaptureGeneration == localGeneration

    private fun isCurrentServerCapture(sessionId: String, serverRevision: Long): Boolean =
        lastCapturedSessionId == sessionId && activeServerCaptureRevision == serverRevision

    private fun cancelCaptureNetworkWork() {
        frameUploader.cancelAll()
        metadataClient.cancelAll()
        finalFrameClient.cancelAll()
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
        cancelCaptureNetworkWork()
        activeCaptureGeneration = 0L
        activeServerCaptureRevision = null
        showResult = false
        guidanceFeedback.playScreenExit()
        guidanceFeedback.announce("사진은 저장됐어요. 홈입니다")
    }

    /**
     * 세션 취소 → 홈 — 다른 복귀 경로와 같은 하강 earcon+TTS 를 함께 낸다
     * (실사용 피드백 2026-08-22: "촬영 중 꾹 누르면 홈으로 가는데 전환 이펙트가 없다").
     */
    private fun cancelSessionToHome() {
        if (sessionManager.state == SessionState.IDLE) return
        cancelCaptureNetworkWork()
        pendingCaptureUploads.clear()
        activeCaptureGeneration = 0L
        activeServerCaptureRevision = null
        sessionManager.cancel()
        guidanceFeedback.playScreenExit()
        guidanceFeedback.announce("촬영을 취소했어요. 홈입니다")
    }

    /** MAIN 화면 아무 곳 두 번 탭 = 메인 기능 — 상태별 진행(시작/발화 종료/셔터/처음으로). */
    private fun onMainMainAction() {
        if (enrollmentActive) return // 등록 스캔 중 오탭으로 세션이 시작되지 않게
        if (showResult) {
            // 결과 화면의 메인 기능 = 다시 촬영
            showResult = false
            if (sessionManager.state == SessionState.IDLE) sessionManager.onVolumePressed()
            return
        }
        sessionManager.onVolumePressed()
    }

    /** MAIN 화면 길게 누르기 = 뒤로 — 등록 취소 / 결과 닫기 / 세션 취소 / (홈) 2회 종료 확인. */
    private fun onMainBackAction() {
        when {
            enrollmentActive -> cancelEnrollmentToHome()
            showResult -> closeResultToHome()
            sessionManager.state != SessionState.IDLE -> cancelSessionToHome()
            else -> onHomeBackPressed()
        }
    }

    /** MAIN 화면 세 번 탭 = 서브 기능 — 결과: 설명 다시 듣기 / 세션 중: 상태 낭독 / 홈: 사진 찾기. */
    private fun onMainSubAction() {
        if (enrollmentActive) return
        when {
            showResult -> lastResultDescription?.let(::speak) ?: speak("설명을 만드는 중이에요")
            sessionManager.state != SessionState.IDLE -> {
                val summary = listOf(statusText, guidanceText)
                    .filter { it.isNotBlank() }
                    .joinToString(". ")
                if (summary.isNotBlank()) guidanceFeedback.announce(summary)
            }
            else -> openGallery()
        }
    }

    /** 설정 화면 두 번 탭(메인) — 현재 설정값을 음성으로 요약한다. */
    private fun announceSettingsSummary() {
        val s = settingsUiState
        guidanceFeedback.announce(
            "진동 강도 ${(s.vibrationIntensity * 100).roundToInt()}퍼센트, " +
                "사운드 강도 ${(s.soundVolume * 100).roundToInt()}퍼센트, " +
                "음성 속도 ${"%.1f".format(s.speechRate)}배, " +
                "서버 AI 사진 설명은 ${if (s.serverAiDescriptionEnabled) "켜짐" else "꺼짐"}입니다"
        )
    }

    /** 설정 화면 세 번 탭(서브) — 안내 방식 설명을 낭독한다. */
    private fun announceGuidanceHelp() {
        guidanceFeedback.announce(
            "촬영 중 방향과 거리는 사운드와 진동으로 안내하고, " +
                "대상을 찾았을 때와 촬영 순간에만 짧은 음성을 사용합니다"
        )
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

    /**
     * 오버레이에 그릴 객체 — 의도가 명확하면 그 대상만 (2026-08-22 피드백).
     *  - 의도 없음·해석 실패·풍경: 전체 탐지 (탐지가 도는지 확인용)
     *  - "유재석 찍어줘"처럼 등록 인물·사물 이름이 발화에 있으면: 그 이름으로 식별된 track 만
     *  - "나무 찍어줘"처럼 클래스 의도: 선택기가 고른 후보만 (나무가 여러 그루면 전부)
     * 분석 스레드에서 호출된다 — 상태 읽기만 한다.
     */
    private fun visibleObjectsForOverlay(output: CvFrameOutput): List<TrackedObject> {
        // 등록 이름 의도는 스펙보다 먼저(STT 직후) 정해진다 — 스펙이 아직 없어도 이름으로 좁힌다
        output.targetIdentityName?.let { name ->
            return output.objects.filter { output.identities[it.trackId] == name }
        }
        val spec = output.targetSpec
        if (spec == null || !spec.isActionable || spec.subjectType == TargetSpec.SubjectType.LANDSCAPE) {
            return output.objects
        }
        val selection = output.selection
        return if (selection == null || selection.state == TargetSelectionState.DISABLED) output.objects
        else selection.candidates
    }

    /** 로컬 타깃 선택과 클라우드 가림은 반드시 같은 ready snapshot과 이름 해석을 사용한다. */
    private fun registeredTargetName(rawText: String): String? {
        if (!identityReloadGate.isReady) return null
        return RegisteredIdentityMatcher.uniqueTarget(
            rawText = rawText,
            registeredPeople = registeredPeople,
            registeredObjects = registeredObjects,
        )?.canonicalName
    }

    /** 사진 찾기 진입 — 목록·검색 인덱스를 매번 새로 읽는다 (촬영 직후 돌아와도 최신이 보이게). */
    private fun openGallery() {
        val permission = galleryReadPermission()
        if (permission != null &&
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        ) {
            galleryPermissionLauncher.launch(permission)
            return
        }
        openGalleryContent()
    }

    private fun openGalleryContent() {
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
        Log.i(TAG, "갤러리 검색 발화 수신 (${utterance.length}자)")
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
            // 기능 2 연동 — people 태그에는 실제 등록 인물만 저장한다.
            peopleNames = registeredPeople,
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
        return PhotoSearchEngine.RollCallItem(
            dateText = dateText,
            description = description,
            people = entry?.people?.toList().orEmpty(), // 등록 인물·사물 이름 — 서버 설명엔 없으니 여기서 덧붙인다
        )
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
        listenForResultLabel(
            sessionId,
            prompt = "이 사진을 뭐라고 기억할까요?",
            allowFixedName = false,
        )
    }

    /**
     * 라벨 이름을 음성으로 받는다. 안내 TTS가 끝난 뒤에 인식을 시작한다(마이크 유입 방지,
     * listeningPrompt와 동일한 이유).
     *
     * [allowFixedName]이 false면 고정 사전(photo_labels.json)의 이름·동의어와 똑같은 라벨을
     * 한 번 거절하고 나만의 이름을 권한다 — 검색 파서가 고정 라벨과 커스텀 라벨을 AND로
     * 조이기 때문에, 겹치는 이름은 "강아지 사진" 검색을 그 사진 하나로 과하게 좁혀버린다
     * (실사용 피드백 2026-08-22). 재차 같은 이름을 말하면 사용자의 의지로 보고 저장한다.
     */
    private fun listenForResultLabel(sessionId: String, prompt: String, allowFixedName: Boolean) {
        guidanceFeedback.announce(prompt, onDone = {
            searchRecognizer.start(object : SpeechToTextRecognizer.Listener {
                override fun onRecognized(text: String) {
                    val label = text.trim().removeSuffix("이라고 기억해줘").removeSuffix("로 기억해줘").trim()
                    if (label.isBlank()) {
                        speak("라벨 이름을 못 알아들었어요")
                        return
                    }
                    if (!allowFixedName && clashesWithFixedLabel(label)) {
                        listenForResultLabel(
                            sessionId,
                            prompt = "\"$label\"은 기본 분류 이름이라 검색이 섞일 수 있어요. " +
                                "\"우리 $label\"처럼 나만의 이름을 권해요. 뭐라고 기억할까요?",
                            allowFixedName = true,
                        )
                        return
                    }
                    resolveAndAttachLabel(sessionId, label)
                }

                override fun onError(message: String) = speak("잘 못 들었어요. 다시 시도해 주세요")
            })
        })
    }

    /** 라벨이 고정 사전의 이름·동의어와 표기까지 같은지 (부분 포함은 허용 — "우리 강아지"는 통과). */
    private fun clashesWithFixedLabel(label: String): Boolean {
        val normalized = PhotoLabelDictionary.normalize(label)
        return photoLabelDictionary.surfaceForms().any { PhotoLabelDictionary.normalize(it) == normalized }
    }

    /**
     * 기존 커스텀 라벨과의 동의어 병합을 판정한 뒤 부착한다 ("내 개" 등록 후 "내 강아지"를
     * 말해도 라벨이 둘로 갈라지지 않게). 표기가 같으면 즉시 병합, 아니면 백엔드 LLM 판정 —
     * 오프라인이거나 판정에 실패하면 새 라벨로 저장한다.
     */
    private fun resolveAndAttachLabel(sessionId: String, spoken: String) {
        val existing = customLabelsCache
        val exact = existing.firstOrNull {
            PhotoLabelDictionary.normalize(it) == PhotoLabelDictionary.normalize(spoken)
        }
        if (exact != null || existing.isEmpty()) {
            attachLabel(sessionId, spoken = spoken, canonical = exact)
            return
        }
        labelNormalizeClient.normalize(spoken, existing) { canonical ->
            attachLabel(sessionId, spoken = spoken, canonical = canonical)
        }
    }

    private fun attachLabel(sessionId: String, spoken: String, canonical: String?) {
        val label = canonical ?: spoken
        Thread({
            photoIndexStore.attachUserLabel(sessionId, label)
            customLabelsCache = photoIndexStore.allCustomLabels()
        }, "SnapSight-AttachLabel").start()
        speak(
            if (canonical != null && canonical != spoken) {
                "\"$spoken\"은 기존의 \"$canonical\"와 같은 뜻으로 보여서 \"$canonical\"로 기억할게요"
            } else {
                "이 사진을 \"$label\"로 기억할게요. 나중에 그렇게 찾을 수 있어요"
            }
        )
    }

    /**
     * 기능 2: 얼굴 등록 흐름 — 카메라 화면(MAIN)으로 이동해 이름을 음성으로 받고,
     * 3초간 프레임에서 얼굴 샘플을 대량 수집한다 (각도 다양성은 안내 음성으로 유도).
     * 동의 확인은 흐름 첫 안내에 포함한다 (기획 문서: 프라이버시 단순화).
     */
    /**
     * Reloads face and object names as one generation-gated snapshot. Redaction remains
     * fail-closed while either DB read or the optional preceding mutation is incomplete.
     */
    private fun reloadRegisteredIdentities(
        threadName: String = "SnapSight-IdentityNames",
        mutation: () -> Unit = {},
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (identityReloadClosed) return
        val reloadToken = identityReloadGate.begin()
        runCatching {
            identityReloadExecutor.execute {
                val mutationResult = runCatching(mutation)
                    .onFailure { Log.e(TAG, "등록 정보 변경 실패 [$threadName]", it) }
                val peopleResult: Result<List<String>> = if (mutationResult.isSuccess) {
                    runCatching { faceRegistry.peopleNames() }
                        .onFailure { Log.e(TAG, "등록 인물 목록 로드 실패", it) }
                } else {
                    Result.failure(mutationResult.exceptionOrNull()!!)
                }
                val objectResult: Result<List<String>> = if (mutationResult.isSuccess) {
                    runCatching { objectRegistry.objectNames() }
                        .onFailure { Log.e(TAG, "등록 사물 목록 로드 실패", it) }
                } else {
                    Result.failure(mutationResult.exceptionOrNull()!!)
                }
                val success = peopleResult.isSuccess && objectResult.isSuccess
                if (!identityReloadClosed) runOnUiThread {
                    if (identityReloadClosed) return@runOnUiThread
                    val accepted = identityReloadGate.complete(reloadToken, success) {
                        registeredPeople = peopleResult.getOrThrow()
                        registeredObjects = objectResult.getOrThrow()
                    }
                    if (accepted) onComplete(success)
                }
            }
        }.onFailure {
            Log.e(TAG, "등록 정보 로드 작업 예약 실패 [$threadName]", it)
            identityReloadGate.complete(reloadToken, success = false) {}
        }
    }

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
        enrollmentActive = true // 홈 오버레이를 내려 미리보기·탐지 상자를 보여준다 (사물 등록과 동일)
        updateAnalysisMode()
        guidanceFeedback.announce(
            "얼굴 등록을 시작합니다. 등록할 분의 동의를 받았다면, 그분을 뭐라고 부를지 말씀해 주세요. " +
                "얼굴 정보는 이 기기에만 저장됩니다",
            onDone = {
            if (!enrollmentActive) return@announce // 안내 중 취소됨
            searchRecognizer.start(object : SpeechToTextRecognizer.Listener {
                override fun onRecognized(text: String) {
                    val name = text.trim()
                    if (name.isBlank()) {
                        finishEnrollmentFlow("이름을 못 알아들었어요. 등록을 취소합니다")
                        return
                    }
                    beginFaceScan(name)
                }

                override fun onError(message: String) =
                    finishEnrollmentFlow("이름을 못 알아들었어요. 등록을 취소합니다")
            })
            },
        )
    }

    private fun beginFaceScan(name: String) {
        guidanceFeedback.announce(
            "$name 님의 얼굴을 카메라에 비춰 주세요. 삐 소리부터 10초 동안 고개를 천천히 좌우·위아래로 돌려 주세요",
            onDone = {
            if (!enrollmentActive) return@announce
            guidanceFeedback.playScanStart() // 이 소리부터 수집 시작
            scheduleScanHalfwayTick()
            enrollmentScanning = true
            updateAnalysisMode()
            faceIdentifier.startEnrollment(name, ENROLL_SCAN_MS) { collected ->
                guidanceFeedback.playScanEnd() // 수집 끝 — 이어서 결과 안내
                if (collected > 0) {
                    finishEnrollmentFlow("${name}님 얼굴 ${collected}장을 저장했어요. 이제 이름으로 찾을 수 있어요")
                } else {
                    finishEnrollmentFlow("얼굴을 찾지 못했어요. 밝은 곳에서 다시 시도해 주세요")
                }
                if (collected > 0) reloadRegisteredIdentities(threadName = "SnapSight-FaceNames")
            }
            },
        )
    }

    /** 인물 삭제 — 임베딩까지 완전히 지운다 (프라이버시). */
    private fun deleteRegisteredPerson(name: String) {
        reloadRegisteredIdentities(
            threadName = "SnapSight-FaceDelete",
            mutation = {
                faceRegistry.deletePerson(name)
                faceIdentifier.invalidateRegistryState()
            },
            onComplete = { success ->
                speak(if (success) "${name}님의 얼굴 정보를 삭제했어요" else "얼굴 정보 삭제에 실패했어요")
            },
        )
    }

    /**
     * 사물 등록 흐름 — 얼굴 등록과 같은 순서(카메라 화면 이동 → 이름 음성 → 3초 스캔)지만
     * 파이프라인은 [ObjectIdentifier] 로 분리돼 있다. 스캔은 화면에서 가장 크게 잡힌
     * 사람 아닌 탐지를 대상으로 하므로, 등록할 사물만 가까이 크게 비추게 안내한다.
     */
    private fun startObjectEnrollment() {
        if (!objectEmbedder.isAvailable) {
            speak("인식 모델이 설치되지 않아 등록할 수 없어요")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED || !searchRecognizer.isAvailable
        ) {
            speak("사물 등록에는 마이크 권한이 필요해요")
            return
        }
        currentScreen = AppScreen.MAIN // 카메라가 돌아야 프레임에서 사물을 딸 수 있다
        enrollmentActive = true // 홈 오버레이를 내려 미리보기·탐지 상자를 보여준다
        updateAnalysisMode()
        guidanceFeedback.announce("사물 등록입니다. 뭐라고 부를까요?", onDone = {
            if (!enrollmentActive) return@announce // 안내 중 취소됨
            searchRecognizer.start(object : SpeechToTextRecognizer.Listener {
                override fun onRecognized(text: String) {
                    val name = text.trim()
                    if (name.isBlank()) {
                        finishEnrollmentFlow("이름을 못 알아들었어요. 등록을 취소합니다")
                        return
                    }
                    beginObjectScan(name)
                }

                override fun onError(message: String) =
                    finishEnrollmentFlow("이름을 못 알아들었어요. 등록을 취소합니다")
            })
        })
    }

    private fun beginObjectScan(name: String) {
        // 스캔은 안내가 끝난 직후 시작 — 고정 딜레이를 쓰면 "3초"라는 안내와 체감이 어긋난다
        guidanceFeedback.announce(
            "$name 를 가운데 크게 비춰 주세요. 삐 소리부터 10초 동안 천천히 한 바퀴 돌려 주세요",
            onDone = {
            if (!enrollmentActive) return@announce
            guidanceFeedback.playScanStart() // 이 소리부터 수집 시작
            scheduleScanHalfwayTick()
            enrollmentScanning = true
            updateAnalysisMode()
            objectIdentifier.startEnrollment(name, ENROLL_SCAN_MS) { collected, yoloLabel ->
                guidanceFeedback.playScanEnd() // 수집 끝 — 이어서 결과 안내
                // 완료 안내는 내장 TTS 즉시 재생 — 백엔드 TTS 왕복을 기다리면 무반응처럼 느껴진다
                if (collected > 0) {
                    finishEnrollmentFlow("$name ${collected}장을 저장했어요. 이제 그 이름으로 찾을 수 있어요")
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "사물 등록 완료(label=$yoloLabel, samples=$collected)")
                    }
                } else {
                    finishEnrollmentFlow("사물을 찾지 못했어요. 배경과 구분되게 크게 비춰서 다시 시도해 주세요")
                }
                if (collected > 0) reloadRegisteredIdentities(threadName = "SnapSight-ObjectNames")
            }
            },
        )
    }

    /** 긴 스캔의 진행감 — 절반 지점에 짧은 틱 한 번 (등록이 취소됐으면 울리지 않는다). */
    private fun scheduleScanHalfwayTick() {
        window.decorView.postDelayed({
            if (enrollmentActive) guidanceFeedback.playScanTick()
        }, ENROLL_SCAN_MS / 2)
    }

    /** 등록 흐름 종료 공통 경로 — 오버레이 복구 + 결과를 내장 TTS로 즉시 안내. */
    private fun finishEnrollmentFlow(message: String) {
        enrollmentScanning = false
        enrollmentActive = false
        updateAnalysisMode()
        guidanceFeedback.announce(message)
    }

    /** 등록 흐름 취소 (길게 누르기·뒤로가기) — 진행 중인 스캔까지 중단하고 홈으로. */
    private fun cancelEnrollmentToHome() {
        searchRecognizer.cancel()
        val faceCancel = faceIdentifier.cancelEnrollment()
        val objectCancel = objectIdentifier.cancelEnrollment()
        val persistenceCompleted = faceCancel == EnrollmentCancelResult.COMPLETED ||
            objectCancel == EnrollmentCancelResult.COMPLETED
        enrollmentScanning = false
        enrollmentActive = false
        updateAnalysisMode()
        if (persistenceCompleted) {
            // The DB commit won the linearized race. Refresh the atomic name snapshot because
            // the normal completion callback was intentionally claimed by cancellation.
            reloadRegisteredIdentities(threadName = "SnapSight-IdentityNames-CancelRace")
        }
        guidanceFeedback.playScreenExit()
        guidanceFeedback.announce(
            if (persistenceCompleted) {
                "등록 마무리가 이미 완료되어 취소할 수 없어요. 저장 상태를 유지합니다. 홈입니다"
            } else {
                "등록을 취소했어요. 홈입니다"
            }
        )
    }

    // ---- 발열 대책 P1 (2026-08-22): 상태별 추론 듀티사이클 + 열 적응 ----

    /**
     * 조준(발화~촬영)·등록 중에만 CV 추론을 켠다. 홈·결과 화면·설정에선 카메라가 돌아도
     * 추론하지 않는다 — 상시 CPU 추론이 발열의 주범이었다.
     */
    private fun updateAnalysisMode() {
        val cvMode = when {
            enrollmentScanning -> AnalysisMode.ENROLL
            sessionState == SessionState.AIMING || sessionState == SessionState.CAPTURING ->
                AnalysisMode.ACTIVE
            else -> AnalysisMode.OFF
        }
        val cameraMode = when {
            enrollmentScanning || sessionState == SessionState.AIMING ||
                sessionState == SessionState.CAPTURING -> CameraController.AnalysisMode.ACTIVE
            sessionState == SessionState.LISTENING || sessionState == SessionState.PARSING ->
                CameraController.AnalysisMode.WARM
            else -> CameraController.AnalysisMode.OFF
        }
        cameraController.setAnalysisMode(cameraMode)
        if (cvProcessor.analysisMode == cvMode) return
        cvProcessor.analysisMode = cvMode
        if (cvMode == AnalysisMode.OFF) { // 멈춘 상자가 화면에 남지 않게
            cvObjects = emptyList()
            overlayObjects = emptyList()
            overlayIdentities = emptyMap()
        }
        Log.i(CV_TAG, "분석 모드: camera=$cameraMode, cv=$cvMode")
    }

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    /** 기기가 뜨거워지면 추론 간격을 늘린다 — 앱이 멈추는 것보다 안내가 조금 느려지는 게 낫다. */
    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        // 리스너 등록 뒤 상태 변화만 기다리면 앱을 켤 때 이미 뜨거운 기기에서 첫 구간이
        // 정상 속도로 돌아간다. 현재 상태를 즉시 반영한 다음 변화 알림을 받는다.
        cvProcessor.thermalSlowdown = thermalSlowdownFor(powerManager.currentThermalStatus)
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            val slowdown = thermalSlowdownFor(status)
            if (cvProcessor.thermalSlowdown != slowdown) {
                cvProcessor.thermalSlowdown = slowdown
                Log.i(CV_TAG, "열 상태 $status → 추론 간격 ×$slowdown")
            }
        }
        powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(this), listener)
        thermalListener = listener
    }

    private fun unregisterThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val listener = thermalListener ?: return
        getSystemService(PowerManager::class.java)?.removeThermalStatusListener(listener)
        thermalListener = null
    }

    /** 열 상태 → 추론 간격 배수. 값은 실기기 발열 실측으로 조정한다. */
    private fun thermalSlowdownFor(status: Int): Float = when {
        status >= PowerManager.THERMAL_STATUS_CRITICAL -> 4f
        status == PowerManager.THERMAL_STATUS_SEVERE -> 2.5f
        status == PowerManager.THERMAL_STATUS_MODERATE -> 1.5f
        else -> 1f
    }

    /** 사물 삭제 — 임베딩까지 완전히 지운다. */
    private fun deleteRegisteredObject(name: String) {
        reloadRegisteredIdentities(
            threadName = "SnapSight-ObjectDelete",
            mutation = {
                objectRegistry.deleteObject(name)
                objectIdentifier.invalidateRegistryState()
            },
            onComplete = { success ->
                speak(if (success) "$name 의 등록 정보를 삭제했어요" else "사물 정보 삭제에 실패했어요")
            },
        )
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
        // 등록 인물·사물 이름은 서버 설명에 없으므로(기기 밖으로 안 내보냄) 앞에 붙여 읽는다
        val names = entry?.people.orEmpty()
        val prefix = if (names.isNotEmpty()) "${names.joinToString(", ")} 나온 사진이에요. " else ""
        speak(prefix + (detail ?: "이 사진의 설명이 아직 준비되지 않았어요"))
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
        cancelCaptureNetworkWork()
        pendingCaptureUploads.clear()
        activeCaptureGeneration = 0L
        activeServerCaptureRevision = null
        if (sessionManager.state != SessionState.IDLE) {
            sessionManager.cancel()
            sessionCancelledInBackground = true
        }
    }

    /** 볼륨 키는 시스템에 그대로 맡긴다. 앱이 사용자의 전역 미디어 볼륨을 임의로 바꾸지 않는다. */
    private fun ensureAudibleMediaVolume() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            Log.w(TAG, "미디어 볼륨이 0 — 사용자가 볼륨 키로 조절할 수 있음")
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
            if (sessionCancelledInBackground) "스냅사이트로 돌아왔습니다. 진행 중이던 촬영은 취소됐어요. 화면을 두 번 탭해 다시 시작하세요"
            else "스냅사이트로 돌아왔습니다. 화면을 두 번 탭해 시작하세요"
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
        guidanceFeedback.announce(text, priority = GuidanceFeedback.SpeechPriority.STATUS)
    }

    /**
     * 타겟 스펙이 (네트워크 지연으로) AIMING 시작보다 늦게 도착했을 때 CV 세션에 반영한다.
     *
     * [SpeechToTextRecognizer][com.example.snap_sight.stt.SpeechToTextRecognizer] 인식 →
     * [UtteranceClient] 응답은 비동기라 AIMING 진입 시점엔 아직 없는 게 보통이다(spec=null로 시작).
     * 응답이 왔을 때 사용자가 이미 촬영을 마쳤거나 세션을 취소·재시작했다면(다른 sessionId,
     * 또는 더 이상 AIMING이 아님) 엉뚱한 세션에 적용하면 안 되므로 여기서 막는다.
     */
    /** Must be called while [targetIntentLock] is held after advancing intent generation. */
    private fun resetTargetDerivedStateLocked() {
        guidanceFeedback.resetSession()
        guidanceTextStabilizer.reset()
        deviationCalculator.reset()
        currentIdentities = emptyMap()
        synchronized(cvSnapshotLock) {
            latestObservedObjects = emptyList()
            latestObservedAtMs = 0L
            trackStableFrames.clear()
        }
        overlayObjects = emptyList()
        overlayIdentities = emptyMap()
        lastAimingVerdictAtMs = 0L
        lastAimingSubjectDetected = false
    }

    private fun applyTargetSpecIfStillAiming(
        sessionId: String,
        spec: TargetSpec?,
        localRawText: String?,
    ) {
        if (sessionManager.sessionId != sessionId || sessionManager.state != SessionState.AIMING) {
            Log.i(TAG, "타겟 스펙 도착했지만 세션이 이미 지나감 [$sessionId] — 무시")
            return
        }
        // localRawText is UI-only and can contain a registered private name. The CV spec and
        // capture upload keep only the redacted server-safe form that was sent to the server.
        val serverRawText = localRawText?.let(::serverSafeUtterance) ?: spec?.rawText.orEmpty()
        val effectiveSpec = spec?.let {
            if (it.rawText == serverRawText) it else it.copy(rawText = serverRawText)
        }
        val identityName = if (effectiveSpec?.subjectType == TargetSpec.SubjectType.LANDSCAPE) {
            null
        } else {
            localRawText?.let(::registeredTargetName)
        }
        val appliedGeneration = synchronized(targetIntentLock) {
            if (sessionManager.sessionId != sessionId ||
                sessionManager.state != SessionState.AIMING
            ) return
            currentRawText = serverRawText
            sessionRawText = localRawText ?: effectiveSpec?.rawText.orEmpty()
            // Spec and local identity are one atomic generation. Force advancement even
            // for equal values so an earlier callback can never become current by equality.
            // Reset the calculator before publishing the new generation. A frame can then
            // only carry old-generation/old-state work or new-generation/reset-state work.
            resetTargetDerivedStateLocked()
            val generation = cvProcessor.setTargetIntent(
                spec = effectiveSpec,
                identityName = identityName,
                forceNewGeneration = true,
            )
            targetSpecPending = false
            generation
        }
        synchronized(targetIntentLock) {
            if (!cvProcessor.isCurrentTargetIntentGeneration(appliedGeneration)) return
            val appliedMode = AimingGuidanceModeResolver.resolve(
                spec = effectiveSpec,
                targetSpecPending = false,
                localIdentityName = identityName,
            )
            if (appliedMode == AimingGuidanceMode.LANDSCAPE) {
                // 풍경은 Objects365 bbox로 조준할 단일 피사체가 없다. 이를 LOST로 오인해 경고하지
                // 않고, 한 번만 장면 전체를 확인하도록 안내한다.
                guidanceText = "풍경 모드예요. 장면 전체가 들어오는지 확인해주세요"
                guidanceFeedback.announce(
                    guidanceText,
                    priority = GuidanceFeedback.SpeechPriority.ADJUSTMENT,
                )
            } else if (appliedMode == AimingGuidanceMode.GENERAL_WAITING) {
                guidanceText = GENERAL_CAPTURE_WAITING_GUIDANCE
                guidanceFeedback.announce(
                    guidanceText,
                    priority = GuidanceFeedback.SpeechPriority.ADJUSTMENT,
                )
            }
        }
    }

    /** 등록 이름은 기기 밖으로 보내지 않고, 촬영 의도만 유지되는 일반 토큰으로 치환한다. */
    private fun serverSafeUtterance(rawText: String): String {
        if (!identityReloadGate.isReady) return GENERIC_SERVER_UTTERANCE
        return CloudTextRedactor.redact(rawText, registeredPeople, registeredObjects)
    }

    /**
     * ② CV 결과 수신 지점 (분석 스레드).
     *
     * 여기서 나오는 `output.objectsJson()` 이 ③ 편차 계산 / ⑥ 햅틱·사운드 렌더링의 입력이다.
     * 매 프레임 로그는 너무 많으므로 1초에 한 번만 남긴다.
     * Logcat 필터: `tag:SnapSightCV`
     */
    private fun onCvFrameResult(output: CvFrameOutput) {
        synchronized(targetIntentLock) {
            if (!cvProcessor.isCurrentTargetIntentGeneration(output.targetIntentGeneration)) return
            onCurrentCvFrameResult(output)
        }
    }

    /** Runs only while target generation is current and [targetIntentLock] is held. */
    private fun onCurrentCvFrameResult(output: CvFrameOutput) {
        if (!output.analyzed) {
            val previous = lastPropagationUiAtMs
            if (output.timestampMs >= previous &&
                output.timestampMs - previous < PROPAGATION_UI_INTERVAL_MS
            ) return
            lastPropagationUiAtMs = output.timestampMs
        }
        val freshIds = output.objects.filterNot { it.predicted }.mapTo(HashSet()) { it.trackId }
        // detector keyframe 사이의 predictOnly 출력도 오버레이·canonical freshness 판정까지
        // 전달한다. 다만 셔터 직후 사실 확인용 스냅샷/안정 프레임 수는 실제 관측에서만 갱신한다.
        if (output.analyzed) {
            synchronized(cvSnapshotLock) {
                val freshObjects = output.objects.filterNot { it.predicted }
                if (freshObjects.isNotEmpty()) {
                    trackStableFrames.keys.retainAll(freshIds)
                    freshIds.forEach { trackId ->
                        trackStableFrames[trackId] = (trackStableFrames[trackId] ?: 0) + 1
                    }
                    latestObservedObjects = freshObjects
                    latestObservedAtMs = output.timestampMs
                } else if (latestObservedAtMs <= 0L ||
                    output.timestampMs - latestObservedAtMs > CAPTURE_SNAPSHOT_HOLD_MS
                ) {
                    // 저신뢰 rescue/한 번의 detector miss가 셔터 직전 YOLO 설명 재료를 지우지 않게 한다.
                    // 오래된 장면만 만료시켜 이미 사라진 피사체를 설명하는 일은 막는다.
                    latestObservedObjects = emptyList()
                    latestObservedAtMs = 0L
                    trackStableFrames.clear()
                }
            }
        }
        // 디버그 오버레이: 항상 전체 탐지 객체를 그린다 — 의도 필터로 후보가 0개여도
        // "탐지 자체는 돌고 있는지"를 눈으로 확인할 수 있어야 한다 (2026-08-21 피드백).
        // 의도 기반 선택 결과는 편차 판정(deviation)·개수 판정에만 쓰인다.
        val visible = visibleObjectsForOverlay(output)
        runOnUiThread {
            synchronized(targetIntentLock) {
                if (cvProcessor.isCurrentTargetIntentGeneration(output.targetIntentGeneration)) {
                    cvObjects = output.objects
                    overlayObjects = visible
                    overlayIdentities = output.identities
                }
            }
        }

        // 기능 2: 등록 인물이 화면에 들어오면 세션당 1회 음성으로 알린다 ("민수님이 화면에 있어요").
        // announcedIdentities 는 메인 스레드에서만 만진다 (여기서 넘기고, 세션 시작 시 clear).
        currentIdentities = output.identities
        if (sessionManager.state == SessionState.AIMING && output.identities.isNotEmpty()) {
            val names = output.identities.values.toSet()
            runOnUiThread {
                synchronized(targetIntentLock) {
                    if (cvProcessor.isCurrentTargetIntentGeneration(output.targetIntentGeneration)) {
                        val newNames = names - announcedIdentities
                        if (newNames.isNotEmpty()) {
                            announcedIdentities.addAll(newNames)
                            newNames.forEach { guidanceFeedback.announce("${it}님이 화면에 있어요") }
                        }
                    }
                }
            }
        }

        // ④ 편차 판정 — 파이프라인이 계산한 기하 편차를 계약 형태로 해석. 조준 중에만 의미가 있다.
        val guidanceMode = AimingGuidanceModeResolver.resolve(
            spec = output.targetSpec,
            targetSpecPending = targetSpecPending,
            localIdentityName = output.targetIdentityName,
        )
        val deviation = if (sessionManager.state == SessionState.AIMING &&
            guidanceMode.allowsCompositionGuidance
        ) {
            DeviationJudgment.judge(
                deviation = output.deviation,
                framing = output.targetSpec?.framing ?: TargetSpec.Framing.FULL_BODY,
            )
        } else null
        val readiness = deviation?.let {
            val verdict = guidanceFeedback.processDeviation(it, output.timestampMs)
            // 셔터 게이트 근거 갱신 — "피사체 없이 찍기 직전" 판정에 쓴다
            lastAimingVerdictAtMs = output.timestampMs
            lastAimingSubjectDetected = it.subjectDetected
            verdict
        }

        // 세션 배율: 0.6배(찾기용)로 시작 → 구도가 5개 관측 프레임 안정되면 1.0배로 복귀. 면적 기반 자동 줌인은
        // AutoZoomController.ZOOM_IN_ENABLED 로 꺼져 있다 (깊이 판단 부정확 — 후처리 붙인 뒤 재검토).
        if (sessionManager.state == SessionState.AIMING) {
            // 예측 프레임까지 streak에 넣으면 detector keyframe 사이 PREDICTED가 매번 정렬 횟수를
            // 0으로 만들어 1.0배 복귀가 영원히 일어나지 않는다. 줌 상태는 실제 관측으로만 갱신한다.
            if (output.analyzed && guidanceMode.allowsAutoZoom) {
                val ready = readiness?.ready == true
                val aligned = readiness?.blockers?.none {
                    it in setOf(
                        ReadinessBlocker.SUBJECT_NOT_DETECTED,
                        ReadinessBlocker.HORIZONTAL,
                        ReadinessBlocker.VERTICAL,
                        ReadinessBlocker.VISIBILITY,
                        ReadinessBlocker.PREDICTED,
                        ReadinessBlocker.HELD,
                        ReadinessBlocker.STALE,
                    )
                } == true
                val dev = output.deviation
                if (dev != null && readiness != null) {
                    autoZoom.onTargetArea(
                        areaRatio = dev.areaRatio,
                        targetArea = readiness.goal.targetAreaRatio,
                        aligned = aligned,
                        hold = ready,
                    )
                } else {
                    autoZoom.onNoTarget() // 광각에서 계속 못 찾으면 1.0배 복귀 (탐색 실패 폴백)
                }
            }

            // 시안 S3 하단 안내 카드 문구 (#80) — 음성·햅틱(⑥)과 같은 판정을 화면 텍스트로도 보여준다.
            // 셀카 모드에서는 구도가 맞아도 시선이 안 맞으면 그 사유를 대신 보여준다.
            val gazeBlock = if (guidanceMode.allowsCompositionGuidance) {
                selfieGaze.readyBlockReason()
            } else {
                null
            }
            val text = when (guidanceMode) {
                AimingGuidanceMode.LANDSCAPE ->
                    "풍경 모드예요. 장면 전체가 들어오는지 확인해주세요"
                AimingGuidanceMode.RESOLVING -> "촬영 요청을 확인하고 있어요"
                AimingGuidanceMode.GENERAL_WAITING -> GENERAL_CAPTURE_WAITING_GUIDANCE
                AimingGuidanceMode.COMPOSITION -> {
                    val proposed = compositionGuidanceText(deviation, readiness, gazeBlock)
                    guidanceTextStabilizer.stabilize(
                        proposedText = proposed,
                        subjectDetected = deviation?.subjectDetected == true && readiness != null,
                        blockers = readiness?.blockers.orEmpty(),
                    )
                }
            }
            // 셀카 모드: 시선이 카메라로 돌아온 순간을 한 번 알려준다 ("카메라를 보고 있어요")
            val gazeNow = if (isSelfieMode && guidanceMode.allowsCompositionGuidance) {
                selfieGaze.state
            } else {
                null
            }
            runOnUiThread {
                synchronized(targetIntentLock) {
                    if (cvProcessor.isCurrentTargetIntentGeneration(output.targetIntentGeneration)) {
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
            }
        }

        // 아래 성능 통계는 detector/임베딩을 실제 실행한 keyframe만 센다. predictOnly를 섞으면
        // detector FPS와 지연이 카메라 FPS처럼 부풀어 발열 튜닝 근거가 틀어진다.
        if (!output.analyzed) return

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
                Log.d(
                    CV_TAG,
                    "편차: x=%+.3f y=%s size=%+.3f ready=%b blockers=%s stable=%dms".format(
                        it.xDeviation,
                        it.yDeviation?.let { y -> "%+.3f".format(y) } ?: "n/a",
                        it.sizeDeviation,
                        readiness?.ready == true,
                        readiness?.blockers.orEmpty(),
                        readiness?.stableForMs ?: 0L,
                    ),
                )
            } else {
                Log.d(CV_TAG, "편차: 피사체 없음 (LOST 후보)")
            }
        }
    }

    /**
     * 화면 카드도 음성 정책이 사용한 동일 [ReadinessVerdict]만 해석한다. 가장 크게 벗어난 축
     * 하나만 보여 주고, 위험한 "뒤로 이동" 요구는 만들지 않는다.
     */
    private fun compositionGuidanceText(
        result: DeviationResult?,
        verdict: ReadinessVerdict?,
        gazeBlockReason: String?,
    ): String {
        if (result?.subjectDetected != true || verdict == null) return "피사체를 찾고 있어요"
        if ((verdict.ready || verdict.candidateReady) && gazeBlockReason != null) {
            return "구도는 좋아요. $gazeBlockReason"
        }
        if (verdict.ready) return "지금이에요! 화면을 두 번 탭하세요"

        if (verdict.blockers.any {
                it == ReadinessBlocker.PREDICTED || it == ReadinessBlocker.HELD ||
                    it == ReadinessBlocker.STALE
            }) {
            return "구도를 다시 확인하고 있어요"
        }

        val goal = verdict.goal
        val directions = buildList<Pair<Float, String>> {
            if (ReadinessBlocker.HORIZONTAL in verdict.blockers) {
                val x = result.xDeviation ?: 0f
                add(
                    kotlin.math.abs(x) / goal.maxAbsXDeviation.coerceAtLeast(1e-6f) to
                        if (x < 0f) "카메라를 조금 왼쪽으로 이동해주세요"
                        else "카메라를 조금 오른쪽으로 이동해주세요"
                )
            }
            if (ReadinessBlocker.VERTICAL in verdict.blockers) {
                val y = result.yDeviation ?: 0f
                add(
                    kotlin.math.abs(y) / goal.maxAbsYDeviation.coerceAtLeast(1e-6f) to
                        if (y < 0f) "카메라를 조금 위로 이동해주세요"
                        else "카메라를 조금 아래로 이동해주세요"
                )
            }
            val size = result.sizeDeviation
            if (ReadinessBlocker.SIZE in verdict.blockers && size != null && size < 0f &&
                !autoZoom.canResolveSmallTarget
            ) {
                add(
                    kotlin.math.abs(size) / goal.maxAbsAreaDeviation.coerceAtLeast(1e-6f) to
                        "조금 더 가까이 비춰주세요"
                )
            }
        }
        directions.maxByOrNull { it.first }?.let { return it.second }

        return when {
            ReadinessBlocker.VISIBILITY in verdict.blockers ->
                "피사체 전체가 화면 안에 들어오게 비춰주세요"
            ReadinessBlocker.SIZE in verdict.blockers && (result.sizeDeviation ?: 0f) < 0f &&
                autoZoom.canResolveSmallTarget -> "구도를 자동으로 맞추는 중이에요"
            ReadinessBlocker.SIZE in verdict.blockers -> "피사체가 화면에 너무 크게 잡혀 있어요"
            ReadinessBlocker.UNSTABLE in verdict.blockers -> "좋아요, 그대로 유지해주세요"
            else -> "구도를 확인하고 있어요"
        }
    }

    /** 대표 컷과 후보 프레임이 모두 모이면 백엔드로 업로드 (⑤→④). */
    private fun maybeUploadFrames(sessionId: String) {
        val pending = pendingCaptureUploads[sessionId] ?: return
        val representative = pending.representative ?: return
        val candidates = pending.candidates ?: return
        pendingCaptureUploads.remove(sessionId)

        if (!pending.serverAiEnabled) {
            Log.i(TAG, "서버 AI 사진 설명 비활성 — 사진 업로드 생략 [$sessionId]")
            return
        }

        frameUploader.uploadCaptureFrames(
            sessionId = sessionId,
            rawText = pending.rawText,
            representativeJpegProvider = {
                contentResolver.openInputStream(representative)?.use { it.readBytes() }
                    ?: throw IllegalStateException("대표 컷을 읽을 수 없음: $representative")
            },
            candidates = candidates,
            // 검색용 메타데이터 재료 (기능 3-B)
            customLabels = pending.customLabels,
            detectedObjects = pending.detectedObjects,
            // 셔터 순간 식별된 대상의 불투명 참조(+위치). 실제 이름은 기기 안 매핑에만 남는다.
            knownSubjects = pending.knownSubjects,
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
                    if (!isCurrentCapture(sessionId, pending.localGeneration)) return
                    if (result.sessionId != sessionId) {
                        Log.w(TAG, "업로드 응답 session 불일치: $sessionId / ${result.sessionId}")
                        if (isCurrentVisibleResult(sessionId)) {
                            lastResultDescriptionStatus =
                                "서버 응답을 확인하지 못해 상세 설명을 가져오지 못했어요."
                        }
                        return
                    }
                    activeServerCaptureRevision = result.captureRevision
                    Log.i(TAG, "업로드 완료 [${result.sessionId}] 후보 ${result.receivedCandidateCount}장")
                    pollCaptureUnderstanding(
                        result.sessionId,
                        result.captureRevision,
                        pending.customLabels.toSet(),
                    )
                }

                override fun onFailure(error: Throwable) {
                    if (!isCurrentCapture(sessionId, pending.localGeneration)) return
                    // 업로드 실패는 촬영 성공과 무관 — 사진은 이미 기기에 저장됨 (재시도는 추후)
                    Log.w(TAG, "업로드 실패 (사진은 기기에 저장됨)", error)
                    if (isCurrentVisibleResult(sessionId)) {
                        lastResultDescriptionStatus =
                            "서버에 사진을 보내지 못했어요. 서버 주소와 연결을 확인해 주세요."
                    }
                }
            },
        )
    }

    /**
     * 셔터 순간의 등록 인물·사물을 세션 한정 불투명 참조·종류·위치로 만든다.
     */
    private fun knownSubjectsAtShutter(
        sessionId: String,
        objects: List<TrackedObject>,
        identities: Map<Int, String>,
        peopleNames: Set<String>,
    ): List<FrameUploader.KnownSubject> {
        val byName = LinkedHashMap<String, FrameUploader.KnownSubject>()
        val localNames = LinkedHashMap<String, LocalSubjectDisplay>()
        for (obj in objects) {
            val name = identities[obj.trackId] ?: continue
            if (name in byName) continue
            val subjectRef = "local_track_${obj.trackId}"
            byName[name] = FrameUploader.KnownSubject(
                subjectRef = subjectRef,
                kind = if (name in peopleNames) "person" else "object",
                bbox = obj.bbox,
            )
            localNames[subjectRef] = LocalSubjectDisplay(name, name in peopleNames)
        }
        subjectNamesBySession[sessionId] = localNames
        while (subjectNamesBySession.size > MAX_LOCAL_SUBJECT_SESSIONS) {
            subjectNamesBySession.remove(subjectNamesBySession.keys.first())
        }
        return byName.values.toList()
    }

    private fun localizeSubjectRefs(sessionId: String, text: String?): String? {
        var localized = text ?: return null
        subjectNamesBySession[sessionId].orEmpty().forEach { (ref, subject) ->
            val display = if (subject.isPerson) "${subject.name}님" else subject.name
            localized = localized.replace(ref, display)
        }
        return localized
    }

    /**
     * 촬영 결과 화면(S4)을 띄운다 — 대표 컷을 백그라운드에서 디코딩해 표시.
     * 디코딩 실패해도 화면은 띄운다 (사진 없이 요약·설명만이라도 들려주는 게 낫다).
     */
    private fun showResultScreen(uri: Uri) {
        lastResultDescription = null
        lastResultDescriptionStatus = if (settingsUiState.serverAiDescriptionEnabled) {
            "서버 AI 상세 설명을 준비하고 있어요."
        } else {
            "서버 AI 사진 설명이 꺼져 있어요."
        }
        lastResultAutoLabels = emptyList()
        lastResultHasDetailedDescription = false
        showResult = true
        Thread({
            val bitmap = try {
                decodeUprightBitmap(uri)
            } catch (t: Throwable) {
                Log.w(TAG, "결과 사진 디코딩 실패: $uri", t)
                null
            }
            runOnUiThread { resultPhoto = bitmap }
        }, "SnapSight-ResultDecode").start()
    }

    /**
     * 저장된 JPEG 를 EXIF 회전까지 적용해 똑바로 선 비트맵으로 디코딩한다 — CameraX 는 픽셀을 센서
     * 방향(가로)으로 두고 EXIF Orientation 으로만 회전을 기록하는데, BitmapFactory 는 이를 무시해
     * 세로 사진이 누워 보였다 (2026-08-23).
     */
    private fun decodeUprightBitmap(uri: Uri): Bitmap? {
        val raw = contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 2 })
        } ?: return null
        val orientation = contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return raw
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            .also { if (it !== raw) raw.recycle() }
    }

    /**
     * 촬영 직후 안내·결과 화면 헤드라인 — 즉시 요약에, 요청한 피사체(예: 노트북)가
     * 셔터 순간 화면에 없었다면 그 사실을 덧붙인다 (막지 않고 알리기만 — 2026-08-21 결정).
     */
    private fun captureHeadline(): String {
        val summary = instantCaptureSummary()
        val missing = shutterMissingTarget ?: return summary
        return "$summary 다만 요청하신 $missing${CaptureAnnouncementBuilder.topicParticle(missing)} 화면에서 찾지 못했어요."
    }

    /**
     * 셔터 순간의 온디바이스 탐지 결과로 즉시 상황을 요약한다 (#80) —
     * 서버 설명(수 초)보다 먼저 "무엇이 찍혔는지"를 들려주는 용도.
     */
    private fun instantCaptureSummary(): String {
        val text = CaptureAnnouncementBuilder.build(CaptureAnnouncementBuilder.Input(
            objects = shutterObjects,
            identities = shutterIdentityMap,
            registeredPeople = registeredPeople.toSet(),
            stableFrames = shutterStableFrames,
            sourceAgeMs = shutterSourceAgeMs,
        ))
        return text
    }

    private fun checkOrRequestPermissions() {
        val required = requiredPermissions()
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

    private fun hasRequiredPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 28) {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private fun galleryReadPermission(): String? = when {
        Build.VERSION.SDK_INT >= 33 -> Manifest.permission.READ_MEDIA_IMAGES
        Build.VERSION.SDK_INT >= 29 -> Manifest.permission.READ_EXTERNAL_STORAGE
        else -> null
    }

    // 볼륨 버튼 매핑 제거 (#84 4-2 3단계) — 탭 문법이 주 조작이 되면서 볼륨 키는
    // 시스템 볼륨(사운드·TTS 크기 조절)으로 환원했다. 진행 동작은 sessionManager.onVolumePressed()
    // 이름 그대로지만 이제 탭 제스처만 호출한다.

    override fun onDestroy() {
        super.onDestroy()
        unregisterThermalListener()
        sessionManager.cancel()
        cancelCaptureNetworkWork()
        faceEmbedder.close()
        objectEmbedder.close()
        identityReloadClosed = true
        identityReloadExecutor.shutdownNow()
        // 분리 시 onDetached() 가 불려 TFLite 인터프리터가 해제된다.
        cameraController.release()
        guidanceFeedback.release()
    }

    private companion object {
        const val WELCOME_TEXT = "스냅사이트입니다. 화면을 두 번 탭해 시작하세요"
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
        /**
         * 등록 스캔 길이 — 10초 (2026-08-22). 샘플 수보다 각도 다양성이 중요해, 짧게 모든 프레임을
         * 받는 대신 길게 잡고 [ENROLL_SAMPLE_INTERVAL_MS] 간격으로만 고른다 (≈40장). 시작·중간·종료는
         * earcon 으로 알린다.
         */
        private const val ENROLL_SCAN_MS = 10_000L

        /** 등록 스캔 중 추론(=샘플 수집) 간격 — 250ms = 4Hz. 열 적응의 영향을 받지 않는다. */
        private const val ENROLL_SAMPLE_INTERVAL_MS = 250L

        /**
         * CV 추론 최소 간격 — 150ms ≈ 6.7Hz (발열 대책 P1). 5Hz(200ms)는 박스 이동이 커져 track 이
         * 자주 끊겨(실기기 로그 2026-08-22) 조금 올렸다. 열 적응 배수가 곱해진다.
         */
        private const val ANALYSIS_INTERVAL_MS = 150L
        private const val PROPAGATION_UI_INTERVAL_MS = 66L
        private const val SHUTTER_IDENTITY_MAX_AGE_MS = 450L
        private const val CAPTURE_SNAPSHOT_HOLD_MS = 1_500L
        private const val GENERAL_CAPTURE_WAITING_GUIDANCE =
            "일반 촬영 모드예요. 화면을 두 번 탭하면 촬영합니다"
        private const val GENERIC_SERVER_UTTERANCE = "사진을 찍어줘"

        /** 타겟 편차 hold — 추론 간격의 4배 (관측 2~3회 공백까지 직전 편차 유지). */
        private const val TARGET_HOLD_MS = ANALYSIS_INTERVAL_MS * 4

        /** detector 출력·tracker 매칭의 최소 confidence — 새 track 생성(0.25)과는 별개의 하한. */
        private const val MATCH_MIN_CONFIDENCE = 0.05f

        /** 미확인 track 의 신원 재시도 간격. 열 제한에 따라 FPS가 바뀌어도 의미가 일정하다. */
        private const val IDENTIFY_ATTEMPT_INTERVAL_MS = 1_000L
        private const val MAX_LOCAL_SUBJECT_SESSIONS = 20

        /** 얼굴 매칭 임계값 — 진짜(0.65~0.75)와 타인 최고점(≈0.5) 사이. 실측 분포가 바뀌면 조정. */
        private const val FACE_MATCH_THRESHOLD = 0.58f

        // 셔터 게이트가 신뢰하는 CV 판정의 최대 나이 — CV 가 멈춰 있으면 막지 않는다 (fail-open)
        private const val VERDICT_FRESH_MS = 1_500L

        /** 홈 뒤로가기 2회 종료 확인 창 (#84) — 1회차 예고 후 이 시간 안에 다시 누르면 종료. */
        private const val HOME_EXIT_CONFIRM_MS = 2_000L
    }
}
