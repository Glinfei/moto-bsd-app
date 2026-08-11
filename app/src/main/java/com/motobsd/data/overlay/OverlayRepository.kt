package com.motobsd.data.overlay

import com.motobsd.data.settings.SettingsRepository
import com.motobsd.model.LightBarOrientation
import com.motobsd.model.OverlayConfig
import com.motobsd.model.OverlaySize
import com.motobsd.model.OverlayStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 悬浮窗配置 + 位置持久化。
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

    private val _configFlow = MutableStateFlow(OverlayConfig())
    /** 实时配置流 — OverlayService 观察此流自动应用变更 */
    val configFlow: StateFlow<OverlayConfig> = _configFlow.asStateFlow()

    /** 首次加载后获取当前值（不触发 collect） */
    fun currentConfig(): OverlayConfig = _configFlow.value

    suspend fun loadConfig(): OverlayConfig {
        // 迁移：旧版本默认 Dot → 新版本默认 LightBar
        val styleOrdinal = settings.getOverlayStyle()
        val migrated = if (styleOrdinal == 0 && !settings.isStyleMigrated()) {
            settings.setStyleMigrated()
            OverlayStyle.LightBar.ordinal
        } else {
            styleOrdinal
        }
        val config = OverlayConfig(
            style = OverlayStyle.entries.getOrElse(migrated) { OverlayStyle.LightBar },
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
            settings.setOverlayStyle(config.style.ordinal)
            settings.setOverlaySize(config.size.ordinal)
            settings.setOverlayAlpha(config.alpha)
            settings.setOverlaySwap(config.swapLeftRight)
            settings.setOverlayOrientation(config.lightBarOrientation.ordinal)
        }
    }

    suspend fun loadPosition(side: BsdSide): Pair<Int?, Int?> {
        return if (side == BsdSide.Left) {
            (settings.getLeftX() to settings.getLeftY())
        } else {
            (settings.getRightX() to settings.getRightY())
        }
    }

    /** 保存位置时的屏幕物理尺寸，旋转后按比例换算坐标用 */
    suspend fun loadScreenDims(side: BsdSide): Pair<Int, Int> {
        return if (side == BsdSide.Left) {
            (settings.getLeftScreenW() to settings.getLeftScreenH())
        } else {
            (settings.getRightScreenW() to settings.getRightScreenH())
        }
    }

    suspend fun savePosition(side: BsdSide, x: Int, y: Int) {
        if (side == BsdSide.Left) {
            settings.setLeftX(x)
            settings.setLeftY(y)
        } else {
            settings.setRightX(x)
            settings.setRightY(y)
        }
    }

    suspend fun saveScreenDims(side: BsdSide, w: Int, h: Int) {
        if (side == BsdSide.Left) {
            settings.setLeftScreenW(w)
            settings.setLeftScreenH(h)
        } else {
            settings.setRightScreenW(w)
            settings.setRightScreenH(h)
        }
    }

    suspend fun resetPositions() {
        settings.clearPositions()
        settings.clearScreenDims()
    }

    // ── Sound ───────────────────────────────────────────

    suspend fun loadSoundVolume(): Int = settings.getSoundVolume()
    suspend fun saveSoundVolume(vol: Int) { settings.setSoundVolume(vol) }

    suspend fun loadLeftFreq(): Int = settings.getLeftFreq()
    suspend fun saveLeftFreq(hz: Int) { settings.setLeftFreq(hz) }
    suspend fun loadRightFreq(): Int = settings.getRightFreq()
    suspend fun saveRightFreq(hz: Int) { settings.setRightFreq(hz) }
}

enum class BsdSide { Left, Right }
