// 이 파일: 업로드 후보 프레임의 눈감음 의심도 계산 (2026-08-23) — ML Kit 얼굴 분류의
// eyeOpenProbability 를 재활용한다 (셀카 시선 판정 GazeJudge 와 같은 재료).
// 서버 MLLM 비교의 tie-breaker 계약(candidate_scores.eyes_closed_score)은 이미 있었고,
// 이 파일이 그 값의 첫 생산자다. 업로드 백그라운드 스레드에서만 호출할 것 (Tasks.await 블로킹).
package com.example.snap_sight.camera

import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class EyesClosedScorer {

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
        )
    }

    /**
     * 후보 JPEG 1장의 눈감음 의심도 (0.0=눈 뜸 ~ 1.0=감음, 백엔드 계약과 동일 방향).
     * 얼굴이 없거나 분류값이 없거나 실패하면 null — 이 후보는 점수 없이 전송돼
     * MLLM 프롬프트에서 해당 항목이 생략된다 (억지 0점보다 정직하다).
     *
     * @param rotationDegrees 프레임의 정방향 회전값 — 옆으로 누운 이미지에선 얼굴 검출이
     *        잘 안 되므로 ML Kit 에 그대로 전달해 바로 세워 분석한다.
     */
    fun score(jpeg: ByteArray, rotationDegrees: Int): Float? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(
                jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample },
            )
            if (bitmap == null) {
                null
            } else {
                val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, rotationDegrees)))
                bitmap.recycle()
                combine(faces.map { it.leftEyeOpenProbability to it.rightEyeOpenProbability })
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "눈감음 점수 계산 실패 — 이 후보는 점수 없이 전송", t)
        null
    }

    fun close() {
        runCatching { detector.close() }
    }

    companion object {
        private const val TAG = "EyesClosedScorer"

        /**
         * 분석용 축소 목표 너비 — FrameScorer(160)보다 크게 잡는다. 블러 분산과 달리
         * 눈 분류에는 눈 영역 해상도가 필요하다. 후보 6장 × 수십 ms 수준이라 업로드
         * 스레드에서 감당 가능.
         */
        private const val TARGET_WIDTH = 480

        /**
         * 얼굴별 (왼눈, 오른눈) 열림 확률 → 사진 전체 눈감음 의심도. 순수 계산부 (JVM 테스트 대상).
         *  - 얼굴별 열림 = 두 눈 중 최댓값 — [com.example.snap_sight.face.GazeJudge] 와 같은 규약
         *    (옆얼굴로 한쪽 눈만 안 보일 때 오탐 방지)
         *  - 사진 점수 = 얼굴들 중 최악값 — 한 명이라도 감았으면 나쁜 후보다
         *  - 분류값이 없는 얼굴은 제외, 판정 가능한 얼굴이 하나도 없으면 null
         */
        internal fun combine(faces: List<Pair<Float?, Float?>>): Float? {
            val closedScores = faces.mapNotNull { (left, right) ->
                val open = listOfNotNull(left, right).maxOrNull() ?: return@mapNotNull null
                (1f - open).coerceIn(0f, 1f)
            }
            return closedScores.maxOrNull()
        }
    }
}
