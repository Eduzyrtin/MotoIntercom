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
        /** Encola [frame] (ya codificada) en el enlace con [peer]. [audio] = se puede descartar si el enlace va atascado. */
        fun send(peer: Int, frame: ByteArray, audio: Boolean)

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
    fun sendLocalAudio(seq: Int, flags: Int, payload: ByteArray) {
        val targets = topology.forwardTargets(selfId, selfId)
        if (targets.isEmpty()) return
        val bytes = FrameCodec.encode(Frame.Audio(selfId, seq, flags, 0, payload))
        for (p in targets) out.send(p, bytes, true)
    }

    fun onAudio(from: Int, f: Frame.Audio, now: Long) {
        if (f.origin == selfId) return
        if (!seen.getOrPut(f.origin) { SeenWindow() }.firstTime(f.seq, now)) {
            duplicates++
            return
        }
        out.deliver(f)
        if (f.hops + 1 >= MAX_HOPS) return
        val targets = topology.forwardTargets(f.origin, from)
        if (targets.isEmpty()) return
        val bytes = FrameCodec.encode(f.nextHop())
        for (p in targets) out.send(p, bytes, true)
        forwarded += targets.size
    }

    fun onLinkState(from: Int, f: Frame.LinkState, now: Long) {
        if (!topology.onLinkState(f, now)) return
        if (f.hops + 1 >= MAX_HOPS) return
        val bytes = FrameCodec.encode(f.copy(hops = f.hops + 1))
        for (p in topology.localNeighbors) if (p != from && p != f.origin) out.send(p, bytes, false)
    }

    /** Anuncia los vecinos de este nodo. Se llama periódicamente y cada vez que se abre o cierra un enlace. */
    fun announce() {
        val local = topology.localNeighbors
        if (local.isEmpty()) return
        linkStateVersion = Seq.next(linkStateVersion)
        val bytes = FrameCodec.encode(Frame.LinkState(selfId, linkStateVersion, 0, topology.selfName, local.toList()))
        for (p in local) out.send(p, bytes, false)
    }

    /** El nodo desapareció de la malla: si vuelve, su secuencia empieza de cero. */
    fun forget(origin: Int) {
        seen.remove(origin)
    }

    companion object {
        /** Límite de saltos: corta bucles si las vistas de la malla difieren un momento. */
        const val MAX_HOPS = 8
    }
}

/**
 * Detecta duplicados por número de secuencia. Un duplicado llega por otro camino en milisegundos; si la misma secuencia
 * vuelve pasados [WINDOW_MS] es una trama nueva (el emisor reinició o la secuencia dio la vuelta).
 */
class SeenWindow {
    private val seqs = IntArray(SLOTS) { -1 }
    private val times = LongArray(SLOTS)

    fun firstTime(seq: Int, now: Long): Boolean {
        val i = seq and (SLOTS - 1)
        if (seqs[i] == seq && now - times[i] < WINDOW_MS) return false
        seqs[i] = seq
        times[i] = now
        return true
    }

    private companion object {
        const val SLOTS = 1024
        const val WINDOW_MS = 2_000L
    }
}
