package com.motobsd.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 应用设置持久化，基于 DataStore。
 *
 * 统一管理：
 * - 上次连接设备 MAC
 * - 悬浮窗配置（粗细/透明度/是否反转/方向）
 * - 引导完成标记
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    // ── Keys ──────────────────────────────────────────────

    private object Keys {
        val LAST_MAC = stringPreferencesKey("last_mac")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")

        // Overlay config
        val OVERLAY_SIZE = intPreferencesKey("overlay_size")
        val OVERLAY_ALPHA = intPreferencesKey("overlay_alpha")
        val OVERLAY_SWAP = booleanPreferencesKey("overlay_swap")
        val OVERLAY_ORIENTATION = intPreferencesKey("overlay_orientation")
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val RIDE_MODE = booleanPreferencesKey("ride_mode")

        // Sound
        val SOUND_VOLUME = intPreferencesKey("sound_volume")
        val SOUND_LEFT_FREQ = intPreferencesKey("sound_left_freq")
        val SOUND_RIGHT_FREQ = intPreferencesKey("sound_right_freq")
        val SOUND_STREAM = intPreferencesKey("sound_stream")
    }

    // ── MAC ───────────────────────────────────────────────

    val lastMac: Flow<String?> = dataStore.data.map { it[Keys.LAST_MAC] }

    suspend fun setLastMac(mac: String) {
        dataStore.edit { it[Keys.LAST_MAC] = mac }
    }

    // ── Onboarding ────────────────────────────────────────

    val onboardingComplete: Flow<Boolean> =
        dataStore.data.map { it[Keys.ONBOARDING_COMPLETE] ?: false }

    suspend fun setOnboardingComplete() {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETE] = true }
    }

    // ── Overlay config ────────────────────────────────────

    suspend fun getOverlaySize(): Int = dataStore.data.first()[Keys.OVERLAY_SIZE] ?: 1
    suspend fun setOverlaySize(ordinal: Int) {
        dataStore.edit { it[Keys.OVERLAY_SIZE] = ordinal }
    }

    suspend fun getOverlayAlpha(): Int = dataStore.data.first()[Keys.OVERLAY_ALPHA] ?: 60
    suspend fun setOverlayAlpha(alpha: Int) {
        dataStore.edit { it[Keys.OVERLAY_ALPHA] = alpha }
    }

    suspend fun getOverlaySwap(): Boolean = dataStore.data.first()[Keys.OVERLAY_SWAP] ?: false
    suspend fun setOverlaySwap(swap: Boolean) {
        dataStore.edit { it[Keys.OVERLAY_SWAP] = swap }
    }

    suspend fun getOverlayOrientation(): Int = dataStore.data.first()[Keys.OVERLAY_ORIENTATION] ?: 0
    suspend fun setOverlayOrientation(ordinal: Int) {
        dataStore.edit { it[Keys.OVERLAY_ORIENTATION] = ordinal }
    }

    /** 悬浮窗是否开启（默认开启） */
    suspend fun getOverlayEnabled(): Boolean = dataStore.data.first()[Keys.OVERLAY_ENABLED] ?: true
    suspend fun setOverlayEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.OVERLAY_ENABLED] = enabled }
    }

    // ── Ride mode ────────────────────────────────────────

    /** 骑行模式是否开启（悬浮窗保持屏幕常亮） */
    val rideModeEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.RIDE_MODE] ?: false }

    suspend fun getRideModeEnabled(): Boolean =
        dataStore.data.first()[Keys.RIDE_MODE] ?: false

    suspend fun setRideModeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.RIDE_MODE] = enabled }
    }

    // ── Sound ───────────────────────────────────────────

    suspend fun getSoundVolume(): Int = dataStore.data.first()[Keys.SOUND_VOLUME] ?: 70
    suspend fun setSoundVolume(volume: Int) {
        dataStore.edit { it[Keys.SOUND_VOLUME] = volume.coerceIn(0, 100) }
    }

    suspend fun getLeftFreq(): Int = dataStore.data.first()[Keys.SOUND_LEFT_FREQ] ?: 1000
    suspend fun setLeftFreq(hz: Int) {
        dataStore.edit { it[Keys.SOUND_LEFT_FREQ] = hz.coerceIn(100, 2000) }
    }

    suspend fun getRightFreq(): Int = dataStore.data.first()[Keys.SOUND_RIGHT_FREQ] ?: 400
    suspend fun setRightFreq(hz: Int) {
        dataStore.edit { it[Keys.SOUND_RIGHT_FREQ] = hz.coerceIn(100, 2000) }
    }

    /** 告警音量跟随的音频流：0=媒体（默认，支持蓝牙耳机），1=闹钟（可穿透其他声音） */
    suspend fun getSoundStream(): Int = dataStore.data.first()[Keys.SOUND_STREAM] ?: 0
    suspend fun setSoundStream(mode: Int) {
        dataStore.edit { it[Keys.SOUND_STREAM] = mode }
    }
}
