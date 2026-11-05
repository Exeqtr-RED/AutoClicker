package com.example.autoclicker

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ФИЧА «Поделиться пресетами» — экспорт и импорт пресетов между
 * устройствами через системный шэринг (мессенджеры, почта, диск).
 *
 * ЭКСПОРТ: пресет сериализуется в версионированный JSON-файл
 * <имя>.sekira.json во внутренний кеш приложения и отправляется через
 * ACTION_SEND (content:// Uri через уже настроенный FileProvider —
 * отдельные разрешения/провайдеры не нужны, cache-path уже прописан в
 * file_paths.xml). Получатель сохраняет файл и открывает его в
 * «Sekira Cliker» («Открыть с помощью») или жмёт «Поделиться →
 * Sekira Cliker» прямо из мессенджера.
 *
 * ИМПОРТ: приложение принимает три сценария (см. intent-фильтры в
 * AndroidManifest и MainActivity/SettingsActivity):
 *  1. ACTION_SEND с EXTRA_STREAM — файл из мессенджера/файлового менеджера;
 *  2. ACTION_SEND с EXTRA_TEXT — пресет, скопированный как ТЕКСТ
 *     (пользователь переслал JSON сообщением);
 *  3. ACTION_VIEW — «Открыть с помощью» на файле .sekira.json.
 *  4. Кнопка «Импорт пресетов» в редакторе — SAF-пикер (ACTION_GET_CONTENT).
 *
 * Формат файла (версионированный, расширяемый без ломки старых версий):
 *   {"format":"sekira-preset","version":1,"exportedAt":1699999999999,
 *    "app":"Sekira Cliker","presets":[ {…Preset.toJson()…}, … ]}
 *
 * Парсер ТЕРПИМЫЙ (урок Preset.fromJson): принимаются
 *  — обёртка формата выше;
 *  — голый JSON-массив пресетов;
 *  — одиночный JSON-объект пресета (в т.ч. без обёртки);
 *  — мусор до/после JSON (BOM, подпись мессенджера «Пресет Sekira Cliker:»)
 *    — берём подстроку от первого '{' или '['.
 * Битые элементы пропускаются, поля чинятся дефолтами/нормализацией.
 *
 * ВАЖНО (безопасность воспроизведения): импортированный пресет НЕ
 * проходит валидацию buildPreset() редактора, поэтому нормализуем сами:
 *  — пресет с ПУСТЫМИ actions отбраковывается целиком (ST-пресет без
 *    тапа при playback тапал бы по дефолтной точке крестика (140, 540) —
 *    тот самый баг v42/S3, его нельзя пускать через импорт);
 *  — mode нормализуется: MTWS остаётся MTWS, всё прочее → ST;
 *  — timingMode нормализуется в {INFINITE, DURATION, CYCLES};
 *  — числовые поля прижимаются к тем же потолкам, что и редактор
 *    (задержка/периодичность ≤ сутки, разброс ≤ минута).
 */
object PresetShare {

    /** Расширение файла пресета — по нему «Открыть с помощью» находит приложение */
    const val FILE_EXTENSION = ".sekira.json"

    private const val FORMAT = "sekira-preset"
    private const val FORMAT_VERSION = 1

    // Потолки нормализации при импорте. SettingsActivity.MAX_* — private,
    // дублируем значения; при изменении потолков в редакторе синхронизировать
    private const val MAX_DELAY_MS = 24L * 60 * 60_000     // сутки
    private const val MAX_JITTER_MS = 60_000L              // минута
    private const val MAX_SWIPE_DURATION_MS = 60_000L      // длительность свайпа

    // ============================================================
    // Экспорт
    // ============================================================

