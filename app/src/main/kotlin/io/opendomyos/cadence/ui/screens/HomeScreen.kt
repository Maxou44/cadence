@file:OptIn(ExperimentalMaterial3Api::class)

package io.opendomyos.cadence.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.opendomyos.ble.BikeData
import io.opendomyos.ble.EquipmentCatalog
import io.opendomyos.ble.Protocol
import io.opendomyos.cadence.program.Program
import io.opendomyos.cadence.program.ProgramCategory
import io.opendomyos.cadence.program.ProgramLibrary
import io.opendomyos.cadence.session.Conn
import io.opendomyos.cadence.ui.AppState
import io.opendomyos.cadence.ui.Routes
import io.opendomyos.cadence.ui.components.ProgramCurve
import io.opendomyos.cadence.ui.components.StatusDot
import io.opendomyos.cadence.ui.components.tabular
import io.opendomyos.cadence.ui.theme.LocalStatusColors
import io.opendomyos.cadence.util.formatClock

private sealed interface Filter {
    data object All : Filter
    data object Favorites : Filter
    data class Category(val category: ProgramCategory) : Filter
}

@Composable
fun HomeScreen(s: AppState, library: ProgramLibrary?) {
    val conn by s.session.connection.collectAsStateWithLifecycle()
    val live by s.session.live.collectAsStateWithLifecycle()
    val workout by s.session.workout.collectAsStateWithLifecycle()
    val favorites by s.favorites
    var filter by remember { mutableStateOf<Filter>(Filter.All) }
    var showDeviceSheet by rememberSaveable { mutableStateOf(false) }
    var showModelSheet by rememberSaveable { mutableStateOf(false) }
    var modelOverride by remember { mutableStateOf(s.prefs.lastEquipmentId) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val connectedId = (conn as? Conn.Connected)?.equipmentId
    val equipmentId = connectedId ?: modelOverride
    val all = library?.forConsole(equipmentId).orEmpty()
    val known = equipmentId != null && library?.programs?.any { equipmentId in it.consoleIds } == true
    val shown = when (val f = filter) {
        Filter.All -> all
        Filter.Favorites -> all.filter { it.key in favorites }
        is Filter.Category -> all.filter { it.category == f.category }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Cadence") },
                actions = { IconButton(onClick = { s.nav.navigate(Routes.HISTORY) }) { Icon(Icons.Filled.History, "Historique") } },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(s.snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { DeviceCard(s, conn, live, onMore = { showDeviceSheet = true }) }
            workout?.let { w -> if (!w.finished) item { ActiveWorkoutCard(w.title, w.elapsedMs, w.durationMs, w.paused) { s.nav.navigate(Routes.SESSION) } } }
            item { FreeRideCard { s.startWorkout(null) } }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Programmes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showModelSheet = true }) {
                        Icon(Icons.Filled.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (known) EquipmentCatalog.findByEquipmentId(equipmentId)?.name ?: "Mon vélo" else "Tous modèles")
                    }
                }
            }
            item {
                val cats = all.map { it.category }.distinct().sortedBy { it.id }
                val filters = listOf<Filter>(Filter.All, Filter.Favorites) + cats.map { Filter.Category(it) }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filters) { f ->
                        val label = when (f) { Filter.All -> "Tous"; Filter.Favorites -> "Favoris"; is Filter.Category -> f.category.label }
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(label) },
                            leadingIcon = if (filter == f) { { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                        )
                    }
                }
            }
            if (library == null) item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
            else if (shown.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.FavoriteBorder, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text(if (filter == Filter.Favorites) "Aucun favori pour l'instant.\nTouchez ♡ sur un programme." else "Aucun programme",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else items(shown, key = { it.key }) { p ->
                ProgramRow(p, p.key in favorites, s.prefs.playCount(p.key),
                    onClick = { s.nav.navigate(Routes.program(p.key)) }, onFavorite = { s.toggleFavorite(p.key) })
            }
        }
    }

    if (showDeviceSheet) DeviceSheet(s, conn) { showDeviceSheet = false }
    if (showModelSheet && library != null) ModelSheet(library, onDismiss = { showModelSheet = false }) { id ->
        s.prefs.lastEquipmentId = id; modelOverride = id; showModelSheet = false
    }
}

