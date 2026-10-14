package com.sanabria.motointercom.core

import com.sanabria.motointercom.core.mesh.LinkPolicy
import com.sanabria.motointercom.core.mesh.Router
import com.sanabria.motointercom.core.mesh.Topology
import com.sanabria.motointercom.core.protocol.Frame
import com.sanabria.motointercom.core.protocol.FrameCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeshTest {

    /** Malla simulada: cada nodo con su Topology y su Router, y las tramas viajando por una cola. */
    private class Sim(ids: List<Int>, edges: List<Pair<Int, Int>>) {
        inner class Node(val id: Int) : Router.Output {
            val topology = Topology(id, "n$id")
            val router = Router(topology, this)
            val delivered = mutableListOf<Int>()

            override fun send(peer: Int, content: ByteArray, audio: Boolean) {
                check(peer in neighbors(id)) { "$id envía a $peer sin enlace" }
                if (audio) audioSends++
                queue.addLast(Triple(id, peer, content))
            }

            override fun deliver(audio: Frame.Audio) {
                delivered += audio.origin
            }
        }

        val links = edges.flatMap { listOf(it, it.second to it.first) }.toSet()
        val nodes = ids.associateWith { Node(it) }
        val queue = ArrayDeque<Triple<Int, Int, ByteArray>>()
        var audioSends = 0
        var now = 0L

        fun neighbors(id: Int) = links.filter { it.first == id }.map { it.second }

        init {
            nodes.values.forEach { it.topology.setLocalNeighbors(neighbors(it.id)) }
        }

        fun converge() {
            repeat(2) {
                nodes.values.forEach { it.router.announce() }
                pump()
            }
            audioSends = 0
        }

        fun pump() {
            while (queue.isNotEmpty()) {
                val (from, to, content) = queue.removeFirst()
                val r = nodes.getValue(to).router
                when (val f = FrameCodec.decodeBody(content)) {
                    is Frame.Audio -> r.onAudio(from, f)
                    is Frame.LinkState -> r.onLinkState(from, f, now)
                    else -> Unit
                }
            }
        }

        fun talk(origin: Int, counter: Int = 1, session: Int = 42) {
            nodes.getValue(origin).router.sendLocalAudio(session, counter, 0, byteArrayOf(1))
            pump()
        }

        fun assertEveryoneHeardOnce(origin: Int) {
            for (n in nodes.values) {
                if (n.id == origin) assertTrue(n.delivered.isEmpty()) else assertEquals(listOf(origin), n.delivered, "nodo ${n.id}")
            }
        }
    }

    @Test
    fun chainRelaysEndToEnd() {
        val sim = Sim(listOf(1, 2, 3, 4), listOf(1 to 2, 2 to 3, 3 to 4))
        sim.converge()
        sim.talk(1)
        sim.assertEveryoneHeardOnce(1)
        assertEquals(3, sim.audioSends)
        assertEquals(3, sim.nodes.getValue(1).topology.nodes().first { it.id == 4 }.hops)
    }

    @Test
    fun fullMeshNeverForwards() {
        val ids = listOf(1, 2, 3, 4)
        val sim = Sim(ids, ids.flatMap { a -> ids.filter { it > a }.map { a to it } })
        sim.converge()
        sim.talk(3)
        sim.assertEveryoneHeardOnce(3)
        assertEquals(3, sim.audioSends)
    }

    @Test
    fun diamondUsesOnePath() {
        // 1 llega a 4 por 2 y por 3; solo debe reenviar uno de los dos (el de id menor)
        val sim = Sim(listOf(1, 2, 3, 4), listOf(1 to 2, 1 to 3, 2 to 4, 3 to 4))
        sim.converge()
        sim.talk(1)
        sim.assertEveryoneHeardOnce(1)
        assertEquals(3, sim.audioSends)
    }

    @Test
    fun unknownTopologyFloodsWithoutDuplicates() {
        val sim = Sim(listOf(1, 2, 3), listOf(1 to 2, 2 to 3, 1 to 3))
        sim.talk(1)
        sim.assertEveryoneHeardOnce(1)
        assertTrue(sim.nodes.values.sumOf { it.router.duplicates } > 0)
    }

    @Test
    fun linkDownReroutes() {
        val sim = Sim(listOf(1, 2, 3), listOf(1 to 2, 2 to 3, 1 to 3))
        sim.converge()
        sim.talk(1, counter = 1)
        assertEquals(2, sim.audioSends)

        // Se cae 1-3: 1 y 3 lo saben localmente; tras el anuncio, 2 pasa a reenviar
        val reduced = Sim(listOf(1, 2, 3), listOf(1 to 2, 2 to 3))
        reduced.converge()
        reduced.talk(1, counter = 2)
        reduced.assertEveryoneHeardOnce(1)
        assertEquals(2, reduced.audioSends)
    }

    @Test
    fun newSessionIsNotADuplicate() {
        val sim = Sim(listOf(1, 2), listOf(1 to 2))
        sim.converge()
        sim.talk(1, counter = 5, session = 1)
        // El emisor se reconecta: misma cuenta, sesión nueva
        sim.talk(1, counter = 5, session = 2)
        assertEquals(listOf(1, 1), sim.nodes.getValue(2).delivered)
        sim.talk(1, counter = 5, session = 2)
        assertEquals(1L, sim.nodes.getValue(2).router.duplicates)
    }

    @Test
    fun expiryForgetsSilentNodes() {
        val sim = Sim(listOf(1, 2, 3), listOf(1 to 2, 2 to 3))
        sim.converge()
        val t = sim.nodes.getValue(1).topology
        assertEquals(listOf(2, 3), t.nodes().map { it.id })
        assertEquals(listOf(2, 3), t.expire(10_000).sorted())
    }

    @Test
    fun policyLowerIdInitiatesAndBacksOff() {
        val low = LinkPolicy(1)
        val high = LinkPolicy(9)
        val now = 100_000L
        val seenSince = now - 1_000
        assertEquals(listOf(9), low.pick(listOf(LinkPolicy.Candidate(9, -60, now, seenSince)), emptySet(), emptySet(), now))
        assertEquals(emptyList(), high.pick(listOf(LinkPolicy.Candidate(1, -60, now, seenSince)), emptySet(), emptySet(), now))
        // Si el de id menor no conecta en 8 s, lo intenta el mayor
        assertEquals(listOf(1), high.pick(listOf(LinkPolicy.Candidate(1, -60, now, now - 9_000)), emptySet(), emptySet(), now))

        low.onFailure(9, now)
        assertEquals(emptyList(), low.pick(listOf(LinkPolicy.Candidate(9, -60, now, seenSince)), emptySet(), emptySet(), now + 500))
        assertEquals(listOf(9), low.pick(listOf(LinkPolicy.Candidate(9, -60, now, seenSince)), emptySet(), emptySet(), now + 1_000))
    }

    @Test
    fun policyPrefersStrongestAndRespectsLimit() {
        val p = LinkPolicy(1).apply { maxLinks = 2 }
        val now = 50_000L
        val c = listOf(-90, -50, -70).mapIndexed { i, rssi -> LinkPolicy.Candidate(10 + i, rssi, now, now) }
        assertEquals(listOf(11), p.pick(c, setOf(5), emptySet(), now))
    }
}
