package io.opendomyos.cadence.ui

import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.opendomyos.cadence.CadenceApp
import io.opendomyos.cadence.program.Program
import io.opendomyos.cadence.program.ProgramLibrary
import io.opendomyos.cadence.session.Conn
import io.opendomyos.cadence.ui.screens.HistoryScreen
import io.opendomyos.cadence.ui.screens.HomeScreen
import io.opendomyos.cadence.ui.screens.PairingScreen
import io.opendomyos.cadence.ui.screens.ProgramScreen
import io.opendomyos.cadence.ui.screens.SessionScreen
import io.opendomyos.cadence.ui.screens.SummaryDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Routes {
    const val HOME = "home"
    const val PAIRING = "pairing"
    const val PROGRAM = "program/{key}"
    const val SESSION = "session"
    const val HISTORY = "history"
    fun program(key: String) = "program/${Uri.encode(key)}"
}

/** Dépendances partagées par les écrans. */
class AppState(
    val app: CadenceApp,
    val nav: NavHostController,
    val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    val withBluetooth: (() -> Unit) -> Unit,
) {
    val session get() = app.session
    val prefs get() = app.prefs

    /** Favoris observables par Compose. */
    val favorites: MutableState<Set<String>> = mutableStateOf(prefs.favorites)

    fun toggleFavorite(key: String) { prefs.toggleFavorite(key); favorites.value = prefs.favorites }

    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }

    fun openPairing() = withBluetooth { nav.navigate(Routes.PAIRING) }

    fun connect() = withBluetooth { session.connect() }

    /** Lance une séance (ou mène l'utilisateur à la connexion du vélo). */
    fun startWorkout(program: Program?) {
        val w = session.workout.value
        when {
            w != null && !w.finished -> nav.navigate(Routes.SESSION) { launchSingleTop = true }
            session.connection.value !is Conn.Connected ->
                if (prefs.device == null) openPairing() else { message("Connexion au vélo…"); connect() }
            else -> {
                session.startWorkout(program)
                nav.navigate(Routes.SESSION) { popUpTo(Routes.HOME); launchSingleTop = true }
            }
        }
    }
}

@Composable
fun CadenceRoot(app: CadenceApp, activity: MainActivity) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val gate = rememberBluetoothGate { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    val state = remember { AppState(app, nav, snackbar, scope, gate) }

    val library by produceState<ProgramLibrary?>(null) { value = withContext(Dispatchers.Default) { app.library } }

    LaunchedEffect(Unit) { app.session.messages.collect { snackbar.showSnackbar(it) } }

    // Ouvre l'écran de séance au lancement (séance en cours) ou depuis la notification.
    val openRequests by activity.openSessionRequests.collectAsStateWithLifecycle()
    LaunchedEffect(openRequests) {
        if (app.session.workout.value != null && nav.currentDestination?.route != Routes.SESSION)
            nav.navigate(Routes.SESSION) { launchSingleTop = true }
    }

    val summary by app.session.summary.collectAsStateWithLifecycle()
    summary?.let { entry ->
        SummaryDialog(entry) {
            app.session.dismissSummary()
            if (nav.currentDestination?.route == Routes.SESSION) nav.popBackStack(Routes.HOME, inclusive = false)
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(state, library) }
        composable(Routes.PAIRING) { PairingScreen(state) }
        composable(Routes.PROGRAM, arguments = listOf(navArgument("key") { type = NavType.StringType })) { entry ->
            val key = entry.arguments?.getString("key")?.let(Uri::decode)
            val program = key?.let { library?.byKey(it) }
            if (program != null) ProgramScreen(state, program)
        }
        composable(Routes.SESSION) { SessionScreen(state) }
        composable(Routes.HISTORY) { HistoryScreen(state) }
    }
}
