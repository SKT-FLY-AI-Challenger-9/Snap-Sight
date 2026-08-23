package com.example.snap_sight.voice

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 미리 구워둔 안내 음원(assets/voice)의 색인.
 *
 * 정본은 `ai/voice/script.json`이고, `python -m ai.tools.generate_voice_assets`가 문장마다
 * mp3 하나와 `manifest.json`을 만든다. 이 클래스는 그 manifest를 읽어 **문장 → 파일**로
 * 찾을 수 있게 한다.
 *
 * 문장(id가 아니라 텍스트)으로 찾는 이유: 앱의 모든 발화는
 * [GuidanceFeedback][com.example.snap_sight.ux.GuidanceFeedback]`.announce(text)`를 지나가고,
 * 호출부는 문장만 알지 음원 id를 모른다. 호출부를 전부 고치는 대신 여기서 문장으로 맞춰
 * 붙인다 — 캐시에 없으면 기존 시스템 TTS로 그대로 흘러가므로 호출부는 바뀔 게 없다.
 *
 * TFLite 모델과 같은 원칙으로, **음원이 없어도 앱은 정상 동작한다**. manifest가 없거나
 * 깨졌으면 [isEmpty]인 빈 색인이 되고 모든 발화가 시스템 TTS로 간다.
 */
class VoiceAssetIndex private constructor(
    /** 이 색인이 담고 있는 안내 목소리 프리셋 id. 빈 색인이면 빈 문자열. */
    val presetId: String,
    private val fileByText: Map<String, String>,
) {

    val size: Int get() = fileByText.size

    fun isEmpty(): Boolean = fileByText.isEmpty()

    /** 문장에 대응하는 assets 상대 경로. 없으면 null. */
    fun assetPathFor(text: String): String? =
        fileByText[normalize(text)]?.let { "$ASSET_DIR/$presetId/$it" }

    companion object {
        private const val TAG = "VoiceAssetIndex"
        const val ASSET_DIR = "voice"

        /**
         * 기본 프리셋 — "최종 기획 정리"의 1순위 프리셋. 설정에서 고른 값이 없을 때 쓴다.
         * 실제 순위는 청취 평가로 확정하기로 되어 있으므로 여기 값도 그때 바뀔 수 있다.
         */
        const val DEFAULT_PRESET = "preset1"

        fun manifestAsset(presetId: String): String = "$ASSET_DIR/$presetId/manifest.json"

        val EMPTY = VoiceAssetIndex("", emptyMap())

        /**
         * 문장 비교용 정규화. 공백 차이만으로 캐시를 놓치지 않도록 앞뒤를 자르고 연속 공백을
         * 하나로 줄인다. 구두점은 건드리지 않는다 — "촬영할게요"와 "촬영할게요."는 다른
         * 문장으로 봐야 억양이 어긋나지 않는다.
         */
        fun normalize(text: String): String = text.trim().replace(WHITESPACE, " ")

        private val WHITESPACE = Regex("\\s+")

        /** assets/voice/<presetId>/manifest.json 을 읽는다. 없거나 깨졌으면 [EMPTY]. */
        fun load(context: Context, presetId: String = DEFAULT_PRESET): VoiceAssetIndex {
            val json = try {
                context.applicationContext.assets.open(manifestAsset(presetId)).use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            } catch (e: Exception) {
                Log.i(TAG, "안내 음원 manifest가 없습니다($presetId) — 시스템 TTS로 동작합니다", e)
                return EMPTY
            }
            return parse(json, presetId).also {
                if (it.isEmpty()) {
                    Log.w(TAG, "manifest를 읽지 못했습니다($presetId) — 시스템 TTS로 동작합니다")
                }
            }
        }

        /**
         * manifest JSON 문자열을 색인으로. 잘못된 항목은 건너뛰고 나머지는 살린다 —
         * 한 줄이 깨졌다고 안내 전체가 시스템 TTS로 떨어지면 손해가 크다.
         *
         * 로그를 남기지 않는 순수 함수다 — `android.util.Log`는 JVM 단위 테스트에서 던지므로,
         * 안내가 왜 비었는지 알리는 일은 Android 전용인 [load]가 맡는다.
         */
        fun parse(json: String, presetId: String = DEFAULT_PRESET): VoiceAssetIndex {
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                return EMPTY
            }
            // manifest 가 자기 프리셋을 적어 두면 그걸 신뢰한다 (폴더와 어긋나면 경로가 깨지므로
            // 굽는 쪽이 적은 값이 정답이다). 없으면 호출자가 요청한 값을 쓴다.
            val resolvedPreset = root.optString("preset").ifBlank { presetId }
            val assets = root.optJSONArray("assets") ?: return EMPTY

            val map = LinkedHashMap<String, String>(assets.length())
            for (index in 0 until assets.length()) {
                val entry = assets.optJSONObject(index) ?: continue
                val text = entry.optString("text").takeIf { it.isNotBlank() } ?: continue
                val file = entry.optString("file").takeIf { it.isNotBlank() } ?: continue
                // 같은 문장이 여러 id에 걸리면 먼저 온 것을 남긴다 (manifest는 id 정렬이라 안정적).
                map.putIfAbsent(normalize(text), file)
            }
            if (map.isEmpty()) return EMPTY
            return VoiceAssetIndex(resolvedPreset, map)
        }
    }
}
