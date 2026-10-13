package com.sanabria.motointercom.ui

import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sanabria.motointercom.R
import com.sanabria.motointercom.ble.BleMesh
import com.sanabria.motointercom.engine.AudioMode
import com.sanabria.motointercom.engine.EngineError
import com.sanabria.motointercom.engine.EngineState
import com.sanabria.motointercom.engine.IntercomSettings
import com.sanabria.motointercom.engine.TalkMode
import com.sanabria.motointercom.engine.VoiceOutput
import kotlin.math.roundToInt

/** Pantalla de uso: conectar, hablar, volúmenes y música, y quién hay en el grupo. Los ajustes van aparte. */
@Composable
fun MainScreen(
    state: EngineState,
    settings: IntercomSettings,
    permissionsDenied: Boolean,
    actions: ScreenActions,
    openSettings: () -> Unit,
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
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = openSettings) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.action_settings))
                }
            }
            ConnectionSection(state, settings, permissionsDenied, actions)
            TalkSection(state, settings, actions)
            VolumeSection(state, settings, actions)
            GroupSection(state)
            DiagnosticsSection(state)
        }
    }
}

// --- Conexión ---

@Composable
private fun ConnectionSection(state: EngineState, settings: IntercomSettings, permissionsDenied: Boolean, actions: ScreenActions) {
    Section(stringResource(R.string.section_connection)) {
        Text(stringResource(R.string.identity_line, settings.name, settings.group), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = if (state.running) actions.stop else actions.start,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = if (state.running) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(stringResource(if (state.running) R.string.action_stop else R.string.action_start))
        }
        Text(statusLine(state), style = MaterialTheme.typography.bodySmall)
        problemText(state)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (permissionsDenied) {
            Text(stringResource(R.string.permissions_denied), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun statusLine(state: EngineState): String {
    val m = state.mesh ?: return stringResource(R.string.status_stopped)
    val parts = mutableListOf<String>()
    parts += stringResource(
        when {
            m.codedAdvertising -> R.string.status_advertising_coded
            m.advertising -> R.string.status_advertising
            else -> R.string.status_not_advertising
        },
    )
    if (m.scanning) parts += stringResource(R.string.status_scanning)
    parts += stringResource(R.string.status_psm, m.psm)
    parts += pluralStringResource(R.plurals.status_seen, m.seen, m.seen)
    return parts.joinToString(" · ")
}

@Composable
private fun problemText(state: EngineState): String? {
    when (state.error) {
        EngineError.BLUETOOTH_OFF -> return stringResource(R.string.error_bluetooth_off)
        EngineError.PERMISSIONS -> return stringResource(R.string.permissions_denied)
        EngineError.CHANNEL -> return stringResource(R.string.error_channel, state.errorDetail)
        EngineError.AUDIO -> return stringResource(R.string.error_audio, state.errorDetail)
        null -> Unit
    }
    val m = state.mesh ?: return null
    return when (m.problem) {
        BleMesh.Problem.NO_ADVERTISER -> stringResource(R.string.problem_no_advertiser)
        BleMesh.Problem.ADVERTISE_FAILED -> stringResource(R.string.problem_advertise, m.problemCode)
        BleMesh.Problem.CODED_ADVERTISE_FAILED -> stringResource(R.string.problem_coded, m.problemCode)
        BleMesh.Problem.SCAN_FAILED -> stringResource(R.string.problem_scan, m.problemCode)
        null -> null
    }
}

// --- Hablar ---

@Composable
private fun TalkSection(state: EngineState, settings: IntercomSettings, actions: ScreenActions) {
    Section(stringResource(R.string.section_talk)) {
        Choice(TalkMode.entries, settings.talkMode, { stringResource(talkModeLabel(it)) }) { t ->
            actions.update { it.copy(talkMode = t) }
        }
        if (settings.talkMode == TalkMode.PTT) PttButton(state.running, actions.ptt)
        MicMeter(state)
    }
}

@StringRes
private fun talkModeLabel(t: TalkMode) = when (t) {
    TalkMode.AUTO -> R.string.talk_auto
    TalkMode.PTT -> R.string.talk_ptt
    TalkMode.OPEN -> R.string.talk_open
    TalkMode.MUTE -> R.string.talk_mute
}

@Composable
private fun PttButton(enabled: Boolean, onPtt: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onPtt(true)
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            onPtt(false)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.ptt_hold),
            color = if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun MicMeter(state: EngineState) {
    val level = ((state.micLevelDb + 60f) / 60f).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { level },
            modifier = Modifier
                .weight(1f)
                .height(8.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(if (state.sending) R.string.mic_sending else R.string.mic_idle),
            color = if (state.sending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
    }
    Text(stringResource(R.string.mic_level, state.micLevelDb.roundToInt()), style = MaterialTheme.typography.bodySmall)
}

// --- Volumen y música ---

@Composable
private fun VolumeSection(state: EngineState, settings: IntercomSettings, actions: ScreenActions) {
    val v = state.volumes
    Section(stringResource(R.string.section_volume)) {
        LabeledSlider(stringResource(R.string.volume_master, percent(v.master)), v.master, onChange = actions.masterVolume)
        LabeledSlider(stringResource(R.string.volume_voices, percent(v.voice)), v.voice, onChange = actions.voiceVolume)
        LabeledSlider(stringResource(R.string.volume_music, percent(v.media)), v.media, onChange = actions.mediaVolume)
        Text(stringResource(R.string.volume_hint), style = MaterialTheme.typography.bodySmall)
        if (settings.audioMode == AudioMode.MIX && settings.voiceOutput == VoiceOutput.MEDIA) {
            Text(stringResource(R.string.mix_voice_limit), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MediaButton(R.drawable.ic_skip_previous, R.string.media_previous) { actions.mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            MediaButton(R.drawable.ic_play_pause, R.string.media_play_pause) { actions.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
            MediaButton(R.drawable.ic_skip_next, R.string.media_next) { actions.mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
        }
    }
}

@Composable
private fun MediaButton(@DrawableRes icon: Int, @StringRes description: Int, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(64.dp)) {
        Icon(painterResource(icon), contentDescription = stringResource(description), modifier = Modifier.size(32.dp))
    }
}

// --- Grupo y diagnóstico ---

@Composable
private fun GroupSection(state: EngineState) {
    Section(stringResource(R.string.section_group)) {
        if (state.nodes.isEmpty()) Text(stringResource(R.string.group_empty), style = MaterialTheme.typography.bodySmall)
        for (n in state.nodes) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (n.speaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(n.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val hops = when {
                        n.hops < 0 -> stringResource(R.string.hops_none)
                        n.hops == 1 -> stringResource(R.string.hops_direct)
                        else -> pluralStringResource(R.plurals.hops_many, n.hops, n.hops)
                    }
                    val speaking = if (n.speaking) " · " + stringResource(R.string.node_speaking) else ""
                    Text(hops + speaking, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.node_stats, n.depthMs, n.targetMs, n.lost, n.late), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsSection(state: EngineState) {
    Section(stringResource(R.string.section_diagnostics)) {
        state.route?.let { r ->
            Text(stringResource(R.string.route_input, deviceLabel(r.inputType, r.inputName)), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.route_output, deviceLabel(r.outputType, r.outputName)), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.section_links), style = MaterialTheme.typography.labelLarge)
        if (state.links.isEmpty()) Text(stringResource(R.string.links_empty), style = MaterialTheme.typography.bodySmall)
        for (l in state.links) {
            val i = l.info
            Column {
                Text(
                    i.name + " · " + stringResource(if (i.initiator) R.string.link_out else R.string.link_in),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val unknown = stringResource(R.string.unknown)
                val rssi = i.rssi?.let { stringResource(R.string.link_rssi, it) } ?: unknown
                val rtt = i.rttMs?.let { stringResource(R.string.link_rtt_ms, it) } ?: unknown
                Text(stringResource(R.string.link_line1, rssi, phyName(i.txPhy), phyName(i.rxPhy), rtt), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.link_line2, l.txKbps, l.rxKbps, i.queued, i.dropped), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
