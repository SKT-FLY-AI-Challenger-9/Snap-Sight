package com.example.snap_sight.ux

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SettingsRepository] 저장·복원 검증. `SharedPreferences`는 인터페이스라 실제 Android 런타임 없이
 * (순수 JVM) [FakeSharedPreferences]로 대체해 테스트한다.
 */
class SettingsRepositoryTest {

    @Test
    fun loadReturnsDefaultsWhenNothingSaved() {
        val repo = SettingsRepository(FakeSharedPreferences())

        val state = repo.load()

        assertEquals(SettingsUiState(vibrationIntensity = 1f, soundVolume = 1f, speechRate = 1f), state)
        assertEquals(true, state.serverAiDescriptionEnabled)
    }

    @Test
    fun saveThenLoadRoundTrips() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)
        val state = SettingsUiState(vibrationIntensity = 0.29f, soundVolume = 0.5f, speechRate = 1.5f)

        repo.save(state)

        assertEquals(state, repo.load())
    }

    @Test
    fun saveOverwritesPreviousValue() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)

        repo.save(SettingsUiState(vibrationIntensity = 1f, soundVolume = 1f, speechRate = 1f))
        repo.save(SettingsUiState(vibrationIntensity = 0f, soundVolume = 0.2f, speechRate = 0.5f))

        assertEquals(SettingsUiState(vibrationIntensity = 0f, soundVolume = 0.2f, speechRate = 0.5f), repo.load())
    }

    @Test
    fun secondRepositoryInstanceOverSamePrefsSeesSavedValue() {
        // MainActivity 재생성(액티비티 재시작) 시나리오를 흉내낸다 — 같은 SharedPreferences를
        // 새 SettingsRepository 인스턴스로 감싸도 이전에 저장한 값을 그대로 읽어야 한다.
        val prefs = FakeSharedPreferences()
        SettingsRepository(prefs).save(
            SettingsUiState(vibrationIntensity = 0.29f, soundVolume = 1f, speechRate = 1f)
        )

        val reloaded = SettingsRepository(prefs).load()

        assertEquals(0.29f, reloaded.vibrationIntensity, 1e-6f)
    }

    @Test
    fun explicitlyDisabledServerDescriptionRemainsDisabled() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)
        repo.save(
            SettingsUiState(
                vibrationIntensity = 1f,
                soundVolume = 1f,
                speechRate = 1f,
                serverAiDescriptionEnabled = false,
            )
        )

        assertEquals(false, SettingsRepository(prefs).load().serverAiDescriptionEnabled)
    }
}

/** 테스트 전용 최소 인메모리 [SharedPreferences] 구현 — float 저장/조회만 실제로 동작한다. */
private class FakeSharedPreferences : SharedPreferences {
    private val floats = mutableMapOf<String, Float>()
    private val booleans = mutableMapOf<String, Boolean>()

    override fun getFloat(key: String, defValue: Float): Float = floats[key] ?: defValue

    override fun edit(): SharedPreferences.Editor = FakeEditor(floats, booleans)

    override fun getAll(): MutableMap<String, *> = (floats.mapValues { it.value as Any } + booleans).toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = booleans[key] ?: defValue
    override fun contains(key: String?): Boolean = floats.containsKey(key) || booleans.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private class FakeEditor(
        private val floats: MutableMap<String, Float>,
        private val booleans: MutableMap<String, Boolean>,
    ) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Float>()
        private val pendingBooleans = mutableMapOf<String, Boolean>()

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun apply() {
            floats.putAll(pending)
            booleans.putAll(pendingBooleans)
        }

        override fun commit(): Boolean {
            floats.putAll(pending)
            booleans.putAll(pendingBooleans)
            return true
        }

        override fun putString(key: String?, value: String?) = this
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = this
        override fun putLong(key: String?, value: Long) = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) pendingBooleans[key] = value
            return this
        }
        override fun remove(key: String?) = this
        override fun clear() = this
    }
}
