package com.sanabria.motointercom.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.os.SystemClock
import androidx.core.content.edit
import com.sanabria.motointercom.core.audio.VoiceFormat
import com.sanabria.motointercom.engine.AudioMode
import com.sanabria.motointercom.engine.MicSource
import com.sanabria.motointercom.engine.VoiceOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Prueba de un modo de audio con el casco de verdad: prepara la ruta (como el intercomunicador), graba 3 s por el micro,
 * los reproduce y pregunta si se ha oído bien. Mide cuánto tarda la ruta en llegar al casco y el nivel del micro.
 * Los resultados se guardan por casco en [HelmetCompat].
 */
class HelmetTester(private val context: Context) {

    enum class Phase { IDLE, ROUTING, RECORDING, PLAYING, ASK }

    data class Result(val heard: Boolean, val routeMs: Long?, val peakDb: Float, val route: AudioIo.Route?)

    data class State(
        val phase: Phase = Phase.IDLE,
        val mode: AudioMode? = null,
        val secondsLeft: Int = 0,
        val levelDb: Float = SILENCE_DB,
        val route: AudioIo.Route? = null,
        val routeMs: Long? = null,
        val peakDb: Float = SILENCE_DB,
        val results: Map<AudioMode, Result> = emptyMap(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val buffer = ShortArray(VoiceFormat.SAMPLE_RATE * SECONDS)
    @Volatile private var recording = false
    @Volatile private var playing = false
    @Volatile private var recPos = 0
    @Volatile private var playPos = 0
    @Volatile private var levelDb = SILENCE_DB
    @Volatile private var peakDb = SILENCE_DB

    private val io = AudioIo(context, object : AudioIo.Callbacks {
        override fun onCapture(pcm: ShortArray) {
            levelDb = rmsDb(pcm)
            if (recording && recPos < buffer.size) {
                val n = min(pcm.size, buffer.size - recPos)
                pcm.copyInto(buffer, recPos, 0, n)
                recPos += n
                peakDb = max(peakDb, levelDb)
            }
        }

        override fun onPlayback(out: ShortArray) {
            if (playing && playPos < recPos) {
                val n = min(out.size, recPos - playPos)
                buffer.copyInto(out, 0, playPos, playPos + n)
                out.fill(0, n, out.size)
                playPos += n
            } else {
                out.fill(0)
            }
        }
    })

    // TYPE_BLE_HEADSET es de API 31: en Android 10 y 11 ningún dispositivo lo tiene y compararlo no hace daño
    @SuppressLint("InlinedApi")
    suspend fun run(mode: AudioMode) {
        recording = false
        playing = false
        recPos = 0
        playPos = 0
        peakDb = SILENCE_DB
        _state.update { it.copy(phase = Phase.ROUTING, mode = mode, secondsLeft = 0, routeMs = null, route = null) }
        val t0 = SystemClock.elapsedRealtime()
        try {
            withContext(Dispatchers.Default) { io.start(mode, MicSource.VOICE, VoiceOutput.MEDIA) }
        } catch (_: Exception) {
            finish(mode, Result(heard = false, routeMs = null, peakDb = SILENCE_DB, route = null))
            return
        }
        // Ruta lista: en HFP, el micro tiene que venir del casco; en mezcla, la salida tiene que ir al casco
        var routeMs: Long? = null
        while (SystemClock.elapsedRealtime() - t0 < ROUTE_TIMEOUT_MS) {
            val r = io.route()
            _state.update { it.copy(route = r, levelDb = levelDb) }
            val ready = when (mode) {
                AudioMode.HELMET -> r.inputType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || r.inputType == AudioDeviceInfo.TYPE_BLE_HEADSET
                AudioMode.MIX -> r.outputType in AudioIo.HELMET_TYPES
            }
            if (ready) {
                routeMs = SystemClock.elapsedRealtime() - t0
                break
            }
            delay(100)
        }
        recording = true
        countdown(Phase.RECORDING)
        recording = false
        playing = true
        countdown(Phase.PLAYING)
        playing = false
        val route = io.route()
        withContext(Dispatchers.Default) { io.stop() }
        _state.update { it.copy(phase = Phase.ASK, routeMs = routeMs, peakDb = peakDb, route = route) }
    }

    /** Respuesta a "¿te has oído bien?". */
    fun answer(heard: Boolean) {
        val s = _state.value
        val mode = s.mode ?: return
        finish(mode, Result(heard, s.routeMs, s.peakDb, s.route))
    }

    fun cancel() {
        recording = false
        playing = false
        io.stop()
        _state.update { it.copy(phase = Phase.IDLE) }
    }

    private fun finish(mode: AudioMode, result: Result) {
        _state.update { it.copy(phase = Phase.IDLE, results = it.results + (mode to result)) }
        val helmet = AudioIo.bluetoothHeadsets(context).firstOrNull() ?: result.route?.outputName?.takeIf { it.isNotBlank() } ?: return
        HelmetCompat(context).save(helmet, mode, result.heard, result.routeMs)
    }

    private suspend fun countdown(phase: Phase) {
        for (s in SECONDS downTo 1) {
            _state.update { it.copy(phase = phase, secondsLeft = s) }
            repeat(10) {
                delay(100)
                _state.update { it.copy(levelDb = levelDb) }
            }
        }
    }

    private fun rmsDb(pcm: ShortArray): Float {
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s
        val rms = sqrt(sum / pcm.size)
        return if (rms < 1.0) SILENCE_DB else (20 * log10(rms / 32768.0)).toFloat()
    }

    private companion object {
        const val SECONDS = 3
        const val ROUTE_TIMEOUT_MS = 6_000L
        const val SILENCE_DB = -96f
    }
}

/** Lo que se sabe de cada casco probado: si cada modo funcionó y cuánto tardó la ruta. */
class HelmetCompat(context: Context) {
    data class Record(val name: String, val helmet: Boolean?, val helmetMs: Long?, val mix: Boolean?, val mixMs: Long?)

    private val prefs = context.getSharedPreferences("helmets", Context.MODE_PRIVATE)

    fun all(): List<Record> = prefs.all.mapNotNull { (name, v) -> (v as? String)?.let { parse(name, it) } }.sortedBy { it.name }

    fun save(name: String, mode: AudioMode, ok: Boolean, routeMs: Long?) {
        val old = (prefs.getString(name, null)?.let { parse(name, it) }) ?: Record(name, null, null, null, null)
        val r = if (mode == AudioMode.HELMET) old.copy(helmet = ok, helmetMs = routeMs) else old.copy(mix = ok, mixMs = routeMs)
        prefs.edit { putString(name, "${r.helmet ?: ""};${r.helmetMs ?: ""};${r.mix ?: ""};${r.mixMs ?: ""}") }
    }

    private fun parse(name: String, v: String): Record? {
        val p = v.split(';')
        if (p.size != 4) return null
        return Record(name, p[0].toBooleanStrictOrNull(), p[1].toLongOrNull(), p[2].toBooleanStrictOrNull(), p[3].toLongOrNull())
    }
}
