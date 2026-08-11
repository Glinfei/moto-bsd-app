package com.motobsd.ui.overlay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.motobsd.data.overlay.OverlayRepository
import com.motobsd.model.LightBarOrientation
import com.motobsd.model.OverlayConfig
import com.motobsd.model.OverlaySize
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OverlayViewModel @Inject constructor(
    private val overlayRepository: OverlayRepository,
    private val soundManager: com.motobsd.audio.SoundManager,
) : ViewModel() {

    private val _config = MutableStateFlow(OverlayConfig())
    val config: StateFlow<OverlayConfig> = _config.asStateFlow()

    private val _soundVolume = MutableStateFlow(70)
    val soundVolume: StateFlow<Int> = _soundVolume.asStateFlow()

    private val _leftFreq = MutableStateFlow(1000)
    val leftFreq: StateFlow<Int> = _leftFreq.asStateFlow()

    private val _rightFreq = MutableStateFlow(400)
    val rightFreq: StateFlow<Int> = _rightFreq.asStateFlow()

    /** true=媒体音量（默认），false=闹钟音量 */
    private val _mediaStream = MutableStateFlow(true)
    val mediaStream: StateFlow<Boolean> = _mediaStream.asStateFlow()

    private val _overlayRunning = MutableStateFlow(com.motobsd.service.OverlayService.isRunning)
    /** 悬浮窗开关状态（初始化自持久化偏好，默认开启） */
    val overlayRunning: StateFlow<Boolean> = _overlayRunning.asStateFlow()

    init {
        viewModelScope.launch {
            _config.value = overlayRepository.loadConfig()
            _soundVolume.value = overlayRepository.loadSoundVolume()
            _leftFreq.value = overlayRepository.loadLeftFreq()
            _rightFreq.value = overlayRepository.loadRightFreq()
            _mediaStream.value = overlayRepository.loadSoundStream() == 0
            _overlayRunning.value = overlayRepository.loadOverlayEnabled()
            // 同步到 SoundManager
            soundManager.setVolume(_soundVolume.value)
            soundManager.setLeftFreq(_leftFreq.value)
            soundManager.setRightFreq(_rightFreq.value)
            soundManager.setStreamMode(_mediaStream.value)
        }
    }

    fun updateConfig(config: OverlayConfig) {
        _config.value = config
        overlayRepository.updateConfig(config)
    }

    fun toggleLightBarOrientation() {
        val newOrientation = if (_config.value.lightBarOrientation == LightBarOrientation.Vertical)
            LightBarOrientation.Horizontal else LightBarOrientation.Vertical
        updateConfig(_config.value.copy(lightBarOrientation = newOrientation))
    }

    fun updateSoundVolume(volume: Int) {
        _soundVolume.value = volume
        soundManager.setVolume(volume)
        viewModelScope.launch {
            overlayRepository.saveSoundVolume(volume)
        }
    }

    fun previewLeft() = soundManager.previewLeft()
    fun previewRight() = soundManager.previewRight()
    fun previewCriticalLeft() = soundManager.previewCriticalLeft()
    fun previewCriticalRight() = soundManager.previewCriticalRight()

    fun updateLeftFreq(hz: Int) {
        _leftFreq.value = hz
        soundManager.setLeftFreq(hz)
        viewModelScope.launch { overlayRepository.saveLeftFreq(hz) }
    }

    fun updateRightFreq(hz: Int) {
        _rightFreq.value = hz
        soundManager.setRightFreq(hz)
        viewModelScope.launch { overlayRepository.saveRightFreq(hz) }
    }

    fun updateStreamMode(media: Boolean) {
        _mediaStream.value = media
        soundManager.setStreamMode(media)
        viewModelScope.launch { overlayRepository.saveSoundStream(if (media) 0 else 1) }
    }

    fun setOverlayRunning(running: Boolean) {
        _overlayRunning.value = running
        viewModelScope.launch {
            overlayRepository.saveOverlayEnabled(running)
        }
    }

    // ── Test Alert ────────────────────────────────────────

    private val _testLeft = MutableStateFlow(com.motobsd.model.AlertLevel.Safe)
    val testLeft: StateFlow<com.motobsd.model.AlertLevel> = _testLeft.asStateFlow()
    private val _testRight = MutableStateFlow(com.motobsd.model.AlertLevel.Safe)
    val testRight: StateFlow<com.motobsd.model.AlertLevel> = _testRight.asStateFlow()

    fun toggleTestLeft() {
        _testLeft.value = com.motobsd.model.AlertLevel.entries
            .getOrElse((_testLeft.value.ordinal + 1) % 4) { com.motobsd.model.AlertLevel.Safe }
        com.motobsd.overlay.OverlayWindowHolder.setTestMode(true)
        com.motobsd.overlay.OverlayWindowHolder.setTestThreat(
            threatFor(_testLeft.value), threatFor(_testRight.value))
        soundManager.updateAlert(_testLeft.value, _testRight.value)
    }

    fun toggleTestRight() {
        _testRight.value = com.motobsd.model.AlertLevel.entries
            .getOrElse((_testRight.value.ordinal + 1) % 4) { com.motobsd.model.AlertLevel.Safe }
        com.motobsd.overlay.OverlayWindowHolder.setTestMode(true)
        com.motobsd.overlay.OverlayWindowHolder.setTestThreat(
            threatFor(_testLeft.value), threatFor(_testRight.value))
        soundManager.updateAlert(_testLeft.value, _testRight.value)
    }

    fun resetTest() {
        _testLeft.value = com.motobsd.model.AlertLevel.Safe
        _testRight.value = com.motobsd.model.AlertLevel.Safe
        com.motobsd.overlay.OverlayWindowHolder.setTestMode(false)
        com.motobsd.overlay.OverlayWindowHolder.setTestThreat(0f, 0f)
        soundManager.updateAlert(
            com.motobsd.model.AlertLevel.Safe, com.motobsd.model.AlertLevel.Safe)
    }

    /** 测试告警等级 → 威胁度（与真实数据的 0~1 连续映射对齐） */
    private fun threatFor(level: com.motobsd.model.AlertLevel): Float = when (level) {
        com.motobsd.model.AlertLevel.Safe -> 0f
        com.motobsd.model.AlertLevel.Warning -> 0.45f
        com.motobsd.model.AlertLevel.Alert -> 0.75f
        com.motobsd.model.AlertLevel.Critical -> 1f
    }
}
