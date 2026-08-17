package com.example.snap_sight.cv

/**
 * `ai/taxonomy/objects365.py` 의 Kotlin 포팅 — detector 출력 class ID 와 canonical label 의
 * 불변 매핑. [Objects365TargetSelector] 가 의도(TargetSpec)의 `objectLabel` 을 검출 결과와
 * 대조할 때 쓴다.
 *
 * 라벨 소스는 detector 가 쓰는 것과 **같은** assets 라벨 파일(줄 번호 = class ID)이다.
 * 같은 파일에서 만들어야 selector 와 detector 의 class ID 해석이 어긋날 수 없다.
 *
 * Python 쪽과 의미가 같아야 하는 규칙:
 *  - canonical label 조회는 대소문자 구분 (`ai/target_spec.py` 가 canonical 값을 보증)
 *  - 관측 label 조회는 casefold 비교 (모델 표기가 `Wine Glass` 여도 매칭)
 *  - class ID 가 있으면 ID 우선, 없을 때만 label fallback
 *
 * 이 파일은 android.* 에 의존하지 않는다 → JVM 단위 테스트에서 그대로 검증 가능.
 */
class ObjectTaxonomy(labels: List<String>) {

    val labels: List<String> = labels.toList()

    private val classIds: Map<String, Int>
    private val normalizedClassIds: Map<String, Int>

    init {
        require(this.labels.isNotEmpty()) { "taxonomy labels must not be empty" }
        require(this.labels.all { it.isNotEmpty() && it == it.trim() }) {
            "taxonomy labels must be non-empty trimmed strings"
        }
        val normalized = this.labels.map { it.lowercase() }
        require(normalized.toSet().size == normalized.size) {
            "taxonomy labels must be unique ignoring case"
        }
        classIds = this.labels.withIndex().associate { (classId, label) -> label to classId }
        normalizedClassIds = normalized.withIndex().associate { (classId, label) -> label to classId }
    }

    val classCount: Int get() = labels.size

    /** `person` 라벨이 없는 대체 taxonomy 도 허용하므로 null 가능. */
    val personClassId: Int? = classIds["person"]

    fun classIdForLabel(label: String): Int? = classIds[label]

    /** class ID 가 있으면 범위 검증만, 없으면 관측 label 을 casefold 로 조회한다. */
    fun classIdForObservation(classId: Int?, observedLabel: String): Int? {
        if (classId != null) return if (classId in 0 until classCount) classId else null
        return normalizedClassIds[observedLabel.trim().lowercase()]
    }

    /** TargetSpec `subjectType=object` 후보로 인정되는 관측인지 — person 이 아닌 taxonomy 소속. */
    fun isSupportedObject(classId: Int?, observedLabel: String): Boolean {
        val observedClassId = classIdForObservation(classId, observedLabel) ?: return false
        return observedClassId != personClassId
    }

    /** canonical label 과의 일치 판정. 안정적인 class ID 를 우선하고 label 은 fallback. */
    fun matches(classId: Int?, observedLabel: String, canonicalLabel: String): Boolean {
        val expectedClassId = classIds[canonicalLabel] ?: return false
        if (classId != null) return classId == expectedClassId
        return observedLabel.trim().lowercase() == canonicalLabel.lowercase()
    }

    companion object {
        /**
         * assets 라벨 파일 내용(줄 번호 = class ID)에서 taxonomy 를 만든다.
         *
         * 파싱은 `TfLiteYoloDetector.readLabels()` 와 **동일해야 한다** — 마지막 개행만 제거하고
         * 중간 빈 줄은 보존한다. 중간 빈 줄을 걸러내면 detector 와 class ID 가 서로 밀린다.
         * 중간 빈 줄이 있으면 생성자 검증이 예외로 잡아낸다 (조용히 틀어지는 것보다 낫다).
         */
        fun fromLabelsText(raw: String): ObjectTaxonomy =
            ObjectTaxonomy(raw.split('\n').map { it.trim('\r', ' ') }.dropLastWhile { it.isEmpty() })
    }
}
