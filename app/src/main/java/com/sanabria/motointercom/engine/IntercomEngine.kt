package com.sanabria.motointercom.engine

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.sanabria.motointercom.audio.AudioIo
import com.sanabria.motointercom.audio.VolumeOutputs
import com.sanabria.motointercom.ble.BleMesh
import com.sanabria.motointercom.core.audio.BitrateController
import com.sanabria.motointercom.core.audio.Earcons
import com.sanabria.motointercom.core.audio.JitterBuffer
import com.sanabria.motointercom.core.audio.Mixer
import com.sanabria.motointercom.core.audio.TalkSender
import com.sanabria.motointercom.core.audio.Vad
import com.sanabria.motointercom.core.audio.VoiceDecoder
import com.sanabria.motointercom.core.audio.VoiceFormat
import com.sanabria.motointercom.core.audio.VolumeLink
import com.sanabria.motointercom.core.crypto.GroupKeys
import com.sanabria.motointercom.core.mesh.LinkQuality
import com.sanabria.motointercom.core.mesh.LinkTransport
import com.sanabria.motointercom.core.mesh.Router
import com.sanabria.motointercom.core.mesh.Topology
import com.sanabria.motointercom.core.protocol.Frame
import com.sanabria.motointercom.crypto.AndroidCrypto
import com.sanabria.motointercom.opus.OpusVoiceDecoder
import com.sanabria.motointercom.opus.OpusVoiceEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

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
    /**
     * Retardo boca-oreja estimado: micro del otro + trama + red + cola de reproducción + salida de este móvil;
     * null si no hay camino.
     */
    val latencyMs: Int?,
)

data class LinkUi(val info: BleMesh.LinkInfo, val name: String, val cost: Int?, val txKbps: Float, val rxKbps: Float)

data class EngineState(
    val running: Boolean = false,
    val error: EngineError? = null,
    val errorDetail: String = "",
    /** El Bluetooth se ha apagado con el intercomunicador en marcha: la malla vuelve sola al encenderlo. */
    val bluetoothOff: Boolean = false,
    val caps: BleMesh.Caps? = null,
    val mesh: BleMesh.Snapshot? = null,
    val links: List<LinkUi> = emptyList(),
    val nodes: List<NodeUi> = emptyList(),
    val micLevelDb: Float = Vad.SILENCE_DB,
    val sending: Boolean = false,
    /** Micro silenciado con el botón del casco o desde la notificación (modos Auto y Abierto). */
    val micMuted: Boolean = false,
    /** En modo PTT, micro abierto con el botón del casco hasta volver a pulsarlo. */
    val pttLatched: Boolean = false,
    val route: AudioIo.Route? = null,
    val volumes: Volumes = Volumes(),
    /** Latencia medida del micro (hasta la app) y de la salida (de la app al oído), si el sistema la da. */
    val inputLatencyMs: Int? = null,
    val outputLatencyMs: Int? = null,
    /** Calidad de voz en uso (la adaptativa puede ir por debajo de la máxima de los ajustes). */
    val bitrate: Int = 0,
)

/** Niveles 0..1 de los tres controles de volumen. */
data class Volumes(val master: Float = 0f, val voice: Float = 0f, val media: Float = 0f)

/**
 * Une el transporte BLE, la malla del núcleo y el audio. Vive mientras vive el proceso (lo crea la Application); lo
 * arranca y lo para el servicio en primer plano.
 *
 * Hilos: captura (VAD + Opus + envío), reproducción (jitter buffers + decodificación + mezcla + avisos), lectura de cada
 * enlace (entrega y reenvío) y un temporizador (anuncios de la malla, caducidad, botones de volumen y estado).
 * Cerrojos: [lock] protege la malla (Topology + Router) y [streamsLock] los jitter buffers; siempre en ese orden.
 */
class IntercomEngine(private val context: Context) : LinkTransport.Listener, Router.Output, AudioIo.Callbacks {

