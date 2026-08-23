// 이 파일: 셀카 모드에서 매 분석 프레임의 얼굴을 보고 "카메라를 보고 있는지"를 추적하는 모니터.
// 3단 판정: 머리 방향(Euler) → 눈 뜸(분류) → 눈 윤곽(컨투어) 안 동공 위치(PupilFinder) —
// 얼굴은 정면인데 눈동자만 옆을 보는 경우까지 잡는다 (2026-08-21 피드백).
// 결과는 조준 안내(⑥)의 READY 보류 사유와 시선 안내 문구로 쓰인다.
package com.example.snap_sight.face

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.example.snap_sight.cv.CvFrame
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit

/**
 * 스레딩: [onFrame] 은 CV 분석 스레드에서 동기 호출된다 (CvFrame 버퍼 재사용 규약 준수 —
 * 필요한 변환은 호출 안에서 끝낸다). 상태는 volatile 스냅샷으로 노출한다.
 *
 * 비용: [enabled] 가 아니면 완전 no-op. 켜져 있으면 [analysisIntervalMs] 간격으로
 * 1회 얼굴 검출(분류+컨투어)을 돌린다 — 컨투어 모드는 가장 두드러진 얼굴 1개만 처리하므로
 * 셀카 용도에 맞고, 동공 탐색은 눈 영역(수십 픽셀)만 봐서 비용이 미미하다.
 */
