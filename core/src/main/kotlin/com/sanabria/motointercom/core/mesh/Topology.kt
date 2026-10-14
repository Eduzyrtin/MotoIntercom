package com.sanabria.motointercom.core.mesh

import com.sanabria.motointercom.core.protocol.Frame
import com.sanabria.motointercom.core.protocol.Seq

/**
 * Mapa de la malla visto desde este nodo (estado de enlaces, como OSPF en pequeño).
 *
 * Cada nodo inunda periódicamente su lista de vecinos directos ([Frame.LinkState]). Con eso todos tienen el mismo grafo y,
 * para cada origen, calculan el mismo árbol de caminos mínimos (BFS, desempate por id). Un nodo solo reenvía una trama a
 * sus hijos en el árbol de su origen: en una fila de motos la voz salta de una a otra sin duplicados, y si todos se ven
 * entre sí nadie reenvía nada.
 *
 * Un enlace entre otros dos nodos existe si cualquiera de los dos lo anuncia (los anuncios se refrescan cada pocos
 * segundos, así que uno caído desaparece rápido). Los enlaces de este nodo los decide solo el estado local.
 */
class Topology(val selfId: Int, var selfName: String, private val expiryMs: Long = 6_000) {

    class Node(val id: Int, val name: String?, val hops: Int)

    private class Entry(var version: Int, var name: String, var neighbors: IntArray, var updatedAt: Long)

    private val entries = HashMap<Int, Entry>()
    private var local = IntArray(0)

    // El grafo y los árboles se recalculan solo cuando cambia algo
    private var revision = 0
    private var graphRevision = -1
    private var graph: Map<Int, IntArray> = emptyMap()
    private val trees = HashMap<Int, Map<Int, Int>>()

    /** Vecinos directos (enlaces abiertos y saludados), ordenados. */
    val localNeighbors: IntArray get() = local

    fun setLocalNeighbors(ids: Collection<Int>): Boolean {
        val n = ids.filter { it != selfId }.distinct().sorted().toIntArray()
        if (n.contentEquals(local)) return false
        local = n
        revision++
        return true
    }

    /** Incorpora el estado de enlaces de otro nodo. Devuelve true si es información nueva (hay que reenviarla). */
    fun onLinkState(ls: Frame.LinkState, now: Long): Boolean {
        if (ls.origin == selfId) return false
        val n = ls.neighbors.distinct().sorted().toIntArray()
        val e = entries[ls.origin]
        if (e == null) {
            entries[ls.origin] = Entry(ls.version, ls.name, n, now)
            revision++
            return true
        }
        if (!Seq.isNewer(ls.version, e.version)) return false
        if (!n.contentEquals(e.neighbors)) revision++
        e.version = ls.version
        e.name = ls.name
        e.neighbors = n
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
     * (para tramas propias, from = selfId). Si el origen no está en el grafo todavía, se inunda a todos menos al
     * que la trajo; los duplicados los filtra el receptor.
     */
    fun forwardTargets(origin: Int, from: Int): IntArray {
        val parent = tree(origin)
        if (parent == null || selfId !in parent) {
            return local.filter { it != from && it != origin }.toIntArray()
        }
        return local.filter { it != from && it != origin && parent[it] == selfId }.toIntArray()
    }

    /** Nodos conocidos (sin este) con su distancia en saltos; -1 si se conoce pero no hay camino. */
    fun nodes(): List<Node> {
        val g = graph()
        val dist = HashMap<Int, Int>()
        dist[selfId] = 0
        val queue = ArrayDeque<Int>()
        queue.addLast(selfId)
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for (v in g[u] ?: IntArray(0)) {
                if (v !in dist) {
                    dist[v] = dist.getValue(u) + 1
                    queue.addLast(v)
                }
            }
        }
        val ids = (g.keys + entries.keys).filter { it != selfId }.distinct().sorted()
        return ids.map { Node(it, entries[it]?.name, dist[it] ?: -1) }
    }

    private fun graph(): Map<Int, IntArray> {
        if (graphRevision == revision) return graph
        val adj = HashMap<Int, MutableSet<Int>>()
        fun link(a: Int, b: Int) {
            if (a == b) return
            adj.getOrPut(a) { mutableSetOf() }.add(b)
            adj.getOrPut(b) { mutableSetOf() }.add(a)
        }
        adj.getOrPut(selfId) { mutableSetOf() }
        for (n in local) link(selfId, n)
        for ((id, e) in entries) {
            for (n in e.neighbors) {
                // Lo que otros digan de sus enlaces con este nodo puede estar desfasado: manda el estado local
                if (id == selfId || n == selfId) continue
                link(id, n)
            }
        }
        graph = adj.mapValues { it.value.sorted().toIntArray() }
        graphRevision = revision
        trees.clear()
        return graph
    }

    /** Árbol de caminos mínimos con raíz en [origin]: nodo -> padre (la raíz es su propio padre). */
    private fun tree(origin: Int): Map<Int, Int>? {
        val g = graph()
        if (origin !in g) return null
        trees[origin]?.let { return it }
        val parent = HashMap<Int, Int>()
        parent[origin] = origin
        val queue = ArrayDeque<Int>()
        queue.addLast(origin)
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for (v in g[u] ?: IntArray(0)) {
                if (v !in parent) {
                    parent[v] = u
                    queue.addLast(v)
                }
            }
        }
        trees[origin] = parent
        return parent
    }
}
