package com.motobsd.data.overlay

import com.motobsd.data.settings.SettingsRepository
import com.motobsd.model.LightBarOrientation
import com.motobsd.model.OverlayConfig
import com.motobsd.model.OverlaySize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 悬浮窗配置持久化。
 *
 * 配置变更通过 [configFlow] 内存直通 OverlayService，不经过 DataStore 中转，
 * 避免异步读写竞态。
 */
class OverlayRepository(
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 悬浮窗开关偏好（默认开启） */
    suspend fun loadOverlayEnabled(): Boolean = settings.getOverlayEnabled()
    suspend fun saveOverlayEnabled(enabled: Boolean) { settings.setOverlayEnabled(enabled) }

    suspend fun loadRideModeEnabled(): Boolean = settings.getRideModeEnabled()
    suspend fun saveRideModeEnabled(enabled: Boolean) { settings.setRideModeEnabled(enabled) }

    private val _configFlow = MutableStateFlow(OverlayConfig())
    /** 实时配置流 — OverlayService 观察此流自动应用变更 */
    val configFlow: StateFlow<OverlayConfig> = _configFlow.asStateFlow()

    /** 首次加载后获取当前值（不触发 collect） */
    fun currentConfig(): OverlayConfig = _configFlow.value

    suspend fun loadConfig(): OverlayConfig {
        val config = OverlayConfig(
            size = OverlaySize.entries.getOrElse(settings.getOverlaySize()) { OverlaySize.Medium },
            alpha = settings.getOverlayAlpha().coerceIn(35, 100),
            swapLeftRight = settings.getOverlaySwap(),
            lightBarOrientation = LightBarOrientation.entries
                .getOrElse(settings.getOverlayOrientation()) { LightBarOrientation.Vertical },
        )
        _configFlow.value = config
        return config
    }

    /** 更新配置：内存立即生效 + 异步持久化 */
    fun updateConfig(config: OverlayConfig) {
        _configFlow.value = config
        scope.launch {
            settings.setOverlaySize(config.size.ordinal)
            settings.setOverlayAlpha(config.alpha)
            settings.setOverlaySwap(config.swapLeftRight)
            settings.setOverlayOrientation(config.lightBarOrientation.ordinal)
        }
    }

    // ── Sound ───────────────────────────────────────────

    suspend fun loadSoundVolume(): Int = settings.getSoundVolume()
    suspend fun saveSoundVolume(vol: Int) { settings.setSoundVolume(vol) }

    suspend fun loadLeftFreq(): Int = settings.getLeftFreq()
    suspend fun saveLeftFreq(hz: Int) { settings.setLeftFreq(hz) }
    suspend fun loadRightFreq(): Int = settings.getRightFreq()
    suspend fun saveRightFreq(hz: Int) { settings.setRightFreq(hz) }

    suspend fun loadSoundStream(): Int = settings.getSoundStream()
    suspend fun saveSoundStream(mode: Int) { settings.setSoundStream(mode) }
}
