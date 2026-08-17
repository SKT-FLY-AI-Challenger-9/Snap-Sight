package com.example.snap_sight.cv

import org.json.JSONObject

/**
 * ① STT/NLU 가 만드는 의도 스펙. `ai/target_spec_schema.md` v0.1/v0.2 의 Kotlin 표현.
 *
 * 후보 선택은 [Objects365TargetSelector] (`ai/on_device_cv/target_selection.py` 포팅)가
 * tracking 뒤에 수행한다. 이 파일은 파싱·검증만 담당하고 선택 규칙을 알지 못한다.
 *
 * 값이 항상 null 일 수 있다는 게 계약이다 — 마이크 권한이 없거나 발화를 건너뛴 세션
 * (`CaptureSessionManager.startSession()` 참고)에서는 의도 자체가 존재하지 않는다.
 */
data class TargetSpec(
    val sessionId: String,
    val rawText: String,
    val source: String,
    val schemaVersion: String = "0.1",
    val status: Status = Status.OK,
    val subjectType: SubjectType = SubjectType.PERSON,
    val objectLabel: String? = null,
    val subjectCount: Int? = null,
    val framing: Framing = Framing.FULL_BODY,
    val confidence: Float = 0f,
) {
    enum class Status(val wire: String) {
        OK("ok"),
        NEEDS_CLARIFICATION("needs_clarification"),
        FAILED("failed");

        companion object {
            fun fromWire(value: String): Status? = entries.firstOrNull { it.wire == value }
        }
    }

    enum class SubjectType(val wire: String) {
        PERSON("person"),
        OBJECT("object"),
        LANDSCAPE("landscape");

        companion object {
            fun fromWire(value: String): SubjectType? = entries.firstOrNull { it.wire == value }
        }
    }

    enum class Framing(val wire: String) {
        CLOSEUP("closeup"),
        FULL_BODY("full_body"),
        WIDE("wide");

        companion object {
            fun fromWire(value: String): Framing? = entries.firstOrNull { it.wire == value }
        }
    }

    init {
        require(schemaVersion in SUPPORTED_SCHEMA_VERSIONS) {
            "Unsupported TargetSpec schemaVersion: $schemaVersion"
        }
        require(sessionId.isNotBlank()) { "TargetSpec sessionId must be a non-empty string" }
        require(status == Status.FAILED || rawText.isNotBlank()) {
            "TargetSpec rawText must not be empty unless status is failed"
        }
        if (objectLabel != null) {
            require(schemaVersion != "0.1") { "TargetSpec objectLabel requires schemaVersion 0.2" }
            require(subjectType == SubjectType.OBJECT) {
                "TargetSpec objectLabel is only valid for subjectType=object"
            }
            require(objectLabel.isNotBlank()) { "TargetSpec objectLabel must not be blank" }
        }
        require(subjectCount == null || subjectCount >= 1) {
            "TargetSpec subjectCount must be null or an integer of at least 1"
        }
        require(confidence.isFinite() && confidence in 0f..1f) {
            "TargetSpec confidence must be in [0, 1]"
        }
    }

    /**
     * 실제 선택 로직이 붙기 전까지 CV 가 이 스펙을 신뢰해도 되는지의 단일 판정 지점.
     * `needs_clarification`/`failed` 는 임의의 객체를 고르면 안 된다는 뜻이다.
     */
    val isActionable: Boolean get() = status == Status.OK

    companion object {
        val SUPPORTED_SCHEMA_VERSIONS = setOf("0.1", "0.2")

        /** 파싱 실패 시 예외를 던진다. 검증 규칙은 `ai/target_spec.py` 와 맞춘다. */
        fun fromJson(json: String): TargetSpec = fromJsonObject(JSONObject(json))

        /**
         * **의도 입력의 null-안전 진입점.**
         *
         * 발화가 없거나(null), 빈 문자열이거나, 스키마를 어긴 payload 가 와도 예외 없이 null 을
         * 돌려준다. CV 루프는 의도 유무와 무관하게 계속 돌아야 하므로 여기서 삼켜야 한다.
         * 무엇 때문에 버렸는지는 [onError] 로 받아 로그에 남긴다.
         */
        fun fromJsonOrNull(json: String?, onError: (Throwable) -> Unit = {}): TargetSpec? {
            if (json.isNullOrBlank()) return null
            return try {
                fromJson(json)
            } catch (t: Throwable) {
                onError(t)
                null
            }
        }

        private fun fromJsonObject(payload: JSONObject): TargetSpec {
            val unknownFields = payload.keys().asSequence().filterNot { it in ALLOWED_FIELDS }.toList()
            require(unknownFields.isEmpty()) {
                "Unknown TargetSpec fields: ${unknownFields.sorted().joinToString(", ")}"
            }
            for (required in REQUIRED_FIELDS) {
                require(payload.has(required)) { "TargetSpec is missing required field: $required" }
            }

            // objectLabel 이 없던 시절 payload 는 버전을 생략한 경우가 많다 → 0.1 로 해석.
            val schemaVersion = payload.optString("schemaVersion", "0.1")
            require(!(schemaVersion == "0.1" && payload.has("objectLabel"))) {
                "TargetSpec v0.1 must not contain objectLabel"
            }

            return TargetSpec(
                schemaVersion = schemaVersion,
                sessionId = payload.getString("sessionId"),
                status = enumField(payload.optString("status", "ok"), "status", Status::fromWire) {
                    Status.entries.map(Status::wire)
                },
                subjectType = enumField(
                    payload.optString("subjectType", "person"),
                    "subjectType",
                    SubjectType::fromWire,
                ) { SubjectType.entries.map(SubjectType::wire) },
                objectLabel = if (payload.isNull("objectLabel")) null else payload.optString("objectLabel"),
                subjectCount = if (payload.isNull("subjectCount")) null else payload.getInt("subjectCount"),
                framing = enumField(
                    payload.optString("framing", "full_body"),
                    "framing",
                    Framing::fromWire,
                ) { Framing.entries.map(Framing::wire) },
                rawText = payload.getString("rawText"),
                confidence = payload.optDouble("confidence", 0.0).toFloat(),
                source = payload.getString("source"),
            )
        }

        private fun <T> enumField(
            value: String,
            name: String,
            parse: (String) -> T?,
            allowed: () -> List<String>,
        ): T = parse(value)
            ?: throw IllegalArgumentException(
                "TargetSpec $name must be one of: ${allowed().joinToString(", ")}"
            )

        private val REQUIRED_FIELDS = listOf("sessionId", "rawText", "source")

        private val ALLOWED_FIELDS = setOf(
            "schemaVersion", "sessionId", "status", "subjectType", "objectLabel",
            "subjectCount", "framing", "rawText", "confidence", "source",
        )
    }
}
