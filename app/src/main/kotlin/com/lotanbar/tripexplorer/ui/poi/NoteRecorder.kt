package com.lotanbar.tripexplorer.ui.poi

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * Records a POI's spoken note with the phone's microphone: Opus, 32 kbps, in an Ogg file (.opus).
 * Pause / resume keep the same file.
 */
class NoteRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    fun start(target: File) {
        target.parentFile?.mkdirs()
        val r = MediaRecorder(context)
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32_000)
            r.setAudioSamplingRate(48_000)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
        output = target
    }

    fun pause() { recorder?.pause() }
    fun resume() { recorder?.resume() }

    /** The loudest sample since the last call (0..32767): what the soundwave shows. */
    fun maxAmplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    /** Stops and returns the file, or null (and no file) if nothing usable was recorded. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val f = output
        output = null
        return if (ok && f != null && f.length() > 0) f else { f?.delete(); null }
    }
}
