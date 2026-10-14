package com.sanabria.motointercom.engine

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.sanabria.motointercom.core.audio.BitrateController
import com.sanabria.motointercom.core.crypto.Group
import com.sanabria.motointercom.core.crypto.GroupInvite
import com.sanabria.motointercom.core.crypto.GroupKeys
import com.sanabria.motointercom.crypto.AndroidCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/** Por dónde va el audio. Solo se usan el casco y el móvil; el micro del móvil, nunca. */
enum class AudioMode {
    /**
     * Música primero, el modo normal: la música no baja nunca de calidad. Las voces llegan por multimedia, mezcladas
     * con la música por el sistema. Para hablar se usa el micro del casco solo si tiene LE Audio, que permite micro y
     * buena calidad a la vez; un casco Bluetooth clásico no tiene micro sin manos libres, así que con él solo se escucha.
     */
    MIX,

    /** Compatibilidad: micro y voces por el casco como manos libres (HFP). Cualquier casco; todo a calidad de llamada. */
    HELMET,
}

/** Cuándo se envía la voz: siempre, cuando se detecta voz, o nunca (solo escuchar). */
enum class TalkMode { OPEN, AUTO, MUTE }

/** PHY que se pide para cada enlace ya abierto (AUTO = lo que decida el controlador). */
enum class PhyPreference { AUTO, LE_1M, LE_2M, CODED_S2, CODED_S8 }

/** Uso de audio de las voces en modo mezcla: multimedia (sigue al volumen de la música) o llamada (volumen propio). */
enum class VoiceOutput { MEDIA, CALL }

/** Un grupo guardado: nombre y clave (32 bytes en base64url). */
data class SavedGroup(val name: String, val keyText: String) {
    val key: ByteArray get() = checkNotNull(GroupInvite.fromBase64Url(keyText))
    val group: Group get() = Group(name, key)
    val code: String get() = groupCode(key)
}

data class IntercomSettings(
    val nodeId: Int,
    val name: String,
    /** Grupos guardados (al menos uno), el que se usa al conectar a mano y el que usa la autoconexión. */
    val groups: List<SavedGroup>,
    val activeGroup: String,
    val defaultGroup: String,
    val audioMode: AudioMode = AudioMode.MIX,
    val talkMode: TalkMode = TalkMode.OPEN,
    /** Niveles 0..1 de voz y multimedia; el maestro es el mayor de los dos (ver VolumeLink). */
    val voiceLevel: Float = 0.8f,
    val mediaLevel: Float = 0.5f,
    val vadSensitivity: Float = 0.5f,
    val phy: PhyPreference = PhyPreference.AUTO,
    val longRange: Boolean = true,
    /** Calidad de voz máxima: la adaptativa baja de aquí si los enlaces se saturan. */
    val bitrate: Int = BitrateController.DEFAULT,
    val maxLinks: Int = 4,
    /** Enlaces a la vez que aguanta el chip de este móvil, aprendido al conectar (0 = no se sabe). */
    val learnedLinkLimit: Int = 0,
    val voiceOutput: VoiceOutput = VoiceOutput.MEDIA,
    /** El botón de reproducir/pausa del casco silencia el micro y lo devuelve. */
    val helmetButton: Boolean = false,
    /** Pitidos al abrir/cerrar el micro y cuando alguien entra o sale del grupo. */
    val earcons: Boolean = true,
    /** Al conectarse el casco elegido, la app se abre y conecta con el grupo por defecto. */
    val autoConnect: Boolean = false,
    val helmetAddress: String = "",
    val helmetName: String = "",
) {
    val active: SavedGroup get() = groups.firstOrNull { it.keyText == activeGroup } ?: groups.first()
    val groupName: String get() = active.name
    val groupKey: ByteArray get() = active.key
    val group: Group get() = active.group
    val groupCode: String get() = active.code
}

/** Código corto de un grupo para comprobar de un vistazo que todos están en el mismo (sale del advId, no revela la clave). */
fun groupCode(key: ByteArray): String =
    "%08X".format(GroupKeys(AndroidCrypto, key).advId).let { it.substring(0, 4) + "-" + it.substring(4) }

