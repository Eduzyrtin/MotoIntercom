package com.sanabria.motointercom.core.mesh

import com.sanabria.motointercom.core.protocol.Frame
import com.sanabria.motointercom.core.protocol.Neighbor
import com.sanabria.motointercom.core.protocol.Seq

/**
 * Mapa de la malla visto desde este nodo (estado de enlaces, como OSPF en pequeño).
 *
 * Cada nodo inunda periódicamente su lista de vecinos directos con el coste de cada enlace ([Frame.LinkState]). Con eso
 * todos tienen el mismo grafo y, para cada origen, calculan el mismo árbol de caminos de menor coste (Dijkstra con
 * desempate por id). Un nodo solo reenvía una trama a sus hijos en el árbol de su origen: en una fila de motos la voz
 * salta de una a otra sin duplicados, si todos se ven bien nadie reenvía nada, y un enlace directo malo se evita si hay
 * dos saltos buenos.
 *
 * Un enlace entre otros dos nodos existe si cualquiera de los dos lo anuncia (los anuncios se refrescan cada pocos
 * segundos, así que uno caído desaparece rápido); su coste es el peor de los que anuncien sus extremos. Que existan los
 * enlaces de este nodo lo decide solo el estado local, pero su coste sigue la misma regla que usan los demás.
 */
class Topology(val selfId: Int, var selfName: String, private val expiryMs: Long = 6_000) {

    class Node(val id: Int, val name: String?, val hops: Int, val inputLatencyMs: Int?)

    private class Entry(
        var version: Int,
        var name: String,
        var neighbors: Map<Int, Int>,
        var inputLatencyMs: Int,
        var updatedAt: Long,
    )

    private val entries = HashMap<Int, Entry>()
    private var local: Map<Int, Int> = emptyMap()
    private var localIds = IntArray(0)

    /** Latencia del micro de este nodo, para anunciarla. */
    var selfInputLatencyMs = 0

    // El grafo y los árboles se recalculan solo cuando cambia algo
    private var revision = 0
    private var graphRevision = -1
    private var graph: Map<Int, Map<Int, Int>> = emptyMap()
    private val trees = HashMap<Int, Map<Int, Int>>()

    /** Vecinos directos (enlaces abiertos y saludados), ordenados. */
    val localNeighbors: IntArray get() = localIds

    /** Vecinos directos con el coste que este nodo anuncia de cada enlace. */
    val localLinks: List<Neighbor> get() = localIds.map { Neighbor(it, local.getValue(it)) }

    /** Vecinos directos con su coste. Devuelve true si algo ha cambiado (hay que anunciarlo). */
    fun setLocalNeighbors(costs: Map<Int, Int>): Boolean {
        val n = costs.filterKeys { it != selfId }
        if (n == local) return false
        local = HashMap(n)
        localIds = n.keys.sorted().toIntArray()
        revision++
        return true
    }

    /** Vecinos directos sin medida todavía: coste por defecto. */
    fun setLocalNeighbors(ids: Collection<Int>): Boolean = setLocalNeighbors(ids.associateWith { LinkQuality.DEFAULT_COST })

    /** Incorpora el estado de enlaces de otro nodo. Devuelve true si es información nueva (hay que reenviarla). */
    fun onLinkState(ls: Frame.LinkState, now: Long): Boolean {
        if (ls.origin == selfId) return false
        val n = ls.neighbors.associate { it.id to it.cost }
        val e = entries[ls.origin]
        if (e == null) {
            entries[ls.origin] = Entry(ls.version, ls.name, n, ls.inputLatencyMs, now)
            revision++
            return true
        }
        if (!Seq.isNewer(ls.version, e.version)) return false
        if (n != e.neighbors) revision++
        e.version = ls.version
        e.name = ls.name
        e.neighbors = n
        e.inputLatencyMs = ls.inputLatencyMs
        e.updatedAt = now
        return true
    }

    /** Olvida los nodos de los que no se sabe nada desde hace [expiryMs]. Devuelve sus ids. */
    fun expire(now: Long): List<Int> {
        val gone = entries.filterValues { now - it.updatedAt > expiryMs }.keys.toList()
        if (gone.isNotEmpty()) {
            gone.forEach { entries.remove(it) }
            revision++
        }
        return gone
    }

