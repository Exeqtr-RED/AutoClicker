package com.example.autoclicker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Юнит-тесты PresetShare — экспорт/импорт пресетов (фича «Поделиться»).
 *
 * Покрывают:
 *  - export → parse round-trip: все поля пресета переживают файл
 *  - терпимый парсер: обёртка формата / голый массив / одиночный объект
 *  - мусор вокруг JSON (подпись мессенджера, BOM) не ломает разбор
 *  - битые элементы пропускаются, пресет с ПУСТЫМИ actions отбраковывается
 *    (валидация, эквивалентная buildPreset: пустой ST-пресет кликал бы
 *    по дефолтной точке крестика)
 *  - нормализация: неизвестный mode → ST, неизвестный timingMode → INFINITE,
 *    cycles < 1 → 1, отрицательные/гигантские задержки прижимаются к потолкам
 *  - sanitizeFileName: кавычки/слэши/управляющие символы → безопасное имя
 *
 * Все тесты — чистый JVM (org.json:json в testImplementation), Context не нужен.
 */
class PresetShareTest {

    private fun samplePreset(
        name: String = "Farm",
        mode: String = "MTWS",
        timingMode: String = "CYCLES",
        cycles: Int = 3,
        actions: List<PresetAction> = listOf(
            PresetAction("tap", 100, 200, 0, 0, 0L, delayMs = 500L),
            PresetAction("swipe", 10, 20, 300, 400, 800L, delayMs = 1500L)
        )
    ): Preset = Preset(
        name = name, mode = mode, timingMode = timingMode,
        durationSec = 0L, cycles = cycles,
        delayMs = 0L, delayJitterMs = 0L, repeatIntervalMs = 30_000L,
        actions = actions
    )

    // ---------- Round-trip ----------

    @Test
    fun `export then parse round-trips all fields`() {
        val original = samplePreset()
        val payload = PresetShare.exportPayload(listOf(original))
        val restored = PresetShare.parsePayload(payload)
        assertEquals(1, restored.size)
        val p = restored[0]
        assertEquals(original.name, p.name)
        assertEquals(original.mode, p.mode)
        assertEquals(original.timingMode, p.timingMode)
        assertEquals(original.cycles, p.cycles)
        assertEquals(original.repeatIntervalMs, p.repeatIntervalMs)
        assertEquals(original.actions.size, p.actions.size)
        assertEquals(original.actions[1].type, p.actions[1].type)
        assertEquals(original.actions[1].x2, p.actions[1].x2)
        assertEquals(original.actions[1].swipeDurationMs, p.actions[1].swipeDurationMs)
        assertEquals(original.actions[0].delayMs, p.actions[0].delayMs)
    }

    @Test
    fun `export payload contains format marker`() {
        val o = JSONObject(PresetShare.exportPayload(listOf(samplePreset())))
        assertEquals("sekira-preset", o.getString("format"))
        assertEquals(1, o.getInt("version"))
        assertTrue(o.getJSONArray("presets").length() == 1)
    }

    // ---------- Терпимый парсер ----------

    @Test
    fun `parse accepts bare array without wrapper`() {
        val arr = JSONArray().apply {
            put(samplePreset(name = "A").toJson())
            put(samplePreset(name = "B", mode = "ST").toJson())
        }
        val parsed = PresetShare.parsePayload(arr.toString())
        assertEquals(2, parsed.size)
        assertEquals("A", parsed[0].name)
        assertEquals("B", parsed[1].name)
    }

    @Test
    fun `parse accepts single preset object without wrapper`() {
        val parsed = PresetShare.parsePayload(samplePreset().toJson().toString())
        assertEquals(1, parsed.size)
        assertEquals("Farm", parsed[0].name)
    }

    @Test
    fun `parse tolerates messenger text around json`() {
        val payload = PresetShare.exportPayload(listOf(samplePreset(name = "Quest")))
        val shared = "Пресет Sekira Cliker «Quest»:\n$payload\nДобавь в Sekira Cliker!"
        val parsed = PresetShare.parsePayload(shared)
        assertEquals(1, parsed.size)
        assertEquals("Quest", parsed[0].name)
    }

    @Test
    fun `parse tolerates BOM before json`() {
        val payload = PresetShare.exportPayload(listOf(samplePreset()))
        val parsed = PresetShare.parsePayload("\uFEFF$payload")
        assertEquals(1, parsed.size)
    }

    @Test
    fun `parse returns empty for garbage and null`() {
        assertTrue(PresetShare.parsePayload(null).isEmpty())
        assertTrue(PresetShare.parsePayload("").isEmpty())
        assertTrue(PresetShare.parsePayload("   ").isEmpty())
        assertTrue(PresetShare.parsePayload("привет, это не json").isEmpty())
        assertTrue(PresetShare.parsePayload("{\"broken\": tru").isEmpty())
    }

    // ---------- Отбраковка и нормализация ----------

