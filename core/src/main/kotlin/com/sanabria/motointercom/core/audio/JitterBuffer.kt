package com.sanabria.motointercom.core.audio

import com.sanabria.motointercom.core.protocol.Frame
import com.sanabria.motointercom.core.protocol.Seq

/**
 * Cola de reproducción de un emisor. Absorbe la irregularidad de llegada (BLE entrega a ráfagas) a cambio de un poco de
 * retardo: espera a tener [target] tramas antes de empezar, y [pull] se llama cada 20 ms desde la reproducción.
 *
 * - Falta una trama y hay posteriores: se da por perdida ([Out.Lost], con la siguiente para usar su FEC).
 * - No hay ninguna: subdesbordamiento. Se vuelve a acumular sin saltar la que falta (si llega tarde, suena) y el objetivo
 *   sube una trama. Si en [IDLE_AFTER_TICKS] no llega nada, la ráfaga se da por terminada.
 * - Si sobra cola de forma sostenida, se descarta una trama para recortar retardo; sin cortes en un rato, el objetivo baja.
 * - La trama marcada [Frame.Audio.END] cierra la ráfaga en cuanto suena.
 *
 * No es seguro entre hilos.
 */
class JitterBuffer(private val minTarget: Int = 2, private val maxTarget: Int = 12) {

    sealed class Out {
        class Packet(val payload: ByteArray) : Out()

        /** Trama perdida: [next] es la siguiente si ya está (su FEC reconstruye esta), o null para ocultarla sin más. */
        class Lost(val next: ByteArray?) : Out()

        object Silence : Out()
    }

    enum class State { IDLE, BUFFERING, PLAYING }

    private val slots = arrayOfNulls<ByteArray>(SLOTS)
    private val slotSeq = IntArray(SLOTS) { -1 }
    private var count = 0
    private var next = 0
    private var endSeq = -1
    private var bufferingSince = 0L
    private var misses = 0
    private var ticksSinceUnderrun = 0
    private var excessTicks = 0

    var state = State.IDLE
        private set
    var target = minTarget
        private set

    var received = 0L
        private set
    var late = 0L
        private set
    var lost = 0L
        private set
    var underruns = 0L
        private set
    var trimmed = 0L
        private set

    /** Retardo acumulado ahora mismo en la cola. */
    val depthMs: Int get() = count * VoiceFormat.FRAME_MS

    fun put(seq: Int, flags: Int, payload: ByteArray, now: Long) {
        received++
        if (state == State.IDLE) {
            restart(seq, now)
        } else {
            val d = Seq.diff(seq, next)
            if (d < 0) {
                late++
                return
            }
            // Muy por delante: el emisor se cortó y volvió, o se perdió media ráfaga. Se empieza de nuevo desde aquí.
            if (d >= SLOTS) restart(seq, now)
        }
        val i = seq and (SLOTS - 1)
        if (slotSeq[i] == seq) return
        if (slotSeq[i] == -1) count++
        slots[i] = payload
        slotSeq[i] = seq
        if (flags and Frame.Audio.END != 0) endSeq = seq
    }

    fun pull(now: Long): Out {
        when (state) {
            State.IDLE -> return Out.Silence
            State.BUFFERING -> {
                val waited = now - bufferingSince >= target * VoiceFormat.FRAME_MS + START_GRACE_MS
                if (count >= target || (count > 0 && waited)) {
                    state = State.PLAYING
                    misses = 0
                } else {
                    if (misses == 0) return Out.Silence
                    misses++
                    if (count == 0 && misses > IDLE_AFTER_TICKS) {
                        toIdle()
                        return Out.Silence
                    }
                    return if (misses <= CONCEAL_TICKS) Out.Lost(null) else Out.Silence
                }
            }
            State.PLAYING -> Unit
        }

        val p = take(next)
        if (p != null) {
            val wasEnd = next == endSeq
            next = Seq.next(next)
            if (wasEnd) toIdle() else adapt()
            return Out.Packet(p)
        }
        if (count > 0) {
            lost++
            val following = peek(Seq.next(next))
            next = Seq.next(next)
            return Out.Lost(following)
        }
        underruns++
        ticksSinceUnderrun = 0
        target = minOf(target + 1, maxTarget)
        state = State.BUFFERING
        bufferingSince = now
        misses = 1
        return Out.Lost(null)
    }

    fun reset() = toIdle()

    private fun adapt() {
        ticksSinceUnderrun++
        if (ticksSinceUnderrun >= RELAX_TICKS && target > minTarget) {
            target--
            ticksSinceUnderrun = 0
        }
        if (count > target + 1) {
            if (++excessTicks >= TRIM_AFTER_TICKS) {
                take(next)
                next = Seq.next(next)
                trimmed++
                excessTicks = 0
            }
        } else {
            excessTicks = 0
        }
    }

    private fun restart(seq: Int, now: Long) {
        clear()
        next = seq
        state = State.BUFFERING
        bufferingSince = now
        misses = 0
    }

    private fun toIdle() {
        clear()
        state = State.IDLE
        misses = 0
    }

    private fun clear() {
        slots.fill(null)
        slotSeq.fill(-1)
        count = 0
        endSeq = -1
        excessTicks = 0
    }

    private fun take(seq: Int): ByteArray? {
        val i = seq and (SLOTS - 1)
        if (slotSeq[i] != seq) return null
        val p = slots[i]
        slots[i] = null
        slotSeq[i] = -1
        count--
        return p
    }

    private fun peek(seq: Int): ByteArray? {
        val i = seq and (SLOTS - 1)
        return if (slotSeq[i] == seq) slots[i] else null
    }

    private companion object {
        const val SLOTS = 64
        const val START_GRACE_MS = 40
        const val CONCEAL_TICKS = 3
        const val IDLE_AFTER_TICKS = 15
        const val RELAX_TICKS = 500
        const val TRIM_AFTER_TICKS = 25
    }
}
