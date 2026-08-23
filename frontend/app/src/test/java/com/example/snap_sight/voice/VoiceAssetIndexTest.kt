package com.example.snap_sight.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAssetIndexTest {

    private fun manifest(vararg pairs: Pair<String, String>): String {
        val entries = pairs.joinToString(",") { (text, file) ->
            """{"id":"x.$file","text":"$text","file":"$file"}"""
        }
        return """{"version":1,"engine":"elevenlabs","assets":[$entries]}"""
    }

    @Test
    fun `maps sentence to asset path`() {
        val index = VoiceAssetIndex.parse(
            manifest("사진을 저장했어요." to "result__saved.mp3")
        )

        assertEquals("voice/preset1/result__saved.mp3", index.assetPathFor("사진을 저장했어요."))
        assertEquals(1, index.size)
    }

    @Test
    fun `returns null for uncached sentence`() {
        val index = VoiceAssetIndex.parse(manifest("사진을 저장했어요." to "a.mp3"))

        assertNull(index.assetPathFor("서버 연결을 확인해 주세요"))
    }

    @Test
    fun `ignores surrounding and repeated whitespace`() {
        val index = VoiceAssetIndex.parse(manifest("촬영할게요." to "capture.mp3"))

        assertNotNull(index.assetPathFor("  촬영할게요.  "))
        assertNotNull(VoiceAssetIndex.parse(manifest("좋아요. 촬영할 수 있어요." to "r.mp3"))
            .assetPathFor("좋아요.  촬영할 수  있어요."))
    }

    @Test
    fun `punctuation difference is a different sentence`() {
        // 마침표 유무는 억양이 달라 같은 음원을 쓰면 안 된다.
        val index = VoiceAssetIndex.parse(manifest("촬영할게요." to "capture.mp3"))

        assertNull(index.assetPathFor("촬영할게요"))
    }

    @Test
    fun `broken manifest yields empty index instead of throwing`() {
        assertTrue(VoiceAssetIndex.parse("이건 JSON 이 아니다").isEmpty())
        assertTrue(VoiceAssetIndex.parse("{}").isEmpty())
        assertTrue(VoiceAssetIndex.parse("""{"assets":[]}""").isEmpty())
    }

    @Test
    fun `skips entries missing text or file and keeps the rest`() {
        val json = """
            {"version":1,"assets":[
              {"id":"a","text":"","file":"a.mp3"},
              {"id":"b","text":"둘째 문장","file":""},
              {"id":"c","text":"셋째 문장","file":"c.mp3"}
            ]}
        """.trimIndent()

        val index = VoiceAssetIndex.parse(json)

        assertEquals(1, index.size)
        assertEquals("voice/preset1/c.mp3", index.assetPathFor("셋째 문장"))
    }

    @Test
    fun `duplicate sentences keep the first entry`() {
        val index = VoiceAssetIndex.parse(
            manifest("같은 문장" to "first.mp3", "같은 문장" to "second.mp3")
        )

        assertEquals("voice/preset1/first.mp3", index.assetPathFor("같은 문장"))
        assertEquals(1, index.size)
    }

    @Test
    fun `empty index reports empty`() {
        assertTrue(VoiceAssetIndex.EMPTY.isEmpty())
        assertNull(VoiceAssetIndex.EMPTY.assetPathFor("아무 문장"))
    }

    // --- 프리셋 3종 ("최종 기획 정리" 안내 목소리) ---

    @Test
    fun `asset path is scoped to the requested preset`() {
        val index = VoiceAssetIndex.parse(manifest("촬영할게요." to "c.mp3"), presetId = "preset2")

        assertEquals("voice/preset2/c.mp3", index.assetPathFor("촬영할게요."))
        assertEquals("preset2", index.presetId)
    }

    @Test
    fun `manifest preset field wins over the requested one`() {
        // 폴더와 manifest 가 어긋나면 굽는 쪽이 적어둔 값이 정답이다.
        val json = """{"version":2,"preset":"preset3","assets":[{"id":"a","text":"문장","file":"a.mp3"}]}"""

        val index = VoiceAssetIndex.parse(json, presetId = "preset1")

        assertEquals("preset3", index.presetId)
        assertEquals("voice/preset3/a.mp3", index.assetPathFor("문장"))
    }

    @Test
    fun `default preset is preset1`() {
        val index = VoiceAssetIndex.parse(manifest("문장" to "a.mp3"))

        assertEquals(VoiceAssetIndex.DEFAULT_PRESET, index.presetId)
        assertEquals("preset1", VoiceAssetIndex.DEFAULT_PRESET)
    }

    @Test
    fun `manifest asset path points into the preset folder`() {
        assertEquals("voice/preset2/manifest.json", VoiceAssetIndex.manifestAsset("preset2"))
    }
}
