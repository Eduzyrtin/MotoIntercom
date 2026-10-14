package com.sanabria.motointercom.core.protocol

/**
 * Lo que cada móvil anuncia por BLE (datos de servicio, 12 bytes): grupo, identidad y el PSM del canal L2CAP donde
 * escucha. Con esto el que escanea sabe si es de su grupo y a qué canal conectar sin leer nada por GATT. El grupo es el
 * `advId` de GroupKeys: sale de la clave, pero no la revela.
 */
data class AdvInfo(val protocol: Int, val groupId: Int, val nodeId: Int, val psm: Int, val flags: Int = 0) {
    fun encode(): ByteArray {
        val w = ByteWriter(SIZE)
        w.u8(protocol)
        w.u32(groupId)
        w.u32(nodeId)
        w.u16(psm)
        w.u8(flags)
        return w.toByteArray()
    }

    companion object {
        const val SIZE = 12

        fun decode(b: ByteArray?): AdvInfo? {
            if (b == null || b.size < SIZE) return null
            val r = ByteReader(b, 0, b.size)
            return AdvInfo(r.u8(), r.u32(), r.u32(), r.u16(), r.u8())
        }
    }
}
