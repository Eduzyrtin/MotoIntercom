package com.sanabria.motointercom.core.mesh

/**
 * Coste de cada enlace para elegir rutas: [BASE] por salto más penalizaciones por señal débil, tramas descartadas en la
 * cola (enlace saturado) y RTT alto. Las medidas se suavizan y el coste anunciado solo cambia cuando el nuevo se
 * mantiene [HYSTERESIS] medidas seguidas: un bache de un segundo no reorganiza la malla. Se llama cada 2 s por enlace.
 */
class LinkQuality {

    /** Lo que se sabe de un enlace ahora: RSSI, RTT y contadores acumulados de tramas de voz enviadas y descartadas. */
    class Sample(val rssi: Int?, val rttMs: Int?, val sentFrames: Long, val droppedFrames: Long)

    private class State(var lastSent: Long, var lastDropped: Long) {
        var rssi: Float? = null
        var rtt: Float? = null
        var loss = 0f
        var announced = DEFAULT_COST
        var pending = 0
        var pendingCount = 0
        var first = true
    }

    private val states = HashMap<Int, State>()

    fun cost(peer: Int, s: Sample): Int {
        val st = states.getOrPut(peer) { State(s.sentFrames, s.droppedFrames) }
        s.rssi?.let { r -> st.rssi = st.rssi?.let { it + (r - it) * ALPHA } ?: r.toFloat() }
        s.rttMs?.let { r -> st.rtt = st.rtt?.let { it + (r - it) * ALPHA } ?: r.toFloat() }
        val sent = s.sentFrames - st.lastSent
        val dropped = s.droppedFrames - st.lastDropped
        st.lastSent = s.sentFrames
        st.lastDropped = s.droppedFrames
        if (sent + dropped > 0) st.loss += (dropped.toFloat() / (sent + dropped) - st.loss) * ALPHA

        val raw = BASE + rssiPenalty(st.rssi) + lossPenalty(st.loss) + rttPenalty(st.rtt)
        when {
            st.first -> {
                st.announced = raw
                st.first = false
            }
            raw == st.announced -> st.pendingCount = 0
            raw == st.pending -> if (++st.pendingCount >= HYSTERESIS) {
                st.announced = raw
                st.pendingCount = 0
            }
            else -> {
                st.pending = raw
                st.pendingCount = 1
            }
        }
        return st.announced
    }

    fun forget(peer: Int) {
        states.remove(peer)
    }

    companion object {
        /** Coste de un salto con un enlace perfecto. */
        const val BASE = 4

        /** Enlace recién abierto, aún sin medidas. */
        const val DEFAULT_COST = BASE + 2

        const val HYSTERESIS = 3
        private const val ALPHA = 0.3f

        fun rssiPenalty(rssi: Float?): Int = when {
            rssi == null -> 2
            rssi >= -70 -> 0
            rssi >= -80 -> 2
            rssi >= -88 -> 4
            else -> 6
        }

        fun lossPenalty(loss: Float): Int = when {
            loss < 0.01f -> 0
            loss < 0.05f -> 2
            loss < 0.15f -> 4
            else -> 6
        }

        fun rttPenalty(rtt: Float?): Int = if (rtt != null && rtt > 150f) 2 else 0
    }
}