    /** Сериализует пресеты в payload-строку (обёртка формата). */
    fun exportPayload(presets: List<Preset>): String {
        val arr = JSONArray()
        presets.forEach { arr.put(it.toJson()) }
        return JSONObject().apply {
            put("format", FORMAT)
            put("version", FORMAT_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("app", "Sekira Cliker")
            put("presets", arr)
        }.toString()
    }

    /**
     * Безопасное имя файла из имени пресета: убираем символы, ломающие
     * файловые системы и мессенджеры (/ \ : * ? " < > | и управляющие),
     * ограничиваем длину. Результат без осмысленных символов
     * (пустой или только '_') → «preset».
     */
    fun sanitizeFileName(name: String): String {
        val cleaned = name.map { ch ->
            val bad = ch in "/\\:*?\"<>|" || ch.code < 32
            if (bad) '_' else ch
        }.joinToString("").trim()
        val cut = if (cleaned.length > 60) cleaned.take(60) else cleaned
        // Только '_' (например, имя «///») или пусто — осмысленного имени нет
        if (cut.isEmpty() || cut.none { it != '_' }) return "preset"
        return cut
    }

    /**
     * Поделиться одним пресетом: файл в кеш → ACTION_SEND через FileProvider.
     * Все шаги в runCatching: шэринг не должен ронять приложение (нет
     * файлового менеджера/шэринг-приложений на прошивке и т.п.).
     */
    fun share(activity: Activity, preset: Preset) {
        runCatching {
            val payload = exportPayload(listOf(preset))
            val dir = File(activity.cacheDir, "shared_presets").apply { mkdirs() }
            // Чистим прошлые экспорты — копим максимум один файл (паттерн
            // cleanupOldApks из Updater; кеш всё равно под контролью системы)
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, sanitizeFileName(preset.name) + FILE_EXTENSION)
            f.writeText(payload)

            val uri = FileProvider.getUriForFile(
                activity, activity.packageName + ".fileprovider", f
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Пресет Sekira Cliker: ${preset.name}")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Пресет «${preset.name}» для Sekira Cliker. " +
                        "Откройте файл в приложении — пресет добавится автоматически."
                )
                // ClipData обязателен: через Chooser grant не всегда
                // наследуется из EXTRA_STREAM (документированная ловушка)
                clipData = ClipData.newRawUri("preset", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(
                Intent.createChooser(send, "Поделиться пресетом")
            )
        }.onFailure {
            Toast.makeText(
                activity,
                "Не удалось поделиться пресетом: ${it.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ============================================================
    // Импорт: парсинг payload
    // ============================================================

    /**
     * Разбирает payload в список пресетов. Возвращает только КОРРЕКТНЫЕ,
     * нормализованные пресеты; битые/пустые элементы отбрасываются.
     * Никогда не бросает исключений — вход может быть любым текстом
     * (EXTRA_TEXT мессенджера, файл из чужого приложения).
     */
    fun parsePayload(text: String?): List<Preset> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = cutJsonRoot(text) ?: return emptyList()
            val arr: JSONArray = when {
                root.startsWith("[") -> JSONArray(root)
                root.startsWith("{") -> {
                    val o = JSONObject(root)
                    // Обёртка формата → массив в "presets"
                    o.optJSONArray("presets") ?: JSONArray().apply { put(o) }
                }
                else -> return emptyList()
            }
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { normalize(it) }
            }
        }.getOrDefault(emptyList())
    }

    /** Отрезает BOM и текст до/после JSON: от первого '{'/'[' до конца.
     *  Хвостовую подпись («…добавьте в Sekira Cliker») JSONObject съест сам. */
    private fun cutJsonRoot(text: String): String? {
        val t = text.trim().removePrefix("\uFEFF").trim()
        val start = t.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        return t.substring(start)
    }

    /**
     * Нормализация одного пресета из чужого файла. Возвращает null, если
     * пресет бесполезен (пустые actions) — валидация, эквивалентная
     * buildPreset() редактора, но для данных из недоверенного источника.
     */
    private fun normalize(raw: JSONObject): Preset? {
        // mode: MTWS остаётся MTWS, всё прочее (ST, st, мусор) → ST.
        // Не отбраковываем: ST-дефолт безопасен (у него валидация действий ниже)
        val mode = if (raw.optString("mode", "ST").uppercase() == "MTWS") "MTWS" else "ST"
        val name = raw.optString("name", "").trim().ifEmpty { "Импорт" }
        val timingMode = when (raw.optString("timingMode", "INFINITE").uppercase()) {
            "DURATION" -> "DURATION"
            "CYCLES" -> "CYCLES"
            else -> "INFINITE"
        }
        val durationSec = raw.optLong("durationSec", 0L).coerceAtLeast(0L)
        val cycles = raw.optInt("cycles", 1).coerceAtLeast(1)

        val actions = mutableListOf<PresetAction>()
        val arr = raw.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("type", "tap")
            if (t != "tap" && t != "swipe") continue
            actions.add(
                PresetAction(
                    t,
                    o.optInt("x1"), o.optInt("y1"),
                    o.optInt("x2"), o.optInt("y2"),
                    o.optLong("swipeDurationMs", 300L)
                        .coerceIn(0L, MAX_SWIPE_DURATION_MS),
                    o.optLong("delayMs", 100L).coerceIn(0L, MAX_DELAY_MS)
                )
            )
        }
        // Пустой список действий не играет (та же валидация, что в редакторе)
        if (actions.isEmpty()) return null

        return Preset(
            name = name,
            mode = mode,
            timingMode = timingMode,
            durationSec = durationSec,
            cycles = cycles,
            // Для MTWS поле не используется при воспроизведении (см. KDoc Preset),
            // но сохраняется ради обратной совместимости
            delayMs = raw.optLong("delayMs", 100L).coerceIn(0L, MAX_DELAY_MS),
            delayJitterMs = raw.optLong("delayJitterMs", 0L)
                .coerceIn(0L, MAX_JITTER_MS),
            repeatIntervalMs = raw.optLong("repeatIntervalMs", 0L)
                .coerceIn(0L, MAX_DELAY_MS),
            actions = actions
        )
    }

    // ============================================================
    // Импорт: чтение Uri и сохранение
    // ============================================================

