// 이 파일: 프레임 속 사람 아닌 YOLO track 을 등록 사물과 대조해 이름을 붙이는 오케스트레이터
// (사물 등록, 2026-08-22). FaceIdentifier 와 같은 정책(track 바인딩·간격 시도)을 따르되
// **파이프라인을 통째로 분리**했다 — 얼굴 검출(ML Kit) 단계가 없이 YOLO bbox 크롭을 바로
// 임베딩하고, 저장소도 ObjectRegistry 로 따로 쓴다.
//
// 임베더는 얼굴 파이프라인과 완전히 분리한다. 기본 구현은 저비용 LocalObjectAppearanceEmbedder,
// 전용 TFLite image embedder를 추가할 때도 ObjectEmbedder 계약만 구현하면 된다.
package com.example.snap_sight.face

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.snap_sight.BuildConfig
import com.example.snap_sight.cv.CvFrame
import com.example.snap_sight.cv.FaceFrameAnalyzer
import com.example.snap_sight.cv.FrameResult
import com.example.snap_sight.cv.TrackedObject

/**
 * 판정 정책 (FaceIdentifier 와 동일):
 *  - 임베딩은 매 프레임이 아니라 미확인 track 에 대해 [attemptIntervalMs] 마다 시도
 *  - 확인된 이름은 track 이 살아있는 동안 재계산하지 않는다 (track 바인딩)
 *  - 등록 사물의 YOLO 라벨과 같은 클래스의 track 하고만 비교한다 (오인식 억제)
 *  - 임베더/등록 사물이 없으면 완전한 no-op
 *
 * 스레딩: [analyze] 는 CV 분석 스레드에서 동기 호출된다. 등록 시작/취소는 메인 스레드에서
 * 불릴 수 있어 상태는 [lock] 으로 보호한다.
 */