    private class Stream(var session: Int, val jb: JitterBuffer) {
        var decoder: VoiceDecoder? = null
        var lastPacketAt = 0L
    }

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val clock: () -> Long = { SystemClock.elapsedRealtime() }

    private val lock = Any()
    @Volatile private var mesh: BleMesh? = null
    private var topology: Topology? = null
    private var router: Router? = null
    /** Enlaces abiertos y el coste que se anuncia de cada uno. */
    private val linkCosts = HashMap<Int, Int>()
    private val linkQuality = LinkQuality()
    private var keys: GroupKeys? = null
    private var bitrateControl = BitrateController(16_000)
    private var lastAudioTotals = 0L to 0L

    private val audio = AudioIo(context, this)
    private val vad = Vad()
    private var encoder: OpusVoiceEncoder? = null
    private val encoded = ByteArray(VoiceFormat.MAX_PACKET)
    private var sender: TalkSender? = null
    private var session = 0
    @Volatile private var pendingBitrate = 0

    private val streamsLock = Any()
    private val streams = HashMap<Int, Stream>()
    private val mixer = Mixer()
    private val pcm = ShortArray(VoiceFormat.FRAME_SAMPLES)
    private var playbackTicks = 0L

    private val earcons = Earcons()
    private val earconPcm = ShortArray(VoiceFormat.FRAME_SAMPLES)
    @Volatile private var earconsOn = true

    @Volatile private var running = false
    @Volatile private var talkMode = TalkMode.AUTO
    @Volatile private var voiceGain = 1f
    @Volatile var pttPressed = false
    @Volatile private var micMuted = false
    @Volatile private var pttLatched = false
    private var settings: IntercomSettings? = null
    private var ticker: ScheduledExecutorService? = null
    private var lastAnnounce = 0L
    private var lastPublish = 0L
    private var lastMeshAttempt = 0L
    private val lastBytes = HashMap<Int, Pair<Long, Long>>()
    private var reachable = emptySet<Int>()

