package io.opendomyos.cadence.util

import kotlinx.coroutines.CoroutineExceptionHandler

fun formatClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Journalise au lieu de faire planter l'app si une coroutine échoue de façon inattendue. */
val LogErrors = CoroutineExceptionHandler { _, e -> android.util.Log.e("Cadence", "Erreur non gérée", e) }
