// 이 파일: 프레임 속 person bbox 에서 얼굴을 찾아 등록 인물인지 판정하고 track 에 신원을
// 붙이는 오케스트레이터 (기능 2). 등록(enrollment) 모드도 여기서 처리한다 — 같은 카메라
// 프레임 스트림을 재사용해 2~3초간 얼굴 샘플을 대량 수집한다.
package com.example.snap_sight.face

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.snap_sight.BuildConfig
import com.example.snap_sight.cv.BoundingBox
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
 *  - 임베딩은 매 프레임이 아니라 **미확인 person track 에 대해 [attemptIntervalMs] 마다** 시도
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
    private val attemptIntervalMs: Long = 1_000L,
    /** 디버그 빌드에서만 연결 — 등록 크롭·식별 시도 크롭과 점수를 파일로 남긴다 (null 이면 없음). */
    private val debugSink: FaceDebugSink? = null,
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

    // track_id → 확인된 이름. "시도했지만 미확인"은 nextAttemptAtMs 로만 관리한다.
    private val identities = HashMap<Int, String>()
    // track_id → 바인딩 당시 점수 — 같은 이름을 두 track 이 주장하면 높은 쪽이 이긴다
    private val identityScores = HashMap<Int, Float>()
    private val nextAttemptAtMs = HashMap<Int, Long>()
    // track 이 마지막으로 보인 시각 + 이번 프레임에 보인 track — 바인딩을 잠깐의 공백에 버리지 않기 위함
    private val lastSeenMs = HashMap<Int, Long>()
    private var visibleNow: Set<Int> = emptySet()

    // 매칭 갤러리 캐시 — 등록이 끝나면 dirty 로 표시해 다음 판정 때 다시 읽는다
    private var galleryCache: Map<String, List<FloatArray>>? = null
    private var registryGeneration = 0L

    private var enrollment: Enrollment? = null
    private val enrollmentCommitGate = EnrollmentCommitGate()

    private class Enrollment(
        val name: String,
        val deadlineMs: Long,
        val onDone: (collected: Int) -> Unit,
        val commitToken: EnrollmentCommitGate.Token,
    ) {
        val embeddings = ArrayList<FloatArray>()
        val collected: Int get() = embeddings.size
        var nextSampleAtMs = 0L
        val poseBucketCounts = HashMap<Int, Int>()
    }

    /**
     * 등록 모드 시작 — [durationMs] 동안 중앙성·크기 우세가 명확한 얼굴만 임베딩해 [name] 으로
     * 저장한다. 모호한 프레임은 건너뛰며, 끝나면 [onDone] 이 메인 스레드에서 수집 장수와 함께 호출된다.
     * 등록 대상의 동의 확인(음성 안내)은 호출자(⑥) 책임이다.
     */
    fun startEnrollment(name: String, durationMs: Long, onDone: (collected: Int) -> Unit) {
        val active = synchronized(lock) {
            Enrollment(
                name = name.trim(),
                deadlineMs = System.currentTimeMillis() + durationMs,
                onDone = onDone,
                commitToken = enrollmentCommitGate.begin(),
            ).also { enrollment = it }
        }
        // 완료 판정은 프레임 분석 안에서 하므로 프레임이 아예 안 들어오면 onDone 이 영영
        // 안 불린다. 반드시 한 번은 불리도록 메인 스레드 워치독을 함께 건다 (2026-08-22).
        mainHandler.postDelayed({ finishEnrollment(active) }, durationMs + ENROLL_WATCHDOG_GRACE_MS)
    }

    fun cancelEnrollment(): EnrollmentCancelResult = synchronized(lock) {
            enrollment = null
            // finishEnrollment가 이미 commit thread를 만들었어도 DB 쓰기 직전 token 검증에서 막는다.
            enrollmentCommitGate.cancel()
    }

    val isEnrolling: Boolean get() = synchronized(lock) { enrollment != null }

    override fun reset() {
        synchronized(lock) {
            clearBindingsLocked()
        }
    }

    /**
     * registry를 외부에서 추가/삭제한 직후 호출한다. 캐시와 현재 track 바인딩을 함께 버려 삭제한
     * 이름이 메모리 갤러리로 다시 인식되는 것을 막는다. 분석 스레드와 동시에 호출해도 안전하다.
     */
    fun invalidateRegistryState() {
        synchronized(lock) {
            registryGeneration++
            galleryCache = null
            clearBindingsLocked()
        }
    }

    private fun clearBindingsLocked() {
        identities.clear()
        identityScores.clear()
        nextAttemptAtMs.clear()
        lastSeenMs.clear()
        visibleNow = emptySet()
    }

    override fun analyze(frame: CvFrame, frameResult: FrameResult): Map<Int, String> {
        if (!embedder.isAvailable) return emptyMap()

        synchronized(lock) { enrollment }?.let { active ->
            runEnrollmentStep(active, frame)
            return emptyMap()
        }

        val now = System.currentTimeMillis()
        synchronized(lock) {
            // 바인딩은 track 이 잠깐 안 보여도 바로 버리지 않는다 — 트래커가 2초 안에 같은 ID 로
            // 되살리면(재획득) 다시 판정할 필요가 없다 (실기기 로그 2026-08-22: 한 인물이 track 을
            // 갈아타며 판정이 깜빡였다). IDENTITY_TTL_MS 동안 안 보이면 그때 정리한다.
            visibleNow = frameResult.objects.map { it.trackId }.toSet()
            for (id in visibleNow) lastSeenMs[id] = now
            lastSeenMs.entries.removeAll { now - it.value > IDENTITY_TTL_MS }
            identities.keys.retainAll(lastSeenMs.keys)
            identityScores.keys.retainAll(identities.keys)
            nextAttemptAtMs.keys.retainAll(visibleNow)
        }
        val predictedNow = frameResult.objects.filter { it.predicted }.map { it.trackId }.toSet()

        // 예측(coasting) 박스는 픽셀이 없는 추정 위치라 크롭 대상에서 제외한다
        val persons = frameResult.objects.filter { isPerson(it) && !it.predicted }
        if (persons.isEmpty()) return snapshotIdentities(predictedNow)

        // 프레임당 최대 1건만 임베딩 — 분석 루프 지연을 한 번의 얼굴 처리로 제한한다
        val candidate = synchronized(lock) {
            persons.firstOrNull { person ->
                person.trackId !in identities &&
                    (nextAttemptAtMs[person.trackId] ?: 0L) <= now
            }?.also { nextAttemptAtMs[it.trackId] = now + attemptIntervalMs }
                ?.let { it to registryGeneration }
        }
        if (candidate != null) {
            val (person, expectedRegistryGeneration) = candidate
            identify(frame, person, expectedRegistryGeneration)?.let { (name, score) ->
                bindUnique(person.trackId, name, score, expectedRegistryGeneration)
            }
        }
        return snapshotIdentities(predictedNow)
    }

    /**
     * 한 이름은 한 track 에만 — 같은 이름을 이미 가진 track 이 있으면:
     *  - 그 track 이 지금 안 보이면(TTL 보관 중) 새 track 이 같은 사람의 재등장이라 보고 바인딩을 옮긴다
     *  - 보이면 점수가 높은 쪽만 남긴다 (임계값에 턱걸이한 오인식이 진짜를 밀어내지 않게)
     */
    private fun bindUnique(trackId: Int, name: String, score: Float, expectedRegistryGeneration: Long) {
        synchronized(lock) {
            if (registryGeneration != expectedRegistryGeneration) return
            val rival = identities.entries.firstOrNull { it.value == name && it.key != trackId }?.key
            if (rival != null) {
                val rivalVisible = rival in visibleNow
                val rivalScore = identityScores[rival] ?: 0f
                if (rivalVisible && rivalScore >= score) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "신원 바인딩 보류: track $trackId / $rival")
                    return
                }
                identities.remove(rival)
                identityScores.remove(rival)
                if (BuildConfig.DEBUG) Log.d(TAG, "신원 바인딩 이동: track $rival → $trackId")
            }
            identities[trackId] = name
            identityScores[trackId] = score
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "신원 바인딩 완료: track $trackId")
    }

    /** 등록된 인물 이름 목록 (검색 파서 연동용). 백그라운드 스레드에서 호출할 것. */
    fun peopleNames(): List<String> = registry.peopleNames()

    // ---- 내부 구현 ----

    /**
     * 지금 화면에 보이는 track 의 신원만 내보낸다 (TTL 안에 숨어 있는 바인딩은 내부 보관만).
     * 같은 이름이 둘 이상 보이면 관측된(coasting 아닌) 것, 그다음 점수 높은 것 하나만 남긴다.
     */
    private fun snapshotIdentities(predictedNow: Set<Int>): Map<Int, String> = synchronized(lock) {
        if (identities.isEmpty()) return emptyMap()
        identities.filterKeys { it in visibleNow }
            .entries
            .groupBy { it.value }
            .mapNotNull { (_, entries) ->
                entries.maxWithOrNull(
                    compareBy<Map.Entry<Int, String>> { it.key !in predictedNow }
                        .thenBy { identityScores[it.key] ?: 0f }
                )
            }
            .associate { it.key to it.value }
    }

    /** 등록 종료 — 분석 스레드(기한 도달)와 메인 스레드(워치독) 어느 쪽이 먼저 와도 1회만 실행된다. */
    private fun finishEnrollment(active: Enrollment) {
        val finished = synchronized(lock) {
            if (enrollment === active) {
                enrollment = null
                active.embeddings.toList()
            } else null
        } ?: return
        Thread({
            val committed = enrollmentCommitGate.commitIfCurrent(active.commitToken) {
                if (finished.isNotEmpty()) registry.addEmbeddings(active.name, finished)
            }
            if (!committed) return@Thread
            invalidateRegistryState()
            mainHandler.post {
                if (enrollmentCommitGate.takeCompletion(active.commitToken)) active.onDone(finished.size)
            }
        }, "SnapSight-FaceEnrollCommit").start()
    }

    private fun runEnrollmentStep(active: Enrollment, frame: CvFrame) {
        val now = System.currentTimeMillis()
        if (now >= active.deadlineMs || active.collected >= MAX_ENROLL_SAMPLES) {
            finishEnrollment(active)
            return
        }
        if (now < active.nextSampleAtMs) return
        active.nextSampleAtMs = now + ENROLL_SAMPLE_INTERVAL_MS
        val bitmap = frame.toBitmap() ?: return
        try {
            val face = detectEnrollmentFace(bitmap) ?: return
            val poseBucket = poseBucket(face)
            if (synchronized(lock) {
                    enrollment !== active ||
                        (active.poseBucketCounts[poseBucket] ?: 0) >= MAX_SAMPLES_PER_POSE_BUCKET
                }) return
            val faceBitmap = cropFace(bitmap, face) ?: return
            try {
                val embedding = embedder.embed(faceBitmap) ?: return
                val accepted = synchronized(lock) {
                    if (enrollment !== active || active.collected >= MAX_ENROLL_SAMPLES) {
                        return@synchronized false
                    }
                    val bucketCount = active.poseBucketCounts[poseBucket] ?: 0
                    if (bucketCount >= MAX_SAMPLES_PER_POSE_BUCKET) return@synchronized false
                    active.poseBucketCounts[poseBucket] = bucketCount + 1
                    active.embeddings.add(embedding)
                    true
                }
                if (!accepted) return
                debugSink?.onEnrollSample("face", active.name, faceBitmap)
            } finally {
                if (faceBitmap !== bitmap) faceBitmap.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun poseBucket(face: Face): Int {
        val yaw = when {
            face.headEulerAngleY < -POSE_BUCKET_DEGREES -> 0
            face.headEulerAngleY > POSE_BUCKET_DEGREES -> 2
            else -> 1
        }
        val pitch = when {
            face.headEulerAngleX < -POSE_BUCKET_DEGREES -> 0
            face.headEulerAngleX > POSE_BUCKET_DEGREES -> 2
            else -> 1
        }
        return pitch * 3 + yaw
    }

    /** @return (이름, 1위 점수) — 임계값·마진 미달이면 null. */
    private fun identify(
        frame: CvFrame,
        person: TrackedObject,
        expectedRegistryGeneration: Long,
    ): Pair<String, Float>? {
        val gallery = synchronized(lock) {
            if (registryGeneration != expectedRegistryGeneration) return null
            galleryCache ?: registry.gallery().also { galleryCache = it }
        }
        if (gallery.isEmpty()) return null

        val region = Rect(
            (person.bbox.xMin * frame.width).toInt().coerceIn(0, frame.width - 1),
            (person.bbox.yMin * frame.height).toInt().coerceIn(0, frame.height - 1),
            (person.bbox.xMax * frame.width).toInt().coerceIn(1, frame.width),
            (person.bbox.yMax * frame.height).toInt().coerceIn(1, frame.height),
        )
        if (region.width() < MIN_REGION_PX || region.height() < MIN_REGION_PX) return null

        val bitmap = frame.toBitmap(region) ?: return null
        try {
            val face = detectLargestFace(bitmap)
            if (face == null) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "track ${person.trackId}: person 영역 ${region.width()}x${region.height()}px 에서 얼굴 못 찾음",
                    )
                }
                debugSink?.onNoFace("face", bitmap)
                return null
            }
            val faceBitmap = cropFace(bitmap, face) ?: return null
            try {
                val embedding = embedder.embed(faceBitmap) ?: return null
                val ranking = FaceMatcher.rank(embedding, gallery, matchConfig.topK)
                val decided = FaceMatcher.decide(ranking, matchConfig)
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "track ${person.trackId} 신원 판정: 후보 ${ranking.size}개, " +
                            "최고 ${"%.2f".format(ranking.firstOrNull()?.second ?: 0f)}, " +
                            "일치=${decided != null}",
                    )
                }
                debugSink?.onIdentifyAttempt("face", faceBitmap, ranking, decided)
                return decided?.let { it to (ranking.firstOrNull()?.second ?: 0f) }
            } finally {
                if (faceBitmap !== bitmap) faceBitmap.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun detectFaces(bitmap: Bitmap): List<Face> = try {
        Tasks.await(
            detector.process(InputImage.fromBitmap(bitmap, 0)),
            DETECT_TIMEOUT_MS, TimeUnit.MILLISECONDS,
        )
            .filter { it.boundingBox.width() >= MIN_FACE_PX && it.boundingBox.height() >= MIN_FACE_PX }
    } catch (t: Throwable) {
        Log.w(TAG, "얼굴 검출 실패", t)
        emptyList()
    }

    private fun detectLargestFace(bitmap: Bitmap): Face? = detectFaces(bitmap)
        .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }

    /** 복수 얼굴이 있으면 중앙성·크기 우세가 명확한 프레임만 등록 샘플로 사용한다. */
    private fun detectEnrollmentFace(bitmap: Bitmap): Face? {
        val faces = detectFaces(bitmap)
        val boxes = faces.mapNotNull { face ->
            val box = face.boundingBox
            BoundingBox.clipped(
                box.left.toFloat() / bitmap.width,
                box.top.toFloat() / bitmap.height,
                box.right.toFloat() / bitmap.width,
                box.bottom.toFloat() / bitmap.height,
            )
        }
        // mapNotNull로 누락된 비정상 박스 때문에 인덱스가 어긋나지 않도록 유효 Face와 함께 묶는다.
        if (boxes.size != faces.size) return null
        return EnrollmentCandidateSelector.selectIndex(boxes)?.let(faces::get)
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
        /** 분석 스레드의 기한 판정이 먼저 오도록 워치독은 약간 늦게 발화한다. */
        const val ENROLL_WATCHDOG_GRACE_MS = 700L
        /** 안 보이는 track 의 신원 바인딩 보관 시간 — 트래커의 lost 버퍼(2초)보다 길게. */
        const val IDENTITY_TTL_MS = 3_000L
        const val ENROLL_SAMPLE_INTERVAL_MS = 650L
        const val MAX_ENROLL_SAMPLES = 12
        const val MAX_SAMPLES_PER_POSE_BUCKET = 4
        const val POSE_BUCKET_DEGREES = 12f
    }
}
