package io.opendomyos.cadence.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.opendomyos.cadence.program.Program
import kotlin.math.max

/** Chiffres à chasse fixe : les compteurs ne « sautent » pas. */
fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

/**
 * Profil d'un programme : un pilier par palier, hauteur ∝ résistance.
 * Réalisé en couleur primaire, palier courant en tertiaire, reste en conteneur primaire.
 */
@Composable
fun ProgramCurve(
    program: Program,
    modifier: Modifier = Modifier,
    progress: Float = -1f,
    offset: Int = 0,
    compact: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val active = cs.primary
    val idle = if (compact) cs.primary.copy(alpha = 0.35f) else cs.primaryContainer
    val current = cs.tertiary
    val cursor = cs.onSurface
    Canvas(modifier) {
        val segs = program.segments
        if (segs.isEmpty()) return@Canvas
        val cur = if (progress >= 0f) program.segmentIndexAt(progress) else -1
        val maxR = max(segs.maxOf { it.resistance + offset }, 1).toFloat()
        val gap = (if (compact) 1.5.dp else 3.dp).toPx()
        val radius = (if (compact) 2.dp else 6.dp).toPx()
        val minH = (if (compact) 3.dp else 6.dp).toPx()
        segs.forEachIndexed { i, s ->
            val left = s.start * size.width + gap / 2
            val right = s.end * size.width - gap / 2
            if (right <= left) return@forEachIndexed
            val res = (s.resistance + if (cur in 0..i) offset else 0).coerceAtLeast(1)
            val h = max(minH, size.height * (res / maxR) * 0.94f)
            val color = when {
                progress < 0f -> if (compact) active else idle
                i == cur -> current
                i < cur -> active
                else -> idle
            }
            drawRoundRect(color, Offset(left, size.height - h), Size(right - left, h), CornerRadius(radius, radius))
            if (i == cur) {
                val x = (progress * size.width).coerceIn(left, right)
                if (x > left) drawRoundRect(active, Offset(left, size.height - h), Size(x - left, h), CornerRadius(radius, radius))
            }
        }
        if (progress >= 0f && !compact) {
            val x = progress * size.width
            drawRoundRect(cursor, Offset(x - 1.5.dp.toPx(), 0f), Size(3.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
        }
    }
}

/** Pastille d'état, éventuellement pulsée. */
@Composable
fun StatusDot(color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val alpha = if (pulsing) {
        val t = rememberInfiniteTransition(label = "dot")
        val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "a")
        a
    } else 1f
    Canvas(modifier.size(8.dp)) { drawCircle(color.copy(alpha = alpha)) }
}

/** Barres de signal (RSSI). */
@Composable
fun SignalBars(rssi: Int, modifier: Modifier = Modifier) {
    val level = when { rssi > -60 -> 4; rssi > -70 -> 3; rssi > -80 -> 2; else -> 1 }
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.size(18.dp, 14.dp)) {
        val bw = size.width / 7f
        for (i in 0 until 4) {
            val bh = size.height * (i + 1) / 4f
            drawRoundRect(if (i < level) on else off, Offset(i * bw * 2f, size.height - bh), Size(bw, bh), CornerRadius(bw / 2))
        }
    }
}

/** Tuile de mesure : icône + unité, valeur en grand. */
@Composable
fun MetricTile(icon: ImageVector, value: String, unit: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.padding(top = 6.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall.tabular())
        }
    }
}

/** Grille 3 colonnes de tuiles. */
@Composable
fun MetricGrid(items: List<Triple<ImageVector, String, String>>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (icon, value, unit) -> MetricTile(icon, value, unit, Modifier.weight(1f)) }
            }
        }
    }
}
