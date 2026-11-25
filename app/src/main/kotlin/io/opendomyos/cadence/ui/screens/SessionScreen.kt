@file:OptIn(ExperimentalMaterial3Api::class)

package io.opendomyos.cadence.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.opendomyos.cadence.data.HistoryEntry
import io.opendomyos.cadence.session.Conn
import io.opendomyos.cadence.ui.AppState
import io.opendomyos.cadence.ui.Routes
import io.opendomyos.cadence.ui.components.MetricGrid
import io.opendomyos.cadence.ui.components.ProgramCurve
import io.opendomyos.cadence.ui.components.tabular
import io.opendomyos.cadence.ui.theme.LocalStatusColors
import io.opendomyos.cadence.util.formatClock

@Composable
fun SessionScreen(s: AppState) {
    val workout by s.session.workout.collectAsStateWithLifecycle()
    val live by s.session.live.collectAsStateWithLifecycle()
    val conn by s.session.connection.collectAsStateWithLifecycle()
    val summary by s.session.summary.collectAsStateWithLifecycle()
    var confirmStop by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val status = LocalStatusColors.current

    // Écran allumé pendant la séance
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // Séance terminée et résumé fermé (ou arrêt court) : retour à l'accueil
    LaunchedEffect(workout == null, summary == null) {
        if (workout == null && summary == null) s.nav.popBackStack(Routes.HOME, inclusive = false)
    }
    val w = workout ?: return

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(w.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { s.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                actions = {
                    when (conn) {
                        is Conn.Connected -> {}
                        is Conn.Connecting, is Conn.Reconnecting -> Icon(Icons.Filled.Bluetooth, "Reconnexion", tint = status.warning, modifier = Modifier.padding(end = 16.dp))
                        else -> Icon(Icons.Filled.BluetoothDisabled, "Déconnecté", tint = cs.error, modifier = Modifier.padding(end = 16.dp))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(s.snackbar) },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(
                    onClick = { if (w.finished) s.session.dismissSummary() else confirmStop = true },
                    modifier = Modifier.size(56.dp),
                ) { Icon(Icons.Filled.Stop, "Terminer la séance") }
                Spacer(Modifier.width(24.dp))
                LargeFloatingActionButton(
                    onClick = { if (w.finished) s.session.dismissSummary() else s.session.togglePause() },
                    containerColor = if (w.paused || w.finished) cs.primary else cs.primaryContainer,
                    contentColor = if (w.paused || w.finished) cs.onPrimary else cs.onPrimaryContainer,
                ) {
                    Icon(
                        when { w.finished -> Icons.Filled.Check; w.paused -> Icons.Filled.PlayArrow; else -> Icons.Filled.Pause },
                        when { w.finished -> "Terminer"; w.paused -> "Reprendre"; else -> "Pause" },
                        Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.width(24.dp))
                Spacer(Modifier.size(56.dp))
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Anneau de progression + temps
            val hasDuration = w.durationMs != null
            val ringTarget = if (hasDuration) w.progress else (w.elapsedMs % 60_000) / 60_000f
            val ring by animateFloatAsState(ringTarget, label = "ring")
            val ringColor by animateColorAsState(
                when { w.finished -> status.success; w.paused || w.waitingForPedaling -> cs.outline; else -> cs.primary }, label = "ringColor",
            )
            Box(Modifier.padding(top = 8.dp).size(260.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { ring }, modifier = Modifier.fillMaxSize(), color = ringColor,
                    strokeWidth = 14.dp, trackColor = cs.secondaryContainer, strokeCap = StrokeCap.Round,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when {
                            w.finished -> "TERMINÉ"
                            w.paused -> "EN PAUSE"
                            w.waitingForPedaling -> if (w.elapsedMs == 0L) "PÉDALEZ POUR DÉMARRER" else "PÉDALEZ POUR REPRENDRE"
                            hasDuration -> "RESTANT"
                            else -> "ÉCOULÉ"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (w.paused || w.waitingForPedaling) cs.tertiary else cs.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Text(
                        if (hasDuration) formatClock((w.durationMs!! - w.elapsedMs).coerceAtLeast(0)) else formatClock(w.elapsedMs),
                        style = MaterialTheme.typography.displayMedium.tabular(),
                    )
                    val step = if (hasDuration && w.segment != null && w.program != null)
                        "Palier ${w.segmentIndex + 1}/${w.program.segments.size} · ${formatClock(w.segmentRemainingMs)}"
                    else "${formatClock(w.elapsedMs)} au total"
                    Text(step, style = MaterialTheme.typography.bodyMedium.tabular(), color = cs.onSurfaceVariant)
                }
            }

            // Résistance
            Card(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer, contentColor = cs.onSecondaryContainer),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); s.session.adjustResistance(-1) },
                        modifier = Modifier.size(64.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.onSecondaryContainer.copy(alpha = 0.10f), contentColor = cs.onSecondaryContainer),
                    ) { Icon(Icons.Filled.Remove, "Diminuer la résistance", Modifier.size(28.dp)) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("RÉSISTANCE", style = MaterialTheme.typography.labelMedium)
                        Text("${w.targetResistance}", style = MaterialTheme.typography.displayLarge.tabular())
                        Text(
                            when {
                                w.program == null -> "manuel"
                                w.userOffset > 0 -> "+${w.userOffset} vs programme"
                                w.userOffset < 0 -> "${w.userOffset} vs programme"
                                else -> "programme"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); s.session.adjustResistance(+1) },
                        modifier = Modifier.size(64.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.onSecondaryContainer.copy(alpha = 0.10f), contentColor = cs.onSecondaryContainer),
                    ) { Icon(Icons.Filled.Add, "Augmenter la résistance", Modifier.size(28.dp)) }
                }
            }

            // Consignes : cadence cible, instruction, palier suivant
            val chips = buildList<@Composable () -> Unit> {
                val seg = w.segment
                seg?.rpmMin?.let { mn ->
                    val mx = seg.rpmMax ?: mn
                    val cad = live?.cadenceRpm ?: 0f
                    val ok = cad >= mn - 3 && cad <= mx + 3
                    add {
                        AssistChip(onClick = {}, label = { Text(if (mx != mn) "Cadence $mn–$mx" else "Cadence $mn") },
                            leadingIcon = { Icon(Icons.Filled.Autorenew, null, Modifier.size(18.dp)) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (ok) status.successContainer else status.warningContainer,
                                labelColor = if (ok) status.onSuccessContainer else cs.onSurface,
                                leadingIconContentColor = if (ok) status.success else status.warning,
                            ),
                            border = null)
                    }
                }
                seg?.instruction?.let { ins ->
                    add {
                        AssistChip(onClick = {}, label = { Text(ins.replaceFirstChar { it.titlecase() }) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.TrendingUp, null, Modifier.size(18.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = cs.tertiaryContainer, labelColor = cs.onTertiaryContainer), border = null)
                    }
                }
                val next = w.nextResistance
                if (w.nextSegment != null && next != null && !w.finished) {
                    val arrow = when { next > w.targetResistance -> "↑"; next < w.targetResistance -> "↓"; else -> "→" }
                    add {
                        AssistChip(onClick = {}, label = { Text("Ensuite $arrow $next dans ${formatClock(w.segmentRemainingMs)}") },
                            leadingIcon = { Icon(Icons.Filled.Timer, null, Modifier.size(18.dp)) })
                    }
                }
            }
            if (chips.isNotEmpty()) {
                LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chips.size) { chips[it]() }
                }
            }

            // Mesures
            MetricGrid(
                listOf(
                    Triple(Icons.Filled.Bolt, live?.powerWatts?.toString() ?: "–", "W"),
                    Triple(Icons.Filled.Autorenew, live?.cadenceRpm?.toInt()?.toString() ?: "–", "rpm"),
                    Triple(Icons.Filled.Speed, live?.speedKmh?.let { "%.1f".format(it) } ?: "–", "km/h"),
                    Triple(Icons.Filled.Flag, "%.1f".format(w.stats.distanceKm), "km"),
                    Triple(Icons.Filled.LocalFireDepartment, "${w.stats.kcal}", "kcal"),
                    Triple(Icons.Filled.MonitorHeart, live?.heartRateBpm?.toString() ?: "–", "bpm"),
                ),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // Profil du programme
            w.program?.let { p ->
                Card(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer)) {
                    ProgramCurve(p, Modifier.fillMaxWidth().height(96.dp).padding(16.dp), progress = w.progress, offset = w.userOffset)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        icon = { Icon(Icons.Filled.Stop, null) },
        title = { Text("Terminer la séance ?") },
        text = { Text("Votre séance sera enregistrée dans l'historique si elle dure plus d'une minute.") },
        confirmButton = { TextButton(onClick = { confirmStop = false; s.session.stopWorkout(save = true) }) { Text("Terminer") } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Continuer") } },
    )
}

/** Résumé de fin de séance. */
@Composable
fun SummaryDialog(e: HistoryEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Check, null) },
        title = { Text(if (e.completed) "Bravo !" else "Séance enregistrée") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(e.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                MetricGrid(listOf<Triple<ImageVector, String, String>>(
                    Triple(Icons.Filled.Timer, formatClock(e.durationSec * 1000L), "durée"),
                    Triple(Icons.Filled.Flag, "%.1f".format(e.distanceKm), "km"),
                    Triple(Icons.Filled.LocalFireDepartment, "${e.kcal}", "kcal"),
                    Triple(Icons.Filled.Bolt, "${e.avgPower}", "W moy."),
                    Triple(Icons.Filled.Autorenew, "${e.avgCadence}", "rpm moy."),
                    Triple(Icons.Filled.MonitorHeart, if (e.avgHeartRate > 0) "${e.avgHeartRate}" else "–", "bpm moy."),
                ))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Terminer") } },
    )
}
