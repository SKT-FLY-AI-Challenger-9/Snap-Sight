// 이 파일: 인물 프레이밍(2026-08-28)의 "머리·발 좌표" 공급원. YOLO person bbox 는 몸통
// 사각형일 뿐 실제 머리·발 위치가 아니라서, ML Kit Pose Detection 으로 코·발목 랜드마크를
// 뽑아 정규화(0..1, 위=0/아래=1) 좌표로 넘긴다(사용자 요청 2026-08-28: "ML kit 기준으로").
package com.example.snap_sight.face

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

/**
 * 스레딩: [onFrame] 은 CV 분석 스레드에서 동기 호출된다([SelfieGazeMonitor]와 같은 규약).
 * 결과는 volatile 스냅샷([headY]/[footY]/[lastUpdatedAtMs])으로 노출해 이후 메인 판정
 * 루프(MainActivity)가 안전하게 읽는다.
 *
 * 비용: [enabled] 가 인물 세션일 때만 true 이고, 그때도 [analysisIntervalMs] 간격으로만
 * 1회 돈다 — 실시간 스트림 모드([PoseDetectorOptions.STREAM_MODE])라 프레임 간 추적을
 * 재사용해 매 호출이 처음부터 다시 찾는 것보다 싸다.
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
            return
        }

        val bitmap = frame.toBitmap() ?: return
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
            headY = landmarkY(pose, PoseLandmark.NOSE, bitmap.height)
            footY = averageAnkleY(pose, bitmap.height)
            lastUpdatedAtMs = now
        } finally {
            bitmap.recycle()
        }
    }

    private fun landmarkY(pose: Pose, type: Int, bitmapHeight: Int): Float? {
        val landmark = pose.getPoseLandmark(type) ?: return null
        if (landmark.inFrameLikelihood < MIN_LIKELIHOOD) return null
        return (landmark.position.y / bitmapHeight).coerceIn(0f, 1f)
    }

    private fun averageAnkleY(pose: Pose, bitmapHeight: Int): Float? {
        val ys = listOfNotNull(
            landmarkY(pose, PoseLandmark.LEFT_ANKLE, bitmapHeight),
            landmarkY(pose, PoseLandmark.RIGHT_ANKLE, bitmapHeight),
        )
        if (ys.isEmpty()) return null
        return ys.sum() / ys.size
    }

    private companion object {
        const val TAG = "PersonFramingPose"
        const val DETECT_TIMEOUT_MS = 1_000L
        const val FRESH_MS = 1_200L

        /** 이보다 신뢰도가 낮은 랜드마크는 화면 밖 추정치로 보고 버린다. */
        const val MIN_LIKELIHOOD = 0.5f
    }
}