class SelfieGazeMonitor(
    private val analysisIntervalMs: Long = 500L,
) {

    enum class GazeState {
        /** 고개도 정면, 눈도 떠 있고, 동공도 중앙 — 카메라를 보고 있다. */
        LOOKING,
        /** 고개가 카메라를 향해 있지 않다. */
        HEAD_TURNED,
        /** 눈을 감고 있다. */
        EYES_CLOSED,
        /** 고개는 정면인데 눈동자가 다른 곳을 본다. */
        EYES_AWAY,
        /** 프레임에서 얼굴을 찾지 못함. */
        NO_FACE,
    }

    /** 셀카 모드일 때만 true. 메인 스레드에서 토글, 분석 스레드에서 읽음. */
    @Volatile
    var enabled: Boolean = false

    @Volatile
    var state: GazeState = GazeState.NO_FACE
        private set

    /** 마지막 판정 시각 — 오래됐으면 소비자는 판정을 신뢰하지 않는다 (fail-open). */
    @Volatile
    var lastVerdictAtMs: Long = 0L
        private set

    private var nextAnalysisAtMs = 0L

    // 분류(눈 뜸) + 컨투어(눈 윤곽 → 동공 탐색)까지 켠 검출기 —
    // FaceIdentifier(FAST·부가정보 없음)와 요구 옵션이 달라 분리한다
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .build()
        )
    }

    fun reset() {
        state = GazeState.NO_FACE
        lastVerdictAtMs = 0L
        nextAnalysisAtMs = 0L
    }

    /** 분석 프레임마다 호출 (CV 분석 스레드). 꺼져 있으면 즉시 리턴. */
    fun onFrame(frame: CvFrame) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now < nextAnalysisAtMs) return
        nextAnalysisAtMs = now + analysisIntervalMs

        val bitmap = frame.toBitmap() ?: return
        try {
            val face = try {
                Tasks.await(
                    detector.process(InputImage.fromBitmap(bitmap, 0)),
                    DETECT_TIMEOUT_MS, TimeUnit.MILLISECONDS,
                ).maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            } catch (t: Throwable) {
                Log.w(TAG, "셀카 시선 검출 실패 — 이 프레임은 건너뜀", t)
                return
            }

            state = if (face == null) {
                GazeState.NO_FACE
            } else {
                when (
                    GazeJudge.judge(
                        eulerYawDegrees = face.headEulerAngleY,
                        eulerPitchDegrees = face.headEulerAngleX,
                        leftEyeOpenProbability = face.leftEyeOpenProbability,
                        rightEyeOpenProbability = face.rightEyeOpenProbability,
                        pupilHorizontalRatio = averagePupilRatio(bitmap, face),
                    )
                ) {
                    GazeJudge.Verdict.LOOKING -> GazeState.LOOKING
                    GazeJudge.Verdict.HEAD_TURNED -> GazeState.HEAD_TURNED
                    GazeJudge.Verdict.EYES_CLOSED -> GazeState.EYES_CLOSED
                    GazeJudge.Verdict.EYES_AWAY -> GazeState.EYES_AWAY
                }
            }
            lastVerdictAtMs = now
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 조준 READY("지금 촬영하세요")를 보류해야 하면 그 사유 문구를, 아니면 null 을 반환한다.
     * 판정이 오래됐으면(멈춤·미지원) null — 시선 기능 때문에 촬영 안내가 인질이 되면 안 된다.
     */
    fun readyBlockReason(nowMs: Long = System.currentTimeMillis()): String? {
        if (!enabled) return null
        if (nowMs - lastVerdictAtMs > VERDICT_FRESH_MS) return null
        return when (state) {
            GazeState.LOOKING -> null
            GazeState.HEAD_TURNED -> "카메라 쪽으로 고개를 돌려 주세요"
            GazeState.EYES_CLOSED -> "눈을 뜨고 카메라를 봐 주세요"
            GazeState.EYES_AWAY -> "눈이 다른 곳을 보고 있어요. 카메라를 봐 주세요"
            GazeState.NO_FACE -> "얼굴이 화면에 잘 안 보여요"
        }
    }

    /**
     * 양쪽 눈 윤곽 안에서 동공의 가로 위치(0..1)를 찾아 평균한다.
     * 한쪽만 찾아지면 그 값, 둘 다 실패(안경 반사·저조도 등)면 null — 머리 방향만으로 판정.
     */
    private fun averagePupilRatio(bitmap: Bitmap, face: Face): Float? {
        val ratios = listOfNotNull(
            pupilRatioInEye(bitmap, face.getContour(FaceContour.LEFT_EYE)?.points),
            pupilRatioInEye(bitmap, face.getContour(FaceContour.RIGHT_EYE)?.points),
        )
        if (ratios.isEmpty()) return null
        return ratios.sum() / ratios.size
    }

    /** 눈 윤곽 점들의 bounding box 를 잘라 [PupilFinder] 로 동공을 찾고 가로 비율을 계산한다. */
    private fun pupilRatioInEye(bitmap: Bitmap, contourPoints: List<PointF>?): Float? {
        if (contourPoints.isNullOrEmpty()) return null
        val minX = contourPoints.minOf { it.x }.toInt().coerceIn(0, bitmap.width - 1)
        val maxX = contourPoints.maxOf { it.x }.toInt().coerceIn(0, bitmap.width)
        val minY = contourPoints.minOf { it.y }.toInt().coerceIn(0, bitmap.height - 1)
        val maxY = contourPoints.maxOf { it.y }.toInt().coerceIn(0, bitmap.height)
        val width = maxX - minX
        val height = maxY - minY
        if (width < MIN_EYE_PX || height < MIN_EYE_PX / 2) return null

        return try {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, minX, minY, width, height)
            val pupil = PupilFinder.find(pixels, width, height) ?: return null
            (pupil.centerX / width).coerceIn(0f, 1f)
        } catch (t: Throwable) {
            Log.w(TAG, "동공 탐색 실패", t)
            null
        }
    }

    private companion object {
        const val TAG = "SelfieGazeMonitor"
        const val DETECT_TIMEOUT_MS = 1_000L
        const val VERDICT_FRESH_MS = 1_500L

        /** 눈 영역이 이보다 작으면 동공 판정을 건너뛴다 (해상도 부족 — 오판보다 침묵). */
        const val MIN_EYE_PX = 16
    }
}
