package com.sanabria.motointercom.core

import com.sanabria.motointercom.core.audio.VolumeLink
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VolumeLinkTest {
    private fun near(expected: Float, actual: Float) = assertTrue(abs(expected - actual) < 1e-4f, "esperado $expected, hay $actual")

    @Test
    fun masterMovesBothKeepingTheGap() {
        val v = VolumeLink(voice = 0.4f, media = 0.6f)
        near(0.6f, v.master)
        v.nudgeMaster(0.1f)
        near(0.5f, v.voice)
        near(0.7f, v.media)
        v.nudgeMaster(-0.3f)
        near(0.2f, v.voice)
        near(0.4f, v.media)
    }

    @Test
    fun gapSurvivesHittingBottomAndTop() {
        val v = VolumeLink(voice = 0.8f, media = 0.3f)
        v.setMaster(0.2f)
        near(0.2f, v.voice)
        near(0f, v.media)
        v.setMaster(0.8f)
        near(0.8f, v.voice)
        near(0.3f, v.media)
        // Arriba del todo el más alto se queda en 1 y el otro conserva la distancia
        v.nudgeMaster(1f)
        near(1f, v.voice)
        near(0.5f, v.media)
    }

    @Test
    fun movingOneSliderSetsANewGap() {
        val v = VolumeLink(voice = 0.5f, media = 0.5f)
        v.setVoice(0.9f)
        near(0.9f, v.master)
        near(0.5f, v.media)
        v.setMedia(0.95f)
        near(0.95f, v.master)
        near(0.9f, v.voice)
    }

    @Test
    fun gainCurve() {
        assertEquals(1f, VolumeLink.gain(0f))
        near(0.01f, VolumeLink.gain(-1f))
        near(10f, VolumeLink.gain(0.5f))
    }
}
