@file:OptIn(ExperimentalMaterial3Api::class)

package io.opendomyos.cadence.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.opendomyos.cadence.program.Program
import io.opendomyos.cadence.session.Conn
import io.opendomyos.cadence.ui.AppState
import io.opendomyos.cadence.ui.components.ProgramCurve
import io.opendomyos.cadence.ui.components.tabular
import io.opendomyos.cadence.util.formatClock
import java.text.DateFormat
import java.util.Date

@Composable
fun ProgramScreen(s: AppState, p: Program) {
    val conn by s.session.connection.collectAsStateWithLifecycle()
    val favorites by s.favorites
    val fav = p.key in favorites
    val connected = conn is Conn.Connected
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val cs = MaterialTheme.colorScheme
    val total = p.durationSec * 1000L

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(p.title) },
                navigationIcon = { IconButton(onClick = { s.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                actions = {
                    IconToggleButton(checked = fav, onCheckedChange = { s.toggleFavorite(p.key) }) {
                        Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favori", tint = if (fav) cs.primary else cs.onSurfaceVariant)
                    }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { s.startWorkout(p) },
                icon = { Icon(if (connected) Icons.Filled.PlayArrow else Icons.Filled.Bluetooth, null) },
                text = { Text(if (connected) "Démarrer" else "Connecter le vélo") },
                containerColor = if (connected) cs.primary else cs.primaryContainer,
                contentColor = if (connected) cs.onPrimary else cs.onPrimaryContainer,
            )
        },
        snackbarHost = { SnackbarHost(s.snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp)) {
            item {
                Text(p.category.label.uppercase(), style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(horizontal = 24.dp))
            }
            item {
                val chips = buildList<Pair<ImageVector?, String>> {
                    add(Icons.Filled.Timer to "${p.durationSec / 60} min")
                    add(Icons.Filled.Tune to "Résistance ${p.minResistance}–${p.maxResistance}")
                    add(Icons.AutoMirrored.Filled.TrendingUp to "${p.segments.size} paliers")
                    p.style?.let { add(null to it) }
                    p.level?.let { add(null to "Niveau $it") }
                    if (p.hasCadenceTargets) add(Icons.Filled.Autorenew to "Cadence guidée")
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chips.size) { i ->
                        val (icon, label) = chips[i]
                        AssistChip(onClick = {}, label = { Text(label) }, leadingIcon = icon?.let { ic -> { Icon(ic, null, Modifier.size(18.dp)) } })
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer)) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp)) {
                        ProgramCurve(p, Modifier.fillMaxWidth().height(180.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text("0:00", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(formatClock(total), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            val plays = s.prefs.playCount(p.key)
            if (plays > 0) item {
                val last = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.prefs.lastPlayed(p.key)))
                Text("Joué $plays fois · dernière séance le $last", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp))
            }
            item { Text("Déroulé", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 4.dp)) }
            itemsIndexed(p.segments) { i, seg ->
                ListItem(
                    leadingContent = {
                        Surface(shape = RoundedCornerShape(12.dp), color = cs.secondaryContainer, modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text("${seg.resistance}", style = MaterialTheme.typography.titleMedium) }
                        }
                    },
                    headlineContent = { Text("${formatClock((seg.start * total).toLong())} – ${formatClock((seg.end * total).toLong())}", style = MaterialTheme.typography.bodyLarge.tabular()) },
                    supportingContent = {
                        Text(listOfNotNull(
                            formatClock((seg.length * total).toLong()),
                            seg.rpmMin?.let { mn -> if (seg.rpmMax != null && seg.rpmMax != mn) "$mn–${seg.rpmMax} rpm" else "$mn rpm" },
                            seg.instruction?.replaceFirstChar { it.titlecase() },
                        ).joinToString(" · "))
                    },
                    trailingContent = { Text("#${i + 1}", style = MaterialTheme.typography.labelMedium, color = cs.outline) },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
