@file:OptIn(ExperimentalMaterial3Api::class)

package io.opendomyos.cadence.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.opendomyos.cadence.ui.AppState
import io.opendomyos.cadence.ui.components.tabular
import io.opendomyos.cadence.ui.theme.LocalStatusColors
import io.opendomyos.cadence.util.formatClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(s: AppState) {
    val entries = remember { s.prefs.history() }
    val cs = MaterialTheme.colorScheme
    val df = remember { SimpleDateFormat("EEEE d MMMM · HH:mm", Locale.getDefault()) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Historique") },
            navigationIcon = { IconButton(onClick = { s.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
        )
    }) { padding ->
        if (entries.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.History, null, Modifier.size(48.dp), tint = cs.outline)
                Spacer(Modifier.height(16.dp))
                Text("Aucune séance pour l'instant", style = MaterialTheme.typography.titleMedium)
                Text("Vos séances apparaîtront ici.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("TOTAL", style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${entries.size}", style = MaterialTheme.typography.displaySmall.tabular())
                            Text(if (entries.size > 1) " séances" else " séance", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        Text(
                            "${formatClock(entries.sumOf { it.durationSec } * 1000L)} · ${"%.1f".format(entries.sumOf { it.distanceKm.toDouble() })} km · ${entries.sumOf { it.kcal }} kcal",
                            style = MaterialTheme.typography.bodyLarge.tabular(),
                        )
                    }
                }
            }
            items(entries) { e ->
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(df.format(Date(e.dateMs)).replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                                Text(e.title, style = MaterialTheme.typography.titleMedium)
                            }
                            if (e.completed) Icon(Icons.Filled.CheckCircle, "Terminé", tint = LocalStatusColors.current.success)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            listOf(formatClock(e.durationSec * 1000L), "%.1f km".format(e.distanceKm), "${e.kcal} kcal", "${e.avgPower} W moy.", "${e.avgCadence} rpm").joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium.tabular(), color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
