package com.sanabria.motointercom.core.mesh

import com.sanabria.motointercom.core.protocol.Frame

/**
 * Un transporte de enlaces punto a punto entre nodos del grupo (BLE L2CAP hoy; Wi-Fi Aware o internet mañana).
 * El saludo y el cifrado ([LinkSession]) y el ping de cada enlace los resuelve el transporte; al motor solo le llegan
 * enlaces ya autenticados y el resto de tramas, descifradas.
 */
interface LinkTransport {
    interface Listener {
        fun onLinkUp(peer: Int)
        fun onLinkDown(peer: Int)
        fun onFrame(peer: Int, frame: Frame)
    }

    /** Envía el contenido de una trama (tipo + cuerpo, en claro): el enlace lo cifra. */
    fun send(peer: Int, content: ByteArray, audio: Boolean)
}