    private val volumeLock = Any()
    private var volumeLink = VolumeLink()
    private val volumeOutputs = VolumeOutputs(context)

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onBluetoothOff()
                BluetoothAdapter.STATE_ON -> startMesh()
            }
        }
    }

    init {
        _state.value = EngineState(caps = BleMesh.capsOf(context), volumes = volumes())
    }

    // --- Arranque y parada ---

    fun start(s: IntercomSettings) {
        if (running) return
        settings = s
        apply(s)
        if (!bluetoothOn()) {
            fail(EngineError.BLUETOOTH_OFF, null)
            return
        }
        val k = GroupKeys(AndroidCrypto, s.groupKey)
        keys = k
        val topo = Topology(s.nodeId, s.name)
        synchronized(lock) {
            topology = topo
            router = Router(topo, this)
            linkCosts.clear()
        }
        bitrateControl = BitrateController(s.bitrate)
        lastAudioTotals = 0L to 0L
        reachable = emptySet()
        micMuted = false
        pttLatched = false
        vad.reset()
        // Sesión nueva en cada conexión: los demás reinician su cola de reproducción y el control de duplicados
        session = Random.nextInt()
        sender = TalkSender { counter, flags, payload -> synchronized(lock) { router?.sendLocalAudio(session, counter, flags, payload) } }
        encoder = OpusVoiceEncoder(s.bitrate)

        running = true
        if (!startMesh()) {
            val e = lastMeshError
            stop()
            fail(if (e is SecurityException) EngineError.PERMISSIONS else EngineError.CHANNEL, e)
            return
        }
        try {
            audio.start(s.audioMode, s.micSource, s.voiceOutput)
        } catch (e: Exception) {
            stop()
            fail(EngineError.AUDIO, e)
            return
        }
        ContextCompat.registerReceiver(
            context, bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "engine-tick") }.also {
            it.scheduleWithFixedDelay(::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
        }
        _state.value = EngineState(running = true, caps = mesh?.caps, volumes = synchronized(volumeLock) { volumes() }, bitrate = s.bitrate)
        synchronized(volumeLock) { applyVolumesLocked() }
    }

    fun stop() {
        val wasRunning = running
        running = false
        ticker?.shutdownNow()
        ticker = null
        if (wasRunning) runCatching { context.unregisterReceiver(bluetoothReceiver) }
        audio.stop()
        synchronized(volumeLock) { volumeOutputs.stop() }
        stopMesh()
        encoder?.close()
        encoder = null
        synchronized(streamsLock) {
            streams.values.forEach { it.decoder?.close() }
            streams.clear()
        }
        synchronized(lock) {
            router = null
            topology = null
            linkCosts.forEach { (peer, _) -> linkQuality.forget(peer) }
            linkCosts.clear()
        }
        lastBytes.clear()
        _state.update {
            it.copy(running = false, bluetoothOff = false, mesh = null, links = emptyList(), nodes = emptyList(), sending = false)
        }
    }

    /** Ajustes que se aplican en marcha. Lo de la radio (grupo, nombre, alcance) necesita reconectar. */
    fun apply(s: IntercomSettings) {
        val old = settings
        settings = s
        talkMode = s.talkMode
        vad.sensitivity = s.vadSensitivity
        earconsOn = s.earcons
        if (s.talkMode != TalkMode.PTT) pttLatched = false
        if (!running || old == null) return
        if (s.bitrate != old.bitrate) {
            bitrateControl.setMax(s.bitrate)
            pendingBitrate = bitrateControl.current
            _state.update { it.copy(bitrate = bitrateControl.current) }
        }
        if (s.phy != old.phy) mesh?.setPhy(s.phy)
        if (s.maxLinks != old.maxLinks) mesh?.setMaxLinks(s.maxLinks)
        if (s.learnedLinkLimit == 0 && old.learnedLinkLimit != 0) mesh?.resetLearnedLimit()
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

    private fun bluetoothOn() = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    // --- Malla BLE: se rehace si el Bluetooth se apaga y vuelve ---

    private var lastMeshError: Exception? = null

    /** Crea y arranca la malla BLE si no la hay. Devuelve false si no ha podido. */
    @Synchronized
    private fun startMesh(): Boolean {
        if (!running || mesh != null) return mesh != null
        val s = settings ?: return false
        val k = keys ?: return false
        lastMeshAttempt = clock()
        val m = BleMesh(
            context, s.nodeId, AndroidCrypto, k,
            BleMesh.Options(s.maxLinks, s.longRange, s.phy, s.learnedLinkLimit), this, clock,
        )
        return try {
            m.start()
            mesh = m
            _state.update { it.copy(bluetoothOff = false, caps = m.caps) }
            true
        } catch (e: Exception) {
            lastMeshError = e
            m.stop()
            false
        }
    }

    @Synchronized
    private fun stopMesh() {
        mesh?.stop()
        mesh = null
    }

    private fun onBluetoothOff() {
        stopMesh()
        synchronized(lock) {
            linkCosts.forEach { (peer, _) -> linkQuality.forget(peer) }
            linkCosts.clear()
            topology?.setLocalNeighbors(linkCosts)
        }
        _state.update { it.copy(bluetoothOff = true, mesh = null, links = emptyList()) }
    }

    // --- Volumen ---

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

    // --- Micro: botón del casco y notificación ---

    /**
     * Botón del casco o acción de la notificación. En PTT abre el micro hasta la siguiente pulsación; en Auto y Abierto
     * lo silencia o lo devuelve. Suena un pitido para saber cómo queda sin mirar.
     */
    fun toggleMic() {
        val open: Boolean
        if (talkMode == TalkMode.PTT) {
            pttLatched = !pttLatched
            open = pttLatched
        } else {
            micMuted = !micMuted
            open = !micMuted
        }
        if (earconsOn) synchronized(earcons) { earcons.play(if (open) Earcons.Kind.MIC_ON else Earcons.Kind.MIC_OFF) }
        _state.update { it.copy(micMuted = micMuted, pttLatched = pttLatched) }
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
            TalkMode.AUTO -> voice && !micMuted
            TalkMode.PTT -> pttPressed || pttLatched
            TalkMode.OPEN -> !micMuted
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
            // Emisores que llevan un rato callados: fuera su decodificador
            if (++playbackTicks % 250 == 0L) {
                val gone = streams.filter { (_, st) -> st.jb.state == JitterBuffer.State.IDLE && now - st.lastPacketAt > STREAM_IDLE_MS }
                gone.forEach { (id, st) ->
                    st.decoder?.close()
                    streams.remove(id)
                }
            }
        }
        if (synchronized(earcons) { earcons.next(earconPcm) }) mixer.add(earconPcm)
        mixer.end(voiceGain, out)
    }

    // --- Malla ---

    override fun send(peer: Int, content: ByteArray, audio: Boolean) {
        mesh?.send(peer, content, audio)
    }

    override fun deliver(audio: Frame.Audio) {
        synchronized(streamsLock) {
            val st = streams.getOrPut(audio.origin) { Stream(audio.session, JitterBuffer()) }
            if (st.session != audio.session) {
                // El emisor se ha reconectado: su cuenta vuelve a empezar
                st.session = audio.session
                st.jb.reset()
            }
            st.jb.put(audio.counter and 0xFFFF, audio.flags, audio.payload, clock())
        }
    }

    override fun onLinkUp(peer: Int) {
        synchronized(lock) {
            linkCosts.putIfAbsent(peer, LinkQuality.DEFAULT_COST)
            topology?.setLocalNeighbors(linkCosts)
            router?.announce()
        }
    }

    override fun onLinkDown(peer: Int) {
        synchronized(lock) {
            linkCosts.remove(peer)
            linkQuality.forget(peer)
            if (topology?.setLocalNeighbors(linkCosts) == true) router?.announce()
        }
    }

    override fun onFrame(peer: Int, frame: Frame) {
        val now = clock()
        synchronized(lock) {
            val r = router ?: return
            when (frame) {
                is Frame.Audio -> r.onAudio(peer, frame)
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
        val names: Map<Int, String>
        val announce = now - lastAnnounce >= ANNOUNCE_MS
        // Cada anuncio lleva el coste medido de cada enlace y la latencia del micro, y ajusta la calidad de voz
        val snap = if (announce) mesh?.snapshot() else null
        if (snap != null) adaptBitrate(snap)
        synchronized(lock) {
            val topo = topology ?: return
            val r = router ?: return
            if (announce) {
                snap?.links?.forEach { l ->
                    if (l.peer in linkCosts) {
                        linkCosts[l.peer] = linkQuality.cost(l.peer, LinkQuality.Sample(l.rssi, l.rttMs, l.sentFrames, l.dropped))
                    }
                }
                topo.setLocalNeighbors(linkCosts)
                topo.selfInputLatencyMs = audio.latency().first ?: 0
                r.announce()
                lastAnnounce = now
            }
            topo.expire(now).forEach { r.forget(it) }
            nodes = topo.nodes()
            names = nodes.associate { it.id to (it.name ?: "") }
        }
        // Si el Bluetooth está encendido pero la malla no arrancó (o se cayó al encenderlo), se reintenta
        if (mesh == null && bluetoothOn() && now - lastMeshAttempt >= MESH_RETRY_MS) startMesh()
        // Botones de volumen del móvil o del casco: mueven el maestro
        synchronized(volumeLock) { if (volumeOutputs.pollButtons(volumeLink)) applyVolumesLocked() }
        groupChimes(nodes)
        if (now - lastPublish >= PUBLISH_MS) publish(now, nodes, names)
    }

    /** Calidad de voz adaptativa: con lo que todos los enlaces enviaron y descartaron desde el último anuncio. */
    private fun adaptBitrate(snap: BleMesh.Snapshot) {
        val sent = snap.links.sumOf { it.sentFrames }
        val dropped = snap.links.sumOf { it.dropped }
        val (sent0, dropped0) = lastAudioTotals
        lastAudioTotals = sent to dropped
        // Si se ha cerrado un enlace los totales bajan: ese periodo no cuenta
        if (sent < sent0 || dropped < dropped0) return
        val before = bitrateControl.current
        val now = bitrateControl.update(sent - sent0, dropped - dropped0)
        if (now != before) {
            pendingBitrate = now
            _state.update { it.copy(bitrate = now) }
        }
    }

    /** Aviso sonoro cuando alguien aparece en el grupo o se pierde (alcanzable directamente o por saltos). */
    private fun groupChimes(nodes: List<Topology.Node>) {
        val now = nodes.filter { it.hops > 0 }.map { it.id }.toSet()
        val joined = (now - reachable).isNotEmpty()
        val left = (reachable - now).isNotEmpty()
        reachable = now
        if (!earconsOn) return
        synchronized(earcons) {
            if (left) earcons.play(Earcons.Kind.LEFT)
            if (joined) earcons.play(Earcons.Kind.JOINED)
        }
    }

    private fun publish(now: Long, nodes: List<Topology.Node>, names: Map<Int, String>) {
        val snap = mesh?.snapshot()
        val dt = (now - lastPublish).coerceAtLeast(1).toFloat()
        lastPublish = now
        fun nameOf(id: Int) = names[id]?.takeIf { it.isNotEmpty() } ?: "%08x".format(id)
        val costs = synchronized(lock) { HashMap(linkCosts) }
        val links = snap?.links.orEmpty().map { l ->
            val (tx0, rx0) = lastBytes[l.peer] ?: (l.txBytes to l.rxBytes)
            lastBytes[l.peer] = l.txBytes to l.rxBytes
            LinkUi(l, nameOf(l.peer), costs[l.peer], (l.txBytes - tx0) * 8f / dt, (l.rxBytes - rx0) * 8f / dt)
        }
        val (inLatency, outLatency) = audio.latency()
        // Red: medio RTT del enlace directo, o medio RTT medio por cada salto
        val rtts = snap?.links.orEmpty().mapNotNull { l -> l.rttMs?.let { l.peer to it } }.toMap()
        val halfRtt = if (rtts.isEmpty()) 0 else rtts.values.average().toInt() / 2
        val nodeUi = synchronized(streamsLock) {
            nodes.map { n ->
                val st = streams[n.id]
                val speaking = st != null && st.jb.state != JitterBuffer.State.IDLE
                val target = (st?.jb?.target ?: 2) * VoiceFormat.FRAME_MS
                val net = when {
                    n.hops < 1 -> null
                    n.hops == 1 -> (rtts[n.id] ?: (halfRtt * 2)) / 2
                    else -> n.hops * halfRtt
                }
                NodeUi(
                    id = n.id,
                    name = nameOf(n.id),
                    hops = n.hops,
                    speaking = speaking,
                    depthMs = st?.jb?.depthMs ?: 0,
                    targetMs = target,
                    lost = st?.jb?.lost ?: 0,
                    late = st?.jb?.late ?: 0,
                    latencyMs = net?.let {
                        // Su micro (o el nuestro como referencia) + trama + red + cola + nuestra salida
                        (n.inputLatencyMs ?: inLatency ?: 0) + VoiceFormat.FRAME_MS + it +
                            (if (speaking) st.jb.depthMs else target) + (outLatency ?: 0)
                    },
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
                micMuted = micMuted,
                pttLatched = pttLatched,
                route = route,
                inputLatencyMs = inLatency,
                outputLatencyMs = outLatency,
            )
        }
    }

    private companion object {
        const val TICK_MS = 250L
        const val ANNOUNCE_MS = 2_000L
        const val PUBLISH_MS = 500L
        const val STREAM_IDLE_MS = 30_000L
        const val MESH_RETRY_MS = 5_000L
    }
}
