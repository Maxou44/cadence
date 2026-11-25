@file:OptIn(ExperimentalMaterial3Api::class)

package io.opendomyos.cadence.ui.screens

import android.os.Build
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.opendomyos.ble.EquipmentKind
import io.opendomyos.cadence.ui.AppState
import io.opendomyos.cadence.ui.components.SignalBars

@Composable
fun PairingScreen(s: AppState) {
    val results by s.session.scanResults.collectAsStateWithLifecycle()
    val scanning by s.session.scanning.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        s.session.startScan()
        onDispose { s.session.stopScan() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appairer un vélo") },
                navigationIcon = { IconButton(onClick = { s.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                actions = { IconButton(onClick = { s.session.startScan() }, enabled = !scanning) { Icon(Icons.Filled.Refresh, "Relancer la recherche") } },
            )
        },
        snackbarHost = { SnackbarHost(s.snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            else Spacer(Modifier.height(4.dp))
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.padding(16.dp)) {
                            Icon(Icons.Filled.Bluetooth, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Text(
                                "Allumez la console et pédalez quelques secondes pour la réveiller. Votre vélo apparaît sous le nom « Domyos-… ».",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (results.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.BluetoothSearching, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(16.dp))
                        Text(if (scanning) "Recherche des vélos à proximité…" else "Aucun vélo trouvé", style = MaterialTheme.typography.titleMedium)
                        if (!scanning) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Vérifiez que la console est allumée et que l'app officielle n'y est pas connectée." +
                                    if (Build.VERSION.SDK_INT < 31) "\nSur cette version d'Android, la localisation doit aussi être activée." else "",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(24.dp))
                            FilledTonalButton(onClick = { s.session.startScan() }) { Text("Relancer") }
                        }
                    }
                } else {
                    item { Text("Appareils trouvés", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) }
                    items(results, key = { it.address }) { d ->
                        Card(onClick = {
                            s.session.pair(d)
                            s.message("Connexion à ${d.name ?: d.address}…")
                            s.nav.popBackStack()
                        }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                leadingContent = {
                                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                                        Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.DirectionsBike, null, Modifier.size(22.dp)) }
                                    }
                                },
                                headlineContent = { Text(d.name ?: "Appareil inconnu") },
                                supportingContent = {
                                    val kind = when (d.kind) {
                                        EquipmentKind.BIKE -> "Vélo"; EquipmentKind.ELLIPTICAL -> "Elliptique"
                                        EquipmentKind.TREADMILL -> "Tapis"; EquipmentKind.ROWER -> "Rameur"; EquipmentKind.UNKNOWN -> "Appareil FTMS"
                                    }
                                    Text("$kind${if (d.advertisesFtms) " · FTMS" else ""} · ${d.address}")
                                },
                                trailingContent = { SignalBars(d.rssi) },
                            )
                        }
                    }
                }
            }
        }
    }
}
