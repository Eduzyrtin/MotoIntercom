package com.sanabria.motointercom.ui

import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.sanabria.motointercom.R
import com.sanabria.motointercom.audio.HelmetCompat
import com.sanabria.motointercom.engine.AudioMode
import com.sanabria.motointercom.engine.EngineState
import com.sanabria.motointercom.engine.IntercomSettings
import com.sanabria.motointercom.engine.MicSource
import com.sanabria.motointercom.engine.PhyPreference
import com.sanabria.motointercom.engine.VoiceOutput
import kotlin.math.roundToInt

/** Ajustes: identidad, grupo, audio, casco, radio, batería e información del móvil. */
@Composable
fun SettingsScreen(
    state: EngineState,
    settings: IntercomSettings,
    helmets: List<HelmetCompat.Record>,
    actions: ScreenActions,
    openHelmetTest: () -> Unit,
    back: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                }
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
            }
            IdentitySection(state, settings, actions)
            GroupSection(state, settings, actions)
            AudioSection(settings, actions)
            HelmetSection(state, settings, helmets, actions, openHelmetTest)
            RadioSection(state, settings, actions)
            BatterySection(actions)
            InfoSection(state, settings)
        }
    }
}

@Composable
private fun IdentitySection(state: EngineState, settings: IntercomSettings, actions: ScreenActions) {
    Section(stringResource(R.string.section_identity)) {
        OutlinedTextField(
            value = settings.name,
            onValueChange = { v -> actions.update { it.copy(name = v.take(MAX_NAME_CHARS)) } },
            label = { Text(stringResource(R.string.field_name)) },
            singleLine = true,
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.radio_restart_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GroupSection(state: EngineState, settings: IntercomSettings, actions: ScreenActions) {
    var showQr by rememberSaveable { mutableStateOf(false) }
    var newGroup by rememberSaveable { mutableStateOf(false) }
    Section(stringResource(R.string.section_group)) {
        Text(stringResource(R.string.group_current, settings.groupName, settings.groupCode), style = MaterialTheme.typography.bodyLarge)
        val free = !state.running
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showQr = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.group_show_qr)) }
            OutlinedButton(onClick = actions.scanInvite, enabled = free, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.group_scan_qr))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.pasteInvite, enabled = free, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.group_paste))
            }
            OutlinedButton(onClick = { newGroup = true }, enabled = free, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.group_new))
            }
        }
        Text(
            stringResource(if (free) R.string.group_hint else R.string.group_locked_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (showQr) QrDialog(settings.group, onShare = actions.shareInvite, onDismiss = { showQr = false })
    if (newGroup) {
        NewGroupDialog(
            onCreate = {
                actions.newGroup(it)
                newGroup = false
            },
            onDismiss = { newGroup = false },
        )
    }
}

@Composable
private fun AudioSection(settings: IntercomSettings, actions: ScreenActions) {
    Section(stringResource(R.string.section_audio)) {
        Choice(AudioMode.entries, settings.audioMode, { stringResource(audioModeLabel(it)) }) { m ->
            actions.update { it.copy(audioMode = m) }
        }
        Text(
            stringResource(if (settings.audioMode == AudioMode.HELMET) R.string.mode_helmet_hint else R.string.mode_mix_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        LabeledSlider(stringResource(R.string.vad_sensitivity, percent(settings.vadSensitivity)), settings.vadSensitivity) { v ->
            actions.update { it.copy(vadSensitivity = v) }
        }
        Text(stringResource(R.string.mic_source_label), style = MaterialTheme.typography.bodyMedium)
        Choice(MicSource.entries, settings.micSource, {
            stringResource(if (it == MicSource.VOICE) R.string.mic_voice else R.string.mic_raw)
        }) { m -> actions.update { it.copy(micSource = m) } }
        Text(stringResource(R.string.voice_output_label), style = MaterialTheme.typography.bodyMedium)
        Choice(VoiceOutput.entries, settings.voiceOutput, {
            stringResource(if (it == VoiceOutput.MEDIA) R.string.voice_output_media else R.string.voice_output_call)
        }) { o -> actions.update { it.copy(voiceOutput = o) } }
    }
}

@StringRes
fun audioModeLabel(m: AudioMode) = when (m) {
    AudioMode.HELMET -> R.string.mode_helmet
    AudioMode.MIX -> R.string.mode_mix
}

@Composable
private fun HelmetSection(
    state: EngineState,
    settings: IntercomSettings,
    helmets: List<HelmetCompat.Record>,
    actions: ScreenActions,
    openHelmetTest: () -> Unit,
) {
    Section(stringResource(R.string.section_helmet)) {
        SwitchRow(stringResource(R.string.helmet_button), settings.helmetButton) { v -> actions.update { it.copy(helmetButton = v) } }
        Text(stringResource(R.string.helmet_button_hint), style = MaterialTheme.typography.bodySmall)
        SwitchRow(stringResource(R.string.earcons_label), settings.earcons) { v -> actions.update { it.copy(earcons = v) } }
        Text(stringResource(R.string.earcons_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = openHelmetTest, enabled = !state.running, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.helmet_test_open))
        }
        if (state.running) Text(stringResource(R.string.helmet_test_locked), style = MaterialTheme.typography.bodySmall)
        if (helmets.isNotEmpty()) {
            Text(stringResource(R.string.helmets_tested), style = MaterialTheme.typography.labelLarge)
            for (h in helmets) {
                Text(
                    stringResource(R.string.helmet_record, h.name, resultText(h.helmet), resultText(h.mix)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun resultText(ok: Boolean?) = stringResource(
    when (ok) {
        true -> R.string.result_ok
        false -> R.string.result_bad
        null -> R.string.result_untested
    },
)

@Composable
private fun RadioSection(state: EngineState, settings: IntercomSettings, actions: ScreenActions) {
    Section(stringResource(R.string.section_radio)) {
        Text(stringResource(R.string.phy_label), style = MaterialTheme.typography.bodyMedium)
        Choice(PhyPreference.entries, settings.phy, { phyPreferenceLabel(it) }) { p -> actions.update { it.copy(phy = p) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.long_range), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = settings.longRange,
                onCheckedChange = { v -> actions.update { it.copy(longRange = v) } },
                enabled = !state.running,
            )
        }
        LabeledSlider(stringResource(R.string.bitrate_label, settings.bitrate / 1000), settings.bitrate / 1000f, 8f..32f, steps = 5) { v ->
            actions.update { it.copy(bitrate = v.roundToInt() * 1000) }
        }
        LabeledSlider(stringResource(R.string.max_links, settings.maxLinks), settings.maxLinks.toFloat(), 1f..7f, steps = 5) { v ->
            actions.update { it.copy(maxLinks = v.roundToInt()) }
        }
    }
}

@Composable
private fun phyPreferenceLabel(p: PhyPreference) = when (p) {
    PhyPreference.AUTO -> stringResource(R.string.phy_auto)
    PhyPreference.LE_1M -> "1M"
    PhyPreference.LE_2M -> "2M"
    PhyPreference.CODED_S2 -> "S2"
    PhyPreference.CODED_S8 -> "S8"
}

@Composable
private fun BatterySection(actions: ScreenActions) {
    val context = LocalContext.current
    var unrestricted by remember { mutableStateOf(true) }
    // Se vuelve a mirar al volver de los ajustes del sistema
    LifecycleResumeEffect(Unit) {
        unrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    Section(stringResource(R.string.section_battery)) {
        Text(
            stringResource(if (unrestricted) R.string.battery_ok else R.string.battery_restricted),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!unrestricted) {
            OutlinedButton(onClick = actions.openBatterySettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.battery_open))
            }
        }
    }
}

@Composable
private fun InfoSection(state: EngineState, settings: IntercomSettings) {
    Section(stringResource(R.string.section_info)) {
        state.caps?.let { c ->
            val yes = stringResource(R.string.yes)
            val no = stringResource(R.string.no)
            Text(
                stringResource(R.string.caps, if (c.codedPhy) yes else no, if (c.le2M) yes else no, if (c.extendedAdvertising) yes else no),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(stringResource(R.string.node_id, "%08X".format(settings.nodeId)), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private const val MAX_NAME_CHARS = 20
