package io.opendomyos.cadence

import io.opendomyos.cadence.program.*
import java.io.File
import kotlin.test.*

private val tests = linkedMapOf<String, () -> Unit>()
private fun test(n: String, b: () -> Unit) { tests[n] = b }

fun runProgramTests(dir: File): Int {
    val lib = ProgramLibrary.load(dir.listFiles()!!.filter { it.extension == "json" }.associate { it.name to it.readText() })

    test("Tous les fichiers vélo sont chargés") {
        assertTrue(lib.programs.size > 300, "programmes = ${lib.programs.size}")
        assertTrue(lib.programs.all { it.segments.isNotEmpty() && it.durationSec > 0 })
    }
    test("EB 520 : 7 programmes dédupliqués, 2 consoles") {
        val p = lib.forConsole(8640779)
        assertEquals(7, p.size)
        assertEquals(setOf(8640779, 8652259), p.first().consoleIds)
        assertEquals("Fractionné 1", p.first().title)
        assertEquals(ProgramCategory.INTERVAL, p.first().category)
        assertEquals(1800, p.first().durationSec)
    }
    test("Paliers : fronts verticaux (PROG 6 EB520)") {
        val p = lib.forConsole(8640779).first { it.idProgram.startsWith("PROG 6") }
        // (0,2)(.125,4)(.25,4)(.25,6)(.375,6)(.375,8)(.5,8)(.5,8)(.625,8)(.625,6)...
        val s = p.segments.map { it.start to it.resistance }
        assertEquals(listOf(0f to 2, 0.125f to 4, 0.25f to 6, 0.375f to 8, 0.625f to 6, 0.75f to 4, 0.875f to 2), s)
        assertEquals(1f, p.segments.last().end)
    }
    test("Lecteur : changement de palier au bon moment") {
        val p = lib.forConsole(8640779).first() // 4,12,4,12… toutes les 225 s
        val pl = ProgramPlayer(p)
        assertEquals(4, pl.targetResistance)
        assertFalse(pl.advance(224_000))
        assertTrue(pl.advance(1_000)); assertEquals(12, pl.targetResistance)
        assertEquals(225_000, pl.segmentRemainingMs)
        pl.advance(10_000_000); assertTrue(pl.finished); assertEquals(1f, pl.progress)
    }
    test("Lecteur : l'écart utilisateur est conservé sur les paliers suivants") {
        val p = lib.forConsole(8640779).first()
        val pl = ProgramPlayer(p)
        assertEquals(6, pl.adjust(+2))
        pl.advance(225_000); assertEquals(14, pl.targetResistance)
        pl.adoptConsoleResistance(10); assertEquals(-2, pl.userOffset)
        pl.advance(225_000); assertEquals(2, pl.targetResistance)
        assertEquals(32, ProgramPlayer(p).apply { adjust(100) }.targetResistance)
        assertEquals(1, ProgramPlayer(p).apply { adjust(-100) }.targetResistance)
    }
    test("Mode libre") {
        val pl = ProgramPlayer(null, startResistance = 5)
        assertEquals(7, pl.adjust(2)); assertFalse(pl.advance(60_000)); assertFalse(pl.finished)
    }
    test("Format récent : cibles de cadence et consignes") {
        val tb = lib.forConsole(8806819)
        assertTrue(tb.all { 8806819 in it.consoleIds })
        assertTrue(tb.any { it.hasCadenceTargets })
        assertTrue(tb.flatMap { it.segments }.any { it.instruction == "danseuse" })
        assertEquals("Intervalles", tb.first().style)
    }
    test("Titres uniques par modèle") {
        lib.programs.groupBy { it.key.substringBefore('/') + it.consoleIds.sorted() }.forEach { (f, ps) ->
            val d = ps.groupBy { it.title }.filterValues { it.size > 1 }.keys
            assertTrue(d.isEmpty(), "$f doublons : $d")
        }
    }
    test("Modèles proposés") {
        val names = lib.models().map { it.first }
        assertTrue("EB 520" in names && "Training Bike 900" in names, names.toString())
    }

    var failed = 0
    for ((n, b) in tests) try { b(); println("✔ $n") } catch (e: Throwable) { failed++; println("✘ $n\n    $e") }
    println("\n${tests.size - failed}/${tests.size} tests OK")
    println("Exemples : " + lib.programs.shuffled(java.util.Random(1)).take(8).joinToString { "${it.title} (${it.category.label})" })
    return failed
}

class ProgramTestSuite {
    @Test
    fun allProgramTests() {
        // Gradle lance les tests depuis le dossier du module (app/)
        val dir = listOf(File("src/main/assets/programs"), File("app/src/main/assets/programs")).first { it.isDirectory }
        assertEquals(0, runProgramTests(dir), "tests en échec")
    }
}
