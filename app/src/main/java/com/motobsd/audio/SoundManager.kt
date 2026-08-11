package com.motobsd.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.motobsd.model.AlertLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * 盲区告警声音管理器。
 *
 * 使用 AudioTrack 实时生成 PCM 三角波音频。
 *
 * 声音设计（基于 ISO 11429 / NHTSA / TU Dresden 2024）：
 * ┌──────┬──────────┬────────────────────┬──────────────────────┐
 * │ 侧   │ 波形     │ Warning            │ Critical             │
 * ├──────┼──────────┼────────────────────┼──────────────────────┤
 * │ 左   │ 三角波   │ 3声×100ms 间隔500ms│ 5声×80ms 间隔100ms   │
 * │ 右   │ 三角波   │ 同上（仅频率不同） │ 同上                 │
 * └──────┴──────────┴────────────────────┴──────────────────────┘
 */
class SoundManager {

    private val _volume = MutableStateFlow(70)
    val volume: StateFlow<Int> = _volume.asStateFlow()

    private var leftFreq = 1000
    private var rightFreq = 400

    @Volatile private var active = false
    /** true=媒体音量（默认，支持蓝牙耳机）；false=闹钟音量（可穿透其他声音） */
    @Volatile private var useMediaStream = true
    private var currentThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())

    private var currentLeftLevel: AlertLevel = AlertLevel.Safe
    private var currentRightLevel: AlertLevel = AlertLevel.Safe

    // ── Public API ────────────────────────────────────────

    fun setVolume(percent: Int) {
        _volume.value = percent.coerceIn(0, 100)
    }

    fun setLeftFreq(hz: Int) { leftFreq = hz.coerceIn(100, 2000) }
    fun setRightFreq(hz: Int) { rightFreq = hz.coerceIn(100, 2000) }
    fun getLeftFreq(): Int = leftFreq
    fun getRightFreq(): Int = rightFreq

    /** 设置告警音跟随的音频流 */
    fun setStreamMode(media: Boolean) {
        useMediaStream = media
    }

    fun updateAlert(left: AlertLevel, right: AlertLevel) {
        currentLeftLevel = left
        currentRightLevel = right
        val hasAlert = left >= AlertLevel.Warning || right >= AlertLevel.Warning

        if (hasAlert && !active) {
            active = true
            startLoop()
        } else if (!hasAlert && active) {
            stopSound()
        }
    }

    fun previewLeft() {
        Thread {
            playPattern(
                AlertLevel.Warning,
                leftFreq,
                (leftFreq * 1.2).toInt().coerceAtMost(2500),
                requireActive = false,
            )
        }.start()
    }

    fun previewRight() {
        Thread { playPattern(AlertLevel.Warning, rightFreq, rightFreq, requireActive = false) }.start()
    }

    /** 预览 Critical（试听紧急音效） */
    fun previewCriticalLeft() {
        Thread {
            playPattern(
                AlertLevel.Critical,
                leftFreq,
                (leftFreq * 1.2).toInt().coerceAtMost(2500),
                requireActive = false,
            )
        }.start()
    }

    fun previewCriticalRight() {
        Thread { playPattern(AlertLevel.Critical, rightFreq, rightFreq, requireActive = false) }.start()
    }

    fun release() {
        stopSound()
    }

    // ── Playback Loop ─────────────────────────────────────

    private fun startLoop() {
        handler.post { scheduleNext() }
    }

    private fun scheduleNext() {
        if (!active) return

        val (side, level) = when {
            currentLeftLevel >= AlertLevel.Critical -> "left" to AlertLevel.Critical
            currentRightLevel >= AlertLevel.Critical -> "right" to AlertLevel.Critical
            currentLeftLevel >= AlertLevel.Warning -> "left" to AlertLevel.Warning
            currentRightLevel >= AlertLevel.Warning -> "right" to AlertLevel.Warning
            else -> { stopSound(); return }
        }

        // 在同一线程串行播放，可被 stopSound 中断
        currentThread = Thread {
            if (!active) return@Thread
            if (side == "left") {
                val critFreq = (leftFreq * 1.2).toInt().coerceAtMost(2500)
                playPattern(level, leftFreq, critFreq)
            } else {
                playPattern(level, rightFreq, rightFreq)
            }
        }.also { it.start() }

        val interval = when (level) {
            AlertLevel.Warning -> 3000L
            AlertLevel.Critical -> 1500L
            else -> 2000L
        }
        handler.postDelayed({ scheduleNext() }, interval)
    }

    private fun stopSound() {
        active = false
        handler.removeCallbacksAndMessages(null)
        // 中断正在播放的线程
        currentThread?.interrupt()
        currentThread = null
    }

    // ── Pattern ─────────────────────────────────────────

    private fun playPattern(level: AlertLevel, freqWarn: Int, freqCrit: Int) {
        playPattern(level, freqWarn, freqCrit, requireActive = true)
    }

    /** 试听走 requireActive=false：不依赖"当前有告警"即可播放 */
    private fun playPattern(level: AlertLevel, freqWarn: Int, freqCrit: Int, requireActive: Boolean) {
        when (level) {
            AlertLevel.Warning -> {
                repeat(3) {
                    if (requireActive && !active) return
                    playTone(freqWarn, 100)
                    sleepOrInterrupt(500)
                }
            }
            AlertLevel.Critical -> {
                repeat(5) {
                    if (requireActive && !active) return
                    playTone(freqCrit, 80)
                    sleepOrInterrupt(100)
                }
            }
            else -> {}
        }
    }

    private fun sleepOrInterrupt(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    // ── PCM Generator ─────────────────────────────────────

    private fun playTone(freqHz: Int, durationMs: Int) {
        val sampleRate = 44100
        val numSamples = (sampleRate * durationMs / 1000.0).toInt()
        if (numSamples <= 0) return
        val vol = _volume.value / 100f

        val fadeInSamp = (sampleRate * 0.005).toInt()
        val fadeOutSamp = (sampleRate * 0.025).toInt()

        val buffer = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val phase = (freqHz.toDouble() * i / sampleRate) % 1.0
            val raw = (4.0 * abs(phase - 0.5) - 1.0)

            val env = when {
                i < fadeInSamp -> i.toDouble() / fadeInSamp
                i >= numSamples - fadeOutSamp -> (numSamples - i).toDouble() / fadeOutSamp
                else -> 1.0
            }
            buffer[i] = (raw * env * Short.MAX_VALUE * vol).toInt().toShort()
        }

        try {
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(
                        if (useMediaStream) AudioAttributes.USAGE_MEDIA
                        else AudioAttributes.USAGE_ALARM
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                numSamples * 2,
                AudioTrack.MODE_STATIC,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            track.write(buffer, 0, numSamples)
            track.play()
            try { Thread.sleep(durationMs.toLong() + 40) } catch (_: InterruptedException) { }
            track.stop()
            track.release()
        } catch (_: Exception) { }
    }
}
