// 이 파일: 참조 토큰 → 실제 이름 치환 시 한국어 조사 교정 (2026-08-23).
// 서버 모델은 실제 이름을 모른 채 토큰("local_track_3") 기준으로 조사를 붙이므로,
// 기기에서 이름으로 갈아끼울 때 "내 인형가"처럼 받침 규칙이 어긋난다. 치환 지점에서
// 조사를 이름의 받침에 맞게 함께 바꾼다 — 순수 로직이라 JVM 단위 테스트 대상.
package com.example.snap_sight

object KoreanJosa {

    /**
     * [text] 안의 [token]을 [name]으로 치환하되, 토큰 바로 뒤에 붙은 조사를 이름의 받침에 맞게
     * 교정한다 ("local_track_3가" + "내 인형" → "내 인형이"). 토큰 뒤가 조사가 아니면 이름만
     * 치환하고, 이름이 한글로 끝나지 않아 받침을 판단할 수 없으면 모델이 쓴 조사를 그대로 둔다.
     */
    fun replaceTokenWithName(text: String, token: String, name: String): String {
        val pattern = Regex(Regex.escape(token) + "($JOSA_ALTERNATION)?")
        return pattern.replace(text) { match ->
            val josa = match.groupValues[1]
            if (josa.isEmpty()) name else name + correctedJosa(name, josa)
        }
    }

    /**
     * "OO가 OO로 변경되었어요"류 설정 변경 알림 문장을 받침에 맞게 조립한다
     * (설정 화면 음성 안내, 2026-08-25).
     */
    fun changedAnnouncement(label: String, value: String): String =
        "$label${correctedJosa(label, "가")} $value${correctedJosa(value, "로")} 변경되었어요"

    /** 이름의 받침에 맞는 조사 형태. 판단 불가(비한글 끝)면 원래 조사 유지. */
    internal fun correctedJosa(name: String, josa: String): String {
        val pair = JOSA_PAIRS.firstOrNull { josa == it.first || josa == it.second } ?: return josa
        val last = name.trimEnd().lastOrNull() ?: return josa
        if (last !in '가'..'힣') return josa // 영문·숫자 등 — 받침 판단 불가, 교정하지 않음
        val jong = (last - '가') % 28
        // (으)로 는 ㄹ받침이면 받침 없는 쪽("로")을 쓰는 예외 규칙
        val hasBatchim = if (pair.first == "으로") jong != 0 && jong != 8 else jong != 0
        return if (hasBatchim) pair.first else pair.second
    }

    /** (받침 있음 형태, 받침 없음 형태) — 긴 것부터 매칭되도록 아래 alternation 순서와 일치. */
    private val JOSA_PAIRS = listOf(
        "이에요" to "예요",
        "이라고" to "라고",
        "이라는" to "라는",
        "이란" to "란",
        "이랑" to "랑",
        "이며" to "며",
        "이야" to "야",
        "으로" to "로",
        "은" to "는",
        "이" to "가",
        "을" to "를",
        "과" to "와",
    )

    private val JOSA_ALTERNATION = JOSA_PAIRS
        .flatMap { listOf(it.first, it.second) }
        .sortedByDescending { it.length }
        .joinToString("|")
}
