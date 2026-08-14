package com.example.snap_sight.cv

/**
 * `ai/on_device_cv/trackers/byte_track_lite.py` 의 Kotlin 포팅.
 *
 * ByteTrack 스타일의 2단계 association 을 쓴다:
 *  - 고신뢰 검출만 새 track ID 를 만든다
 *  - 저신뢰 검출은 **직전 프레임에서 관측된** track 복구에만 쓴다 (새 ID 생성 불가)
 *  - 등속도 bbox 예측 + 최적 IoU 할당 + confidence 가중 label 투표
 *
 * upstream ByteTrack 의 Kalman filter 구현을 복사한 것이 아니다. Python 참조 구현과
 * 같은 association 의미를 유지하는 게 목적이며, 임계값 기본값도 동일하다.
 * 외부 의존성 없이 순수 JVM 코드라 `src/test` 에서 그대로 검증할 수 있다.
 */

/** [ByteTrackLiteTracker] 의 association·수명 임계값. Python `ByteTrackLiteConfig` 와 동일. */
data class ByteTrackLiteConfig(
    val trackActivationThreshold: Float = 0.25f,
    val minimumMatchingConfidence: Float = 0.10f,
    val firstMatchIouThreshold: Double = 0.30,
    val secondMatchIouThreshold: Double = 0.20,
    val lostTrackBuffer: Int = 30,
    val velocityMomentum: Double = 0.40,
    val classAware: Boolean = true,
    val labelMismatchPenalty: Double = 0.80,
    val labelVoteDecay: Double = 0.98,
    val labelSwitchMargin: Double = 1.50,
) {
    init {
        val probabilities = mapOf(
            "trackActivationThreshold" to trackActivationThreshold.toDouble(),
            "minimumMatchingConfidence" to minimumMatchingConfidence.toDouble(),
            "firstMatchIouThreshold" to firstMatchIouThreshold,
            "secondMatchIouThreshold" to secondMatchIouThreshold,
            "velocityMomentum" to velocityMomentum,
            "labelMismatchPenalty" to labelMismatchPenalty,
            "labelVoteDecay" to labelVoteDecay,
        )
        for ((name, value) in probabilities) {
            require(value.isFinite() && value in 0.0..1.0) { "$name must be in [0, 1]" }
        }
        require(minimumMatchingConfidence <= trackActivationThreshold) {
            "minimumMatchingConfidence cannot exceed trackActivationThreshold"
        }
        require(lostTrackBuffer >= 0) { "lostTrackBuffer must be non-negative" }
        require(labelSwitchMargin.isFinite() && labelSwitchMargin >= 1.0) {
            "labelSwitchMargin must be finite and at least 1"
        }
    }
}

