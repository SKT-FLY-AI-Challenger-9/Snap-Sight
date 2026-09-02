// 이 파일: 인물 프레이밍(2026-08-28)의 "머리·발 좌표" 공급원. YOLO person bbox 는 몸통
// 사각형일 뿐 실제 머리·발 위치가 아니라서, ML Kit Pose Detection 으로 코·발목 랜드마크를
// 뽑아 정규화(0..1, 위=0/아래=1) 좌표로 넘긴다(사용자 요청 2026-08-28: "ML kit 기준으로").
package com.example.snap_sight.face

import android.graphics.Rect
import android.util.Log
import com.example.snap_sight.cv.CvFrame
import com.example.snap_sight.cv.FrameResult
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 스레딩: [onFrame] 은 CV 분석 스레드에서 동기 호출된다([SelfieGazeMonitor]와 같은 규약).
 * 결과는 volatile 스냅샷([headY]/[footY]/[lastUpdatedAtMs])으로 노출해 이후 메인 판정
 * 루프(MainActivity)가 안전하게 읽는다.
 *
 * 비용: [enabled] 가 인물 세션일 때만 true 이고, 그때도 [analysisIntervalMs] 간격으로만
 * 1회 돈다 — 실시간 스트림 모드([PoseDetectorOptions.STREAM_MODE])라 프레임 간 추적을
 * 재사용해 매 호출이 처음부터 다시 찾는 것보다 싸다.
 *
 * 검출은 전체 프레임이 아니라 [PADDING_RATIO] 여백을 더한 사람 bbox 영역만 잘라서 돌린다
 * (사용자 요청 2026-08-28 — "ML kit가 얼굴은 거의 인식을 못하더라"). 전체 프레임에는 배경이
 * 대부분이라 ML Kit 내부 리사이즈 후 사람이 차지하는 실제 픽셀이 너무 작아졌던 게 원인 —
 * [FaceIdentifier]/[ObjectIdentifier]가 이미 같은 이유로 track bbox 를 잘라서 쓴다.
 */
