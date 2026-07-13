package no.blckswan.monicakey

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

object AudioCallEngine {
    private const val SAMPLE_RATE = 16_000
    private val incoming = LinkedBlockingQueue<ByteArray>(200)

    @Volatile private var running = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    private var audioManager: AudioManager? = null

    fun start(context: Context): Boolean {
        val app = context.applicationContext
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppBus.status = "Mikrofontillatelse mangler"
            AppBus.changed()
            return false
        }
        if (running) return true
        running = true
        AppBus.callActive = true
        configureAudio(app)
        startPlayback(app)
        startCapture(app)
        AppBus.status = "Kryptert lydsamtale"
        AppBus.changed()
        return true
    }

    fun startPlayback(context: Context) {
        if (player != null) return
        val min = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(min)
            .build()
        player = track
        track.play()
        thread(name = "monica-key-audio-play", isDaemon = true) {
            while (running || incoming.isNotEmpty()) {
                val frame = incoming.take()
                if (frame.isEmpty()) continue
                runCatching { track.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING) }
            }
        }
    }

    private fun startCapture(context: Context) {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            min * 2
        )
        recorder = record
        record.startRecording()
        thread(name = "monica-key-audio-capture", isDaemon = true) {
            val frame = ByteArray(640)
            val live = LiveClient.get(context)
            while (running) {
                val count = runCatching { record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING) }.getOrDefault(0)
                if (count > 0) live.sendAudioFrame(frame.copyOf(count))
            }
        }
    }

    fun acceptFrame(frame: ByteArray) {
        if (!running || frame.isEmpty()) return
        if (!incoming.offer(frame)) {
            incoming.poll()
            incoming.offer(frame)
        }
    }

    fun stop() {
        if (!running && recorder == null && player == null) return
        running = false
        incoming.offer(ByteArray(0))
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        incoming.clear()
        audioManager?.mode = AudioManager.MODE_NORMAL
        audioManager = null
        AppBus.callActive = false
        AppBus.changed()
    }

    private fun configureAudio(context: Context) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        runCatching { audioManager?.isSpeakerphoneOn = false }
    }
}
