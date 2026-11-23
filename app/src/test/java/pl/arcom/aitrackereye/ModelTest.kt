package pl.arcom.aitrackereye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import pl.arcom.aitrackereye.model.*

/** Port testów tests/test_model.py z wersji Windows. */
class ModelTest {

    private fun weekCfg(vararg overrides: Pair<String, String>): MutableMap<String, String> {
        val cfg = linkedMapOf<String, String>()
        for (d in 0..6) for (i in 0..5) {
            cfg["Record[0].TimeSection[$d][$i]"] = if (i == 0) "1 00:00:00-24:00:00" else EMPTY_SLOT
        }
        cfg.putAll(overrides)
        return cfg
    }

    private fun assertFails(msg: String? = null, block: () -> Unit) {
        try {
            block()
            fail("Oczekiwano IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            if (msg != null) assertTrue(e.message, e.message!!.contains(msg))
        }
    }

    // ---------- godziny ----------
    @Test fun normalizeTime_ok() {
        assertEquals("08:05:00", normalizeTime("8:05"))
        assertEquals("24:00:00", normalizeTime("24:00"))
        assertEquals("00:00:00", normalizeTime("00:00"))
        assertEquals("23:59:59", normalizeTime("23:59:59"))
    }

    @Test fun normalizeTime_bad() {
        for (bad in listOf("24:01", "25:00", "12:60", "abc", "", "  :  ")) assertFails { normalizeTime(bad) }
    }

    // ---------- harmonogram ----------
    @Test fun readWeek_ignoresEmptySlots() {
        val week = readWeek(weekCfg(), 0)
        assertEquals(listOf(Segment(1, "00:00:00", "24:00:00")), week[1])
        assertEquals(6, slotsPerDay(weekCfg(), 0))
    }

    @Test fun rangeEndIsExact() {
        val cfg = weekCfg()
        val week = readWeek(cfg, 0).toMutableMap()
        week[1] = listOf(Segment(1, "08:00:00", "16:00:00"))
        assertEquals(mapOf("Record[0].TimeSection[1][0]" to "1 08:00:00-16:00:00"), buildWeekUpdate(cfg, 0, week))
    }

    @Test fun untilMidnightRoundtrip() {
        val cfg = weekCfg("Record[0].TimeSection[2][0]" to "1 08:00:00-24:00:00")
        val week = readWeek(cfg, 0)
        assertEquals(listOf(Segment(1, "08:00:00", "24:00:00")), week[2])
        assertEquals(emptyMap<String, String>(), buildWeekUpdate(cfg, 0, week))
    }

    @Test fun untouchedKeepsOtherBits() {
        val cfg = weekCfg("Record[0].TimeSection[3][0]" to "18 00:00:00-12:00:00",
            "Record[0].TimeSection[3][1]" to "5 12:00:00-24:00:00")
        val week = readWeek(cfg, 0)
        assertFalse(week[3]!![0].enabled)
        assertTrue(week[3]!![1].enabled)
        assertEquals(emptyMap<String, String>(), buildWeekUpdate(cfg, 0, week))
    }

    @Test fun multipleSegmentsAndClearing() {
        val cfg = weekCfg()
        val week = readWeek(cfg, 0).toMutableMap()
        week[0] = listOf(Segment(1, "06:00:00", "12:00:00"), Segment(0, "12:00:00", "24:00:00"))
        week[6] = emptyList()
        val up = buildWeekUpdate(cfg, 0, week)
        assertEquals("1 06:00:00-12:00:00", up["Record[0].TimeSection[0][0]"])
        assertEquals("0 12:00:00-24:00:00", up["Record[0].TimeSection[0][1]"])
        assertEquals(EMPTY_SLOT, up["Record[0].TimeSection[6][0]"])
    }

    @Test fun scheduleValidation() {
        val cfg = weekCfg()
        val w1 = readWeek(cfg, 0).toMutableMap().apply { put(1, listOf(Segment(1, "10:00:00", "09:00:00"))) }
        assertFails("przed końcem") { buildWeekUpdate(cfg, 0, w1) }
        val w2 = readWeek(cfg, 0).toMutableMap().apply { put(1, List(7) { Segment(1, "00:00:00", "01:00:00") }) }
        assertFails("maksymalnie 6") { buildWeekUpdate(cfg, 0, w2) }
        assertFails { buildWeekUpdate(emptyMap(), 0, emptyMap()) }
    }

    @Test fun disabledSegmentRoundtrip() {
        val cfg = weekCfg()
        val week = readWeek(cfg, 0).toMutableMap()
        week[4] = listOf(Segment(1, "08:00:00", "12:00:00").withEnabled(false))
        val up = buildWeekUpdate(cfg, 0, week)
        assertEquals("0 08:00:00-12:00:00", up["Record[0].TimeSection[4][0]"])
        cfg.putAll(up)
        assertEquals(listOf(Segment(0, "08:00:00", "12:00:00")), readWeek(cfg, 0)[4])
        assertEquals(2, Segment(3, "00:00:00", "01:00:00").withEnabled(false).mask)
        assertEquals(3, Segment(2, "00:00:00", "01:00:00").withEnabled(true).mask)
    }

    @Test fun emptySlotsHidden() {
        for (v in listOf("0 00:00:00-24:00:00", "0 00:00:00-23:59:59", "0 00:00:00-00:00:00")) {
            assertTrue(parseSegment(v)!!.isEmptySlot)
        }
    }

    // ---------- nagrywanie ----------
    @Test fun recordStream() {
        val f = recordStreamField(mapOf("Record[0].Stream" to "0"), 0, 1)!!
        assertEquals("Record[0].Stream", f.key)
        assertEquals(listOf("0", "1"), f.options.map { it.first })
        val t = recordStreamField(mapOf("Record[0].RecordStream" to "SubStream"), 0, 1)!!
        assertEquals("SubStream", t.current)
        assertEquals("MainStream", t.options[0].first)
        assertNull(recordStreamField(emptyMap(), 0, 1))
        assertNull(recordStreamField(mapOf("Record[0].RecordStream" to "Dziwne"), 0, 1))
    }

    @Test fun intRangeTest() {
        assertEquals(1 to 60, intRange(mapOf("caps.X[0]" to "1", "caps.X[1]" to "120"), "caps.X", 1 to 60))
        assertEquals(1 to 120, intRange(mapOf("caps.X" to "1,120"), "caps.X", 1 to 60))
    }

    // ---------- czas ----------
    @Test fun timeFormat() {
        assertEquals(TimeFormatParts("yyyy-MM-dd", true, "HH:mm:ss"), splitTimeFormat("yyyy-MM-dd HH:mm:ss"))
        assertFalse(splitTimeFormat("dd-MM-yyyy hh:mm:ss tt")!!.hour24)
        assertNull(splitTimeFormat("dziwny"))
        assertEquals("dd-MM-yyyy HH:mm:ss", joinTimeFormat("dd-MM-yyyy", true, "hh:mm:ss tt"))
        assertEquals("yyyy-MM-dd hh:mm:ss tt", joinTimeFormat("yyyy-MM-dd", false, "HH:mm:ss"))
    }

    @Test fun timezoneConsistency() {
        assertTrue(timezoneConsistent("1", null))
        assertTrue(timezoneConsistent("1", "(UTC+01:00) Belgrad"))
        assertFalse(timezoneConsistent("1", "(UTC+02:00) Ateny"))
        assertFalse(timezoneConsistent("99", null))
        assertTrue(timezoneConsistent("25", "GMT-05:00"))
    }

    @Test fun dstUpdate() {
        val loc = DST_FIELDS.associate { "Locales.DSTStart.$it" to "0" }.toMutableMap()
        loc["Locales.DSTStart.Month"] = "3"
        assertEquals(mapOf("Locales.DSTStart.Week" to "-1", "Locales.DSTStart.Hour" to "2"),
            buildDstUpdate(loc, "DSTStart", DSTPoint(3, -1, 0, 2, 0)))
        assertEquals(DSTPoint(3, 0, 0, 0, 0), readDst(loc, "DSTStart"))
        assertNull(readDst(emptyMap(), "DSTStart"))
    }

    // ---------- kodowanie ----------
    @Test fun resolutions() {
        assertEquals(listOf(2688 to 1520, 1920 to 1080, 1280 to 720, 704 to 576),
            parseResolutions("2688x1520, 1080P,720P,4M,D1"))
        assertEquals("704×576 (D1)", resolutionLabel(704 to 576))
    }

    @Test fun profile() {
        assertEquals("Main", profileFix("H.265", "High"))
        assertNull(profileFix("H.265", "Main"))
        assertNull(profileFix("H.264", "High"))
        assertNull(profileFix("H.265", null))
    }

    @Test fun sizesAndDurations() {
        assertEquals("3.21 GB", humanSize(3446711255))
        assertEquals("500 B", humanSize(500))
        assertEquals("5 min", durationText(300))
        assertEquals("12 h 30 min", durationText(45000))
        assertEquals("1 d 1 h 1 min", durationText(90061))
        assertEquals("42 s", durationText(42))
        assertEquals(300L, secondsBetween("2026-11-22 08:00:00", "2026-11-22 08:05:00"))
        assertEquals("2026-11-22 08:01:30", addSeconds("2026-11-22 08:00:00", 90))
        assertEquals("01:05", clock(65))
        assertEquals("1:00:05", clock(3605))
    }

    @Test fun folderNames() {
        assertEquals("Brama wjazdowa", folderName("Brama wjazdowa"))
        assertEquals("A_B_C______", folderName("A/B:C*?\"<>|"))
        assertEquals("Kamera", folderName("  "))
        assertEquals("Kamera", folderName("CON"))
        assertEquals("Wjazd", folderName("Wjazd."))
        assertEquals("2026-11-22_08-00-00_08-05-00.dav",
            recordingFilename("2026-11-22 08:00:00", "2026-11-22 08:05:00", "dav"))
    }

    @Test fun importChangesTest() {
        val current = mapOf("NTP" to mapOf("NTP.Port" to "123", "NTP.Address" to "a"),
            "Encode" to mapOf("Encode[0].MainFormat[0].Video.FPS" to "25"))
        val doc: Map<String, Map<String, Any?>> = mapOf(
            "NTP" to mapOf("NTP.Port" to "124", "NTP.Address" to "a", "NTP.Inny" to "x"),
            "Encode" to mapOf("Encode[0].MainFormat[0].Video.FPS" to "25"),
            "Network" to mapOf("Network.IP" to "1"))
        assertEquals(mapOf("NTP.Port" to "124"), importChanges(current, doc))
        assertEquals(listOf("Data i czas", "Obraz"), changedTabs(mapOf("NTP.Port" to "1", "Encode[0].X" to "2")))
    }
}
