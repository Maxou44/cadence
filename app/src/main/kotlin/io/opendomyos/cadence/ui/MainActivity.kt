package io.opendomyos.cadence.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.opendomyos.cadence.CadenceApp
import io.opendomyos.cadence.session.Conn
import io.opendomyos.cadence.ui.theme.CadenceTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val app get() = application as CadenceApp

    /** Incrémenté quand l'utilisateur touche la notification : l'UI ouvre l'écran de séance. */
    val openSessionRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CadenceTheme { CadenceRoot(app, this) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reconnexion automatique au vélo appairé si les autorisations sont déjà accordées.
        val s = app.session
        if (app.prefs.device != null && s.connection.value is Conn.Idle && Permissions.granted(this) && Permissions.bluetoothOn(this)) s.connect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openSessionRequests.value++
    }
}
