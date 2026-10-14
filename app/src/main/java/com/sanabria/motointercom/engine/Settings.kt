package com.sanabria.motointercom.engine

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.sanabria.motointercom.core.crypto.Group
import com.sanabria.motointercom.core.crypto.GroupInvite
import com.sanabria.motointercom.core.crypto.GroupKeys
import com.sanabria.motointercom.crypto.AndroidCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** Grupo actual: nombre y clave (32 bytes en base64url). Solo se ven los móviles con la misma clave. */
    val groupName: String,
    val groupKeyText: String,
    val audioMode: AudioMode = AudioMode.HELMET,
    val talkMode: TalkMode = TalkMode.AUTO,
    /** Niveles 0..1 de voz y multimedia; el maestro es el mayor de los dos (ver VolumeLink). */
    val voiceLevel: Float = 0.8f,
    val mediaLevel: Float = 0.5f,
    val vadSensitivity: Float = 0.5f,
    val phy: PhyPreference = PhyPreference.AUTO,
    val longRange: Boolean = true,
    /** Calidad de voz máxima: la adaptativa baja de aquí si los enlaces se saturan. */
    val bitrate: Int = 16_000,
    val maxLinks: Int = 4,
    /** Enlaces a la vez que aguanta el chip de este móvil, aprendido al conectar (0 = no se sabe). */
    val learnedLinkLimit: Int = 0,
    val micSource: MicSource = MicSource.VOICE,
    val voiceOutput: VoiceOutput = VoiceOutput.MEDIA,
    /** El botón de reproducir/pausa del casco abre y cierra el micro (en PTT, habla mientras está activado). */
    val helmetButton: Boolean = false,
    /** Pitidos al abrir/cerrar el micro y cuando alguien entra o sale del grupo. */
    val earcons: Boolean = true,
) {
    val groupKey: ByteArray get() = checkNotNull(GroupInvite.fromBase64Url(groupKeyText))

    val group: Group get() = Group(groupName, groupKey)

    val groupCode: String get() = groupCode(groupKey)
}

/** Código corto de un grupo para comprobar de un vistazo que todos están en el mismo (sale del advId, no revela la clave). */
fun groupCode(key: ByteArray): String =
    "%08X".format(GroupKeys(AndroidCrypto, key).advId).let { it.substring(0, 4) + "-" + it.substring(4) }

/**
 * Ajustes guardados y su estado observable: la interfaz y el servicio leen de aquí.
 */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("intercom", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<IntercomSettings> = _settings.asStateFlow()

    val current: IntercomSettings get() = _settings.value

    fun update(change: (IntercomSettings) -> IntercomSettings): IntercomSettings {
        val s = change(_settings.value)
        if (s != _settings.value) {
            _settings.value = s
            save(s)
        }
        return s
    }

    /** Grupo nuevo con clave aleatoria: los demás tendrán que volver a unirse con el QR. */
    fun newGroup(name: String) =
        update { it.copy(groupName = name, groupKeyText = GroupInvite.base64Url(AndroidCrypto.randomBytes(GroupKeys.KEY_BYTES))) }

    fun joinGroup(group: Group) = update { it.copy(groupName = group.name, groupKeyText = GroupInvite.base64Url(group.key)) }

    private fun load(): IntercomSettings {
        var id = prefs.getInt(NODE_ID, 0)
        if (id == 0) {
            // Identidad del nodo en la malla: aleatoria, fija para este móvil
            id = Random.nextInt().let { if (it == 0) 1 else it }
            prefs.edit { putInt(NODE_ID, id) }
        }
        // Antes de las claves el grupo era solo un nombre: se le da una clave nueva
        val key = prefs.getString(GROUP_KEY, null)?.takeIf { GroupInvite.fromBase64Url(it)?.size == GroupKeys.KEY_BYTES }
            ?: GroupInvite.base64Url(AndroidCrypto.randomBytes(GroupKeys.KEY_BYTES)).also { prefs.edit { putString(GROUP_KEY, it) } }
        val d = IntercomSettings(nodeId = id, name = Build.MODEL, groupName = "MOTO", groupKeyText = key)
        return d.copy(
            name = prefs.getString("name", null) ?: d.name,
            groupName = prefs.getString("groupName", null) ?: prefs.getString("group", null) ?: d.groupName,
            audioMode = enumOr(prefs.getString("audioMode", null), d.audioMode),
            talkMode = enumOr(prefs.getString("talkMode", null), d.talkMode),
            voiceLevel = prefs.getFloat("voiceLevel", d.voiceLevel),
            mediaLevel = prefs.getFloat("mediaLevel", d.mediaLevel),
            vadSensitivity = prefs.getFloat("vadSensitivity", d.vadSensitivity),
            phy = enumOr(prefs.getString("phy", null), d.phy),
            longRange = prefs.getBoolean("longRange", d.longRange),
            bitrate = prefs.getInt("bitrate", d.bitrate),
            maxLinks = prefs.getInt("maxLinks", d.maxLinks),
            learnedLinkLimit = prefs.getInt("learnedLinkLimit", d.learnedLinkLimit),
            micSource = enumOr(prefs.getString("micSource", null), d.micSource),
            voiceOutput = enumOr(prefs.getString("voiceOutput", null), d.voiceOutput),
            helmetButton = prefs.getBoolean("helmetButton", d.helmetButton),
            earcons = prefs.getBoolean("earcons", d.earcons),
        )
    }

    private fun save(s: IntercomSettings) = prefs.edit {
        putString("name", s.name)
        putString("groupName", s.groupName)
        putString(GROUP_KEY, s.groupKeyText)
        putString("audioMode", s.audioMode.name)
        putString("talkMode", s.talkMode.name)
        putFloat("voiceLevel", s.voiceLevel)
        putFloat("mediaLevel", s.mediaLevel)
        putFloat("vadSensitivity", s.vadSensitivity)
        putString("phy", s.phy.name)
        putBoolean("longRange", s.longRange)
        putInt("bitrate", s.bitrate)
        putInt("maxLinks", s.maxLinks)
        putInt("learnedLinkLimit", s.learnedLinkLimit)
        putString("micSource", s.micSource.name)
        putString("voiceOutput", s.voiceOutput.name)
        putBoolean("helmetButton", s.helmetButton)
        putBoolean("earcons", s.earcons)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val NODE_ID = "nodeId"
        const val GROUP_KEY = "groupKey"
    }
}
