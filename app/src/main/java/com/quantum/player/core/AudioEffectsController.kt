package com.quantum.player.core

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * On-screen tuner state and attachment logic for the EQ bottom sheet.
 *
 * The equalizer / bass boost attach to the player's **audio session id** with
 * the platform [android.media.audiofx] APIs (no backend dependency, works for
 * every engine implementation). The 200% volume boost itself lives in the
 * ExoPlayer audio processor chain (see PlaybackManager) because audiofx has no
 * post-fader gain stage; this class owns the equalizer and bass boost only.
 *
 * Nothing here is a Settings screen: it is created per playback session and
 * driven directly from the HUD's 🎛️ chip.
 */
class AudioEffectsController {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null

    private val _bands = MutableStateFlow<List<EqBand>>(emptyList())
    val bands: StateFlow<List<EqBand>> = _bands.asStateFlow()

    private val _bassBoostStrength = MutableStateFlow(0)
    val bassBoostStrength: StateFlow<Int> = _bassBoostStrength.asStateFlow()

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    /** Number of equalizer bands the device exposes. */
    val bandCount: Int get() = equalizer?.numberOfBands?.toInt() ?: 0

    /**
     * Attach effects to [audioSessionId]. Safe to call again with a new session
     * id (the old effects are released first). Failures are logged and leave
     * [available] false so the UI shows the tuner as unavailable, never crashing.
     */
    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId == AudioSessionUnavailable) return
        try {
            val eq = Equalizer(0, audioSessionId).also { it.enabled = true }
            val bands = (0 until eq.numberOfBands.toInt()).map { index ->
                val levelRange = eq.getBandLevelRange()
                EqBand(
                    index = index,
                    centerFreqHz = eq.getCenterFreq(index.toShort()) / 1000,
                    levelMb = eq.getBandLevel(index.toShort()).toInt(),
                    minLevelMb = levelRange[0].toInt(),
                    maxLevelMb = levelRange[1].toInt()
                )
            }
            equalizer = eq
            val boost = BassBoost(0, audioSessionId).also { it.enabled = true }
            bassBoost = boost
            _bands.value = bands
            _bassBoostStrength.value = boost.roundedStrength.toInt().coerceIn(0, 1000)
            _available.value = true
        } catch (t: Throwable) {
            // Equalizer unsupported / no audio output yet: the sheet degrades.
            Log.w(TAG, "Audio effects unavailable: ${t.message}")
            _available.value = false
        }
    }

    /** Set one band's gain in millibels (clamped to the device range). */
    fun setBandLevel(bandIndex: Int, levelMb: Int) {
        val eq = equalizer ?: return
        val band = _bands.value.getOrNull(bandIndex) ?: return
        val clamped = levelMb.coerceIn(band.minLevelMb, band.maxLevelMb).toShort()
        runCatching { eq.setBandLevel(bandIndex.toShort(), clamped) }
        _bands.value = _bands.value.toMutableList().also {
            it[bandIndex] = it[bandIndex].copy(levelMb = clamped.toInt())
        }
    }

    /** Bass boost strength 0..1000. */
    fun setBassBoost(strength: Int) {
        val boost = bassBoost ?: return
        val clamped = strength.coerceIn(0, 1000).toShort()
        runCatching {
            boost.setStrength(clamped)
            boost.enabled = clamped > 0
        }
        _bassBoostStrength.value = clamped.toInt()
    }

    /** Reset every band to flat and disable bass boost. */
    fun reset() {
        equalizer?.let { eq ->
            (0 until eq.numberOfBands.toInt()).forEach { index ->
                runCatching { eq.setBandLevel(index.toShort(), 0) }
            }
        }
        bassBoost?.let {
            runCatching { it.setStrength(0); it.enabled = false }
        }
        _bands.value = _bands.value.map { it.copy(levelMb = 0) }
        _bassBoostStrength.value = 0
    }

    /** Release the native effect handles. */
    fun release() {
        runCatching { equalizer?.enabled = false; equalizer?.release() }
        runCatching { bassBoost?.enabled = false; bassBoost?.release() }
        equalizer = null
        bassBoost = null
        _available.value = false
        _bands.value = emptyList()
    }

    /** One equalizer band: center frequency plus current/range gain in millibels. */
    data class EqBand(
        val index: Int,
        val centerFreqHz: Int,
        val levelMb: Int,
        val minLevelMb: Int,
        val maxLevelMb: Int
    ) {
        /** 0f..1f slider position for the band gain. */
        val fraction: Float
            get() = if (maxLevelMb == minLevelMb) 0.5f
            else (levelMb - minLevelMb).toFloat() / (maxLevelMb - minLevelMb)
    }

    companion object {
        private const val TAG = "QuantumAudioFx"
        const val AudioSessionUnavailable = -1
    }
}