class ObjectIdentifier(
    private val registry: ObjectRegistry,
    private val embedder: ObjectEmbedder,
    // 심층 특징 임베더(TfLiteObjectEmbedder) 실기기 캘리브레이션 (2026-08-23, 인형 5개 장면):
    // 진짜 등록 인형 0.90, 다른 인형들 0.56~0.71. 0.78 은 가짜 최고와 0.07, 진짜와 0.12 여유 —
    // 양쪽 모두에서 안전 마진 확보. 분포가 달라 보이면 ObjectIdentifier 판정 로그로 재조정.
    private val matchConfig: FaceMatchConfig = FaceMatchConfig(similarityThreshold = 0.78f, margin = 0.05f),
    private val attemptIntervalMs: Long = 1_000L,
    /** 디버그 빌드에서만 연결 — 등록 크롭·식별 시도 크롭과 점수를 파일로 남긴다 (null 이면 없음). */
    private val debugSink: FaceDebugSink? = null,
) : FaceFrameAnalyzer {

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val identities = HashMap<Int, String>()
    private val identityScores = HashMap<Int, Float>()
    private val nextAttemptAtMs = HashMap<Int, Long>()
    private val lastSeenMs = HashMap<Int, Long>()
    private var visibleNow: Set<Int> = emptySet()

    // 갤러리·라벨 캐시 — 등록이 끝나면 비워 다음 판정 때 다시 읽는다
    private var galleryCache: Map<String, List<FloatArray>>? = null
    private var labelCache: Map<String, String>? = null
    private var registryGeneration = 0L

    private var enrollment: Enrollment? = null
    private val enrollmentCommitGate = EnrollmentCommitGate()

    private class Enrollment(
        val name: String,
        val deadlineMs: Long,
        val onDone: (collected: Int, yoloLabel: String?) -> Unit,
        val commitToken: EnrollmentCommitGate.Token,
    ) {
        val embeddings = ArrayList<FloatArray>()
        val collected: Int get() = embeddings.size
        var yoloLabel: String? = null
        var nextSampleAtMs = 0L
    }

    /**
     * 등록 모드 시작 — [durationMs] 동안 중앙성·크기 우세가 명확한 사람 아닌 탐지만
     * 임베딩해 [name] 으로 저장한다. 모호한 프레임은 건너뛴다. 끝나면 [onDone] 이 메인
     * 스레드에서 (수집 장수, 탐지된 YOLO 라벨)과 함께 호출된다.
     */
    fun startEnrollment(name: String, durationMs: Long, onDone: (collected: Int, yoloLabel: String?) -> Unit) {
        val active = synchronized(lock) {
            Enrollment(
                name = name.trim(),
                deadlineMs = System.currentTimeMillis() + durationMs,
                onDone = onDone,
                commitToken = enrollmentCommitGate.begin(),
            ).also { enrollment = it }
        }
        // 완료 판정은 프레임 분석([analyze]) 안에서 하지만, 카메라 프레임이 아예 안 들어오면
        // 그 판정 자체가 실행되지 않는다. onDone 이 반드시 한 번은 불리도록 메인 스레드
        // 워치독을 함께 건다 (2026-08-22 실기기 무반응 수정).
        mainHandler.postDelayed({ finishEnrollment(active) }, durationMs + ENROLL_WATCHDOG_GRACE_MS)
    }

    fun cancelEnrollment(): EnrollmentCancelResult = synchronized(lock) {
            enrollment = null
            enrollmentCommitGate.cancel()
    }

    val isEnrolling: Boolean get() = synchronized(lock) { enrollment != null }

    override fun reset() {
        synchronized(lock) {
            clearBindingsLocked()
        }
    }

    /** 외부 registry 추가/삭제 뒤 캐시와 현재 track 바인딩을 원자적으로 무효화한다. */
    fun invalidateRegistryState() {
        synchronized(lock) {
            registryGeneration++
            galleryCache = null
            labelCache = null
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
            runEnrollmentStep(active, frame, frameResult)
            return emptyMap()
        }

        val now = System.currentTimeMillis()
        synchronized(lock) {
            // FaceIdentifier 와 동일 — 잠깐 안 보인 track 의 바인딩은 TTL 동안 보관한다
            visibleNow = frameResult.objects.map { it.trackId }.toSet()
            for (id in visibleNow) lastSeenMs[id] = now
            lastSeenMs.entries.removeAll { now - it.value > IDENTITY_TTL_MS }
            identities.keys.retainAll(lastSeenMs.keys)
            identityScores.keys.retainAll(identities.keys)
            nextAttemptAtMs.keys.retainAll(visibleNow)
        }
        val predictedNow = frameResult.objects.filter { it.predicted }.map { it.trackId }.toSet()

        val (expectedRegistryGeneration, labels) = synchronized(lock) {
            registryGeneration to (labelCache ?: registry.labels().also { labelCache = it })
        }
        if (labels.isEmpty()) return emptyMap()
        val registeredClasses = labels.values.toSet()

        // 등록 사물과 같은 YOLO 클래스인 track 만 후보 — 프레임당 최대 1건만 임베딩
        val candidate = synchronized(lock) {
            if (registryGeneration != expectedRegistryGeneration) return@synchronized null
            frameResult.objects.firstOrNull { item ->
                !isPerson(item) &&
                    !item.predicted && // 예측 박스는 픽셀이 없는 추정 위치 — 크롭 대상 제외
                    normalizedLabel(item) in registeredClasses &&
                    item.trackId !in identities &&
                    (nextAttemptAtMs[item.trackId] ?: 0L) <= now
            }?.also { nextAttemptAtMs[it.trackId] = now + attemptIntervalMs }
        }
        if (candidate != null) {
            identify(frame, candidate, labels, expectedRegistryGeneration)?.let { (name, score) ->
                bindUnique(candidate.trackId, name, score, expectedRegistryGeneration)
            }
        }
        return snapshotIdentities(predictedNow)
    }

    /** 한 이름은 한 track 에만 — FaceIdentifier.bindUnique 와 같은 규칙. */
    private fun bindUnique(trackId: Int, name: String, score: Float, expectedRegistryGeneration: Long) {
        synchronized(lock) {
            if (registryGeneration != expectedRegistryGeneration) return
            val rival = identities.entries.firstOrNull { it.value == name && it.key != trackId }?.key
            if (rival != null) {
                val rivalVisible = rival in visibleNow
                val rivalScore = identityScores[rival] ?: 0f
                if (rivalVisible && rivalScore >= score) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "사물 바인딩 보류: track $trackId / $rival")
                    return
                }
                identities.remove(rival)
                identityScores.remove(rival)
                if (BuildConfig.DEBUG) Log.d(TAG, "사물 바인딩 이동: track $rival → $trackId")
            }
            identities[trackId] = name
            identityScores[trackId] = score
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "사물 바인딩 완료: track $trackId")
    }

    /** 등록된 사물 이름 목록 (검색 파서 연동용). 백그라운드 스레드에서 호출할 것. */
    fun objectNames(): List<String> = registry.objectNames()

    // ---- 내부 구현 ----

    /** 보이는 track 의 신원만, 같은 이름은 관측된 것·점수 높은 것 하나만 (FaceIdentifier 와 동일). */
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
                Triple(active.embeddings.toList(), active.yoloLabel, active.name)
            } else null
        } ?: return
        Thread({
            val embeddings = finished.first
            val label = finished.second
            val name = finished.third
            val committed = enrollmentCommitGate.commitIfCurrent(active.commitToken) {
                if (label != null && embeddings.isNotEmpty()) {
                    registry.addEmbeddings(name, label, embedder.modelId, embeddings)
                }
            }
            if (!committed) return@Thread
            invalidateRegistryState()
            mainHandler.post {
                if (enrollmentCommitGate.takeCompletion(active.commitToken)) {
                    active.onDone(embeddings.size, label)
                }
            }
        }, "SnapSight-ObjectEnrollCommit").start()
    }

    private fun runEnrollmentStep(active: Enrollment, frame: CvFrame, frameResult: FrameResult) {
        val now = System.currentTimeMillis()
        if (now >= active.deadlineMs || active.collected >= MAX_ENROLL_SAMPLES) {
            finishEnrollment(active)
            return
        }
        if (now < active.nextSampleAtMs) return
        active.nextSampleAtMs = now + ENROLL_SAMPLE_INTERVAL_MS
        // 중앙에 있고 크기가 명확히 우세한 단일 후보만 등록한다. 모호한 프레임은 건너뛰되 세션은
        // 계속해, 사용자가 대상을 중앙에 단독으로 보여 주는 다음 프레임에서 다시 시도한다.
        val candidates = frameResult.objects.filter { !isPerson(it) && !it.predicted }
        val selectedIndex = EnrollmentCandidateSelector.selectIndex(candidates.map { it.bbox }) ?: return
        val target = candidates[selectedIndex]
        if (active.yoloLabel != null && normalizedLabel(target) != active.yoloLabel) return
        val region = bboxRegion(target, frame) ?: return
        val bitmap = frame.toBitmap(region) ?: return
        try {
            val embedding = embedder.embed(bitmap) ?: return
            val label = normalizedLabel(target)
            val accepted = synchronized(lock) {
                if (enrollment !== active || active.collected >= MAX_ENROLL_SAMPLES) return@synchronized false
                if (active.yoloLabel == null) active.yoloLabel = label
                if (active.yoloLabel != label) return@synchronized false
                active.embeddings.add(embedding)
                true
            }
            if (!accepted) return
            debugSink?.onEnrollSample("object", active.name, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** @return (이름, 1위 점수) — 임계값·마진 미달이면 null. */
    private fun identify(
        frame: CvFrame,
        item: TrackedObject,
        labels: Map<String, String>,
        expectedRegistryGeneration: Long,
    ): Pair<String, Float>? {
        val gallery = synchronized(lock) {
            if (registryGeneration != expectedRegistryGeneration) return null
            galleryCache ?: registry.gallery(embedder.modelId).also { galleryCache = it }
        }
        if (gallery.isEmpty()) return null

        // 같은 YOLO 클래스로 등록된 사물하고만 비교한다
        val itemLabel = normalizedLabel(item)
        val sameClass = gallery.filterKeys { labels[it] == itemLabel }
        if (sameClass.isEmpty()) return null

        val region = bboxRegion(item, frame) ?: return null
        val bitmap = frame.toBitmap(region) ?: return null
        try {
            val embedding = embedder.embed(bitmap) ?: return null
            val ranking = FaceMatcher.rank(embedding, sameClass, matchConfig.topK)
            val decided = FaceMatcher.decide(ranking, matchConfig)
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "track ${item.trackId}(${itemLabel}) 사물 판정: 후보 ${ranking.size}개, " +
                        "최고 ${"%.2f".format(ranking.firstOrNull()?.second ?: 0f)}, " +
                        "일치=${decided != null}",
                )
            }
            debugSink?.onIdentifyAttempt("object", bitmap, ranking, decided)
            return decided?.let { it to (ranking.firstOrNull()?.second ?: 0f) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun bboxRegion(item: TrackedObject, frame: CvFrame): Rect? {
        val region = Rect(
            (item.bbox.xMin * frame.width).toInt().coerceIn(0, frame.width - 1),
            (item.bbox.yMin * frame.height).toInt().coerceIn(0, frame.height - 1),
            (item.bbox.xMax * frame.width).toInt().coerceIn(1, frame.width),
            (item.bbox.yMax * frame.height).toInt().coerceIn(1, frame.height),
        )
        if (region.width() < MIN_REGION_PX || region.height() < MIN_REGION_PX) return null
        return region
    }

    private fun isPerson(item: TrackedObject): Boolean =
        item.label.trim().equals("person", ignoreCase = true)

    private fun normalizedLabel(item: TrackedObject): String = item.label.trim().lowercase()

    private companion object {
        const val TAG = "ObjectIdentifier"
        const val MIN_REGION_PX = 40
        /** 분석 스레드의 기한 판정이 먼저 오도록 워치독은 약간 늦게 발화한다. */
        const val ENROLL_WATCHDOG_GRACE_MS = 700L
        /** 안 보이는 track 의 바인딩 보관 시간 — 트래커의 lost 버퍼(2초)보다 길게. */
        const val IDENTITY_TTL_MS = 3_000L
        const val ENROLL_SAMPLE_INTERVAL_MS = 650L
        const val MAX_ENROLL_SAMPLES = 12
    }
}
