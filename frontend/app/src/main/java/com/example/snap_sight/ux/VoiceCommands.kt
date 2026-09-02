// 이 파일: 홈 발화("두 번 탭 → 말하기")를 앱 명령으로 해석하는 순수 파서 (2026-08-23).
// 설정처럼 탭 문법이 배정되지 않은 진입점을 화면을 보지 않고도 열 수 있게 한다 —
// 모든 발화가 촬영 의도인 것은 아니다.
package com.example.snap_sight.ux

/** 홈 발화로 실행되는 앱 명령 — 촬영 의도가 아니라 화면 이동·설정 토글. */
enum class AppVoiceCommand {
    OPEN_SETTINGS,
    OPEN_GALLERY,
    GRID_ON,
    GRID_OFF,
}

/**
 * 발화 → [AppVoiceCommand] 해석. 촬영 의도로 보이면 null 을 돌려줘 기존 흐름(백엔드 파싱)을
 * 계속 탄다. 공백 제거 후 포함 비교 — STT 띄어쓰기 흔들림에 안전하다.
 *
 * 판정 우선순위: 그리드 토글 → 설정 → 사진 찾기. "찍어"가 들어간 발화는 무조건 촬영
 * 의도로 본다 ("설정에 가서"류의 오귀속 방지가 아니라, "노을 배경 설정으로 찍어줘"처럼
 * 촬영 문장에 명령 단어가 섞이는 경우를 걸러내기 위함).
 */
object VoiceCommands {

    fun parse(utterance: String): AppVoiceCommand? {
        val t = utterance.replace(" ", "")
        if (CAPTURE_WORDS.any(t::contains)) return null
        val mentionsGrid = GRID_WORDS.any(t::contains)
        return when {
            mentionsGrid && GRID_OFF_WORDS.any(t::contains) -> AppVoiceCommand.GRID_OFF
            mentionsGrid && GRID_ON_WORDS.any(t::contains) -> AppVoiceCommand.GRID_ON
            t.contains("설정") -> AppVoiceCommand.OPEN_SETTINGS
            GALLERY_WORDS.any(t::contains) -> AppVoiceCommand.OPEN_GALLERY
            else -> null
        }
    }

    private val CAPTURE_WORDS = listOf("찍어", "찍고", "촬영해")
    private val GRID_WORDS = listOf("그리드", "격자")
    private val GRID_ON_WORDS = listOf("켜", "보여", "표시")
    private val GRID_OFF_WORDS = listOf("꺼", "끄", "숨겨", "없애")
    private val GALLERY_WORDS = listOf("사진찾", "갤러리", "앨범", "사진첩")
}
