package io.opendomyos.cadence.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Couleurs d'état complémentaires (succès / avertissement), absentes du schéma M3 standard. */
@Immutable
data class StatusColors(val success: Color, val onSuccessContainer: Color, val successContainer: Color, val warning: Color, val warningContainer: Color)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(Color(0xFF1E7A34), Color(0xFF002106), Color(0xFFA6F5B0), Color(0xFF8A5A00), Color(0xFFFFDDB3))
}

private val LightStatus = StatusColors(Color(0xFF1E7A34), Color(0xFF002106), Color(0xFFA6F5B0), Color(0xFF8A5A00), Color(0xFFFFDDB3))
private val DarkStatus = StatusColors(Color(0xFF8BD89A), Color(0xFFA6F5B0), Color(0xFF00531A), Color(0xFFFFB951), Color(0xFF684300))

/**
 * Thème Material 3 officiel : couleurs dynamiques (Material You) sur Android 12+,
 * palette Material 3 de référence sinon ; clair/sombre selon le système.
 */
@Composable
fun CadenceTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
