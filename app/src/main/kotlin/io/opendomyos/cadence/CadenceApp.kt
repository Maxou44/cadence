package io.opendomyos.cadence

import android.app.Application
import io.opendomyos.cadence.data.Prefs
import io.opendomyos.cadence.program.ProgramLibrary
import io.opendomyos.cadence.session.SessionManager

class CadenceApp : Application() {
    val prefs by lazy { Prefs(this) }
    val session by lazy { SessionManager(this, prefs) }

    /** Programmes Decathlon embarqués (assets/programs). Chargement ~200 ms : à appeler hors du thread UI. */
    val library: ProgramLibrary by lazy {
        val files = assets.list("programs").orEmpty().filter { it.endsWith(".json") }
        ProgramLibrary.load(files.associateWith { name -> assets.open("programs/$name").bufferedReader().use { it.readText() } })
    }
}
