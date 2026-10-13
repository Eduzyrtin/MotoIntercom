package com.sanabria.motointercom.core.mesh

import com.sanabria.motointercom.core.protocol.Frame

/**
 * Un transporte de enlaces punto a punto entre nodos del grupo (BLE L2CAP hoy; Wi-Fi Aware o internet mañana).
 * El saludo ([Frame.Hello]) y el ping de cada enlace los resuelve el transporte; al motor solo le llegan enlaces ya
 * identificados y el resto de tramas.
 */
interface LinkTransport {
    interface Listener {
        fun onLinkUp(peer: Int, name: String)
        fun onLinkDown(peer: Int)
        fun onFrame(peer: Int, frame: Frame)
    }

    fun send(peer: Int, frame: ByteArray, audio: Boolean)
}