class ByteTrackLiteTracker(
    private val config: ByteTrackLiteConfig = ByteTrackLiteConfig(),
) : Tracker {

    private val tracks = LinkedHashMap<Int, Track>()
    private var nextTrackId = 1
    private var frameIndex = 0
    private var currentTime = 0.0
    private var lastExternalTimestamp: Double? = null
    private var usesExternalTimestamps: Boolean? = null

    override fun update(detections: List<Detection>, timestampS: Double?): List<TrackedObject> {
        val candidates = detections
            .filter { it.confidence >= config.minimumMatchingConfidence }
            .sortedWith(DETECTION_ORDER)

        val frameTime = validatedTime(timestampS)
        val elapsedTime = if (frameIndex > 0) frameTime - currentTime else 1.0
        frameIndex++
        currentTime = frameTime
        if (timestampS != null) lastExternalTimestamp = timestampS

        val highConfidence = candidates.filter { it.confidence >= config.trackActivationThreshold }
        val lowConfidence = candidates.filter { it.confidence < config.trackActivationThreshold }

        val activeTracks = tracks.values.sortedBy { it.trackId }
        for (track in activeTracks) track.predict(elapsedTime)

        val (firstMatches, unmatchedTracks, unmatchedHigh) =
            associate(activeTracks, highConfidence, config.firstMatchIouThreshold)

        // 저신뢰 단계는 "직전 프레임에 관측된" track 에만 허용한다.
        // 저신뢰 오탐이 이미 잃어버린 track 을 되살리거나 버퍼를 무한 연장하면 안 되므로.
        val lowConfidenceEligible = unmatchedTracks.filter { it.missedFrames == 0 }
        val (secondMatches, _, _) =
            associate(lowConfidenceEligible, lowConfidence, config.secondMatchIouThreshold)

        val observed = ArrayList<TrackedObject>(candidates.size)
        val matchedTrackIds = HashSet<Int>()
        for ((track, detection) in firstMatches + secondMatches) {
            track.update(detection, frameTime, config)
            matchedTrackIds.add(track.trackId)
            observed.add(track.asObserved(detection))
        }

        for (track in activeTracks) {
            if (track.trackId !in matchedTrackIds) track.missedFrames++
        }

        for (detection in unmatchedHigh) {
            val track = Track.create(nextTrackId, detection, frameTime)
            tracks[track.trackId] = track
            nextTrackId++
            observed.add(track.asObserved(detection))
        }

        val expired = tracks.values.filter { it.missedFrames > config.lostTrackBuffer }
        for (track in expired) tracks.remove(track.trackId)

        observed.sortBy { it.trackId }
        return observed
    }

    override fun reset() {
        tracks.clear()
        nextTrackId = 1
        frameIndex = 0
        currentTime = 0.0
        lastExternalTimestamp = null
        usesExternalTimestamps = null
    }

    private fun validatedTime(timestampS: Double?): Double {
        val usesExternal = timestampS != null
        val previousMode = usesExternalTimestamps
        require(previousMode == null || usesExternal == previousMode) {
            "Use timestamps for every frame in a stream or for none of them"
        }

        val frameTime: Double
        if (timestampS == null) {
            frameTime = currentTime + 1.0
        } else {
            require(timestampS.isFinite()) { "timestampS must be finite" }
            val previous = lastExternalTimestamp
            require(previous == null || timestampS > previous) {
                "timestampS must increase strictly within a stream"
            }
            frameTime = timestampS
        }

        if (usesExternalTimestamps == null) usesExternalTimestamps = usesExternal
        return frameTime
    }

    /**
     * 유효한 (track, detection) 쌍만 남긴 뒤 독립 부분문제별로 최적 할당을 푼다.
     * @return (매칭, 매칭 안 된 track, 매칭 안 된 detection)
     */
    private fun associate(
        tracks: List<Track>,
        detections: List<Detection>,
        minimumIou: Double,
    ): Triple<List<Pair<Track, Detection>>, List<Track>, List<Detection>> {
        if (tracks.isEmpty() || detections.isEmpty()) {
            return Triple(emptyList(), tracks.toList(), detections.toList())
        }

        val trackCount = tracks.size
        val detectionCount = detections.size
        val scores = Array(trackCount) { DoubleArray(detectionCount) }
        val valid = Array(trackCount) { BooleanArray(detectionCount) }

        for (trackIndex in 0 until trackCount) {
            val trackBox = tracks[trackIndex].bbox
            for (detectionIndex in 0 until detectionCount) {
                val detection = detections[detectionIndex]
                var score = iou(trackBox, detection.bbox)
                var isValid = score > 0.0 && score >= minimumIou
                if (config.classAware && classIdentityMismatch(tracks[trackIndex], detection)) {
                    score *= config.labelMismatchPenalty
                    isValid = isValid && score > 0.0
                }
                scores[trackIndex][detectionIndex] = score
                valid[trackIndex][detectionIndex] = isValid
            }
        }

        val matchedTrackIndices = HashSet<Int>()
        val matchedDetectionIndices = HashSet<Int>()
        val matches = ArrayList<Pair<Track, Detection>>()

        for ((componentTracks, componentDetections) in associationComponents(valid)) {
            val localTrackCount = componentTracks.size
            val localDetectionCount = componentDetections.size

            // track 당 비용 0 인 더미 열을 하나씩 붙이면, 최적화기가 억지 매칭 대신
            // "매칭 안 함" 을 선택할 수 있다.
            val costs = Array(localTrackCount) {
                DoubleArray(localDetectionCount + localTrackCount)
            }
            for (localTrack in 0 until localTrackCount) {
                val trackIndex = componentTracks[localTrack]
                for (localDetection in 0 until localDetectionCount) {
                    val detectionIndex = componentDetections[localDetection]
                    costs[localTrack][localDetection] = if (valid[trackIndex][detectionIndex]) {
                        -scores[trackIndex][detectionIndex]
                    } else {
                        UNMATCHABLE_COST
                    }
                }
            }

            val assignment = linearSumAssignment(costs)
            for (localTrack in 0 until localTrackCount) {
                val localDetection = assignment[localTrack]
                // 더미 열에 배정됐거나(= 매칭 안 함) 배정이 없으면 건너뛴다.
                if (localDetection !in 0 until localDetectionCount) continue
                val trackIndex = componentTracks[localTrack]
                val detectionIndex = componentDetections[localDetection]
                if (!valid[trackIndex][detectionIndex]) continue
                matches.add(tracks[trackIndex] to detections[detectionIndex])
                matchedTrackIndices.add(trackIndex)
                matchedDetectionIndices.add(detectionIndex)
            }
        }

        val unmatchedTracks = tracks.filterIndexed { index, _ -> index !in matchedTrackIndices }
        val unmatchedDetections =
            detections.filterIndexed { index, _ -> index !in matchedDetectionIndices }
        return Triple(matches, unmatchedTracks, unmatchedDetections)
    }

    private companion object {
        /** 유효하지 않은 쌍의 비용. 더미 열(0)보다 훨씬 커서 절대 선택되지 않는다. */
        const val UNMATCHABLE_COST = 1e6

        val DETECTION_ORDER: Comparator<Detection> =
            compareBy<Detection> { it.bbox.xMin }
                .thenBy { it.bbox.yMin }
                .thenBy { it.bbox.xMax }
                .thenBy { it.bbox.yMax }
                .thenBy { it.label }
                .thenByDescending { it.confidence }
                .thenBy { it.classId ?: -1 }
    }
}

