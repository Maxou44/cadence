package io.opendomyos.cadence.session

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.opendomyos.cadence.CadenceApp
import io.opendomyos.cadence.R
import io.opendomyos.cadence.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import io.opendomyos.cadence.util.formatClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Service de premier plan (type connectedDevice) : garde la liaison Bluetooth et le lecteur
 * de programme actifs écran éteint / app en arrière-plan, avec une notification de contrôle.
 */
@android.annotation.SuppressLint("MissingPermission")
class WorkoutService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + io.opendomyos.cadence.util.LogErrors)
    private var wakeLock: PowerManager.WakeLock? = null
    private val session get() = (application as CadenceApp).session

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = NotificationManagerCompat.from(this)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Séance en cours")
                .setDescription("Connexion au vélo et programme en cours")
                .setShowBadge(false)
                .build()
        )
        goForeground(build())
        scope.launch {
            while (isActive) {
                val conn = session.connection.value
                val w = session.workout.value
                if (conn is Conn.Idle && w == null) { stopSelf(); break }
                if (conn is Conn.Failed && w == null) { stopSelf(); break }
                updateWakeLock(w != null && !w.finished)
                if (nm.areNotificationsEnabled()) runCatching { nm.notify(NOTIF_ID, build()) }
                delay(1_000)
            }
        }
    }

    private fun goForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(build()) // requis après chaque startForegroundService()
        when (intent?.action) {
            ACTION_TOGGLE -> session.togglePause()
            ACTION_STOP -> session.stopWorkout(save = true)
            ACTION_DISCONNECT -> session.disconnect()
        }
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        val conn = session.connection.value
        val w = session.workout.value
        val live = session.live.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bike)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (w != null) {
            val clock = if (w.durationMs != null) "${formatClock(w.elapsedMs)} / ${formatClock(w.durationMs)}" else formatClock(w.elapsedMs)
            val status = when {
                w.finished -> "Terminé"
                w.paused -> "En pause"
                w.waitingForPedaling -> "Pédalez pour reprendre"
                else -> "Résistance ${w.targetResistance}"
            }
            b.setContentTitle("${w.title} · $clock")
            b.setContentText(listOfNotNull(status, live?.powerWatts?.let { "$it W" }, live?.cadenceRpm?.let { "${it.toInt()} rpm" }).joinToString(" · "))
            if (w.durationMs != null) b.setProgress(1000, (w.progress * 1000).toInt(), false)
            if (!w.finished) {
                b.addAction(action(if (w.paused) R.drawable.ic_play else R.drawable.ic_pause, if (w.paused) "Reprendre" else "Pause", ACTION_TOGGLE, 1))
                b.addAction(action(R.drawable.ic_stop, "Terminer", ACTION_STOP, 2))
            }
        } else {
            b.setContentTitle(
                when (conn) {
                    is Conn.Connected -> "Connecté à ${conn.model ?: conn.name}"
                    is Conn.Connecting -> "Connexion à ${conn.name}…"
                    is Conn.Reconnecting -> "Reconnexion à ${conn.name}…"
                    else -> "Cadence"
                }
            )
            b.setContentText(live?.let { "${it.powerWatts ?: 0} W · ${it.cadenceRpm?.toInt() ?: 0} rpm · résistance ${it.resistanceLevel ?: "-"}" } ?: "Prêt")
            b.addAction(action(R.drawable.ic_bluetooth_off, "Déconnecter", ACTION_DISCONNECT, 3))
        }
        return b.build()
    }

    private fun action(icon: Int, title: String, act: String, req: Int): NotificationCompat.Action {
        val pi = PendingIntent.getService(this, req, Intent(this, WorkoutService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(icon, title, pi).build()
    }

    private fun updateWakeLock(want: Boolean) {
        val held = wakeLock?.isHeld == true
        if (want && !held) {
            wakeLock = getSystemService(PowerManager::class.java)!!
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cadence:workout")
                .apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
        } else if (!want && held) wakeLock?.release()
    }

    override fun onDestroy() {
        scope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "workout"
        const val NOTIF_ID = 42
        const val ACTION_TOGGLE = "io.opendomyos.cadence.TOGGLE"
        const val ACTION_STOP = "io.opendomyos.cadence.STOP"
        const val ACTION_DISCONNECT = "io.opendomyos.cadence.DISCONNECT"
    }
}
