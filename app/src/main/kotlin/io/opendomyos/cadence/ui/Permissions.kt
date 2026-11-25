package io.opendomyos.cadence.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.content.ContextCompat

object Permissions {
    val bluetooth: List<String> = if (Build.VERSION.SDK_INT >= 31)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    val requested: List<String> = bluetooth + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    fun granted(ctx: Context) = bluetooth.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    fun bluetoothOn(ctx: Context) = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true
}

/**
 * Renvoie une fonction `run(action)` qui exécute [action] après avoir obtenu les autorisations
 * « Appareils à proximité » (+ notifications) et l'activation du Bluetooth.
 */
@android.annotation.SuppressLint("MissingPermission")
@Composable
fun rememberBluetoothGate(onDenied: (String) -> Unit): (() -> Unit) -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val denied = rememberUpdatedState(onDenied)
    val pending = remember { arrayOfNulls<() -> Unit>(1) }

    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val a = pending[0]; pending[0] = null
        if (Permissions.bluetoothOn(ctx)) a?.invoke() else denied.value("Activez le Bluetooth pour vous connecter au vélo")
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!Permissions.granted(ctx)) { pending[0] = null; denied.value("L'autorisation « Appareils à proximité » est nécessaire pour trouver le vélo"); return@rememberLauncherForActivityResult }
        if (Permissions.bluetoothOn(ctx)) { val a = pending[0]; pending[0] = null; a?.invoke() }
        else enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }
    return remember {
        { action: () -> Unit ->
            pending[0] = action
            val missing = Permissions.requested.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
            when {
                missing.isNotEmpty() -> perms.launch(missing.toTypedArray())
                !Permissions.bluetoothOn(ctx) -> enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                else -> { pending[0] = null; action() }
            }
        }
    }
}
