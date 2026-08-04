package com.motobsd.ui.overlay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.motobsd.data.overlay.OverlayRepository
import com.motobsd.model.OverlayConfig
import com.motobsd.model.OverlaySize
import com.motobsd.model.OverlayStyle
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

    init {
        viewModelScope.launch {
            _config.value = overlayRepository.loadConfig()
            _soundVolume.value = overlayRepository.loadSoundVolume()
            _leftFreq.value = overlayRepository.loadLeftFreq()
            _rightFreq.value = overlayRepository.loadRightFreq()
            // 同步到 SoundManager
            soundManager.setLeftFreq(_leftFreq.value)
            soundManager.setRightFreq(_rightFreq.value)
        }
    }

    fun updateConfig(config: OverlayConfig) {
        _config.value = config
        overlayRepository.updateConfig(config)
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

    fun onResetPosition() {
        viewModelScope.launch {
            overlayRepository.resetPositions()
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
        com.motobsd.overlay.OverlayWindowHolder.updateAlert(_testLeft.value, _testRight.value)
        soundManager.updateAlert(_testLeft.value, _testRight.value)
    }

    fun toggleTestRight() {
        _testRight.value = com.motobsd.model.AlertLevel.entries
            .getOrElse((_testRight.value.ordinal + 1) % 4) { com.motobsd.model.AlertLevel.Safe }
        com.motobsd.overlay.OverlayWindowHolder.updateAlert(_testLeft.value, _testRight.value)
        soundManager.updateAlert(_testLeft.value, _testRight.value)
    }

    fun resetTest() {
        _testLeft.value = com.motobsd.model.AlertLevel.Safe
        _testRight.value = com.motobsd.model.AlertLevel.Safe
        com.motobsd.overlay.OverlayWindowHolder.updateAlert(
            com.motobsd.model.AlertLevel.Safe, com.motobsd.model.AlertLevel.Safe)
        soundManager.updateAlert(
            com.motobsd.model.AlertLevel.Safe, com.motobsd.model.AlertLevel.Safe)
    }
}
