package com.quantum.player.core

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Software volume boost [AudioProcessor] for the ExoPlayer render chain.
 *
 * Android's stream volume caps at 100%; mpv-style players boost *after*
 * decoding instead. This processor applies a linear gain (1.0f = 100%, up to
 * 2.0f = 200%) to every PCM sample. Hard clipping is used deliberately: like
 * MX Player's "volume boost", loud peaks clip rather than wrapping, which
 * produces far less objectionable distortion.
 *
 * 16-bit PCM only (the format [android.media.MediaCodec] decoders and
 * [androidx.media3.exoplayer.audio.DefaultAudioSink] emit by default); any other
 * encoding passes through untouched.
 */
@UnstableApi
class VolumeBoostAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var gain: Float = 1.0f

    /** Set the post-decode gain; 1.0 = passthrough, up to 2.0 (200%). */
    fun setGain(value: Float) {
        // queueInput() checks the live [gain] field for every buffer, so a gain
        // change takes effect on the very next frame without reconfiguring the
        // sink chain (isActive() stays true so the processor is never skipped).
        gain = value.coerceIn(0f, MAX_GAIN)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.sampleRate != AudioFormat.NO_VALUE &&
            inputAudioFormat.channelCount != AudioFormat.NO_VALUE
        ) {
            inputAudioFormat
        } else {
            AudioFormat.NOT_SET
        }

    // Always active once configured: passthrough at gain == 1f is applied in
    // queueInput(), so toggling the boost never detaches the processor.
    override fun isActive(): Boolean = super.isActive()

    override fun queueInput(inputBuffer: ByteBuffer) {
        val currentGain = gain
        val size = inputBuffer.remaining()
        if (size <= 0) return

        val output = replaceOutputBuffer(size)
        if (currentGain == 1.0f) {
            // Passthrough: copy the bytes straight across.
            output.put(inputBuffer)
        } else {
            // 16-bit little-endian PCM: scale each short sample with hard clip.
            val outShorts = output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val inShorts = inputBuffer.asShortBuffer()
            val sampleCount = inShorts.remaining()
            for (i in 0 until sampleCount) {
                val boosted = inShorts.get(i).toInt() * currentGain
                val clipped = when {
                    boosted > Short.MAX_VALUE.toInt() -> Short.MAX_VALUE
                    boosted < Short.MIN_VALUE.toInt() -> Short.MIN_VALUE
                    else -> boosted.toInt().toShort()
                }
                outShorts.put(i, clipped)
            }
            // Advance the byte buffer past the written samples.
            output.position(output.position() + sampleCount * 2)
            inputBuffer.position(inputBuffer.limit())
        }
        output.flip()
    }

    private companion object {
        const val MAX_GAIN = 2.0f
    }
}
