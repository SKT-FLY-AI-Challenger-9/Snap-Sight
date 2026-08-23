package com.example.snap_sight.privacy

/**
 * 등록 인물/사물 이름을 발화에서 찾는 단일 정본 matcher.
 *
 * 로컬 타깃 선택과 클라우드 전송 전 가림이 반드시 이 matcher를 함께 써야 한다. 한쪽만
 * 부분 이름을 알아보면 등록 이름 일부가 클라우드로 나갈 수 있기 때문이다.
 */
internal object RegisteredIdentityMatcher {
    enum class IdentityKind { PERSON, OBJECT }

    enum class MatchBasis { EXACT, PARTIAL }

    data class Identity(
        val canonicalName: String,
        val kind: IdentityKind,
        internal val normalizedName: String,
    )

    /** [sourceSpan]은 원문에서 안전하게 지울 수 있을 때만 존재한다. */
    data class AliasMatch(
        val identity: Identity,
        val normalizedAlias: String,
        val basis: MatchBasis,
        val sourceSpan: IntRange?,
    )

    sealed interface Resolution {
        data object None : Resolution

        /** exact/partial이 여러 번 잡혀도 모두 같은 canonical identity일 때만 반환한다. */
        data class Unique(
            val identity: Identity,
            val matches: List<AliasMatch>,
        ) : Resolution

        /** 서로 다른 이름 또는 같은 이름의 인물/사물이 동시에 잡히면 임의 선택하지 않는다. */
        data class Ambiguous(
            val identities: Set<Identity>,
            val matches: List<AliasMatch>,
        ) : Resolution
    }

    fun resolve(
        rawText: String,
        registeredPeople: Collection<String>,
        registeredObjects: Collection<String>,
    ): Resolution {
        if (rawText.isBlank()) return Resolution.None
        val identities = registeredIdentities(registeredPeople, registeredObjects)
        if (identities.isEmpty()) return Resolution.None

        val normalizedRaw = normalizeWithOffsets(rawText)
        val partialTokens = partialTokens(rawText)
        val matches = buildList {
            identities.forEach { identity ->
                exactOccurrences(normalizedRaw, identity.normalizedName).forEach { span ->
                    add(
                        AliasMatch(
                            identity = identity,
                            normalizedAlias = identity.normalizedName,
                            basis = MatchBasis.EXACT,
                            sourceSpan = span,
                        )
                    )
                }
                partialTokens.forEach { token ->
                    // 토큰이 canonical 전체와 같으면 위 exact span이 더 정확하다. partial을
                    // 중복 추가하면 "민수님을"의 조사까지 지워 문장 의도를 불필요하게 훼손한다.
                    if (token.normalized != identity.normalizedName &&
                        identity.normalizedName.contains(token.normalized)
                    ) {
                        // 부분 이름은 조사 경계를 완전히 복원하기 어렵다. 토큰 전체를 지우면
                        // "재석이를" 같은 표면형에서도 이름 조각이 남지 않는다.
                        add(
                            AliasMatch(
                                identity = identity,
                                normalizedAlias = token.normalized,
                                basis = MatchBasis.PARTIAL,
                                sourceSpan = token.sourceSpan,
                            )
                        )
                    }
                }
            }
        }
        if (matches.isEmpty()) return Resolution.None

        val identitiesFound = matches.mapTo(LinkedHashSet()) { it.identity }
        return if (identitiesFound.size == 1) {
            Resolution.Unique(identitiesFound.first(), matches)
        } else {
            Resolution.Ambiguous(identitiesFound, matches)
        }
    }

    /** MainActivity 같은 로컬 소비자는 ambiguous를 선택하지 않고 null로 처리한다. */
    fun uniqueTarget(
        rawText: String,
        registeredPeople: Collection<String>,
        registeredObjects: Collection<String>,
    ): Identity? = (resolve(rawText, registeredPeople, registeredObjects) as? Resolution.Unique)?.identity

    /** 기존 로컬 이름 matcher와 같은 정규화: trim + 소문자 + ASCII 공백 제거. */
    internal fun normalize(text: String): String = text.trim().lowercase().replace(" ", "")

    private fun registeredIdentities(
        people: Collection<String>,
        objects: Collection<String>,
    ): List<Identity> {
        val seen = HashSet<Pair<IdentityKind, String>>()
        return buildList {
            fun addNames(names: Collection<String>, kind: IdentityKind) {
                names.forEach { rawName ->
                    val canonical = rawName.trim()
                    val normalized = normalize(canonical)
                    if (canonical.isNotEmpty() && normalized.isNotEmpty() && seen.add(kind to normalized)) {
                        add(Identity(canonical, kind, normalized))
                    }
                }
            }
            addNames(people, IdentityKind.PERSON)
            addNames(objects, IdentityKind.OBJECT)
        }
    }

