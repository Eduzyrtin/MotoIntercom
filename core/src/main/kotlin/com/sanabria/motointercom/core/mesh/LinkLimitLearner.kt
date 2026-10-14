package com.sanabria.motointercom.core.mesh

/**
 * Cuántos enlaces BLE a la vez aguanta el chip de este móvil. Android no lo dice (depende del controlador), así que se
 * aprende: si al intentar un enlace más con un nodo que se ve bien falla [FAILS_TO_LEARN] veces seguidas teniendo ya
 * los mismos enlaces abiertos, ese número es el límite. Si más tarde un enlace más sí funciona (por ejemplo uno
 * entrante), el límite sube. Por debajo de [MIN_LEVEL] enlaces los fallos son de alcance, no del chip.
 *
 * [learned] = 0 significa que aún no se sabe (sin límite propio).
 */
class LinkLimitLearner(learned: Int = 0) {
    var learned = learned
        private set

    private var failLevel = -1
    private var fails = 0

    /**
     * Resultado de añadir un enlace teniendo [ready] abiertos. [goodSignal] = el otro se veía con buena señal.
     * Devuelve true si el límite aprendido ha cambiado (hay que guardarlo).
     */
    fun onResult(ready: Int, goodSignal: Boolean, success: Boolean): Boolean {
        if (success) {
            if (failLevel == ready) {
                failLevel = -1
                fails = 0
            }
            if (learned != 0 && ready + 1 > learned) {
                learned = ready + 1
                return true
            }
            return false
        }
        if (!goodSignal || ready < MIN_LEVEL) return false
        if (failLevel != ready) {
            failLevel = ready
            fails = 0
        }
        if (++fails >= FAILS_TO_LEARN && (learned == 0 || ready < learned)) {
            learned = ready
            fails = 0
            return true
        }
        return false
    }

    /** Enlaces máximos a usar: el del usuario, sin pasar del límite aprendido. */
    fun limit(userMax: Int): Int = if (learned == 0) userMax else minOf(userMax, learned)

    fun reset() {
        learned = 0
        failLevel = -1
        fails = 0
    }

    companion object {
        const val FAILS_TO_LEARN = 3
        const val MIN_LEVEL = 2
    }
}
