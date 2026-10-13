package com.sanabria.motointercom.core.mesh

/**
 * Decide a qué nodos del grupo, de los que se ven por radio, conectar.
 *
 * - Inicia el de id menor; el mayor espera [ASYMMETRIC_WAIT_MS] por si el otro no le ve (alcance asimétrico) y entonces
 *   inicia él. Si se cruzan dos enlaces, el transporte se queda con el que inició el de id menor.
 * - Como mucho [maxLinks] enlaces. Con la malla con relevo no hace falta que todos se conecten con todos: se prefieren los
 *   de mejor señal y el resto llega por saltos.
 * - Tras un fallo, espera creciente (1, 2, 4, 8 y como mucho 15 s).
 */
class LinkPolicy(private val selfId: Int) {

    /** Un nodo del grupo visto por radio. [waitingSince] = desde cuándo se ve sin enlace con él. */
    class Candidate(val nodeId: Int, val rssi: Int, val lastSeen: Long, val waitingSince: Long)

    var maxLinks = 4

    private val failures = HashMap<Int, Int>()
    private val retryAt = HashMap<Int, Long>()

    fun pick(candidates: List<Candidate>, linked: Set<Int>, connecting: Set<Int>, now: Long): List<Int> {
        val free = maxLinks - linked.size - connecting.size
        if (free <= 0) return emptyList()
        return candidates
            .filter {
                it.nodeId != selfId &&
                    now - it.lastSeen < SEEN_TIMEOUT_MS &&
                    it.nodeId !in linked && it.nodeId !in connecting &&
                    now >= (retryAt[it.nodeId] ?: 0L) &&
                    (selfId < it.nodeId || now - it.waitingSince > ASYMMETRIC_WAIT_MS)
            }
            .sortedByDescending { it.rssi }
            .take(free)
            .map { it.nodeId }
    }

    fun onFailure(nodeId: Int, now: Long) {
        val n = (failures[nodeId] ?: 0) + 1
        failures[nodeId] = n
        retryAt[nodeId] = now + minOf(MAX_BACKOFF_MS, 1_000L shl minOf(n - 1, 4))
    }

    fun onSuccess(nodeId: Int) {
        failures.remove(nodeId)
        retryAt.remove(nodeId)
    }

    companion object {
        const val SEEN_TIMEOUT_MS = 3_000L
        const val ASYMMETRIC_WAIT_MS = 8_000L
        const val MAX_BACKOFF_MS = 15_000L
    }
}