    private data class NormalizedText(
        val value: String,
        /** normalized UTF-16 index -> 원문 UTF-16 index. 복원이 확실하지 않으면 null. */
        val sourceOffsets: IntArray?,
    )

    private fun normalizeWithOffsets(rawText: String): NormalizedText {
        val normalized = normalize(rawText)
        if (normalized.isEmpty()) return NormalizedText(normalized, IntArray(0))

        val start = rawText.indexOfFirst { !it.isWhitespace() }
        val end = rawText.indexOfLast { !it.isWhitespace() }
        if (start < 0 || end < start) return NormalizedText(normalized, null)

        val rebuilt = StringBuilder()
        val offsets = ArrayList<Int>()
        for (index in start..end) {
            val char = rawText[index]
            if (char == ' ') continue
            val lowered = char.toString().lowercase()
            rebuilt.append(lowered)
            repeat(lowered.length) { offsets.add(index) }
        }
        // 드문 Unicode 문맥 의존 case-folding에서 문자별 변환 결과가 전체 문자열 변환과
        // 다르면 위치를 추측하지 않는다. matcher는 찾되 redactor가 generic으로 fail-close한다.
        val safeOffsets = if (rebuilt.toString() == normalized) offsets.toIntArray() else null
        return NormalizedText(normalized, safeOffsets)
    }

    private fun exactOccurrences(text: NormalizedText, needle: String): List<IntRange?> {
        if (needle.isEmpty() || text.value.isEmpty()) return emptyList()
        return buildList {
            var fromIndex = 0
            while (fromIndex <= text.value.length - needle.length) {
                val index = text.value.indexOf(needle, startIndex = fromIndex)
                if (index < 0) break
                val offsets = text.sourceOffsets
                add(
                    if (offsets != null && index + needle.length - 1 < offsets.size) {
                        offsets[index]..offsets[index + needle.length - 1]
                    } else {
                        null
                    }
                )
                fromIndex = index + 1
            }
        }
    }

    private data class PartialToken(
        val normalized: String,
        val sourceSpan: IntRange,
    )

    private fun partialTokens(rawText: String): List<PartialToken> = buildList {
        NON_WHITESPACE.findAll(rawText).forEach { token ->
            partialToken(token.value, token.range)?.let { add(it) }

            // STT가 "재석이를찍어줘"처럼 띄어쓰기를 모두 잃는 경우만 제한적으로 복원한다.
            // 임의의 2글자 substring을 훑지 않고, 명확한 촬영 동사 바로 앞 구간에 가상의
            // 공백을 하나 넣었을 때 생기는 subject token만 기존 조사/partial 규칙에 넣는다.
            val commandIndex = CAPTURE_COMMAND_MARKERS
                .map { marker -> token.value.indexOf(marker) }
                .filter { it > 0 }
                .minOrNull()
                ?: return@forEach
            val subject = token.value.substring(0, commandIndex)
            val subjectRange = token.range.first..(token.range.first + commandIndex - 1)
            partialToken(subject, subjectRange)?.let { add(it) }
        }
    }.distinctBy { it.normalized to it.sourceSpan }

    private fun partialToken(surface: String, sourceSpan: IntRange): PartialToken? {
        val stripped = stripJosa(surface)
        val normalized = normalize(stripped)
        return if (normalized.length < MIN_PARTIAL_LENGTH) null
        else PartialToken(normalized, sourceSpan)
    }

    /**
     * 흔한 조사를 긴 것부터 반복해서 제거한다. 반복은 "재석이를" -> "재석이" -> "재석"처럼
     * 이름 뒤 호칭과 조사가 연달아 붙은 STT 표면형을 현재의 2자 부분 규칙으로 처리하기 위함이다.
     */
    private fun stripJosa(word: String): String {
        var stripped = word
        while (true) {
            val suffix = JOSA_SUFFIXES.firstOrNull { josa ->
                if (!stripped.endsWith(josa)) return@firstOrNull false
                val remaining = stripped.length - josa.length
                remaining >= 2 || (remaining == 1 && josa.length >= 2)
            } ?: return stripped
            stripped = stripped.dropLast(suffix.length)
        }
    }

    private const val MIN_PARTIAL_LENGTH = 2
    private val NON_WHITESPACE = Regex("\\S+")
    private val CAPTURE_COMMAND_MARKERS = listOf("찍어", "촬영", "담아")
    private val JOSA_SUFFIXES = listOf(
        "에서", "이랑", "하고", "까지", "부터",
        "은", "는", "이", "가", "을", "를", "에", "로", "와", "과",
    )
}