    @Test
    fun `parse skips broken items and keeps valid ones`() {
        val arr = JSONArray().apply {
            put(JSONObject("{\"name\": \"Broken, no actions\"}"))
            put(samplePreset(name = "Good").toJson())
            put("just a string")
        }
        val parsed = PresetShare.parsePayload(arr.toString())
        assertEquals(1, parsed.size)
        assertEquals("Good", parsed[0].name)
    }

    @Test
    fun `parse rejects preset with empty actions`() {
        // Критично: ST-пресет без тапа при playback кликал бы по дефолтной
        // точке крестика (баг v42/S3) — импорт обязан его отбраковать
        val json = samplePreset(name = "Empty").copy(actions = emptyList()).toJson()
        assertTrue(PresetShare.parsePayload(json.toString()).isEmpty())
    }

    @Test
    fun `parse skips actions with unknown type`() {
        val raw = samplePreset(name = "OddAction").toJson()
        val actions = raw.getJSONArray("actions")
        actions.getJSONObject(0).put("type", "long_press")  // неизвестный тип
        val parsed = PresetShare.parsePayload(raw.toString())
        assertEquals(1, parsed.size)
        assertEquals(1, parsed[0].actions.size)
        assertEquals("swipe", parsed[0].actions[0].type)
    }

    @Test
    fun `parse normalizes unknown mode and timingMode`() {
        val raw = samplePreset(name = "Weird").toJson()
        raw.put("mode", "spam")
        raw.put("timingMode", "sometimes")
        val parsed = PresetShare.parsePayload(raw.toString())
        assertEquals(1, parsed.size)
        assertEquals("ST", parsed[0].mode)
        assertEquals("INFINITE", parsed[0].timingMode)
    }

    @Test
    fun `parse normalizes lowercase mode and timingMode`() {
        val raw = samplePreset(name = "Lower").toJson()
        raw.put("mode", "mtws")
        raw.put("timingMode", "duration")
        val parsed = PresetShare.parsePayload(raw.toString())
        assertEquals("MTWS", parsed[0].mode)
        assertEquals("DURATION", parsed[0].timingMode)
    }

    @Test
    fun `parse clamps negative and oversized numeric fields`() {
        val raw = samplePreset(name = "Clamp").toJson()
        raw.put("cycles", 0)
        raw.put("durationSec", -5L)
        raw.put("delayMs", Long.MAX_VALUE)
        raw.put("delayJitterMs", -100L)
        raw.put("repeatIntervalMs", 999_999_999_999L)
        raw.getJSONArray("actions").getJSONObject(0).put("delayMs", -1L)
        val p = PresetShare.parsePayload(raw.toString())[0]
        assertEquals(1, p.cycles)
        assertEquals(0L, p.durationSec)
        assertEquals(24L * 60 * 60_000, p.delayMs)
        assertEquals(0L, p.delayJitterMs)
        assertEquals(24L * 60 * 60_000, p.repeatIntervalMs)
        assertEquals(0L, p.actions[0].delayMs)
    }

    @Test
    fun `parse fills missing name with Import`() {
        val raw = samplePreset().toJson()
        raw.remove("name")
        val parsed = PresetShare.parsePayload(raw.toString())
        assertEquals("Импорт", parsed[0].name)
    }

    @Test
    fun `parse fills missing timing fields with defaults`() {
        // Старый пресет без jitter/repeat — как в Preset.fromJson
        val raw = samplePreset().toJson()
        raw.remove("delayJitterMs")
        raw.remove("repeatIntervalMs")
        raw.remove("cycles")
        val parsed = PresetShare.parsePayload(raw.toString())
        assertEquals(0L, parsed[0].delayJitterMs)
        assertEquals(0L, parsed[0].repeatIntervalMs)
        assertEquals(1, parsed[0].cycles)
    }

    // ---------- sanitizeFileName ----------

    @Test
    fun `sanitizeFileName removes filesystem-hostile characters`() {
        assertEquals("farm_quest", PresetShare.sanitizeFileName("farm/quest"))
        assertEquals("farm_quest", PresetShare.sanitizeFileName("farm:quest"))
        assertEquals("farm_quest", PresetShare.sanitizeFileName("farm\"quest"))
        assertEquals("farm_quest", PresetShare.sanitizeFileName("farm\\quest"))
    }

    @Test
    fun `sanitizeFileName keeps normal names`() {
        assertEquals("Фарм дракона (2)", PresetShare.sanitizeFileName("Фарм дракона (2)"))
        assertEquals("Farm-1", PresetShare.sanitizeFileName("Farm-1"))
    }

    @Test
    fun `sanitizeFileName handles empty and hostile-only names`() {
        assertEquals("preset", PresetShare.sanitizeFileName(""))
        assertEquals("preset", PresetShare.sanitizeFileName("///"))
        assertEquals("preset", PresetShare.sanitizeFileName("\u0000\u0001"))
    }

    @Test
    fun `sanitizeFileName caps length`() {
        val long = "а".repeat(300)
        assertTrue(PresetShare.sanitizeFileName(long).length <= 60)
    }
}
