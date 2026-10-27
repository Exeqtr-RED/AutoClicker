package com.example.autoclicker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Юнит-тесты Preset / PresetAction — модели пресетов и сериализация.
 *
 * ФИКС (audit 3.3): раньше это был шаблон Android Studio с тривиальным
 * assertEquals(4, 2 + 2) — никакой реальной пользы. Теперь покрыты:
 *   - toJson / fromJson round-trip (включая новые поля delayMs, jitter, repeat)
 *   - обратная совместимость: старый пресет без delayMs у действий
 *     использует fallback-задержку пресета
 *   - защита от битых данных: optXxx вместо getXxx — null/пустые поля
 *     не бросают исключение, восстанавливаются в дефолты
 *   - устаревший формат без delayJitterMs / repeatIntervalMs — восстанавливается
 */
class PresetTest {

    @Test
    fun `presetAction toJson then fromJson preserves all fields`() {
        val action = PresetAction(
            type = "swipe",
            x1 = 100, y1 = 200, x2 = 300, y2 = 400,
            swipeDurationMs = 500L, delayMs = 250L
        )
        val json = action.toJson()
        val restored = PresetAction.fromJson(json)
        assertEquals(action.type, restored.type)
        assertEquals(action.x1, restored.x1)
        assertEquals(action.y1, restored.y1)
        assertEquals(action.x2, restored.x2)
        assertEquals(action.y2, restored.y2)
        assertEquals(action.swipeDurationMs, restored.swipeDurationMs)
        assertEquals(action.delayMs, restored.delayMs)
    }

    @Test
    fun `presetAction fromJson uses fallback delay when delayMs missing`() {
        // Старый формат: у действий нет поля delayMs — берётся fallback
        val legacy = JSONObject().apply {
            put("type", "tap")
            put("x1", 50); put("y1", 60)
            put("x2", 0); put("y2", 0)
            put("swipeDurationMs", 0L)
            // delayMs намеренно отсутствует
        }
        val restored = PresetAction.fromJson(legacy, fallbackDelayMs = 750L)
        assertEquals(750L, restored.delayMs)
        assertEquals("tap", restored.type)
        assertEquals(50, restored.x1)
    }

    @Test
    fun `presetAction fromJson tolerates broken fields with defaults`() {
        // Битый JSON: type отсутствует, координаты не числа — отдаёт дефолт
        val broken = JSONObject().apply {
            // type намеренно отсутствует → дефолт "tap"
            put("x1", "not_a_number")  // optInt вернёт 0
            put("y1", 42)
            put("x2", 0); put("y2", 0)
            put("swipeDurationMs", "broken")  // optLong вернёт дефолт 300L
            put("delayMs", 100L)
        }
        val restored = PresetAction.fromJson(broken)
        assertEquals("tap", restored.type)
        assertEquals(0, restored.x1)
        assertEquals(42, restored.y1)
        assertEquals(300L, restored.swipeDurationMs)
        assertEquals(100L, restored.delayMs)
    }

    @Test
    fun `preset toJson then fromJson preserves all fields`() {
        val preset = Preset(
            name = "TestFarm",
            mode = "MTWS",
            timingMode = "CYCLES",
            durationSec = 0L,
            cycles = 5,
            delayMs = 0L,           // для MTWS не используется, но сохраняется
            delayJitterMs = 0L,
            repeatIntervalMs = 30_000L,
            actions = listOf(
                PresetAction("tap", 100, 200, 0, 0, 0L, delayMs = 500L),
                PresetAction("swipe", 10, 20, 30, 40, 800L, delayMs = 1000L)
            )
        )
        val json = preset.toJson()
        val restored = Preset.fromJson(json)
        assertEquals(preset.name, restored.name)
        assertEquals(preset.mode, restored.mode)
        assertEquals(preset.timingMode, restored.timingMode)
        assertEquals(preset.durationSec, restored.durationSec)
        assertEquals(preset.cycles, restored.cycles)
        assertEquals(preset.delayMs, restored.delayMs)
        assertEquals(preset.delayJitterMs, restored.delayJitterMs)
        assertEquals(preset.repeatIntervalMs, restored.repeatIntervalMs)
        assertEquals(preset.actions.size, restored.actions.size)
        assertEquals(preset.actions[0].delayMs, restored.actions[0].delayMs)
        assertEquals(preset.actions[1].type, restored.actions[1].type)
        assertEquals(preset.actions[1].swipeDurationMs, restored.actions[1].swipeDurationMs)
    }

    @Test
    fun `preset fromJson restores legacy preset without jitter and repeat`() {
        // Старый формат: нет delayJitterMs / repeatIntervalMs — должны
        // восстановиться в 0 (анти-детект выключен, паузы между прогонами нет).
        val legacy = JSONObject().apply {
            put("name", "OldPreset")
            put("mode", "ST")
            put("timingMode", "INFINITE")
            put("durationSec", 0L)
            put("cycles", 1)
            put("delayMs", 200L)
            // delayJitterMs и repeatIntervalMs намеренно отсутствуют
            put("actions", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "tap")
                    put("x1", 500); put("y1", 600)
                    put("x2", 0); put("y2", 0)
                    put("swipeDurationMs", 0L)
                    put("delayMs", 0L)
                })
            })
        }
        val restored = Preset.fromJson(legacy)
        assertEquals("OldPreset", restored.name)
        assertEquals("ST", restored.mode)
        assertEquals(200L, restored.delayMs)
        assertEquals(0L, restored.delayJitterMs)
        assertEquals(0L, restored.repeatIntervalMs)
        assertEquals(1, restored.actions.size)
        assertEquals(500, restored.actions[0].x1)
    }

    @Test
    fun `preset fromJson tolerates broken name and mode with defaults`() {
        // Битый пресет: name и mode отсутствуют — optString отдаёт дефолты
        val broken = JSONObject().apply {
            // name и mode намеренно отсутствуют
            put("timingMode", "DURATION")
            put("durationSec", 60L)
            put("cycles", 1)
            put("delayMs", 100L)
            put("actions", JSONArray())
        }
        val restored = Preset.fromJson(broken)
        assertEquals("", restored.name)
        assertEquals("ST", restored.mode)
        assertEquals("DURATION", restored.timingMode)
        assertEquals(60L, restored.durationSec)
        assertEquals(0, restored.actions.size)
    }

    @Test
    fun `preset with ST tap action round-trips tap coordinates exactly`() {
        val preset = Preset(
            name = "ST",
            mode = "ST",
            timingMode = "INFINITE",
            durationSec = 0L, cycles = 1, delayMs = 100L,
            actions = listOf(PresetAction("tap", 770, 1300, 0, 0, 0L, 100L))
        )
        val json = preset.toJson()
        val restored = Preset.fromJson(json)
        assertEquals(770, restored.actions[0].x1)
        assertEquals(1300, restored.actions[0].y1)
        assertEquals("tap", restored.actions[0].type)
    }
}