// ---------------------------------------------------------------------------
// track 상태
// ---------------------------------------------------------------------------

/** state/lastObservation 은 `[centerX, centerY, width, height]` 형식이다. */
private class Track(
    val trackId: Int,
    var state: DoubleArray,
    var velocity: DoubleArray,
    var lastObservation: DoubleArray,
    var lastObservedTime: Double,
    var label: String,
    var classId: Int?,
) {
    val labelVotes = LinkedHashMap<String, Double>()
    var missedFrames = 0
    var age = 1
    var hits = 1

    val bbox: BoundingBox get() = stateToBbox(state)

    fun predict(elapsedTime: Double) {
        val predicted = DoubleArray(4) { state[it] + velocity[it] * elapsedTime }
        state = sanitizeState(predicted)
        age++
    }

    fun update(detection: Detection, frameTime: Double, config: ByteTrackLiteConfig) {
        val observedState = bboxToState(detection.bbox)
        val elapsedTime = maxOf(1e-9, frameTime - lastObservedTime)
        for (index in 0 until 4) {
            val measuredVelocity = (observedState[index] - lastObservation[index]) / elapsedTime
            velocity[index] = config.velocityMomentum * velocity[index] +
                    (1.0 - config.velocityMomentum) * measuredVelocity
        }
        state = observedState
        lastObservation = observedState.copyOf()
        lastObservedTime = frameTime
        missedFrames = 0
        hits++
        if (classId == null && detection.classId != null) classId = detection.classId

        val stale = ArrayList<String>()
        for (entry in labelVotes.entries) {
            entry.setValue(entry.value * config.labelVoteDecay)
            if (entry.value < 1e-8) stale.add(entry.key)
        }
        for (key in stale) labelVotes.remove(key)
        labelVotes[detection.label] =
            (labelVotes[detection.label] ?: 0.0) + detection.confidence

        // 최다 득표, 동률이면 사전순으로 앞선 label
        val challenger = labelVotes.entries.minWith(
            compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }
        ).key
        if (challenger == label) return
        val currentVote = labelVotes[label] ?: 0.0
        if (labelVotes.getValue(challenger) >= currentVote * config.labelSwitchMargin) {
            label = challenger
        }
    }

    /**
     * confidence 는 detector 가 **이번 프레임 label 에** 매긴 점수다.
     * 투표로 정해지는 [label] 은 association 내부 상태일 뿐이므로
     * 공개 출력에서는 label/confidence 쌍의 의미를 깨지 않도록 검출값을 그대로 쓴다.
     */
    fun asObserved(detection: Detection): TrackedObject = TrackedObject(
        trackId = trackId,
        label = detection.label,
        confidence = detection.confidence,
        bbox = detection.bbox,
        classId = detection.classId,
    )

    companion object {
        fun create(trackId: Int, detection: Detection, frameTime: Double): Track {
            val state = bboxToState(detection.bbox)
            return Track(
                trackId = trackId,
                state = state,
                velocity = DoubleArray(4),
                lastObservation = state.copyOf(),
                lastObservedTime = frameTime,
                label = detection.label,
                classId = detection.classId,
            ).also {
                it.labelVotes[detection.label] = maxOf(detection.confidence.toDouble(), 1e-6)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 기하 helper
// ---------------------------------------------------------------------------

private const val MIN_SIDE = 1e-6

/** Float 로 내려도 폭/높이가 0 이 되지 않도록 하는 최소값. Float 정밀도(≈1.2e-7)보다 크다. */
private const val MIN_OUTPUT_SIDE = 1e-5

private fun bboxToState(bbox: BoundingBox): DoubleArray {
    val width = (bbox.xMax - bbox.xMin).toDouble()
    val height = (bbox.yMax - bbox.yMin).toDouble()
    return doubleArrayOf(
        bbox.xMin.toDouble() + width / 2.0,
        bbox.yMin.toDouble() + height / 2.0,
        width,
        height,
    )
}

private fun sanitizeState(state: DoubleArray): DoubleArray {
    val result = state.copyOf()
    result[2] = result[2].coerceIn(MIN_SIDE, 1.0)
    result[3] = result[3].coerceIn(MIN_SIDE, 1.0)
    result[0] = result[0].coerceIn(result[2] / 2.0, 1.0 - result[2] / 2.0)
    result[1] = result[1].coerceIn(result[3] / 2.0, 1.0 - result[3] / 2.0)
    return result
}

private fun stateToBbox(state: DoubleArray): BoundingBox {
    val sanitized = sanitizeState(state)
    val width = maxOf(sanitized[2], MIN_OUTPUT_SIDE)
    val height = maxOf(sanitized[3], MIN_OUTPUT_SIDE)
    val centerX = sanitized[0].coerceIn(width / 2.0, 1.0 - width / 2.0)
    val centerY = sanitized[1].coerceIn(height / 2.0, 1.0 - height / 2.0)
    return BoundingBox(
        xMin = (centerX - width / 2.0).coerceIn(0.0, 1.0).toFloat(),
        yMin = (centerY - height / 2.0).coerceIn(0.0, 1.0).toFloat(),
        xMax = (centerX + width / 2.0).coerceIn(0.0, 1.0).toFloat(),
        yMax = (centerY + height / 2.0).coerceIn(0.0, 1.0).toFloat(),
    )
}

private fun iou(first: BoundingBox, second: BoundingBox): Double {
    val intersectionWidth =
        maxOf(0.0, minOf(first.xMax, second.xMax).toDouble() - maxOf(first.xMin, second.xMin))
    val intersectionHeight =
        maxOf(0.0, minOf(first.yMax, second.yMax).toDouble() - maxOf(first.yMin, second.yMin))
    val intersection = intersectionWidth * intersectionHeight
    val firstArea = first.width.toDouble() * first.height.toDouble()
    val secondArea = second.width.toDouble() * second.height.toDouble()
    val union = firstArea + secondArea - intersection
    return if (union > 0.0) intersection / union else 0.0
}

private fun classIdentityMismatch(track: Track, detection: Detection): Boolean {
    val trackClassId = track.classId
    val detectionClassId = detection.classId
    if (trackClassId != null && detectionClassId != null) return trackClassId != detectionClassId
    return !track.label.trim().equals(detection.label.trim(), ignoreCase = true)
}

// ---------------------------------------------------------------------------
// 할당 solver
// ---------------------------------------------------------------------------

/** 희소 이분 매칭 그래프를 독립적인 정확 부분문제들로 분해한다. */
private fun associationComponents(valid: Array<BooleanArray>): List<Pair<List<Int>, List<Int>>> {
    val trackCount = valid.size
    val detectionCount = if (trackCount == 0) 0 else valid[0].size
    val seenTracks = BooleanArray(trackCount)
    val seenDetections = BooleanArray(detectionCount)
    val components = ArrayList<Pair<List<Int>, List<Int>>>()

    for (startingTrack in 0 until trackCount) {
        if (seenTracks[startingTrack] || valid[startingTrack].none { it }) continue
        val componentTracks = ArrayList<Int>()
        val componentDetections = ArrayList<Int>()
        val stack = ArrayDeque<Int>()
        stack.addLast(startingTrack)
        seenTracks[startingTrack] = true

        while (stack.isNotEmpty()) {
            val trackIndex = stack.removeLast()
            componentTracks.add(trackIndex)
            for (detectionIndex in 0 until detectionCount) {
                if (!valid[trackIndex][detectionIndex] || seenDetections[detectionIndex]) continue
                seenDetections[detectionIndex] = true
                componentDetections.add(detectionIndex)
                for (otherTrack in 0 until trackCount) {
                    if (valid[otherTrack][detectionIndex] && !seenTracks[otherTrack]) {
                        seenTracks[otherTrack] = true
                        stack.addLast(otherTrack)
                    }
                }
            }
        }

        componentTracks.sort()
        componentDetections.sort()
        components.add(componentTracks to componentDetections)
    }
    return components
}

/**
 * SciPy 없이 직사각 최소비용 할당을 푼다 (shortest augmenting path / JV).
 *
 * 열 개수가 행 개수 이상이어야 한다 — tracker 는 더미 열을 붙여 항상 이를 만족시킨다.
 * @return 행마다 배정된 열 인덱스
 */
private fun linearSumAssignment(costs: Array<DoubleArray>): IntArray {
    val rowCount = costs.size
    if (rowCount == 0) return IntArray(0)
    val columnCount = costs[0].size
    require(columnCount >= rowCount) { "cost matrix must have at least as many columns as rows" }
    require(costs.all { row -> row.size == columnCount && row.all { it.isFinite() } }) {
        "cost matrix must be rectangular and contain only finite values"
    }

    val rowPotential = DoubleArray(rowCount + 1)
    val columnPotential = DoubleArray(columnCount + 1)
    val columnMatch = IntArray(columnCount + 1)
    val predecessor = IntArray(columnCount + 1)
    val epsilon = 1e-12

    for (row in 1..rowCount) {
        columnMatch[0] = row
        var currentColumn = 0
        val minimumValues = DoubleArray(columnCount + 1) { Double.POSITIVE_INFINITY }
        val used = BooleanArray(columnCount + 1)

        while (true) {
            used[currentColumn] = true
            val currentRow = columnMatch[currentColumn]
            var delta = Double.POSITIVE_INFINITY
            var nextColumn = 0
            for (column in 1..columnCount) {
                if (used[column]) continue
                val reducedCost = costs[currentRow - 1][column - 1] -
                        rowPotential[currentRow] - columnPotential[column]
                if (reducedCost < minimumValues[column] - epsilon) {
                    minimumValues[column] = reducedCost
                    predecessor[column] = currentColumn
                }
                val isSmaller = minimumValues[column] < delta - epsilon
                val isTieWithLowerIndex = kotlin.math.abs(minimumValues[column] - delta) <= epsilon &&
                        (nextColumn == 0 || column < nextColumn)
                if (isSmaller || isTieWithLowerIndex) {
                    delta = minimumValues[column]
                    nextColumn = column
                }
            }

            for (column in 0..columnCount) {
                if (used[column]) {
                    rowPotential[columnMatch[column]] += delta
                    columnPotential[column] -= delta
                } else {
                    minimumValues[column] -= delta
                }
            }
            currentColumn = nextColumn
            if (columnMatch[currentColumn] == 0) break
        }

        while (true) {
            val previousColumn = predecessor[currentColumn]
            columnMatch[currentColumn] = columnMatch[previousColumn]
            currentColumn = previousColumn
            if (currentColumn == 0) break
        }
    }

    val assignedColumns = IntArray(rowCount) { -1 }
    for (column in 1..columnCount) {
        val matchedRow = columnMatch[column]
        if (matchedRow != 0) assignedColumns[matchedRow - 1] = column - 1
    }
    return assignedColumns
}
