package com.example.autoclicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PresetAction(
    val type: String,        // "tap" or "swipe"
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val swipeDurationMs: Long,
    // ФИЧА: индивидуальная задержка ПОСЛЕ этого действия перед следующим (мс).
    // У последнего действия цикла не используется — пауза перед повторным
    // запуском пресета задаётся полем Preset.repeatIntervalMs («Периодичность»)
    val delayMs: Long = 100L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("x1", x1); put("y1", y1)
        put("x2", x2); put("y2", y2)
        put("swipeDurationMs", swipeDurationMs)
        put("delayMs", delayMs)
    }

    companion object {
        /**
         * ФИЧА (обратная совместимость): в пресетах старого формата у действий
         * нет поля delayMs — тогда каждому действию назначается глобальная
         * задержка старого пресета, и ритм воспроизведения не меняется.
         */
        fun fromJson(o: JSONObject, fallbackDelayMs: Long = 100L) = PresetAction(
            o.getString("type"),
            o.getInt("x1"), o.getInt("y1"),
            o.getInt("x2"), o.getInt("y2"),
            o.optLong("swipeDurationMs", 300L),
            o.optLong("delayMs", fallbackDelayMs)
        )
    }
}

data class Preset(
    val name: String,
    val mode: String,          // "ST" / "MTWS"
    val timingMode: String,    // "INFINITE" / "DURATION" / "CYCLES"
    val durationSec: Long,
    val cycles: Int,
    val delayMs: Long,
    // ФИЧА: разброс задержки (мс) для ST — реальная пауза между кликами
    // выбирается СЛУЧАЙНО из [delayMs − delayJitterMs; delayMs + delayJitterMs].
    // 0 = разброс выключен (пауза постоянная). Анти-детект: клики не идут
    // метрономом с фиксированным интервалом
    val delayJitterMs: Long = 0L,
    // ФИЧА: периодичность запуска пресета (мс) — пауза после завершения
    // всех действий перед повторным прогоном. Для ST-пресетов = 0
    val repeatIntervalMs: Long = 0L,
    val actions: List<PresetAction>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("mode", mode)
        put("timingMode", timingMode)
        put("durationSec", durationSec)
        put("cycles", cycles)
        put("delayMs", delayMs)
        put("delayJitterMs", delayJitterMs)
        put("repeatIntervalMs", repeatIntervalMs)
        val arr = JSONArray()
        actions.forEach { arr.put(it.toJson()) }
        put("actions", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Preset {
            val legacyDelay = o.optLong("delayMs", 100L)
            val arr = o.optJSONArray("actions") ?: JSONArray()
            val list = mutableListOf<PresetAction>()
            for (i in 0 until arr.length()) {
                list.add(PresetAction.fromJson(arr.getJSONObject(i), legacyDelay))
            }
            return Preset(
                o.getString("name"),
                o.getString("mode"),
                o.getString("timingMode"),
                o.optLong("durationSec", 0L),
                o.optInt("cycles", 1),
                legacyDelay,
                o.optLong("delayJitterMs", 0L),
                o.optLong("repeatIntervalMs", 0L),
                list
            )
        }
    }
}

object PresetStorage {
    private const val PREF = "presets_v1"
    private const val KEY = "list"

    fun getAll(ctx: Context): List<Preset> {
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        // ФИКС: битый JSON больше не роняет приложение при каждом открытии
        // настроек — строка целиком и каждая запись парсятся с защитой,
        // повреждённые элементы просто пропускаются
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { Preset.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun getByMode(ctx: Context, mode: String): List<Preset> =
        getAll(ctx).filter { it.mode == mode }

    fun save(ctx: Context, preset: Preset) {
        val all = getAll(ctx).toMutableList()
        all.removeAll { it.name == preset.name && it.mode == preset.mode }
        all.add(preset)
        persist(ctx, all)
    }

    fun delete(ctx: Context, name: String, mode: String) {
        val all = getAll(ctx).toMutableList()
        all.removeAll { it.name == name && it.mode == mode }
        persist(ctx, all)
    }

    private fun persist(ctx: Context, list: List<Preset>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
