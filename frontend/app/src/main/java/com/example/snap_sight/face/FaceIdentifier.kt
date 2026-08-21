// 이 파일: 프레임 속 person bbox 에서 얼굴을 찾아 등록 인물인지 판정하고 track 에 신원을
// 붙이는 오케스트레이터 (기능 2). 등록(enrollment) 모드도 여기서 처리한다 — 같은 카메라
// 프레임 스트림을 재사용해 2~3초간 얼굴 샘플을 대량 수집한다.
package com.example.snap_sight.face

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.snap_sight.cv.CvFrame
import com.example.snap_sight.cv.FaceFrameAnalyzer
import com.example.snap_sight.cv.FrameResult
import com.example.snap_sight.cv.TrackedObject
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit

/**
 * 신원 판정 정책 (docs/feature-expansion-plan.md 기능 2):
 *  - 임베딩은 매 프레임이 아니라 **미확인 person track 에 대해 [attemptIntervalFrames] 마다** 시도
 *  - 확인된 신원은 track 이 살아있는 동안 재계산하지 않는다 (track 바인딩)
 *  - track 이 끊기고 재등장하면 새 track 으로 다시 확인된다 (= 기능 1-E 재식별)
 *  - 임베더/등록 인물이 없으면 완전한 no-op — 파이프라인 성능에 영향 없음
 *
 * 스레딩: [analyze] 는 CV 분석 스레드에서 동기 호출된다. 등록 시작/취소는 메인 스레드에서
 * 불릴 수 있어 상태는 [lock] 으로 보호한다.
 */
