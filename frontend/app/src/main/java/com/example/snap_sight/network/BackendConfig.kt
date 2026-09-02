// 이 파일: 백엔드 주소의 런타임 설정 — 빌드 시 주입값(BuildConfig)을 기본으로 쓰되,
// 설정 화면에서 바꾼 값을 SharedPreferences에 저장해 재빌드 없이 다른 Wi-Fi(다른 PC IP)로
// 옮겨갈 수 있게 한다 (시연장 네트워크 대비).
package com.example.snap_sight.network

import android.content.SharedPreferences
import com.example.snap_sight.BuildConfig

object BackendConfig {

    /** 모든 네트워크 클라이언트가 요청 시점에 읽는 현재 백엔드 주소 (끝 슬래시 없음). */
    @Volatile
    var baseUrl: String = BuildConfig.BACKEND_BASE_URL
        private set

    /** 앱 시작 시 저장된 재정의 값을 복원한다 — 없으면 빌드 주입값 그대로. */
    fun load(prefs: SharedPreferences) {
        // debug에서 저장한 평문 LAN 주소가 release 설치에 남아 있어도 복원하지 않는다.
        baseUrl = prefs.getString(KEY_BASE_URL, null)?.let(::normalize)
            ?: BuildConfig.BACKEND_BASE_URL
    }

    /**
     * 사용자가 입력한 주소를 정규화해 적용·저장한다. 빈 입력은 "빌드 기본값으로 복귀"로 취급한다.
     * @return 실제로 적용된 주소
     */
    fun save(prefs: SharedPreferences, rawInput: String): String {
        val normalized = normalize(rawInput)
        baseUrl = normalized ?: BuildConfig.BACKEND_BASE_URL
        prefs.edit().putString(KEY_BASE_URL, normalized ?: "").apply()
        return baseUrl
    }

    /** debug는 스킴이 없으면 http, release는 https를 붙인다. release의 명시적 http는 거부한다. */
    fun normalize(rawInput: String): String? =
        normalize(rawInput, allowCleartext = BuildConfig.ALLOW_CLEARTEXT_BACKEND)

    internal fun normalize(rawInput: String, allowCleartext: Boolean): String? {
        val trimmed = rawInput.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) ->
                trimmed.takeIf { allowCleartext }
            "://" in trimmed -> null
            allowCleartext -> "http://$trimmed"
            else -> "https://$trimmed"
        }
    }

    /** 설정 화면 입력칸에 보여줄 저장된 재정의 값 — 재정의가 없으면 빈 문자열. */
    fun storedOverride(prefs: SharedPreferences): String =
        prefs.getString(KEY_BASE_URL, "").orEmpty().let(::normalize).orEmpty()

    private const val KEY_BASE_URL = "backend_base_url"
}