    fun name(id: Int): String? = if (id == selfId) selfName else entries[id]?.name

    /**
     * Vecinos directos a los que hay que reenviar una trama nacida en [origin] que llegó por el vecino [from]
     * (para tramas propias, from = selfId). Si el origen no está en el grafo todavía, se inunda a todos menos al que la
     * trajo; los duplicados los filtra el receptor.
     */
    fun forwardTargets(origin: Int, from: Int): IntArray {
        val parent = tree(origin)
        if (parent == null || selfId !in parent) {
            return localIds.filter { it != from && it != origin }.toIntArray()
        }
        return localIds.filter { it != from && it != origin && parent[it] == selfId }.toIntArray()
    }

    /** Nodos conocidos (sin este) con su distancia en saltos (-1 si se conoce pero no hay camino). */
    fun nodes(): List<Node> {
        val g = graph()
        val dist = HashMap<Int, Int>()
        dist[selfId] = 0
        val queue = ArrayDeque<Int>()
        queue.addLast(selfId)
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for (v in g[u]?.keys.orEmpty()) {
                if (v !in dist) {
                    dist[v] = dist.getValue(u) + 1
                    queue.addLast(v)
                }
            }
        }
        val ids = (g.keys + entries.keys).filter { it != selfId }.distinct().sorted()
        return ids.map { Node(it, entries[it]?.name, dist[it] ?: -1, entries[it]?.inputLatencyMs?.takeIf { l -> l > 0 }) }
    }

    /** Lo que [a] dice del coste de su enlace con [b] (este nodo, lo que anuncia). */
    private fun reported(a: Int, b: Int): Int? = if (a == selfId) local[b] else entries[a]?.neighbors?.get(b)

    private fun graph(): Map<Int, Map<Int, Int>> {
        if (graphRevision == revision) return graph
        val adj = HashMap<Int, HashMap<Int, Int>>()
        fun link(a: Int, b: Int) {
            if (a == b) return
            val cost = maxOf(reported(a, b) ?: 0, reported(b, a) ?: 0).coerceAtLeast(1)
            adj.getOrPut(a) { HashMap() }[b] = cost
            adj.getOrPut(b) { HashMap() }[a] = cost
        }
        adj.getOrPut(selfId) { HashMap() }
        for (n in localIds) link(selfId, n)
        for ((id, e) in entries) {
            for (n in e.neighbors.keys) {
                // Lo que otros digan de sus enlaces con este nodo puede estar desfasado: si existen lo decide el estado local
                if (id == selfId || n == selfId) continue
                link(id, n)
            }
        }
        graph = adj
        graphRevision = revision
        trees.clear()
        return graph
    }

    /**
     * Árbol de caminos de menor coste con raíz en [origin]: nodo -> padre (la raíz es su propio padre). Dijkstra
     * determinista: se visita por (distancia, id) y, a igual distancia, gana el padre de id menor. Con la misma vista
     * del grafo, todos los nodos calculan el mismo árbol.
     */
    private fun tree(origin: Int): Map<Int, Int>? {
        val g = graph()
        if (origin !in g) return null
        trees[origin]?.let { return it }
        val dist = HashMap<Int, Int>()
        val parent = HashMap<Int, Int>()
        val done = HashSet<Int>()
        dist[origin] = 0
        parent[origin] = origin
        while (true) {
            // Pocos nodos (un grupo de motos): basta con buscar el mínimo a mano
            var u: Int? = null
            for ((id, d) in dist) {
                if (id in done) continue
                val best = u
                if (best == null || d < dist.getValue(best) || (d == dist.getValue(best) && id < best)) u = id
            }
            val cur = u ?: break
            done += cur
            val du = dist.getValue(cur)
            for ((v, cost) in g[cur].orEmpty()) {
                if (v in done) continue
                val nd = du + cost
                val old = dist[v]
                if (old == null || nd < old || (nd == old && cur < parent.getValue(v))) {
                    dist[v] = nd
                    parent[v] = cur
                }
            }
        }
        trees[origin] = parent
        return parent
    }
}
