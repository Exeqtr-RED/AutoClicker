package com.example.autoclicker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnitRunner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Инструментированные тесты PresetStorage — записи и чтения пресетов
 * через SharedPreferences.
 *
 * ФИКС (audit 3.3): раньше это был шаблон Android Studio с тривиальным
 * assertEquals(4, 2 + 2). Теперь покрыты реальные сценарии:
 *   - save / getAll round-trip: пресет сохраняется и читается без потерь
 *   - upsert по (name, mode): повторный save с тем же именем и режимом
 *     заменяет, а не дублирует
 *   - разные режимы сохраняются независимо: ST «Farm» и MTWS «Farm»
 *     — это два разных пресета
 *   - getByMode фильтрует корректно
 *   - delete удаляет только нужный (по name + mode), не трогая соседей
 *   - кэш PresetStorage.cache обновляется синхронно после save
 *
 * Запуск: ./gradlew connectedAndroidTest (нужен эмулятор/устройство).
 * SharedPreferences доступны только в инструментированных тестах.
 */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class PresetStorageTest {

    private val ctx: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun cleanup() {
        // Чистим пресеты перед каждым тестом — иначе тесты влияют друг на друга
        ctx.getSharedPreferences("presets_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Сбрасываем кэш через getAll — он будет перестроен при первом чтении
        // (через delete с пустым списком, который перепишет KEY пустым массивом)
        PresetStorage.delete(ctx, "_test_dummy_", "ST")
    }

    @After
    fun tearDown() {
        ctx.getSharedPreferences("presets_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun samplePreset(name: String, mode: String = "ST"): Preset = Preset(
        name = name, mode = mode,
        timingMode = "INFINITE",
        durationSec = 0L, cycles = 1, delayMs = 100L,
        actions = listOf(PresetAction("tap", 100, 200, 0, 0, 0L, 100L))
    )

    @Test
    fun `save and getAll round-trips preset`() {
        val p = samplePreset("TestRoundTrip")
        PresetStorage.save(ctx, p)
        val all = PresetStorage.getAll(ctx)
        assertEquals(1, all.size)
        assertEquals("TestRoundTrip", all[0].name)
        assertEquals(100, all[0].actions[0].x1)
        assertEquals(200, all[0].actions[0].y1)
    }

    @Test
    fun `save replaces preset with same name and mode`() {
        val p1 = samplePreset("ReplaceMe").copy(delayMs = 100L)
        val p2 = samplePreset("ReplaceMe").copy(delayMs = 500L)
        PresetStorage.save(ctx, p1)
        PresetStorage.save(ctx, p2)
        val all = PresetStorage.getAll(ctx)
        assertEquals(1, all.size)
        assertEquals(500L, all[0].delayMs)
    }

    @Test
    fun `save keeps separate presets with same name but different mode`() {
        val st = samplePreset("Farm", mode = "ST")
        val mtws = samplePreset("Farm", mode = "MTWS")
            .copy(actions = listOf(PresetAction("tap", 50, 60, 0, 0, 0L, 100L)))
        PresetStorage.save(ctx, st)
        PresetStorage.save(ctx, mtws)
        val all = PresetStorage.getAll(ctx)
        assertEquals(2, all.size)
        assertTrue(all.any { it.name == "Farm" && it.mode == "ST" })
        assertTrue(all.any { it.name == "Farm" && it.mode == "MTWS" })
    }

    @Test
    fun `getByMode filters correctly`() {
        PresetStorage.save(ctx, samplePreset("A", mode = "ST"))
        PresetStorage.save(ctx, samplePreset("B", mode = "MTWS")
            .copy(actions = listOf(PresetAction("tap", 1, 2, 0, 0, 0L, 0L))))
        PresetStorage.save(ctx, samplePreset("C", mode = "ST"))
        val stOnly = PresetStorage.getByMode(ctx, "ST")
        assertEquals(2, stOnly.size)
        assertTrue(stOnly.all { it.mode == "ST" })
        val mtwsOnly = PresetStorage.getByMode(ctx, "MTWS")
        assertEquals(1, mtwsOnly.size)
        assertEquals("B", mtwsOnly[0].name)
    }

    @Test
    fun `delete removes only matching preset by name and mode`() {
        PresetStorage.save(ctx, samplePreset("Keep1", mode = "ST"))
        PresetStorage.save(ctx, samplePreset("Delete", mode = "ST"))
        PresetStorage.save(ctx, samplePreset("Keep2", mode = "ST"))
        PresetStorage.delete(ctx, "Delete", "ST")
        val all = PresetStorage.getAll(ctx)
        assertEquals(2, all.size)
        assertTrue(all.none { it.name == "Delete" })
        assertTrue(all.any { it.name == "Keep1" })
        assertTrue(all.any { it.name == "Keep2" })
    }

    @Test
    fun `delete does not touch preset with same name but different mode`() {
        PresetStorage.save(ctx, samplePreset("Shared", mode = "ST"))
        PresetStorage.save(ctx, samplePreset("Shared", mode = "MTWS")
            .copy(actions = listOf(PresetAction("tap", 1, 1, 0, 0, 0L, 0L))))
        PresetStorage.delete(ctx, "Shared", "ST")
        val all = PresetStorage.getAll(ctx)
        assertEquals(1, all.size)
        assertEquals("MTWS", all[0].mode)
    }

    @Test
    fun `getAll returns empty list when no presets`() {
        val all = PresetStorage.getAll(ctx)
        assertNotNull(all)
        assertEquals(0, all.size)
    }

    @Test
    fun `cache is consistent after save`() {
        // Дважды подряд вызываем getAll — кэш должен возвращать тот же список
        PresetStorage.save(ctx, samplePreset("Cache1"))
        val first = PresetStorage.getAll(ctx)
        val second = PresetStorage.getAll(ctx)
        assertEquals(first.size, second.size)
        assertEquals(first[0].name, second[0].name)
    }
}