    /** Читает текст из content:// / file:// Uri (SAF, мессенджеры).
     *  null при любой ошибке чтения/кодировки. */
    fun readTextFromUri(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            }
        }.getOrNull()

    /**
     * Имя без коллизий: если (base, mode) уже занято — «base (2)»,
     * «base (3)», … Импорт никогда не затирает пресеты пользователя.
     */
    fun uniqueName(ctx: Context, base: String, mode: String): String {
        val existing = PresetStorage.getAll(ctx)
            .filter { it.mode == mode }
            .map { it.name }
            .toHashSet()
        if (base !in existing) return base
        var i = 2
        while (i < 1000) {
            val candidate = "$base ($i)"
            if (candidate !in existing) return candidate
            i++
        }
        return "$base (${System.currentTimeMillis() % 10000})"
    }

    /** Итог импорта — для тоста пользователю. */
    data class ImportSummary(
        val imported: Int,
        val renamed: List<String>,   // «Было → стало» для переименованных
        val skipped: Int             // отбраковано при нормализации
    ) {
        fun toMessage(): String = buildString {
            append("Импортировано: $imported")
            if (skipped > 0) append(", пропущено битых: $skipped")
            if (renamed.isNotEmpty()) {
                append("\nПереименованы (было совпадение по имени):")
                renamed.forEach { append("\n• $it") }
            }
        }
    }

    /** Сохраняет пресеты с переименованием при коллизиях. */
    fun importPresets(ctx: Context, presets: List<Preset>): ImportSummary {
        val renamed = mutableListOf<String>()
        var skipped = 0
        presets.forEach { p ->
            // Пресеты с одинаковым именем внутри ОДНОГО файла легитимны?
            // Нет — оставляем оба: uniqueName считает только ХРАНИЛИЩЕ,
            // поэтому второй одноимённый из пакета получил бы то же имя.
            // Дедупликация внутри пакета: ведём множество уже выданных имён
            val finalName = uniqueName(ctx, p.name, p.mode)
            val stored = if (finalName != p.name) {
                renamed.add("${p.name} → $finalName")
                p.copy(name = finalName)
            } else p
            PresetStorage.save(ctx, stored)
        }
        // skipped считает вызывавший (parsePayload уже отбросил битые)
        return ImportSummary(presets.size, renamed, skipped)
    }

    /**
     * Диалог подтверждения импорта: показывает, ЧТО будет добавлено
     * (режим + имя), и итог после сохранения. Единая точка входа для
     * MainActivity (шэринг/VIEW) и SettingsActivity (SAF-пикер).
     */
    fun showImportDialog(activity: Activity, presets: List<Preset>) {
        if (presets.isEmpty()) {
            Toast.makeText(activity, "В файле нет корректных пресетов", Toast.LENGTH_LONG).show()
            return
        }
        val listStr = buildString {
            val shown = presets.take(12)
            shown.forEach { p ->
                val label = if (p.mode == "ST") p.name else "MTWS · ${p.name}"
                append("• $label\n")
                append("   действий: ${p.actions.size}, ")
                append(
                    when (p.timingMode) {
                        "DURATION" -> "длительность ${p.durationSec} с"
                        "CYCLES" -> "циклов: ${p.cycles}"
                        else -> "бесконечно"
                    }
                )
                append("\n")
            }
            if (presets.size > shown.size) append("…и ещё ${presets.size - shown.size}\n")
        }
        AlertDialog.Builder(activity)
            .setTitle("Импорт пресетов")
            .setMessage(
                "Найдено пресетов: ${presets.size}\n\n$listStr\n" +
                    "Добавить в сохранённые? Совпадающие имена " +
                    "автоматически получат суффикс «(2)», «(3)»…"
            )
            .setPositiveButton("Импортировать") { _, _ ->
                val s = importPresets(activity, presets)
                Toast.makeText(activity, s.toMessage(), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Приём пресетов из внешнего Intent (ACTION_SEND / ACTION_VIEW).
     * Вызывается из MainActivity.onCreate / onNewIntent.
     * Тихо выходит, если intent не про пресеты (обычный запуск лаунчера,
     * обычный текст без JSON) — приложение продолжает открываться как обычно.
     */
    fun handleIncomingIntent(activity: Activity, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val text: String? = try {
            when (action) {
                Intent.ACTION_SEND -> {
                    // Приоритет — файл (EXTRA_STREAM); его нет — пробуем ТЕКСТ
                    @Suppress("DEPRECATION")
                    val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (stream != null) readTextFromUri(activity, stream)
                    else intent.getStringExtra(Intent.EXTRA_TEXT)
                }
                Intent.ACTION_VIEW -> intent.data?.let { readTextFromUri(activity, it) }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrBlank()) return
        val presets = parsePayload(text)
        if (presets.isEmpty()) {
            // Текст без JSON (обычный шаренный текст) — молча игнорируем:
            // приложение не должно спамить тостами на каждый чужой шар
            if (action == Intent.ACTION_VIEW) {
                Toast.makeText(
                    activity,
                    "Файл не похож на пресет Sekira Cliker",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        showImportDialog(activity, presets)
    }
}
