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
 * - 悬浮窗配置（样式/大小/透明度/是否反转）
 * - 悬浮窗位置
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
        val OVERLAY_STYLE = intPreferencesKey("overlay_style")
        val OVERLAY_SIZE = intPreferencesKey("overlay_size")
        val OVERLAY_ALPHA = intPreferencesKey("overlay_alpha")
        val OVERLAY_SWAP = booleanPreferencesKey("overlay_swap")
        val OVERLAY_ORIENTATION = intPreferencesKey("overlay_orientation")
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")

        // Overlay position
        val LEFT_X = intPreferencesKey("left_x")
        val LEFT_Y = intPreferencesKey("left_y")
        val RIGHT_X = intPreferencesKey("right_x")
        val RIGHT_Y = intPreferencesKey("right_y")
        val LEFT_SCREEN_W = intPreferencesKey("left_screen_w")
        val LEFT_SCREEN_H = intPreferencesKey("left_screen_h")
        val RIGHT_SCREEN_W = intPreferencesKey("right_screen_w")
        val RIGHT_SCREEN_H = intPreferencesKey("right_screen_h")

        // Sound
        val SOUND_VOLUME = intPreferencesKey("sound_volume")
        val SOUND_LEFT_FREQ = intPreferencesKey("sound_left_freq")
        val SOUND_RIGHT_FREQ = intPreferencesKey("sound_right_freq")

        val STYLE_MIGRATED = booleanPreferencesKey("style_migrated_v1")
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

    suspend fun getOverlayStyle(): Int = dataStore.data.first()[Keys.OVERLAY_STYLE] ?: 0
    suspend fun setOverlayStyle(ordinal: Int) {
        dataStore.edit { it[Keys.OVERLAY_STYLE] = ordinal }
    }

    suspend fun getOverlaySize(): Int = dataStore.data.first()[Keys.OVERLAY_SIZE] ?: 2
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

    // ── Overlay position ──────────────────────────────────

    suspend fun getLeftX(): Int? = dataStore.data.first()[Keys.LEFT_X]
    suspend fun setLeftX(x: Int) { dataStore.edit { it[Keys.LEFT_X] = x } }
    suspend fun getLeftY(): Int? = dataStore.data.first()[Keys.LEFT_Y]
    suspend fun setLeftY(y: Int) { dataStore.edit { it[Keys.LEFT_Y] = y } }
    suspend fun getRightX(): Int? = dataStore.data.first()[Keys.RIGHT_X]
    suspend fun setRightX(x: Int) { dataStore.edit { it[Keys.RIGHT_X] = x } }
    suspend fun getRightY(): Int? = dataStore.data.first()[Keys.RIGHT_Y]
    suspend fun setRightY(y: Int) { dataStore.edit { it[Keys.RIGHT_Y] = y } }

    suspend fun getLeftScreenW(): Int = dataStore.data.first()[Keys.LEFT_SCREEN_W] ?: 0
    suspend fun setLeftScreenW(w: Int) { dataStore.edit { it[Keys.LEFT_SCREEN_W] = w } }
    suspend fun getLeftScreenH(): Int = dataStore.data.first()[Keys.LEFT_SCREEN_H] ?: 0
    suspend fun setLeftScreenH(h: Int) { dataStore.edit { it[Keys.LEFT_SCREEN_H] = h } }
    suspend fun getRightScreenW(): Int = dataStore.data.first()[Keys.RIGHT_SCREEN_W] ?: 0
    suspend fun setRightScreenW(w: Int) { dataStore.edit { it[Keys.RIGHT_SCREEN_W] = w } }
    suspend fun getRightScreenH(): Int = dataStore.data.first()[Keys.RIGHT_SCREEN_H] ?: 0
    suspend fun setRightScreenH(h: Int) { dataStore.edit { it[Keys.RIGHT_SCREEN_H] = h } }

    suspend fun clearPositions() {
        dataStore.edit {
            it.remove(Keys.LEFT_X)
            it.remove(Keys.LEFT_Y)
            it.remove(Keys.RIGHT_X)
            it.remove(Keys.RIGHT_Y)
        }
    }

    suspend fun clearScreenDims() {
        dataStore.edit {
            it.remove(Keys.LEFT_SCREEN_W)
            it.remove(Keys.LEFT_SCREEN_H)
            it.remove(Keys.RIGHT_SCREEN_W)
            it.remove(Keys.RIGHT_SCREEN_H)
        }
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

    // ── Migration ───────────────────────────────────────

    suspend fun isStyleMigrated(): Boolean =
        dataStore.data.first()[Keys.STYLE_MIGRATED] ?: false

    suspend fun setStyleMigrated() {
        dataStore.edit { it[Keys.STYLE_MIGRATED] = true }
    }
}
