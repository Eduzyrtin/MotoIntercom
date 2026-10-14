package com.sanabria.motointercom.core.mesh

import com.sanabria.motointercom.core.protocol.Frame
import com.sanabria.motointercom.core.protocol.FrameCodec
import com.sanabria.motointercom.core.protocol.Seq
import kotlin.random.Random

/**
 * Encaminamiento de la malla: entrega la voz que llega a este nodo, la reenvía a quien le toca según [Topology] e inunda
 * los estados de enlace. No es seguro entre hilos: quien lo use lo protege con un cerrojo.
 */
class Router(private val topology: Topology, private val out: Output) {

    interface Output {
        /**
         * Encola el contenido de una trama (tipo + cuerpo, en claro) en el enlace con [peer]; el enlace lo cifra al
         * enviarlo. [audio] = se puede descartar si el enlace va atascado.
         */
        fun send(peer: Int, content: ByteArray, audio: Boolean)

        /** Voz de otro nodo para reproducir. */
        fun deliver(audio: Frame.Audio)
    }

    private val selfId get() = topology.selfId
    private val seen = HashMap<Int, SeenWindow>()
    private var linkStateVersion = Random.nextInt(0x10000)

    var duplicates = 0L
        private set
    var forwarded = 0L
        private set

    /** Voz capturada aquí: va a todos los vecinos directos. */
    fun sendLocalAudio(session: Int, counter: Int, flags: Int, payload: ByteArray) {
        val targets = topology.forwardTargets(selfId, selfId)
        if (targets.isEmpty()) return
        val content = FrameCodec.encodeBody(Frame.Audio(selfId, session, counter, flags, 0, payload))
        for (p in targets) out.send(p, content, true)
    }

    fun onAudio(from: Int, f: Frame.Audio) {
        if (f.origin == selfId) return
        val window = seen[f.origin]?.takeIf { it.session == f.session } ?: SeenWindow(f.session).also { seen[f.origin] = it }
        if (!window.firstTime(f.counter)) {
            duplicates++
            return
        }
        out.deliver(f)
        if (f.hops + 1 >= MAX_HOPS) return
        val targets = topology.forwardTargets(f.origin, from)
        if (targets.isEmpty()) return
        val content = FrameCodec.encodeBody(f.nextHop())
        for (p in targets) out.send(p, content, true)
        forwarded += targets.size
    }

    fun onLinkState(from: Int, f: Frame.LinkState, now: Long) {
        if (!topology.onLinkState(f, now)) return
        if (f.hops + 1 >= MAX_HOPS) return
        val content = FrameCodec.encodeBody(f.copy(hops = f.hops + 1))
        for (p in topology.localNeighbors) if (p != from && p != f.origin) out.send(p, content, false)
    }

    /** Anuncia los vecinos de este nodo. Se llama periódicamente y cada vez que se abre o cierra un enlace. */
    fun announce() {
        val local = topology.localNeighbors
        if (local.isEmpty()) return
        linkStateVersion = Seq.next(linkStateVersion)
        val content = FrameCodec.encodeBody(Frame.LinkState(selfId, linkStateVersion, 0, topology.selfName, local.toList()))
        for (p in local) out.send(p, content, false)
    }

    /** El nodo desapareció de la malla. */
    fun forget(origin: Int) {
        seen.remove(origin)
    }

    companion object {
        /** Límite de saltos: corta bucles si las vistas de la malla difieren un momento. */
        const val MAX_HOPS = 8
    }
}

/**
 * Detecta duplicados dentro de una sesión de un emisor. El contador no se repite en la sesión, así que basta con
 * recordar los últimos [SLOTS] (20 s de voz): un duplicado llega por otro camino en milisegundos.
 */
class SeenWindow(val session: Int) {
    private val counters = IntArray(SLOTS)
    private val used = BooleanArray(SLOTS)

    fun firstTime(counter: Int): Boolean {
        val i = counter and (SLOTS - 1)
        if (used[i] && counters[i] == counter) return false
        counters[i] = counter
        used[i] = true
        return true
    }

    private companion object {
        const val SLOTS = 1024
    }
}
