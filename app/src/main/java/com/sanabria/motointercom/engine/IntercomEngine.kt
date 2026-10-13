package com.sanabria.motointercom.engine

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import com.sanabria.motointercom.audio.AudioIo
import com.sanabria.motointercom.audio.VolumeOutputs
import com.sanabria.motointercom.ble.BleMesh
import com.sanabria.motointercom.core.audio.JitterBuffer
import com.sanabria.motointercom.core.audio.Mixer
import com.sanabria.motointercom.core.audio.TalkSender
import com.sanabria.motointercom.core.audio.Vad
import com.sanabria.motointercom.core.audio.VoiceDecoder
import com.sanabria.motointercom.core.audio.VoiceFormat
import com.sanabria.motointercom.core.audio.VolumeLink
import com.sanabria.motointercom.core.mesh.LinkTransport
import com.sanabria.motointercom.core.mesh.Router
import com.sanabria.motointercom.core.mesh.Topology
import com.sanabria.motointercom.core.protocol.Frame
import com.sanabria.motointercom.core.protocol.GroupId
import com.sanabria.motointercom.opus.OpusVoiceDecoder
import com.sanabria.motointercom.opus.OpusVoiceEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

enum class EngineError { BLUETOOTH_OFF, PERMISSIONS, CHANNEL, AUDIO }

data class NodeUi(
    val id: Int,
    val name: String,
    /** 1 = enlace directo; -1 = se conoce pero no hay camino. */
    val hops: Int,
    val speaking: Boolean,
    val depthMs: Int,
    val targetMs: Int,
    val lost: Long,
    val late: Long,
)

data class LinkUi(val info: BleMesh.LinkInfo, val txKbps: Float, val rxKbps: Float)

data class EngineState(
    val running: Boolean = false,
    val error: EngineError? = null,
    val errorDetail: String = "",
    val caps: BleMesh.Caps? = null,
    val mesh: BleMesh.Snapshot? = null,
    val links: List<LinkUi> = emptyList(),
    val nodes: List<NodeUi> = emptyList(),
    val micLevelDb: Float = Vad.SILENCE_DB,
    val sending: Boolean = false,
    val route: AudioIo.Route? = null,
    val volumes: Volumes = Volumes(),
)

/** Niveles 0..1 de los tres controles de volumen. */
data class Volumes(val master: Float = 0f, val voice: Float = 0f, val media: Float = 0f)

/**
 * Une el transporte BLE, la malla del núcleo y el audio.
 *
 * Hilos: captura (VAD + Opus + envío), reproducción (jitter buffers + decodificación + mezcla), lectura de cada enlace
 * (entrega y reenvío) y un temporizador (anuncios de la malla, caducidad y estado para la interfaz).
 * Cerrojos: [lock] protege la malla (Topology + Router) y [streamsLock] los jitter buffers; siempre en ese orden.
 */
class IntercomEngine(private val context: Context) : LinkTransport.Listener, Router.Output, AudioIo.Callbacks {

    private class Stream(val jb: JitterBuffer) {
        var decoder: VoiceDecoder? = null
        var lastPacketAt = 0L
    }

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val clock: () -> Long = { SystemClock.elapsedRealtime() }

    private val lock = Any()
    private var mesh: BleMesh? = null
    private var topology: Topology? = null
    private var router: Router? = null
    private val linked = HashSet<Int>()
    private val directNames = HashMap<Int, String>()

    private val audio = AudioIo(context, this)
    private val vad = Vad()
    private var encoder: OpusVoiceEncoder? = null
    private val encoded = ByteArray(VoiceFormat.MAX_PACKET)
    private var sender: TalkSender? = null
    @Volatile private var pendingBitrate = 0

    private val streamsLock = Any()
    private val streams = HashMap<Int, Stream>()
    private val mixer = Mixer()
    private val pcm = ShortArray(VoiceFormat.FRAME_SAMPLES)
    private var playbackTicks = 0L

    @Volatile private var running = false
    @Volatile private var talkMode = TalkMode.AUTO
    @Volatile private var voiceGain = 1f
    @Volatile var pttPressed = false
    private var settings: IntercomSettings? = null
    private var ticker: ScheduledExecutorService? = null
    private var lastAnnounce = 0L
    private var lastPublish = 0L
    private val lastBytes = HashMap<Int, Pair<Long, Long>>()

    private val volumeLock = Any()
    private var volumeLink = VolumeLink()
    private val volumeOutputs = VolumeOutputs(context)

    init {
        _state.value = EngineState(caps = BleMesh.capsOf(context), volumes = volumes())
    }

    /** Niveles guardados de voz y multimedia (al abrir la app). */
    fun loadVolumes(voice: Float, media: Float) = synchronized(volumeLock) {
        volumeLink = VolumeLink(voice, media)
        applyVolumesLocked()
    }

    fun setMasterVolume(level: Float) = synchronized(volumeLock) {
        volumeLink.setMaster(level)
        applyVolumesLocked()
    }

