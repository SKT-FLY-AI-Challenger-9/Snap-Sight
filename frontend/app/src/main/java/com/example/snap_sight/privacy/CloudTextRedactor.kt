package com.example.snap_sight.privacy

/** 등록 이름은 로컬 기능에만 남기고 클라우드 요청에서는 의미 보존 토큰으로 바꾼다. */
internal object CloudTextRedactor {
    const val GENERIC_CAPTURE_UTTERANCE = "사진을 찍어줘"

    fun redact(
        rawText: String,
        registeredPeople: Collection<String>,
        registeredObjects: Collection<String>,
    ): String {
        val resolution = RegisteredIdentityMatcher.resolve(
            rawText = rawText,
            registeredPeople = registeredPeople,
            registeredObjects = registeredObjects,
        )
        return when (resolution) {
            RegisteredIdentityMatcher.Resolution.None -> rawText
            is RegisteredIdentityMatcher.Resolution.Ambiguous -> GENERIC_CAPTURE_UTTERANCE
            is RegisteredIdentityMatcher.Resolution.Unique -> redactUnique(rawText, resolution)
        }
    }

    private fun redactUnique(
        rawText: String,
        resolution: RegisteredIdentityMatcher.Resolution.Unique,
    ): String {
        // matcher가 민감 alias를 찾았는데 원문 위치 하나라도 확정하지 못하면 부분 가림을
        // 시도하지 않는다. 전체를 generic으로 바꿔 이름 조각이 남을 가능성을 닫는다.
        val spans = resolution.matches.map { it.sourceSpan ?: return GENERIC_CAPTURE_UTTERANCE }
        if (spans.isEmpty() || spans.any { it.first < 0 || it.last >= rawText.length || it.isEmpty() }) {
            return GENERIC_CAPTURE_UTTERANCE
        }

        val replacement = when (resolution.identity.kind) {
            RegisteredIdentityMatcher.IdentityKind.PERSON -> "등록 인물"
            RegisteredIdentityMatcher.IdentityKind.OBJECT -> "등록 사물"
        }
        var safe = rawText
        mergeOverlapping(spans).asReversed().forEach { span ->
            safe = safe.replaceRange(span.first, span.last + 1, replacement)
        }

        // 구현 변경으로 span 규칙이 흔들려도 matcher가 찾은 alias가 남으면 전송하지 않는다.
        val normalizedSafe = RegisteredIdentityMatcher.normalize(safe)
        val aliasRemains = resolution.matches.any { match ->
            match.normalizedAlias.isNotEmpty() && normalizedSafe.contains(match.normalizedAlias)
        }
        return if (aliasRemains) GENERIC_CAPTURE_UTTERANCE else safe
    }

    private fun mergeOverlapping(spans: List<IntRange>): List<IntRange> {
        val sorted = spans.sortedWith(compareBy<IntRange> { it.first }.thenBy { it.last })
        val merged = ArrayList<IntRange>(sorted.size)
        sorted.forEach { span ->
            val previous = merged.lastOrNull()
            if (previous != null && span.first <= previous.last + 1) {
                merged[merged.lastIndex] = previous.first..maxOf(previous.last, span.last)
            } else {
                merged.add(span)
            }
        }
        return merged
    }
}
