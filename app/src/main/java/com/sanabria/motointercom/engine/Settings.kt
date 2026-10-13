package com.sanabria.motointercom.engine

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlin.random.Random

/** Por dónde va el audio. */
enum class AudioMode {
    /** Micro y voces por el casco como manos libres (HFP). Funciona con cualquier casco; calidad de llamada. */
    HELMET,

    /** Voces por A2DP mezcladas con la música por el sistema; micro del móvil (o uno con cable). */
    MIX,
}

/** Cuándo se envía la voz. */
enum class TalkMode { AUTO, PTT, OPEN, MUTE }

/** PHY que se pide para cada enlace ya abierto (AUTO = lo que decida el controlador). */
enum class PhyPreference { AUTO, LE_1M, LE_2M, CODED_S2, CODED_S8 }

/** Fuente del micro en modo mezcla: con el procesado de voz del sistema (ruido, ganancia) o en bruto. */
enum class MicSource { VOICE, RAW }

/** Uso de audio de las voces en modo mezcla: multimedia (sigue al volumen de la música) o llamada (volumen propio). */
enum class VoiceOutput { MEDIA, CALL }

data class IntercomSettings(
    val nodeId: Int,
    val name: String,
    val group: String = "MOTO",
    val audioMode: AudioMode = AudioMode.HELMET,
    val talkMode: TalkMode = TalkMode.AUTO,
    /** Niveles 0..1 de voz y multimedia; el maestro es el mayor de los dos (ver VolumeLink). */
    val voiceLevel: Float = 0.8f,
    val mediaLevel: Float = 0.5f,
    val vadSensitivity: Float = 0.5f,
    val phy: PhyPreference = PhyPreference.AUTO,
    val longRange: Boolean = true,
    val bitrate: Int = 16_000,
    val maxLinks: Int = 4,
    val micSource: MicSource = MicSource.VOICE,
    val voiceOutput: VoiceOutput = VoiceOutput.MEDIA,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("intercom", Context.MODE_PRIVATE)

    fun load(): IntercomSettings {
        var id = prefs.getInt(NODE_ID, 0)
        if (id == 0) {
            // Identidad del nodo en la malla: aleatoria, fija para este móvil
            id = Random.nextInt().let { if (it == 0) 1 else it }
            prefs.edit { putInt(NODE_ID, id) }
        }
        val d = IntercomSettings(nodeId = id, name = Build.MODEL)
        return IntercomSettings(
            nodeId = id,
            name = prefs.getString("name", null) ?: d.name,
            group = prefs.getString("group", null) ?: d.group,
            audioMode = enumOr(prefs.getString("audioMode", null), d.audioMode),
            talkMode = enumOr(prefs.getString("talkMode", null), d.talkMode),
            voiceLevel = prefs.getFloat("voiceLevel", d.voiceLevel),
            mediaLevel = prefs.getFloat("mediaLevel", d.mediaLevel),
            vadSensitivity = prefs.getFloat("vadSensitivity", d.vadSensitivity),
            phy = enumOr(prefs.getString("phy", null), d.phy),
            longRange = prefs.getBoolean("longRange", d.longRange),
            bitrate = prefs.getInt("bitrate", d.bitrate),
            maxLinks = prefs.getInt("maxLinks", d.maxLinks),
            micSource = enumOr(prefs.getString("micSource", null), d.micSource),
            voiceOutput = enumOr(prefs.getString("voiceOutput", null), d.voiceOutput),
        )
    }

    fun save(s: IntercomSettings) = prefs.edit {
        putString("name", s.name)
        putString("group", s.group)
        putString("audioMode", s.audioMode.name)
        putString("talkMode", s.talkMode.name)
        putFloat("voiceLevel", s.voiceLevel)
        putFloat("mediaLevel", s.mediaLevel)
        putFloat("vadSensitivity", s.vadSensitivity)
        putString("phy", s.phy.name)
        putBoolean("longRange", s.longRange)
        putInt("bitrate", s.bitrate)
        putInt("maxLinks", s.maxLinks)
        putString("micSource", s.micSource.name)
        putString("voiceOutput", s.voiceOutput.name)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val NODE_ID = "nodeId"
    }
}