/**
 * Ajustes guardados y su estado observable: la interfaz, el servicio y la autoconexión leen de aquí.
 */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("intercom", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<IntercomSettings> = _settings.asStateFlow()

    val current: IntercomSettings get() = _settings.value

    @Synchronized
    fun update(change: (IntercomSettings) -> IntercomSettings): IntercomSettings {
        val s = change(_settings.value)
        if (s != _settings.value) {
            _settings.value = s
            save(s)
        }
        return s
    }

    /** Grupo nuevo con clave aleatoria, en uso (y por defecto si es el primero de verdad): los demás se unen con el QR. */
    fun newGroup(name: String) = addGroup(SavedGroup(name, GroupInvite.base64Url(AndroidCrypto.randomBytes(GroupKeys.KEY_BYTES))))

    /** Unirse a un grupo de una invitación: se guarda (o se renombra si ya estaba) y pasa a estar en uso. */
    fun joinGroup(group: Group) = addGroup(SavedGroup(group.name, GroupInvite.base64Url(group.key)))

    fun activate(keyText: String) = update { s -> if (s.groups.any { it.keyText == keyText }) s.copy(activeGroup = keyText) else s }

    fun makeDefault(keyText: String) = update { s -> if (s.groups.any { it.keyText == keyText }) s.copy(defaultGroup = keyText) else s }

    /** Borra un grupo guardado (nunca el último). Si era el que estaba en uso o el de por defecto, pasa a otro. */
    fun deleteGroup(keyText: String) = update { s ->
        val rest = s.groups.filter { it.keyText != keyText }
        if (rest.isEmpty()) return@update s
        val fallback = rest.firstOrNull { it.keyText == s.defaultGroup }?.keyText ?: rest.first().keyText
        s.copy(
            groups = rest,
            activeGroup = if (s.activeGroup == keyText) fallback else s.activeGroup,
            defaultGroup = if (s.defaultGroup == keyText) rest.first().keyText else s.defaultGroup,
        )
    }

    private fun addGroup(g: SavedGroup) = update { s ->
        val exists = s.groups.any { it.keyText == g.keyText }
        val groups = if (exists) s.groups.map { if (it.keyText == g.keyText) g else it } else s.groups + g
        // El grupo de arranque (sin nadie más, creado solo) deja de ser el de por defecto en cuanto hay uno de verdad
        val default = if (s.groups.size == 1 && prefs.getBoolean(PLACEHOLDER, false)) g.keyText else s.defaultGroup
        prefs.edit { putBoolean(PLACEHOLDER, false) }
        s.copy(groups = groups, activeGroup = g.keyText, defaultGroup = default)
    }

    private fun load(): IntercomSettings {
        var id = prefs.getInt(NODE_ID, 0)
        if (id == 0) {
            // Identidad del nodo en la malla: aleatoria, fija para este móvil
            id = Random.nextInt().let { if (it == 0) 1 else it }
            prefs.edit { putInt(NODE_ID, id) }
        }
        val groups = loadGroups()
        val active = prefs.getString("activeGroup", null)?.takeIf { k -> groups.any { it.keyText == k } } ?: groups.first().keyText
        val default = prefs.getString("defaultGroup", null)?.takeIf { k -> groups.any { it.keyText == k } } ?: active
        val d = IntercomSettings(nodeId = id, name = Build.MODEL, groups = groups, activeGroup = active, defaultGroup = default)
        // Ajustes de audio de antes de "música primero" (manos libres, PTT, voz a 16 kHz): se vuelve a lo nuevo
        if (prefs.getInt(AUDIO_VERSION, 0) < 2) {
            prefs.edit {
                remove("audioMode")
                remove("bitrate")
                remove("talkMode")
                putInt(AUDIO_VERSION, 2)
            }
        }
        return d.copy(
            name = prefs.getString("name", null) ?: d.name,
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
            voiceOutput = enumOr(prefs.getString("voiceOutput", null), d.voiceOutput),
            helmetButton = prefs.getBoolean("helmetButton", d.helmetButton),
            earcons = prefs.getBoolean("earcons", d.earcons),
            autoConnect = prefs.getBoolean("autoConnect", d.autoConnect),
            helmetAddress = prefs.getString("helmetAddress", null) ?: d.helmetAddress,
            helmetName = prefs.getString("helmetName", null) ?: d.helmetName,
        )
    }

    /** Grupos guardados: una línea "clave TAB nombre" por grupo. Si no hay (instalación nueva o versión anterior), uno. */
    private fun loadGroups(): List<SavedGroup> {
        val saved = prefs.getString(GROUPS, null).orEmpty().lines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val key = line.substring(0, tab)
            if (GroupInvite.fromBase64Url(key)?.size != GroupKeys.KEY_BYTES) return@mapNotNull null
            SavedGroup(line.substring(tab + 1), key)
        }
        if (saved.isNotEmpty()) return saved
        // Antes había un solo grupo (y antes aún, solo un nombre sin clave)
        val oldKey = prefs.getString(OLD_GROUP_KEY, null)?.takeIf { GroupInvite.fromBase64Url(it)?.size == GroupKeys.KEY_BYTES }
        val oldName = prefs.getString("groupName", null) ?: prefs.getString("group", null)
        val first = SavedGroup(oldName ?: "MOTO", oldKey ?: GroupInvite.base64Url(AndroidCrypto.randomBytes(GroupKeys.KEY_BYTES)))
        // Un grupo creado solo al instalar no es "de verdad": el primero que se cree o al que se una pasa a ser el de por defecto
        if (oldKey == null) prefs.edit { putBoolean(PLACEHOLDER, true) }
        return listOf(first)
    }

    private fun save(s: IntercomSettings) = prefs.edit {
        putString("name", s.name)
        putString(GROUPS, s.groups.joinToString("\n") { it.keyText + "\t" + it.name.replace('\t', ' ').replace('\n', ' ') })
        putString("activeGroup", s.activeGroup)
        putString("defaultGroup", s.defaultGroup)
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
        putString("voiceOutput", s.voiceOutput.name)
        putBoolean("helmetButton", s.helmetButton)
        putBoolean("earcons", s.earcons)
        putBoolean("autoConnect", s.autoConnect)
        putString("helmetAddress", s.helmetAddress)
        putString("helmetName", s.helmetName)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val NODE_ID = "nodeId"
        const val GROUPS = "groups"
        const val OLD_GROUP_KEY = "groupKey"
        const val PLACEHOLDER = "groupPlaceholder"
        const val AUDIO_VERSION = "audioVersion"
    }
}