class FaceIdentifier(
    private val registry: FaceRegistry,
    private val embedder: FaceEmbedder,
    private val matchConfig: FaceMatchConfig = FaceMatchConfig(),
    private val attemptIntervalFrames: Int = 10,
) : FaceFrameAnalyzer {

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )
    }

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    // track_id → 확인된 이름. "시도했지만 미확인"은 nextAttemptFrame 으로만 관리한다.
    private val identities = HashMap<Int, String>()
    private val nextAttemptFrame = HashMap<Int, Long>()
    private var frameCounter = 0L

    // 매칭 갤러리 캐시 — 등록이 끝나면 dirty 로 표시해 다음 판정 때 다시 읽는다
    private var galleryCache: Map<String, List<FloatArray>>? = null

    private var enrollment: Enrollment? = null

    private class Enrollment(
        val name: String,
        val deadlineMs: Long,
        val onDone: (collected: Int) -> Unit,
    ) {
        var collected = 0
    }

    /**
     * 등록 모드 시작 — [durationMs] 동안 프레임마다 가장 큰 얼굴을 임베딩해 [name] 으로
     * 저장한다. 끝나면 [onDone] 이 메인 스레드에서 수집 장수와 함께 호출된다.
     * 등록 대상의 동의 확인(음성 안내)은 호출자(⑥) 책임이다.
     */
    fun startEnrollment(name: String, durationMs: Long, onDone: (collected: Int) -> Unit) {
        synchronized(lock) {
            enrollment = Enrollment(
                name = name.trim(),
                deadlineMs = System.currentTimeMillis() + durationMs,
                onDone = onDone,
            )
        }
    }

    fun cancelEnrollment() {
        synchronized(lock) { enrollment = null }
    }

    val isEnrolling: Boolean get() = synchronized(lock) { enrollment != null }

    override fun reset() {
        synchronized(lock) {
            identities.clear()
            nextAttemptFrame.clear()
            frameCounter = 0
        }
    }

    override fun analyze(frame: CvFrame, frameResult: FrameResult): Map<Int, String> {
        if (!embedder.isAvailable) return emptyMap()

        synchronized(lock) { enrollment }?.let { active ->
            runEnrollmentStep(active, frame)
            return emptyMap()
        }

        val currentFrame: Long
        synchronized(lock) {
            frameCounter++
            currentFrame = frameCounter
            // 사라진 track 의 바인딩 정리 — 재등장하면 새 track 으로 다시 확인된다
            val visible = frameResult.objects.map { it.trackId }.toSet()
            identities.keys.retainAll(visible)
            nextAttemptFrame.keys.retainAll(visible)
        }

        val persons = frameResult.objects.filter { isPerson(it) }
        if (persons.isEmpty()) return snapshotIdentities()

        // 프레임당 최대 1건만 임베딩 — 분석 루프 지연을 한 번의 얼굴 처리로 제한한다
        val candidate = synchronized(lock) {
            persons.firstOrNull { person ->
                person.trackId !in identities &&
                    (nextAttemptFrame[person.trackId] ?: 0L) <= currentFrame
            }
        }
        if (candidate != null) {
            synchronized(lock) {
                nextAttemptFrame[candidate.trackId] = currentFrame + attemptIntervalFrames
            }
            identify(frame, candidate)?.let { name ->
                synchronized(lock) { identities[candidate.trackId] = name }
                Log.i(TAG, "track ${candidate.trackId} = $name")
            }
        }
        return snapshotIdentities()
    }

    /** 등록된 인물 이름 목록 (검색 파서 연동용). 백그라운드 스레드에서 호출할 것. */
    fun peopleNames(): List<String> = registry.peopleNames()

    // ---- 내부 구현 ----

    private fun snapshotIdentities(): Map<Int, String> =
        synchronized(lock) { if (identities.isEmpty()) emptyMap() else HashMap(identities) }

    private fun runEnrollmentStep(active: Enrollment, frame: CvFrame) {
        if (System.currentTimeMillis() >= active.deadlineMs) {
            val finished = synchronized(lock) {
                if (enrollment === active) enrollment = null
                active
            }
            galleryCache = null // 새 임베딩 반영
            mainHandler.post { finished.onDone(finished.collected) }
            return
        }
        val bitmap = frame.toBitmap() ?: return
        val face = detectLargestFace(bitmap) ?: return
        val faceBitmap = cropFace(bitmap, face) ?: return
        val embedding = embedder.embed(faceBitmap) ?: return
        registry.addEmbedding(active.name, embedding)
        active.collected++
    }

    private fun identify(frame: CvFrame, person: TrackedObject): String? {
        val gallery = galleryCache ?: registry.gallery().also { galleryCache = it }
        if (gallery.isEmpty()) return null

        val region = Rect(
            (person.bbox.xMin * frame.width).toInt().coerceIn(0, frame.width - 1),
            (person.bbox.yMin * frame.height).toInt().coerceIn(0, frame.height - 1),
            (person.bbox.xMax * frame.width).toInt().coerceIn(1, frame.width),
            (person.bbox.yMax * frame.height).toInt().coerceIn(1, frame.height),
        )
        if (region.width() < MIN_REGION_PX || region.height() < MIN_REGION_PX) return null

        val bitmap = frame.toBitmap(region) ?: return null
        val face = detectLargestFace(bitmap) ?: return null
        val faceBitmap = cropFace(bitmap, face) ?: return null
        val embedding = embedder.embed(faceBitmap) ?: return null
        return FaceMatcher.match(embedding, gallery, matchConfig)
    }

    private fun detectLargestFace(bitmap: Bitmap): Face? = try {
        val faces = Tasks.await(
            detector.process(InputImage.fromBitmap(bitmap, 0)),
            DETECT_TIMEOUT_MS, TimeUnit.MILLISECONDS,
        )
        faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            ?.takeIf { it.boundingBox.width() >= MIN_FACE_PX && it.boundingBox.height() >= MIN_FACE_PX }
    } catch (t: Throwable) {
        Log.w(TAG, "얼굴 검출 실패", t)
        null
    }

    private fun cropFace(source: Bitmap, face: Face): Bitmap? {
        // 검출 박스에 약간의 여백을 줘 이마·턱이 잘리지 않게 한다
        val box = face.boundingBox
        val marginX = (box.width() * FACE_CROP_MARGIN).toInt()
        val marginY = (box.height() * FACE_CROP_MARGIN).toInt()
        val left = (box.left - marginX).coerceAtLeast(0)
        val top = (box.top - marginY).coerceAtLeast(0)
        val right = (box.right + marginX).coerceAtMost(source.width)
        val bottom = (box.bottom + marginY).coerceAtMost(source.height)
        if (right - left < MIN_FACE_PX || bottom - top < MIN_FACE_PX) return null
        return try {
            Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        } catch (t: Throwable) {
            null
        }
    }

    private fun isPerson(item: TrackedObject): Boolean =
        item.label.trim().equals("person", ignoreCase = true)

    private companion object {
        const val TAG = "FaceIdentifier"
        const val MIN_REGION_PX = 40
        const val MIN_FACE_PX = 32
        const val FACE_CROP_MARGIN = 0.2f
        const val DETECT_TIMEOUT_MS = 1_000L
    }
}