@Composable
private fun DeviceCard(s: AppState, conn: Conn, live: BikeData?, onMore: () -> Unit) {
    val saved = s.prefs.device
    val status = LocalStatusColors.current
    val cs = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(16.dp), color = cs.primaryContainer, modifier = Modifier.size(56.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.DirectionsBike, null, Modifier.size(28.dp), tint = cs.onPrimaryContainer) }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        (conn as? Conn.Connected)?.let { it.model ?: it.name } ?: saved?.name ?: "Aucun vélo",
                        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val (label, color, pulse) = when (conn) {
                        is Conn.Connected -> Triple("Connecté · ${if (conn.protocol == Protocol.FTMS) "FTMS" else "Domyos"}", status.success, false)
                        is Conn.Connecting -> Triple("Connexion…", cs.primary, true)
                        is Conn.Reconnecting -> Triple("Reconnexion…", status.warning, true)
                        is Conn.Failed -> Triple(conn.message, cs.error, false)
                        Conn.Idle -> Triple(if (saved == null) "Appairez votre vélo pour commencer" else "Déconnecté", cs.outline, false)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        StatusDot(color, pulse); Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
                if (saved != null) IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, "Options du vélo") }
            }
            if (conn is Conn.Connected && live != null) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveChip(Icons.Filled.Bolt, "${live.powerWatts ?: 0}", "W", Modifier.weight(1f))
                    LiveChip(Icons.Filled.Autorenew, "${live.cadenceRpm?.toInt() ?: 0}", "rpm", Modifier.weight(1f))
                    LiveChip(Icons.Filled.Tune, "${live.resistanceLevel ?: "–"}", "résist.", Modifier.weight(1f))
                }
            }
            if (conn !is Conn.Connected) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    when {
                        saved == null -> Button(onClick = { s.openPairing() }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                            Icon(Icons.Filled.Bluetooth, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text("Appairer un vélo")
                        }
                        conn is Conn.Idle || conn is Conn.Failed -> {
                            TextButton(onClick = { s.openPairing() }) { Text("Changer") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { s.connect() }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                                Icon(Icons.Filled.Bluetooth, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text("Connecter")
                            }
                        }
                        else -> TextButton(onClick = { s.session.disconnect() }) { Text("Annuler") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveChip(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, unit: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(value, style = MaterialTheme.typography.titleMedium.tabular())
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActiveWorkoutCard(title: String, elapsedMs: Long, durationMs: Long?, paused: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer)) {
        Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SÉANCE EN COURS", style = MaterialTheme.typography.labelMedium)
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(formatClock(elapsedMs) + (durationMs?.let { " / " + formatClock(it) } ?: "") + if (paused) " · en pause" else "",
                    style = MaterialTheme.typography.bodyMedium.tabular())
            }
            Icon(Icons.Filled.PlayArrow, "Ouvrir la séance", Modifier.size(28.dp))
        }
    }
}

@Composable
private fun FreeRideCard(onStart: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Séance libre", style = MaterialTheme.typography.titleLarge)
                Text("Réglez la résistance à la volée, sans programme", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(12.dp))
            FilledIconButton(onClick = onStart, modifier = Modifier.size(56.dp), shape = RoundedCornerShape(16.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = cs.primary, contentColor = cs.onPrimary)) {
                Icon(Icons.Filled.PlayArrow, "Démarrer une séance libre")
            }
        }
    }
}

@Composable
private fun ProgramRow(p: Program, favorite: Boolean, plays: Int, onClick: () -> Unit, onFavorite: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer)) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = cs.surfaceContainer),
            leadingContent = {
                Box(Modifier.size(width = 88.dp, height = 56.dp).background(cs.surfaceContainerHighest, RoundedCornerShape(12.dp)).padding(8.dp)) {
                    ProgramCurve(p, Modifier.fillMaxSize(), compact = true)
                }
            },
            headlineContent = { Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column {
                    Text(listOfNotNull("${p.durationSec / 60} min", p.category.label, p.style, p.level?.let { "niv. $it" }).joinToString(" · "))
                    if (plays > 0) Text("Joué $plays fois", style = MaterialTheme.typography.labelSmall, color = cs.primary)
                }
            },
            trailingContent = {
                IconToggleButton(checked = favorite, onCheckedChange = { onFavorite() }) {
                    Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favori", tint = if (favorite) cs.primary else cs.onSurfaceVariant)
                }
            },
        )
    }
}

@Composable
private fun DeviceSheet(s: AppState, conn: Conn, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        val dev = s.prefs.device
        ListItem(
            headlineContent = { Text(dev?.name ?: "Vélo") },
            supportingContent = { Text(dev?.address ?: "") },
            leadingContent = { Icon(Icons.AutoMirrored.Filled.DirectionsBike, null) },
        )
        if (conn is Conn.Connected || conn is Conn.Connecting || conn is Conn.Reconnecting)
            ListItem(headlineContent = { Text("Déconnecter") }, leadingContent = { Icon(Icons.Filled.BluetoothDisabled, null) },
                modifier = Modifier.clickableRow { onDismiss(); s.session.disconnect() })
        ListItem(headlineContent = { Text("Appairer un autre vélo") }, leadingContent = { Icon(Icons.Filled.Refresh, null) },
            modifier = Modifier.clickableRow { onDismiss(); s.openPairing() })
        ListItem(headlineContent = { Text("Oublier ce vélo") }, leadingContent = { Icon(Icons.Filled.Delete, null) },
            modifier = Modifier.clickableRow { onDismiss(); s.session.forgetDevice() })
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModelSheet(library: ProgramLibrary, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Modèle de vélo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Text("Une fois le vélo connecté, son modèle est détecté automatiquement.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp))
        LazyColumn {
            items(library.models()) { (name, ids) ->
                ListItem(headlineContent = { Text(name) }, leadingContent = { Icon(Icons.AutoMirrored.Filled.DirectionsBike, null) },
                    modifier = Modifier.clickableRow { onPick(ids.first()) })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

internal fun Modifier.clickableRow(onClick: () -> Unit) = this.fillMaxWidth().clickable(onClick = onClick)
