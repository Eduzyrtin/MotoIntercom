package com.sanabria.motointercom.core.audio

import com.sanabria.motointercom.core.protocol.Frame

/**
 * Convierte el flujo continuo de tramas codificadas en ráfagas de voz. Mientras la puerta está cerrada guarda las
 * últimas [preRoll] tramas: al abrir se envían primero (el detector tarda 40 ms en reaccionar y se comería el inicio de
 * la palabra). La primera trama lleva [Frame.Audio.START] y la de cierre [Frame.Audio.END].
 *
 * El contador solo avanza con lo que se envía, así que dentro de una ráfaga un hueco es siempre una pérdida.
 */
class TalkSender(private val preRoll: Int = 2, private val emit: (counter: Int, flags: Int, payload: ByteArray) -> Unit) {
    private var counter = 0
    private val pending = ArrayDeque<ByteArray>()

    var talking = false
        private set

    /** Una trama codificada cada 20 ms; [open] = la puerta de voz está abierta. [payload] tiene que ser una copia propia. */
    fun onFrame(payload: ByteArray, open: Boolean) {
        if (open) {
            if (!talking) {
                talking = true
                var flags = Frame.Audio.START
                for (p in pending) {
                    send(p, flags)
                    flags = 0
                }
                pending.clear()
                send(payload, flags)
            } else {
                send(payload, 0)
            }
        } else if (talking) {
            talking = false
            send(payload, Frame.Audio.END)
        } else {
            pending.addLast(payload)
            while (pending.size > preRoll) pending.removeFirst()
        }
    }

    fun reset() {
        talking = false
        pending.clear()
    }

    private fun send(payload: ByteArray, flags: Int) {
        emit(counter, flags, payload)
        counter++
    }
}
