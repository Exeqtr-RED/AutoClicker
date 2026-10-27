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
            // ФИКС: optXxx вместо getXxx — битое/слишком старое действие раньше
            // бросало исключение, per-item runCatching в PresetStorage ТУШИЛ
            // ВЕСЬ пресет: он молча исчезал из списка пользователя
            o.optString("type", "tap"),
            o.optInt("x1"), o.optInt("y1"),
            o.optInt("x2"), o.optInt("y2"),
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
    /**
     * Задержка между кликами (мс) — ТОЛЬКО для режима ST.
     *
     * Для MTWS-режима это поле НЕ используется при воспроизведении: ритм
     * MTWS задаётся индивидуальными [PresetAction.delayMs] у каждого
     * действия пресета. Поле сохраняется в JSON и читается обратно
     * исключительно ради обратной совместимости со старыми пресетами
     * (где у действий могло не быть своего delayMs, и fallback-логика
     * в [PresetAction.fromJson] брала глобальное значение).
     *
     * Не редактируйте это поле для MTWS-пресетов в UI — UI и так его
     * скрывает. При программной сборке MTWS-пресета передавайте 0L.
     *
     * ФИКС (audit 3.1): формально помечено как устаревшее для MTWS.
     */
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
            // ФИКС: optString для name/mode/timingMode — битое/чужое пресет
            // раньше бросал исключение и ТУШИЛ ВЕСЬ список пресетов
            // (per-item runCatching в getAll его пропускает, но лучше явно)
            return Preset(
                o.optString("name", ""),
                o.optString("mode", "ST"),
                o.optString("timingMode", "INFINITE"),
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
    // ФИКС (audit 2.3.1): резервный ключ для битого JSON. Если парсинг
    // списка пресетов упал, оригинал сохраняется сюда (с таймстемпом),
    // а следующий save() НЕ перезаписывает его «пустотой» — пользователь
    // может восстановить пресеты вручную из backup-ключа (или мы сделаем
    // авто-импорт в следующей версии). Без backup-ключа damaged JSON
    // терялся безвозвратно.
    private const val KEY_BACKUP_PREFIX = "list_corrupted_"

    // ФИКС (v36, перф): кэш распарсенных пресетов — getAll() больше не читает
    // и не парсит JSON при каждом вызове (панель, редактор и фильтр getByMode
    // зовут его по несколько раз за экран; при десятках пресетов парсинг
    // заметен). Кэш строится при первом чтении и обновляется при каждой
    // записи (persist); объект пишет только этот — внешних изменений нет
    @Volatile private var cache: List<Preset>? = null

    fun getAll(ctx: Context): List<Preset> {
        cache?.let { return it }
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList<Preset>().also { cache = it }
        // ФИКС: битый JSON больше не роняет приложение при каждом открытии
        // настроек — строка целиком и каждая запись парсятся с защитой,
        // повреждённые элементы просто пропускаются.
        // ФИКС (audit 2.3.1): при полной неудаче парсинга сохраняем битый
        // JSON в backup-ключ — на случай, если это единственный шанс
        // восстановить пресеты после системного сбоя флешки.
        val parsed = runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { Preset.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrElse {
            // Сохраняем битый оригинал в backup — без него следующий save()
            // перезаписал бы KEY пустым массивом, и пресеты терялись бы
            // безвозвратно. runCatching: пишем синхронно (commit), иначе
            // backup сам может потеряться при тех же условиях, что и KEY.
            runCatching {
                val ts = System.currentTimeMillis()
                ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit()
                    .putString("$KEY_BACKUP_PREFIX$ts", s)
                    .commit()
            }
            emptyList()
        }
        // ФИКС (v38, корректность): defensive copy. mapNotNull возвращает
        // ArrayList под типом List — внешние кастинги могли бы мутировать
        // кэш в обход save/delete. toList() даёт неизменяемую копию.
        // ФИКС (audit 2.2.1): возвращаем именно cache (defensive copy),
        // а не parsed (исходный ArrayList). Если внешний код приведёт
        // результат к MutableList и замутирует, кэш PresetStorage
        // останется нетронутым.
        cache = parsed.toList()
        return cache!!
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
        // ФИКС (audit 2.1.1): apply() → commit(). Раньше persist() писал
        // АСИНХРОННО — если пользователь сохранял пресет и сразу убивал
        // приложение (force-stop / OOM-kill / смахивание из недавних),
        // фоновый writer мог не успеть записать, и пресет терялся.
        // Тот же сценарий уже исправлен в ClickService.persistLastPreset()
        // (фикс v42 S5: apply → commit). Здесь логика фиксируется для
        // PresetStorage — несоответствие устранено.
        // commit() синхронный (~5–15 мс на современных флешках), persist()
        // зовётся редко (только при явном save/delete) — накладные расходы
        // незаметны, зато данные надёжно переживают гибель процесса.
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).commit()
        // ФИКС (v36): кэш синхронен с хранилищем сразу после записи.
        // ФИКС (v38, корректность): defensive copy — вызывающий код
        // передаёт MutableList (save/delete зовут persist(ctx, all)),
        // и мог бы продолжить мутировать его после persist. toList()
        // разрывает ссылку: будущие мутации all не заденут кэш.
        cache = list.toList()
    }
}
