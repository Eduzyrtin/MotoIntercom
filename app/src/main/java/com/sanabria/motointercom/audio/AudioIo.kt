package com.sanabria.motointercom.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import com.sanabria.motointercom.core.audio.VoiceFormat
import com.sanabria.motointercom.engine.AudioMode
import com.sanabria.motointercom.engine.MicSource
import com.sanabria.motointercom.engine.VoiceOutput
import kotlin.concurrent.thread

/**
 * Captura y reproducción a 16 kHz mono en tramas de 20 ms, cada una en su hilo de prioridad de audio.
 *
 * - [AudioMode.HELMET]: modo comunicación y el casco como dispositivo de comunicación (HFP/SCO, o LE Audio si lo tiene).
 *   Micro y voces van por el casco.
 * - [AudioMode.MIX]: modo normal. Las voces salen como multimedia (A2DP, el sistema las mezcla con la música de otras
 *   apps) y se capta con el micro con cable/USB si hay, o con el del móvil.
 *
 * La reproducción escribe siempre (silencio si nadie habla): así el enlace A2DP/SCO no se duerme y la primera palabra
 * no llega tarde.
 */
@SuppressLint("MissingPermission")
class AudioIo(context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        /** Hilo de captura: una trama de [VoiceFormat.FRAME_SAMPLES] muestras. */
        fun onCapture(pcm: ShortArray)

        /** Hilo de reproducción: rellenar [out] con la mezcla de voces. */
        fun onPlayback(out: ShortArray)
    }

    /** Dispositivos por los que va el audio ahora mismo (tipo de AudioDeviceInfo, o null si no hay). */
    data class Route(val inputType: Int?, val inputName: String, val outputType: Int?, val outputName: String)

    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    @Volatile private var helmetRoute = false

    /** Si el casco se va (Bluetooth apagado, fuera de alcance) y vuelve, se recupera la ruta HFP sin tocar nada. */
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (helmetRoute && added.any { it.type in HELMET_TYPES }) enterHelmetRoute()
        }
    }

    fun start(mode: AudioMode, mic: MicSource, voiceOutput: VoiceOutput) {
        stop()
        if (mode == AudioMode.HELMET) {
            enterHelmetRoute()
            am.registerAudioDeviceCallback(devices, main)
        } else {
            am.mode = AudioManager.MODE_NORMAL
        }
        val rec = buildRecord(mode, mic)
        val trk = buildTrack(mode, voiceOutput)
        record = rec
        track = trk
        running = true
        captureThread = thread(name = "audio-capture") { captureLoop(rec) }
        playbackThread = thread(name = "audio-playback") { playbackLoop(trk) }
    }

    fun stop() {
        am.unregisterAudioDeviceCallback(devices)
        running = false
        captureThread?.join(500)
        playbackThread?.join(500)
        captureThread = null
        playbackThread = null
        record?.release()
        track?.release()
        record = null
        track = null
        if (helmetRoute) leaveHelmetRoute()
    }

    fun route(): Route {
        val i = record?.routedDevice
        val o = track?.routedDevice
        return Route(i?.type, i?.productName?.toString().orEmpty(), o?.type, o?.productName?.toString().orEmpty())
    }

    /** Pulsa una tecla multimedia (anterior, reproducir/pausa, siguiente): la recibe la app de música activa. */
    fun sendMediaKey(code: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun enterHelmetRoute() {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        helmetRoute = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val available = am.availableCommunicationDevices
            val helmet = available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (helmet != null && am.communicationDevice?.id != helmet.id) am.setCommunicationDevice(helmet)
        } else {
            @Suppress("DEPRECATION")
            am.startBluetoothSco()
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = true
        }
    }

    private fun leaveHelmetRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            am.stopBluetoothSco()
        }
        am.mode = AudioManager.MODE_NORMAL
        helmetRoute = false
    }

    private fun buildRecord(mode: AudioMode, mic: MicSource): AudioRecord {
        val source = if (mode == AudioMode.HELMET || mic == MicSource.VOICE) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }
        val min = AudioRecord.getMinBufferSize(VoiceFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
            .setBufferSizeInBytes(maxOf(min, FRAME_BYTES * 4))
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord" }
        if (mode == AudioMode.MIX) phoneMic()?.let { rec.setPreferredDevice(it) }
        return rec
    }

    private fun buildTrack(mode: AudioMode, voiceOutput: VoiceOutput): AudioTrack {
        val usage = if (mode == AudioMode.HELMET || voiceOutput == VoiceOutput.CALL) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        val min = AudioTrack.getMinBufferSize(VoiceFormat.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
            .setBufferSizeInBytes(maxOf(min, FRAME_BYTES * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    /** En modo mezcla: micro con cable o USB si hay uno; si no, el del móvil (nunca el del casco, que abriría HFP). */
    private fun phoneMic(): AudioDeviceInfo? {
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val wired = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        return inputs.firstOrNull { it.type in wired } ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    private fun format(channel: Int) = AudioFormat.Builder()
        .setSampleRate(VoiceFormat.SAMPLE_RATE)
        .setChannelMask(channel)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .build()

    private fun captureLoop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(VoiceFormat.FRAME_SAMPLES)
        rec.startRecording()
        try {
            while (running) {
                var got = 0
                while (got < buf.size && running) {
                    val n = rec.read(buf, got, buf.size - got)
                    if (n < 0) return
                    got += n
                }
                if (got == buf.size) callbacks.onCapture(buf)
            }
        } finally {
            rec.stop()
        }
    }

    private fun playbackLoop(trk: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val out = ShortArray(VoiceFormat.FRAME_SAMPLES)
        trk.play()
        try {
            while (running) {
                callbacks.onPlayback(out)
                var off = 0
                while (off < out.size && running) {
                    val n = trk.write(out, off, out.size - off)
                    if (n < 0) return
                    off += n
                }
            }
        } finally {
            trk.pause()
            trk.flush()
            trk.stop()
        }
    }

    companion object {
        private const val FRAME_BYTES = VoiceFormat.FRAME_SAMPLES * 2

        /** Tipos de dispositivo que son un casco por Bluetooth (LE Audio es de API 31; antes ningún dispositivo lo tiene). */
        @SuppressLint("InlinedApi")
        val HELMET_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

        /** Cascos (o auriculares) Bluetooth conectados ahora mismo, por nombre. */
        fun bluetoothHeadsets(context: Context): List<String> =
            context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type in HELMET_TYPES }
                .map { it.productName.toString() }
                .distinct()
    }
}
