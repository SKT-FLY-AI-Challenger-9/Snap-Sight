package com.example.snap_sight.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [VoiceCommands] — 홈 발화의 앱 명령 해석. 촬영 의도를 명령으로 오귀속하지 않는 것이 핵심 계약. */
class VoiceCommandsTest {

    @Test
    fun `settings and gallery open commands are recognized`() {
        assertEquals(AppVoiceCommand.OPEN_SETTINGS, VoiceCommands.parse("설정 열어줘"))
        assertEquals(AppVoiceCommand.OPEN_SETTINGS, VoiceCommands.parse("설정"))
        assertEquals(AppVoiceCommand.OPEN_GALLERY, VoiceCommands.parse("사진 찾기 열어줘"))
        assertEquals(AppVoiceCommand.OPEN_GALLERY, VoiceCommands.parse("갤러리 보여줘"))
        assertEquals(AppVoiceCommand.OPEN_GALLERY, VoiceCommands.parse("앨범"))
    }

    @Test
    fun `grid toggle commands are recognized in both wordings`() {
        assertEquals(AppVoiceCommand.GRID_ON, VoiceCommands.parse("그리드 켜줘"))
        assertEquals(AppVoiceCommand.GRID_ON, VoiceCommands.parse("격자 보여줘"))
        assertEquals(AppVoiceCommand.GRID_OFF, VoiceCommands.parse("그리드 꺼줘"))
        assertEquals(AppVoiceCommand.GRID_OFF, VoiceCommands.parse("격자 없애 줘"))
    }

    @Test
    fun `capture utterances are never treated as commands`() {
        assertNull(VoiceCommands.parse("인물 사진 찍어줘"))
        assertNull(VoiceCommands.parse("유재석 찍어줘"))
        // "설정"·"그리드"가 섞여도 찍는 문장이면 촬영 의도
        assertNull(VoiceCommands.parse("노을 배경 설정으로 찍어줘"))
        assertNull(VoiceCommands.parse("그리드 배경으로 찍고 싶어"))
        // 평범한 촬영 문장
        assertNull(VoiceCommands.parse("풍경을 촬영해줘"))
    }

    @Test
    fun `unrelated utterances fall through to capture flow`() {
        assertNull(VoiceCommands.parse("우리 강아지"))
        assertNull(VoiceCommands.parse(""))
    }
}
