package com.example.snap_sight.ux

import android.content.SharedPreferences

/**
 * S5 설정값([SettingsUiState])의 저장·복원만 담당한다 (이슈 #54).
 *
 * `SharedPreferences`는 인터페이스라 Android 런타임 없이도(순수 JVM) 가짜 구현을 넣어
 * 단위 테스트할 수 있다 — [MainActivity][com.example.snap_sight.MainActivity]에서 이 클래스를
 * 분리한 이유이기도 하다.
 */
class SettingsRepository(private val prefs: SharedPreferences) {

    /** 저장된 적 없는 값은 기본값(최대 강도·기본 속도)으로 채운다. */
    fun load(): SettingsUiState = SettingsUiState(
        vibrationIntensity = prefs.getFloat(KEY_VIBRATION_INTENSITY, DEFAULT_VALUE),
        soundVolume = prefs.getFloat(KEY_SOUND_VOLUME, DEFAULT_VALUE),
        speechRate = prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_VALUE),
        // 앱은 임의의 외부 클라우드가 아니라 사용자가 설정한 Snap-Sight 서버에 요청한다.
        // 명시적으로 끈 기록이 없을 때는 빠른 로컬 설명 뒤 상세 설명까지 이어지도록 기본 활성화한다.
        serverAiDescriptionEnabled = prefs.getBoolean(KEY_SERVER_AI_DESCRIPTION, true),
    )

    fun save(state: SettingsUiState) {
        prefs.edit()
            .putFloat(KEY_VIBRATION_INTENSITY, state.vibrationIntensity)
            .putFloat(KEY_SOUND_VOLUME, state.soundVolume)
            .putFloat(KEY_SPEECH_RATE, state.speechRate)
            .putBoolean(KEY_SERVER_AI_DESCRIPTION, state.serverAiDescriptionEnabled)
            .apply()
    }

    private companion object {
        const val DEFAULT_VALUE = 1f
        const val KEY_VIBRATION_INTENSITY = "vibration_intensity"
        const val KEY_SOUND_VOLUME = "sound_volume"
        const val KEY_SPEECH_RATE = "speech_rate"
        // 기존 key를 유지해 사용자가 명시적으로 끈 선택은 업데이트 뒤에도 보존한다.
        const val KEY_SERVER_AI_DESCRIPTION = "cloud_description_enabled"
    }
}
