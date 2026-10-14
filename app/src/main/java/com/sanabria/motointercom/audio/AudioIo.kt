package com.sanabria.motointercom.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import com.sanabria.motointercom.core.audio.VoiceFormat
import com.sanabria.motointercom.engine.AudioMode
import com.sanabria.motointercom.engine.VoiceOutput
import kotlin.concurrent.thread

/**
 * Captura y reproducción a 48 kHz mono en tramas de 20 ms, cada una en su hilo de prioridad de audio.
 *
 * Solo se usan el casco y el móvil; el micro del móvil, nunca. Sin micro del casco no se capta nada ([micOpen] = false)
 * y la app solo escucha.
 * - [AudioMode.MIX] (música primero): modo normal y la música no se toca. Las voces salen como multimedia hacia el casco
 *   y el sistema las mezcla con la música de otras apps. El micro del casco solo se usa si es LE Audio, que da micro y
 *   buena calidad a la vez; un casco clásico no tiene micro sin manos libres (HFP), y eso hundiría la música.
 * - [AudioMode.HELMET] (compatibilidad): modo comunicación todo el rato. Micro y voces van por el casco como manos libres.
 * En los dos, el micro se abre en cuanto el casco aparece y se cierra si se va.
 *
 * La reproducción escribe siempre (silencio si nadie habla): así el enlace con el casco no se duerme y la primera
 * palabra no llega tarde.
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
    private val lock = Any()

    @Volatile private var running = false
    private var mode = AudioMode.MIX
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    @Volatile private var capturing = false
    @Volatile private var helmetRoute = false
    @Volatile private var micEnabled = true
    private var micDevice: AudioDeviceInfo? = null
    @Volatile private var inputLatencyMs = -1
    @Volatile private var outputLatencyMs = -1

    /** El micro del casco está captando. */
    val micOpen: Boolean get() = capturing

    /** Si el casco se va (Bluetooth apagado, fuera de alcance) y vuelve, el micro y la salida se recuperan solos. */
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh()
    }

    fun start(mode: AudioMode, voiceOutput: VoiceOutput) {
        stop()
        this.mode = mode
        am.mode = if (mode == AudioMode.HELMET) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        am.registerAudioDeviceCallback(devices, main)
        val trk = buildTrack(mode, voiceOutput)
        track = trk
        running = true
        playbackThread = thread(name = "audio-playback") { playbackLoop(trk) }
        main.post { refresh() }
    }

    fun stop() {
        am.unregisterAudioDeviceCallback(devices)
        running = false
        closeMic()
        playbackThread?.join(500)
        playbackThread = null
        track?.release()
        track = null
        if (helmetRoute) leaveHelmetRoute()
        am.mode = AudioManager.MODE_NORMAL
    }

    /**
     * En música primero, soltar el micro del casco (solo escuchar) o volver a usarlo. Suelto, el casco queda solo con la
     * música. En manos libres no hace nada: la ruta de llamada no se toca para no cortar.
     */
    fun setMicEnabled(enabled: Boolean) {
        if (micEnabled == enabled) return
        micEnabled = enabled
        main.post { refresh() }
    }

    /** El casco tiene LE Audio: en música primero hay micro sin bajar la calidad. */
    val leAudio: Boolean get() = leMic() != null

    fun route(): Route {
        val i = if (capturing) record?.routedDevice else null
        val o = track?.routedDevice
        return Route(i?.type, i?.productName?.toString().orEmpty(), o?.type, o?.productName?.toString().orEmpty())
    }

    /** Pulsa una tecla multimedia (anterior, reproducir/pausa, siguiente): la recibe la app de música activa. */
    fun sendMediaKey(code: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** Lo que tarda el audio en llegar del micro a la app y de la app al altavoz, o null si el sistema no lo dice. */
    fun latency(): Pair<Int?, Int?> = inputLatencyMs.takeIf { it >= 0 } to outputLatencyMs.takeIf { it >= 0 }

    /** Pone el micro que toca según lo conectado (o lo cierra si no hay) y, en música primero, las voces hacia el casco. */
    private fun refresh() {
        if (!running) return
        if (mode == AudioMode.MIX) track?.setPreferredDevice(helmetOutput())
        val want = when {
            mode == AudioMode.HELMET -> helmetMic()
            micEnabled -> leMic()
            else -> null
        }
        if (capturing && want != null && want.id == micDevice?.id) return
        closeMic()
        if (want == null) {
            // Sin micro, la música queda sola en el casco
            if (mode == AudioMode.MIX && helmetRoute) leaveHelmetRoute()
            return
        }
        enterHelmetRoute()
        openMic(want)
    }

    private fun openMic(input: AudioDeviceInfo): Unit = synchronized(lock) {
        if (capturing) return
        val rec = runCatching { buildRecord(input) }.getOrNull() ?: return
        record = rec
        micDevice = input
        capturing = true
        captureThread = thread(name = "audio-capture") { captureLoop(rec) }
    }

    private fun closeMic(): Unit = synchronized(lock) {
        if (!capturing && record == null) return
        capturing = false
        captureThread?.join(500)
        captureThread = null
        record?.release()
        record = null
        micDevice = null
    }

    /** El micro del casco para manos libres: LE Audio si lo tiene, si no el de HFP (SCO). */
    private fun helmetMic(): AudioDeviceInfo? =
        leMic() ?: am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    /** El micro LE Audio del casco, si lo tiene. */
    private fun leMic(): AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == TYPE_BLE_HEADSET }

    /** Salida del casco para las voces en música primero (A2DP, o LE Audio), si está conectado. */
    private fun helmetOutput(): AudioDeviceInfo? {
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } ?: outputs.firstOrNull { it.type == TYPE_BLE_HEADSET }
    }

    /**
     * Pide al sistema el micro del casco como dispositivo de comunicación. En música primero solo se llega aquí con LE
     * Audio, que no quita la música de alta calidad; en manos libres, LE Audio si lo hay y si no SCO.
     */
    private fun enterHelmetRoute() {
        helmetRoute = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val available = am.availableCommunicationDevices
            val helmet = available.firstOrNull { it.type == TYPE_BLE_HEADSET }
                ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }.takeIf { mode == AudioMode.HELMET }
            if (helmet != null && am.communicationDevice?.id != helmet.id) am.setCommunicationDevice(helmet)
        } else if (mode == AudioMode.HELMET) {
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
        helmetRoute = false
    }

    private fun buildRecord(input: AudioDeviceInfo): AudioRecord {
        val min = AudioRecord.getMinBufferSize(VoiceFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
            .setBufferSizeInBytes(maxOf(min, FRAME_BYTES * 4))
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord" }
        // El micro del casco se pide explícitamente: si no, Android podría coger el del móvil
        rec.setPreferredDevice(input)
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

    private fun format(channel: Int) = AudioFormat.Builder()
        .setSampleRate(VoiceFormat.SAMPLE_RATE)
        .setChannelMask(channel)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .build()

    private fun captureLoop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(VoiceFormat.FRAME_SAMPLES)
        val ts = AudioTimestamp()
        var framesRead = 0L
        rec.startRecording()
        try {
            while (capturing) {
                var got = 0
                while (got < buf.size && capturing) {
                    val n = rec.read(buf, got, buf.size - got)
                    if (n < 0) return
                    got += n
                }
                framesRead += got
                // Una vez por segundo: la marca dice cuándo se captó una muestra; la última leída se captó
                // (leídas - marca) muestras después. Lo que falta hasta ahora es la latencia de entrada.
                if (framesRead % LATENCY_EVERY_FRAMES < got &&
                    rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                ) {
                    val capturedAt = ts.nanoTime + (framesRead - ts.framePosition) * NANOS_PER_FRAME
                    inputLatencyMs = ((System.nanoTime() - capturedAt) / 1_000_000).toInt().coerceAtLeast(0)
                }
                if (got == buf.size) callbacks.onCapture(buf)
            }
        } finally {
            runCatching { rec.stop() }
            inputLatencyMs = -1
        }
    }

    private fun playbackLoop(trk: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val out = ShortArray(VoiceFormat.FRAME_SAMPLES)
        val ts = AudioTimestamp()
        var framesWritten = 0L
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
                framesWritten += off
                // La marca dice cuándo sale por el altavoz (o el casco) una muestra; lo escrito y aún no presentado,
                // menos el tiempo desde la marca, es la latencia de salida (incluye la del Bluetooth si el sistema la da)
                if (framesWritten % LATENCY_EVERY_FRAMES < off && trk.getTimestamp(ts)) {
                    val pendingNs = (framesWritten - ts.framePosition) * NANOS_PER_FRAME
                    outputLatencyMs = ((pendingNs - (System.nanoTime() - ts.nanoTime)) / 1_000_000).toInt().coerceAtLeast(0)
                }
            }
        } finally {
            outputLatencyMs = -1
            trk.pause()
            trk.flush()
            trk.stop()
        }
    }

    companion object {
        private const val FRAME_BYTES = VoiceFormat.FRAME_SAMPLES * 2
        private const val LATENCY_EVERY_FRAMES = VoiceFormat.SAMPLE_RATE.toLong()
        private const val NANOS_PER_FRAME = 1_000_000_000L / VoiceFormat.SAMPLE_RATE

        /** LE Audio es de API 31: antes ningún dispositivo tiene este tipo y compararlo no hace daño. */
        @SuppressLint("InlinedApi")
        private const val TYPE_BLE_HEADSET = AudioDeviceInfo.TYPE_BLE_HEADSET

        /** Tipos de dispositivo que son un casco por Bluetooth. */
        val HELMET_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET)

        /** Cascos (o auriculares) Bluetooth conectados ahora mismo, por nombre. */
        fun bluetoothHeadsets(context: Context): List<String> =
            context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type in HELMET_TYPES }
                .map { it.productName.toString() }
                .distinct()
    }
}
