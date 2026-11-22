package com.universalremote.tv.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures raw PCM audio for the TV's native voice channel.
 *
 * The Android TV remote protocol carries 16-bit PCM, mono, 8 kHz samples in protobuf payloads.
 * The reference implementation sends chunks of at most 20 KB and pads every chunk to at least
 * 8 KB, otherwise some devices (Shield TV) reject the stream, so that is reproduced here.
 * Nothing is written to disk.
 */
class VoiceStreamer(
    private val scope: CoroutineScope,
    private val onChunk: (ByteArray) -> Unit,
) {

    private var record: AudioRecord? = null
    private var job: Job? = null
    private var pending = ByteArray(0)

    val isRunning: Boolean get() = job?.isActive == true

    @SuppressLint("MissingPermission") // permission is checked by the caller before starting
    fun start(): Boolean {
        if (isRunning) return true
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer <= 0) {
            Log.w(TAG, "Unsupported audio configuration (min buffer $minBuffer)")
            return false
        }
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                maxOf(minBuffer * 2, CHUNK_SIZE_BYTES),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not create AudioRecord", e)
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        record = recorder
        pending = ByteArray(0)
        return try {
            recorder.startRecording()
            job = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(CHUNK_SIZE_BYTES / 4)
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) {
                        if (read < 0) break else continue
                    }
                    append(buffer, read)
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start recording", e)
            release()
            false
        }
    }

    /** Stops capture and sends the padded remainder so the TV closes the stream cleanly. */
    fun stop() {
        job?.cancel()
        job = null
        try {
            record?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Stop failed", e)
        }
        release()
        if (pending.isNotEmpty()) {
            onChunk(pad(pending))
            pending = ByteArray(0)
        }
    }

    private fun append(buffer: ByteArray, length: Int) {
        val merged = ByteArray(pending.size + length)
        System.arraycopy(pending, 0, merged, 0, pending.size)
        System.arraycopy(buffer, 0, merged, pending.size, length)
        var offset = 0
        while (merged.size - offset >= CHUNK_SIZE_BYTES) {
            onChunk(merged.copyOfRange(offset, offset + CHUNK_SIZE_BYTES))
            offset += CHUNK_SIZE_BYTES
        }
        pending = if (offset == 0) merged else merged.copyOfRange(offset, merged.size)
    }

    private fun pad(chunk: ByteArray): ByteArray =
        if (chunk.size >= MIN_CHUNK_BYTES) chunk else chunk.copyOf(MIN_CHUNK_BYTES)

    private fun release() {
        try {
            record?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Release failed", e)
        }
        record = null
    }

    companion object {
        private const val TAG = "VoiceStreamer"
        const val SAMPLE_RATE = 8000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE_BYTES = 20 * 1024
        private const val MIN_CHUNK_BYTES = 8 * 1024
    }
}