class PersonFramingPoseTracker(
    private val analysisIntervalMs: Long = 400L,
) {
    /** 인물 세션일 때만 true. 메인 스레드에서 토글, 분석 스레드에서 읽음. */
    @Volatile
    var enabled: Boolean = false

    /** 코(머리 대용) 의 프레임 기준 정규화 y — 위=0, 아래=1. 미검출/저신뢰면 null. */
    @Volatile
    var headY: Float? = null
        private set

    /** 좌우 발목 평균(발 대용) 의 프레임 기준 정규화 y. 미검출/저신뢰면 null. */
    @Volatile
    var footY: Float? = null
        private set

    /**
     * 좌우 골반 평균의 프레임 기준 정규화 y. 미검출/저신뢰(프레임 밖 추정 포함)면 null —
     * 상반신 구도(2026-08-31 개편)의 줌 종료 판정("골반이 하단 근처 또는 프레임 밖")에 쓴다.
     */
    @Volatile
    var hipY: Float? = null
        private set

    @Volatile
    var lastUpdatedAtMs: Long = 0L
        private set

    private var nextAnalysisAtMs = 0L

    private val detector by lazy {
        PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )
    }

    fun reset() {
        headY = null
        footY = null
        hipY = null
        lastUpdatedAtMs = 0L
        nextAnalysisAtMs = 0L
    }

    /** 현재 판정에 쓸 만큼 신선한 좌표인지 — 오래됐으면 소비자는 null 취급해야 한다. */
    fun isFresh(nowMs: Long): Boolean = nowMs - lastUpdatedAtMs <= FRESH_MS

    /** 분석 프레임마다 호출 (CV 분석 스레드). 꺼져 있거나 사람 track 이 없으면 즉시 리턴. */
    fun onFrame(frame: CvFrame, frameResult: FrameResult) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now < nextAnalysisAtMs) return
        nextAnalysisAtMs = now + analysisIntervalMs

        // 가장 큰 사람 bbox 하나만 본다 — 인물 세션은 대상이 한 명이라는 전제(3분할 크롭과 동일).
        val personBox = frameResult.objects
            .filter { it.label.trim().equals("person", ignoreCase = true) && !it.predicted }
            .maxByOrNull { it.bbox.width * it.bbox.height }
        if (personBox == null) {
            headY = null
            footY = null
            hipY = null
            return
        }

        val region = paddedRegion(
            xMin = personBox.bbox.xMin, yMin = personBox.bbox.yMin,
            xMax = personBox.bbox.xMax, yMax = personBox.bbox.yMax,
            frameWidth = frame.width, frameHeight = frame.height,
        )
        val bitmap = frame.toBitmap(region) ?: return
        try {
            val pose = try {
                Tasks.await(
                    detector.process(InputImage.fromBitmap(bitmap, 0)),
                    DETECT_TIMEOUT_MS, TimeUnit.MILLISECONDS,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "인물 프레이밍 자세 검출 실패 — 이 프레임은 건너뜀", t)
                return
            }
            headY = landmarkY(pose, PoseLandmark.NOSE, region.top, frame.height)
            footY = averageAnkleY(pose, region.top, frame.height)
            hipY = averagePairY(pose, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP, region.top, frame.height)
            lastUpdatedAtMs = now
        } finally {
            bitmap.recycle()
        }
    }

    /** 사람 bbox(정규화 0..1) 에 [PADDING_RATIO] 여백을 더한 픽셀 영역 — 프레임 밖으로 안 나가게 클램프. */
    private fun paddedRegion(
        xMin: Float, yMin: Float, xMax: Float, yMax: Float,
        frameWidth: Int, frameHeight: Int,
    ): Rect {
        val padX = (xMax - xMin) * PADDING_RATIO
        val padY = (yMax - yMin) * PADDING_RATIO
        val left = ((xMin - padX) * frameWidth).roundToInt().coerceIn(0, frameWidth - 1)
        val top = ((yMin - padY) * frameHeight).roundToInt().coerceIn(0, frameHeight - 1)
        val right = ((xMax + padX) * frameWidth).roundToInt().coerceIn(left + 1, frameWidth)
        val bottom = ((yMax + padY) * frameHeight).roundToInt().coerceIn(top + 1, frameHeight)
        return Rect(left, top, right, bottom)
    }

    /** 크롭 영역 안 랜드마크 픽셀 y 를 [regionTop] 만큼 되돌려 전체 프레임 기준 정규화 y 로 바꾼다. */
    private fun landmarkY(pose: Pose, type: Int, regionTop: Int, frameHeight: Int): Float? {
        val landmark = pose.getPoseLandmark(type) ?: return null
        if (landmark.inFrameLikelihood < MIN_LIKELIHOOD) return null
        val fullFramePixelY = regionTop + landmark.position.y
        return (fullFramePixelY / frameHeight).coerceIn(0f, 1f)
    }

    private fun averageAnkleY(pose: Pose, regionTop: Int, frameHeight: Int): Float? =
        averagePairY(pose, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE, regionTop, frameHeight)

    private fun averagePairY(pose: Pose, leftType: Int, rightType: Int, regionTop: Int, frameHeight: Int): Float? {
        val ys = listOfNotNull(
            landmarkY(pose, leftType, regionTop, frameHeight),
            landmarkY(pose, rightType, regionTop, frameHeight),
        )
        if (ys.isEmpty()) return null
        return ys.sum() / ys.size
    }

    private companion object {
        const val TAG = "PersonFramingPose"
        const val DETECT_TIMEOUT_MS = 1_000L
        const val FRESH_MS = 1_200L

        /** 이보다 신뢰도가 낮은 랜드마크는 화면 밖 추정치로 보고 버린다. */
        const val MIN_LIKELIHOOD = 0.3f

        /** 사람 bbox 사방에 더하는 여백 비율 — 머리 끝·발끝이 bbox 밖으로 살짝 나가는 것 대비. */
        const val PADDING_RATIO = 0.25f
    }
}
