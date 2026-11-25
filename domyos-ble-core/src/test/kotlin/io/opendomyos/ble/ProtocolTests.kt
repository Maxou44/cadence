package io.opendomyos.ble

import io.opendomyos.ble.domyos.*
import io.opendomyos.ble.ftms.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.test.*

/**
 * Tests exécutables sans JUnit (`main`) : vecteurs de trames dérivés du SDK d'origine,
 * réassemblage, moteur de commandes et clients Domyos/FTMS contre le simulateur.
 */
private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.h() = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

private val tests = linkedMapOf<String, suspend () -> Unit>()
private fun test(name: String, body: suspend () -> Unit) { tests[name] = body }

fun runProtocolTests(): Int {
    // ---------- Trames (vecteurs calculés à partir de getRequestData() du SDK) ----------
    test("GetInfoValue = F0 AC 9C") { assertEquals("F0 AC 9C", DomyosCommands.getInfoValue().request.h()) }
    test("GetEquipmentId = F0 C9 B9") { assertEquals("F0 C9 B9", DomyosCommands.getEquipmentId().request.h()) }
    test("SetFanSpeed(3) = F0 CA 03 BD") { assertEquals("F0 CA 03 BD", DomyosCommands.setFanSpeed(3).request.h()) }
    test("SetHotKey(START) = F0 C8 01 B9") { assertEquals("F0 C8 01 B9", DomyosCommands.setHotKey(HotKey.START).request.h()) }
    test("SetSessionData(CLEAR) = F0 C4 03 B7") { assertEquals("F0 C4 03 B7", DomyosCommands.setSessionData().request.h()) }
    test("SetInfoValue(résistance 10) : 23 octets, tout à FF sauf résistance") {
        val r = DomyosCommands.setInfoValue(DomyosSetInfo(resistanceLevel = 10)).request
        assertEquals(23, r.size)
        val expected = ByteArray(22) { -1 }.also { it[0] = 0xF0.toByte(); it[1] = 0xAD.toByte(); it[10] = 10 }
        assertContentEquals(expected, r.copyOf(22))
        assertEquals(DomyosFrame.checksum(r, 22), r[22])
    }
    test("SetInfoValue inclinaison : +5 % → 1050, -3 % → 30") {
        val p = DomyosCommands.setInfoValue(DomyosSetInfo(inclinePercent = 5f)).request
        assertEquals(1050, ((p[13].toInt() and 0xFF) shl 8) or (p[14].toInt() and 0xFF))
        val n = DomyosCommands.setInfoValue(DomyosSetInfo(inclinePercent = -3f)).request
        assertEquals(30, ((n[13].toInt() and 0xFF) shl 8) or (n[14].toInt() and 0xFF))
    }
    test("Paramètres hors plage refusés") {
        assertFailsWith<IllegalArgumentException> { DomyosCommands.setInfoValue(DomyosSetInfo(resistanceLevel = 0)) }
        assertFailsWith<IllegalArgumentException> { DomyosCommands.setFanSpeed(6) }
    }
    test("Afficheur : zone nombre 1 décimale et horloge") {
        val r = DomyosCommands.setDisplayZones(DisplayPage(z1 = DisplayZone.Clock(12, 34), z2 = DisplayZone.Number(25.4f, 1))).request
        assertEquals(27, r.size)
        assertEquals("03 0C 22 FF", r.copyOfRange(2, 6).h())
        assertEquals("01 00 FE 01", r.copyOfRange(6, 10).h())
        assertEquals("FF FF FF FF", r.copyOfRange(10, 14).h())
    }
    test("Equipment ID BCD 00 08 80 68 19 → 8806819") {
        val f = DomyosFrame.build(0xD9, hex("00 08 80 68 19"))
        assertEquals(8806819, DomyosCommands.getEquipmentId().parse(f))
    }

    // ---------- Réassemblage ----------
    test("Assembleur : réponse 26 octets en 2 paquets avec octets parasites") {
        val frame = DomyosFrame.build(0xBC, ByteArray(23) { it.toByte() })
        val a = ResponseAssembler(0xBC, 26)
        assertNull(a.feed(hex("00 11") + frame.copyOfRange(0, 18)))
        assertContentEquals(frame, a.feed(frame.copyOfRange(18, 26)))
    }
    test("Assembleur : faux départ (checksum faux) puis vraie trame") {
        val good = DomyosFrame.build(0xDA, byteArrayOf(2))
        val bad = good.copyOf().also { it[3] = 0 }
        assertContentEquals(good, ResponseAssembler(0xDA, 4).feed(bad + good))
    }
    test("Assembleur : F0 en fin de paquet conservé") {
        val f = DomyosFrame.build(0xDA, byteArrayOf(1))
        val a = ResponseAssembler(0xDA, 4)
        assertNull(a.feed(hex("33 F0")))
        assertContentEquals(f, a.feed(f.copyOfRange(1, 4)))
    }

    // ---------- Protocole Domyos de bout en bout ----------
    test("Domyos : ouverture, identification, clear, polling, commandes") {
        coroutineScope {
            val console = FakeConsole().apply { garbagePrefix = hex("00") }
            val bike = ExerciseBike.open(console, this)
            assertEquals(Protocol.DOMYOS, bike.protocol)
            assertEquals(8640779, bike.equipmentId)
            assertEquals("EB 520", bike.model?.name)
            assertTrue(BleUuids.DOMYOS_NOTIFY in console.notified)
            assertTrue(console.cleared)
            val info = bike.domyos!!.consoleInfo!!
            assertEquals("2.7.0", info.firmwareVersion)
            assertEquals("190312345", info.serialNumber)
            assertEquals(300, info.usageHours)
            assertEquals(1234, info.cumulativeKm)
            assertEquals(5, info.bluetoothVersion)

            val d = withTimeout(2000) { bike.data.filterNotNull().first() }
            assertEquals(25.4f, d.speedKmh); assertEquals(120, d.powerWatts); assertEquals(75f, d.cadenceRpm)
            assertEquals(0, d.kcal); assertEquals(0f, d.distanceKm); assertNull(d.heartRateBpm)

            bike.setResistance(12); assertEquals(12, console.resistance)
            bike.domyos!!.setFanSpeed(4); assertEquals(4, console.fan)
            bike.start(); assertEquals(1, console.hotKey)
            bike.domyos!!.setBeep(false); assertEquals(0, console.beep)
            val w = bike.domyos!!.getWorkoutResult(1)
            assertEquals(DomyosWorkoutResult(1, 45, 15.2f, 300, 20.3f, 128), w)

            val r = withTimeout(2000) { bike.data.first { it?.resistanceLevel == 12 && it.fanSpeedLevel == 4 } }
            assertEquals(HotKey.START, r!!.consoleState)
            bike.close()
        }
    }
    test("Domyos : bouton console → événement") {
        coroutineScope {
            val console = FakeConsole()
            val bike = ExerciseBike.open(console, this, clearSession = false)
            withTimeout(2000) { bike.data.filterNotNull().first() }
            val ev = async { withTimeout(2000) { bike.domyos!!.buttons.first() } }
            yield(); console.pressedButton = BikeButton.LOAD_PLUS.code
            assertEquals(BikeButton.LOAD_PLUS, ev.await())
            bike.close()
        }
    }
    test("Domyos : renvoi après timeout (SetHotKey, 2 requêtes perdues)") {
        coroutineScope {
            val console = FakeConsole()
            val client = DomyosProtocolClient(console, this)
            console.dropNextRequests = 2
            client.execute(DomyosCommands.setHotKey(HotKey.PAUSE).let { DomyosCommand(it.name, it.opcode, it.payload, it.responseLength, 4, 200) { } })
            assertEquals(0, console.hotKey)
            assertEquals(3, console.written.size)
            coroutineContext.cancelChildren()
        }
    }
    test("Domyos : timeout définitif sans renvoi") {
        coroutineScope {
            val console = FakeConsole()
            val client = DomyosProtocolClient(console, this)
            console.dropNextRequests = 1
            val c = DomyosCommands.getFirmwareVersion().let { DomyosCommand(it.name, it.opcode, it.payload, it.responseLength, 0, 150) { _ -> "" } }
            assertFailsWith<DomyosTimeoutException> { client.execute(c) }
            coroutineContext.cancelChildren()
        }
    }

    // ---------- FTMS ----------
    test("FTMS : Indoor Bike Data") {
        val v = FakeConsole.le16((1 shl 2) or (1 shl 6)) + FakeConsole.le16(3000) + FakeConsole.le16(160) + FakeConsole.le16(210)
        val d = IndoorBikeData.parse(v)
        assertEquals(30f, d.speedKmh); assertEquals(80f, d.cadenceRpm); assertEquals(210, d.powerWatts); assertNull(d.heartRateBpm)
        val more = IndoorBikeData.parse(FakeConsole.le16(1 or (1 shl 9)) + byteArrayOf(140.toByte()))
        assertNull(more.speedKmh)
        val m = d.mergedWith(more)
        assertEquals(30f, m.speedKmh); assertEquals(140, m.heartRateBpm)
    }
    test("FTMS : encodage Control Point") {
        assertEquals("04 50", FtmsControl.targetResistance(8f).h())
        assertEquals("05 2C 01", FtmsControl.targetPower(300).h())
        assertEquals("08 02", FtmsControl.pause().h())
        assertEquals("11 00 00 F4 01 28 33", FtmsControl.indoorBikeSimulation(0f, 5f).h())
    }
    test("FTMS : ouverture, contrôle, résistance mise à l'échelle, données") {
        coroutineScope {
            val console = FakeConsole(withDomyos = false, withFtms = true, modelNumber = "8806819")
            val bike = ExerciseBike.open(console, this)
            assertEquals(Protocol.FTMS, bike.protocol)
            assertEquals("Training Bike 900", bike.model?.name)
            assertTrue(console.ftmsControl)
            assertEquals(FtmsRange(1f, 32f, 1f), bike.ftms!!.resistanceRange)
            assertTrue(bike.ftms!!.features!!.canSetResistance)
            bike.setResistance(8); assertEquals(80, console.ftmsResistanceRaw)
            bike.setTargetPower(2000); assertEquals(1000, console.ftmsPower) // borné à la plage
            bike.start(); assertTrue(console.ftmsStarted)
            console.emitIndoorBikeData()
            val d = withTimeout(1000) { bike.data.filterNotNull().first() }
            assertEquals(25.4f, d.speedKmh); assertEquals(80f, d.cadenceRpm); assertEquals(1.234f, d.distanceKm)
            assertEquals(8, d.resistanceLevel); assertEquals(150, d.powerWatts); assertEquals(600, d.elapsedSeconds)
            bike.close()
        }
    }
    test("FTMS : contrôle perdu → nouvelle demande automatique") {
        coroutineScope {
            val console = FakeConsole(withDomyos = false, withFtms = true)
            val bike = ExerciseBike.open(console, this)
            console.ftmsControl = false
            bike.setResistance(5)
            assertEquals(50, console.ftmsResistanceRaw)
            bike.close()
        }
    }
    test("Choix du protocole : Domyos préféré, FTMS sur demande") {
        coroutineScope {
            val b1 = ExerciseBike.open(FakeConsole(withDomyos = true, withFtms = true), this, clearSession = false)
            assertEquals(Protocol.DOMYOS, b1.protocol); b1.close()
            val b2 = ExerciseBike.open(FakeConsole(withDomyos = true, withFtms = true), this, preferred = Protocol.FTMS)
            assertEquals(Protocol.FTMS, b2.protocol); b2.close()
        }
    }
    test("Noms Bluetooth → type d'appareil") {
        assertEquals(EquipmentKind.BIKE, EquipmentKind.fromAdvertisedName("Domyos-EB-1234"))
        assertEquals(EquipmentKind.BIKE, EquipmentKind.fromAdvertisedName("DOMYOS-BIKE-0042"))
        assertEquals(EquipmentKind.ELLIPTICAL, EquipmentKind.fromAdvertisedName("Domyos-EL-77"))
        assertEquals(EquipmentKind.ROWER, EquipmentKind.fromAdvertisedName("pd123"))
        assertEquals(EquipmentKind.UNKNOWN, EquipmentKind.fromAdvertisedName("JBL Flip"))
    }

    var failed = 0
    runBlocking {
        for ((name, body) in tests) {
            try { withTimeout(15_000) { body() }; println("✔ $name") }
            catch (e: Throwable) { failed++; println("✘ $name\n    $e") }
        }
    }
    println("\n${tests.size - failed}/${tests.size} tests OK")
    return failed
}

class ProtocolTestSuite {
    @kotlin.test.Test
    fun allProtocolTests() = assertEquals(0, runProtocolTests(), "tests en échec")
}