    fun setVoiceVolume(level: Float) = synchronized(volumeLock) {
        volumeLink.setVoice(level)
        applyVolumesLocked()
    }

    fun setMediaVolume(level: Float) = synchronized(volumeLock) {
        volumeLink.setMedia(level)
        applyVolumesLocked()
    }

    /** Anterior, reproducir/pausa o siguiente en la app de música que esté sonando. */
    fun mediaKey(keyCode: Int) = audio.sendMediaKey(keyCode)

    private fun volumes() = Volumes(volumeLink.master, volumeLink.voice, volumeLink.media)

    /** Con el motor en marcha lleva los niveles al sistema y a las voces; parado, solo los recuerda. */
    private fun applyVolumesLocked() {
        val s = settings
        if (running && s != null) voiceGain = volumeOutputs.apply(volumeLink, s.audioMode, s.voiceOutput)
        val v = volumes()
        _state.update { it.copy(volumes = v) }
    }

    fun start(s: IntercomSettings) {
        if (running) return
        settings = s
        apply(s)
        if (context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true) {
            fail(EngineError.BLUETOOTH_OFF, null)
            return
        }
        val topo = Topology(s.nodeId, s.name)
        synchronized(lock) {
            topology = topo
            router = Router(topo, this)
            linked.clear()
            directNames.clear()
        }
        vad.reset()
        sender = TalkSender { seq, flags, payload -> synchronized(lock) { router?.sendLocalAudio(seq, flags, payload) } }
        encoder = OpusVoiceEncoder(s.bitrate)

        val m = BleMesh(
            context, s.nodeId, GroupId.of(s.group), s.name,
            BleMesh.Options(s.maxLinks, s.longRange, s.phy), this, clock,
        )
        try {
            m.start()
        } catch (e: Exception) {
            m.stop()
            fail(if (e is SecurityException) EngineError.PERMISSIONS else EngineError.CHANNEL, e)
            return
        }
        mesh = m
        running = true
        try {
            audio.start(s.audioMode, s.micSource, s.voiceOutput)
        } catch (e: Exception) {
            stop()
            fail(EngineError.AUDIO, e)
            return
        }
        ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "engine-tick") }.also {
            it.scheduleWithFixedDelay(::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
        }
        _state.value = EngineState(running = true, caps = m.caps, volumes = synchronized(volumeLock) { volumes() })
        synchronized(volumeLock) { applyVolumesLocked() }
    }

    fun stop() {
        running = false
        ticker?.shutdownNow()
        ticker = null
        audio.stop()
        synchronized(volumeLock) { volumeOutputs.stop() }
        mesh?.stop()
        mesh = null
        encoder?.close()
        encoder = null
        synchronized(streamsLock) {
            streams.values.forEach { it.decoder?.close() }
            streams.clear()
        }
        synchronized(lock) {
            router = null
            topology = null
            linked.clear()
        }
        lastBytes.clear()
        _state.update { it.copy(running = false, mesh = null, links = emptyList(), nodes = emptyList(), sending = false) }
    }

    /** Ajustes que se aplican en marcha. Lo de la radio (grupo, nombre, alcance) necesita reconectar. */
    fun apply(s: IntercomSettings) {
        val old = settings
        settings = s
        talkMode = s.talkMode
        vad.sensitivity = s.vadSensitivity
        if (!running || old == null) return
        if (s.bitrate != old.bitrate) pendingBitrate = s.bitrate
        if (s.phy != old.phy) mesh?.setPhy(s.phy)
        if (s.maxLinks != old.maxLinks) mesh?.setMaxLinks(s.maxLinks)
        if (s.audioMode != old.audioMode || s.micSource != old.micSource || s.voiceOutput != old.voiceOutput) {
            try {
                audio.start(s.audioMode, s.micSource, s.voiceOutput)
            } catch (e: Exception) {
                stop()
                fail(EngineError.AUDIO, e)
                return
            }
            // Cambia el canal que mueven los botones y cómo se reparten los volúmenes
            synchronized(volumeLock) { applyVolumesLocked() }
        }
    }

    private fun fail(error: EngineError, e: Exception?) {
        _state.update {
            it.copy(
                running = false,
                error = error,
                errorDetail = e?.let { ex -> ex.message ?: ex.javaClass.simpleName }.orEmpty(),
                mesh = null,
                links = emptyList(),
                nodes = emptyList(),
            )
        }
    }

    // --- Audio ---

    override fun onCapture(pcm: ShortArray) {
        val enc = encoder ?: return
        val ts = sender ?: return
        pendingBitrate.let {
            if (it != 0) {
                enc.setBitrate(it)
                pendingBitrate = 0
            }
        }
        val voice = vad.process(pcm)
        val open = when (talkMode) {
            TalkMode.AUTO -> voice
            TalkMode.PTT -> pttPressed
            TalkMode.OPEN -> true
            TalkMode.MUTE -> false
        }
        // Se codifica siempre: Opus necesita continuidad y el pre-roll trae las tramas de antes de abrir
        val n = enc.encode(pcm, encoded)
        if (n > 0) ts.onFrame(encoded.copyOf(n), open)
    }

    override fun onPlayback(out: ShortArray) {
        val now = clock()
        mixer.begin()
        synchronized(streamsLock) {
            for (st in streams.values) {
                val dec = st.decoder
                when (val r = st.jb.pull(now)) {
                    is JitterBuffer.Out.Packet -> {
                        val d = dec ?: OpusVoiceDecoder().also { st.decoder = it }
                        val n = d.decode(r.payload, r.payload.size, pcm)
                        if (n > 0) mixer.add(pcm, n)
                        st.lastPacketAt = now
                    }
                    is JitterBuffer.Out.Lost -> if (dec != null) {
                        val next = r.next
                        val n = if (next != null) dec.decodeFec(next, next.size, pcm) else dec.conceal(pcm)
                        if (n > 0) mixer.add(pcm, n)
                    }
                    JitterBuffer.Out.Silence -> Unit
                }
            }
            // Emisores que llevan un rato callados y ya no están en la malla: fuera su decodificador
            if (++playbackTicks % 250 == 0L) {
                val gone = streams.filter { (_, st) -> st.jb.state == JitterBuffer.State.IDLE && now - st.lastPacketAt > STREAM_IDLE_MS }
                gone.forEach { (id, st) ->
                    st.decoder?.close()
                    streams.remove(id)
                }
            }
        }
        mixer.end(voiceGain, out)
    }

    // --- Malla ---

    override fun send(peer: Int, frame: ByteArray, audio: Boolean) {
        mesh?.send(peer, frame, audio)
    }

    override fun deliver(audio: Frame.Audio) {
        synchronized(streamsLock) {
            streams.getOrPut(audio.origin) { Stream(JitterBuffer()) }.jb.put(audio.seq, audio.flags, audio.payload, clock())
        }
    }

    override fun onLinkUp(peer: Int, name: String) {
        synchronized(lock) {
            directNames[peer] = name
            linked += peer
            topology?.setLocalNeighbors(linked)
            router?.announce()
        }
    }

    override fun onLinkDown(peer: Int) {
        synchronized(lock) {
            linked -= peer
            if (topology?.setLocalNeighbors(linked) == true) router?.announce()
        }
    }

    override fun onFrame(peer: Int, frame: Frame) {
        val now = clock()
        synchronized(lock) {
            val r = router ?: return
            when (frame) {
                is Frame.Audio -> r.onAudio(peer, frame, now)
                is Frame.LinkState -> r.onLinkState(peer, frame, now)
                else -> Unit
            }
        }
    }

    // --- Temporizador ---

    private fun tick() {
        if (!running) return
        val now = clock()
        val nodes: List<Topology.Node>
        synchronized(lock) {
            val topo = topology ?: return
            val r = router ?: return
            if (now - lastAnnounce >= ANNOUNCE_MS) {
                r.announce()
                lastAnnounce = now
            }
            topo.expire(now).forEach { r.forget(it) }
            nodes = topo.nodes()
        }
        // Botones de volumen del móvil o del casco: mueven el maestro
        synchronized(volumeLock) { if (volumeOutputs.pollButtons(volumeLink)) applyVolumesLocked() }
        if (now - lastPublish >= PUBLISH_MS) publish(now, nodes)
    }

    private fun publish(now: Long, nodes: List<Topology.Node>) {
        val m = mesh ?: return
        val snap = m.snapshot()
        val dt = (now - lastPublish).coerceAtLeast(1).toFloat()
        lastPublish = now
        val links = snap.links.map { l ->
            val (tx0, rx0) = lastBytes[l.peer] ?: (l.txBytes to l.rxBytes)
            lastBytes[l.peer] = l.txBytes to l.rxBytes
            LinkUi(l, (l.txBytes - tx0) * 8f / dt, (l.rxBytes - rx0) * 8f / dt)
        }
        val names = synchronized(lock) { HashMap(directNames) }
        val nodeUi = synchronized(streamsLock) {
            nodes.map { n ->
                val st = streams[n.id]
                NodeUi(
                    id = n.id,
                    name = n.name ?: names[n.id] ?: "%08x".format(n.id),
                    hops = n.hops,
                    speaking = st != null && st.jb.state != JitterBuffer.State.IDLE,
                    depthMs = st?.jb?.depthMs ?: 0,
                    targetMs = (st?.jb?.target ?: 0) * VoiceFormat.FRAME_MS,
                    lost = st?.jb?.lost ?: 0,
                    late = st?.jb?.late ?: 0,
                )
            }
        }
        val route = audio.route()
        _state.update {
            it.copy(
                mesh = snap,
                links = links,
                nodes = nodeUi,
                micLevelDb = vad.levelDb,
                sending = sender?.talking == true,
                route = route,
            )
        }
    }

    private companion object {
        const val TICK_MS = 250L
        const val ANNOUNCE_MS = 2_000L
        const val PUBLISH_MS = 500L
        const val STREAM_IDLE_MS = 30_000L
    }
}
