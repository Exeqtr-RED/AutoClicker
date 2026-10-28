package com.example.autoclicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * ФИЧА (Task): автообновление APK из GitHub Releases.
 *
 * Логика:
 *  1. По тапу «Обновить приложение» — checkLatestRelease() через GitHub API
 *     (https://api.github.com/repos/Exeqtr-RED/AutoClicker/releases/latest).
 *  2. Сравниваем tag_name release с текущей версией приложения.
 *  3. Если новее — показываем диалог «Найдено обновление vX.Y.Z», пользователь
 *     подтверждает.
 *  4. Скачиваем первый .apk asset из release во внешнее files-хранилище.
 *  5. Запрашиваем разрешение на установку из неизвестных источников, если
 *     ещё не выдано (Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES на API 26+).
 *  6. Открываем системный диалог установки через Intent.ACTION_VIEW +
 *     FileProvider content:// Uri. Пользователь подтверждает установку.
 *
 * Ограничения:
 *  - Полностью «тихо» в фоне обновить нельзя — Android 13+ требует
 *    явный системный диалог установки.
 *  - Работает только если на GitHub есть release с загруженным .apk asset.
 *    Если вы публикуете только исходники (без APK в release) — кнопка
 *    покажет «обновлений нет», даже если новая версия в master.
 *  - GitHub API без токена: 60 запросов в час на IP — для одной кнопки
 *    более чем достаточно.
 *
 * Используем только стандартные java.net (HttpURLConnection), org.json и
 * androidx.core (FileProvider) — без новых зависимостей. Запросы идут в
 * фоновом потоке (Thread), UI-обновления через Handler(mainLooper).
 *
 * ФИКС (audit, CRITICAL утечка памяти): activity и hintView раньше
 * захватывались лямбдой фонового Thread сильной ссылкой. Если пользователь
 * закрывал окно во время сетевого запроса (до 70с — connect 10с + read 60с),
 * Activity не могла быть собрана GC — типичная утечка Activity со всем
 * деревом View. Теперь Activity хранится через WeakReference: закрытое
 * окно собирается GC немедленно, фоновый поток продолжает работу и
 * проверяет isAlive() перед любым UI-обновлением.
 *
 * ФИКС (audit, CRITICAL логика): sameVersion() раньше использовал
 * t.contains(cur) — подстроковое сравнение давало ложное «совпадает»
 * для версий-префиксов (current="0.5", tag="v0.55" → contains →
 * «та же версия», обновление не предлагалось). Теперь числовое
 * сравнение SemVer-компонентов: (major, minor, patch).
 *
 * ФИКС (audit, HIGH краш на уничтоженной Activity): AlertDialog.Builder
 * и startActivity на isFinishing/isDestroyed Activity роняли приложение
 * (BadTokenException / WindowManagerLeaked). Теперь каждый UI-вызов
 * проходит через guard isAlive(), в крайнем случае тихо выходим.
 *
 * ФИКС (audit, HIGH атомарность APK): раньше httpDownload писал прямо в
 * target — обрыв связи оставлял недокачанный .apk, и следующий «Обновить»
 * мог установить ПОРЧЕННЫЙ файл. Теперь пишем в target.tmp, затем
 * atomic renameTo(target) — на диске всегда либо целый старый, либо целый
 * новый файл, но никогда не полу-файл.
 *
 * ФИКС (audit, MEDIUM накопление APK): каждый release с новым именем
 * файла оставлял старый .apk в externalFilesDir навсегда. Теперь перед
 * загрузкой cleanupOldApks() удаляет все .apk кроме (опционально)
 * сохраняемого — копим только актуальный.
 *
 * ФИКС (audit, MEDIUM rate-limit): GitHub API без токена — 60 запросов/час.
 * Раньше 403-й ответ молча трактовался как «нет связи». Теперь проверяем
 * X-RateLimit-Remaining и показываем пользователю внятное сообщение.
 *
 * ФИКС (audit, MEDIUM локаль): String.format без явной локали в ru_RU
 * давал «1,5 МБ» (запятая) — некорректный разделитель для технической
 * строки. Теперь Locale.US — всегда точка.
 */
object Updater {

    private const val TAG = "Updater"

    // Репозиторий для проверки release. Если форкаете — поменяйте owner/repo.
    private const val GITHUB_OWNER = "Exeqtr-RED"
    private const val GITHUB_REPO = "AutoClicker"
    private const val API_LATEST =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    // Скачиваем только asset с типом application/vnd.android.package-archive
    // или с расширением .apk. Берём ПЕРВЫЙ подходящий (в вашем релизном
    // workflow обычно один APK: «Sekira Cliker.apk»).
    // Timeout: connect 10s, read 60s (большой APK на медленном интернете).
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 60_000

    private val mainHandler = Handler(Looper.getMainLooper())

    // ============================================================
    // Публичный API
    // ============================================================

    /**
     * Проверяет последний release на GitHub. Если версия новее текущей —
     * показывает диалог и в колбэке onUserConfirm скачивает APK и открывает
     * системный установщик. Всё в фоне; UI-мутации через Handler(mainLooper).
     *
     * @param activity нужна для показа AlertDialog и запуска Intent'ов.
     *                 Хранится через WeakReference — закрытое окно не
     *                 удерживается фоновым потоком (ФИКС audit CRITICAL).
     * @param hintView TextView для живой обратной связи (статус проверки).
     *                 Может быть null — тогда только Toast (через Activity).
     */
    fun checkForUpdate(activity: Activity, hintView: android.widget.TextView? = null) {
        val cur = currentVersionName(activity)
        setHint(hintView, "Проверяю обновления (сейчас: v$cur)…")

        // ФИКС (audit, CRITICAL утечка): WeakReference на activity — фоновый
        // поток живёт до 70с, за это время пользователь может закрыть окно.
        val activityRef = WeakReference(activity)
        val hintRef = WeakReference(hintView)

        Thread {
            try {
                val resp = httpGetJson(API_LATEST)
                when (resp) {
                    null -> {
                        // httpGetJson вернул null — либо сеть, либо 403 rate-limit.
                        // Различаем по последнему известному rateLimitState.
                        if (rateLimitExhausted) {
                            mainToast(activityRef, "GitHub API: лимит 60 запросов/час исчерпан. Подождите или используйте VPN.")
                            setHint(hintRef, "Лимит GitHub API исчерпан — попробуйте через час")
                        } else {
                            mainToast(activityRef, "Не удалось связаться с GitHub. Проверьте интернет.")
                            setHint(hintRef, "Проверка не удалась — попробуйте позже")
                        }
                        return@Thread
                    }
                }

                val tag = resp.optString("tag_name", "")   // например "v0.35_AlfaTest"
                val name = resp.optString("name", tag)      // заголовок release
                val htmlUrl = resp.optString("html_url",
                    "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases")
                val body = resp.optString("body", "")      // описание release

                if (tag.isEmpty()) {
                    mainToast(activityRef, "На GitHub нет опубликованных релизов.")
                    setHint(hintRef, "Релизов нет — обновление вручную через GitHub")
                    return@Thread
                }

                // ФИКС (audit, CRITICAL логика): compareVersions возвращает:
                //   < 0 — текущая версия СТАРШЕ тега (что-то не так, не предлагаем)
                //   = 0 — версии совпадают (обновление не нужно)
                //   > 0 — тег НОВЕЕ текущей (обновление есть)
                val cmp = compareVersions(cur, tag)
                val isNewer = cmp > 0

                if (!isNewer) {
                    mainToast(activityRef, "Установлена последняя версия (v$cur).")
                    setHint(hintRef, "Установлена последняя версия: v$cur")
                    return@Thread
                }

                // Найдено обновление — ищем .apk asset
                val assets = resp.optJSONArray("assets") ?: run {
                    setHint(hintRef, "Найден release $tag, но без APK — открываю GitHub")
                    openUrl(activityRef, htmlUrl)
                    return@Thread
                }

                var apkUrl: String? = null
                var apkName: String? = null
                var apkSize: Long = 0
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val an = a.optString("name", "")
                    val ct = a.optString("content_type", "")
                    val url = a.optString("browser_download_url", "")
                    val size = a.optLong("size", 0)
                    val isApk = an.lowercase().endsWith(".apk") ||
                        ct.contains("android.package") ||
                        ct.contains("vnd.android")
                    if (isApk && url.isNotEmpty()) {
                        apkUrl = url; apkName = an; apkSize = size
                        break
                    }
                }

                if (apkUrl == null) {
                    setHint(hintRef, "Release $tag без APK-файла — открываю GitHub")
                    openUrl(activityRef, htmlUrl)
                    return@Thread
                }

                // Диалог: «Найдено обновление vX.Y.Z (N МБ). Скачать и установить?»
                val sizeStr = if (apkSize > 0) formatSize(apkSize) else "?"
                val message = buildString {
                    append("Текущая версия: v$cur\n")
                    append("Новая версия: $tag\n")
                    append("Размер: $sizeStr\n\n")
                    if (body.isNotEmpty()) {
                        // Описание release обрезаем до 500 символов — длинные
                        // логи диффов не нужны в диалоге.
                        val preview = if (body.length > 500) body.substring(0, 500) + "…" else body
                        append(preview)
                        append("\n\n")
                    }
                    append("Скачать APK и открыть системный установщик?")
                }

                val apkUrlFinal = apkUrl
                val apkNameFinal = apkName
                mainHandler.post {
                    val act = activityRef.get()
                    // ФИКС (audit, HIGH краш на уничтоженной Activity):
                    // AlertDialog.Builder на isFinishing/isDestroyed окне
                    // бросает BadTokenException. Guard гарантирует, что
                    // диалог создаётся только на живом окне.
                    if (!isAlive(act)) return@post
                    AlertDialog.Builder(act!!)
                        .setTitle("Найдено обновление: $name")
                        .setMessage(message)
                        .setPositiveButton("Скачать и установить") { _, _ ->
                            setHint(hintRef, "Скачиваю $apkNameFinal ($sizeStr)…")
                            downloadAndInstall(activityRef, apkUrlFinal, apkNameFinal, hintRef)
                        }
                        .setNegativeButton("Открыть в браузере") { _, _ ->
                            openUrl(activityRef, htmlUrl)
                        }
                        .setNeutralButton("Отмена", null)
                        .show()
                }
            } catch (e: Exception) {
                mainToast(activityRef, "Ошибка проверки: ${e.message}")
                setHint(hintRef, "Ошибка: ${e.message}")
            }
        }.start()
    }

    // ============================================================
    // Скачивание + установка
    // ============================================================

    /**
     * Скачивает APK во externalFilesDir, затем открывает системный установщик.
     * Перед установкой проверяет/запрашивает REQUEST_INSTALL_PACKAGES.
     *
     * ФИКС (audit, CRITICAL утечка): принимает WeakReference<Activity>,
     * не сильную ссылку. Фоновый поток живёт до 70с (timeout), за это время
     * окно могло быть закрыто — без WeakReference утекала бы вся Activity.
     */
    private fun downloadAndInstall(
        activityRef: WeakReference<Activity>,
        apkUrl: String,
        apkName: String,
        hintRef: WeakReference<android.widget.TextView?>
    ) {
        Thread {
            try {
                val act = activityRef.get()
                if (!isAlive(act)) return@Thread
                val target = File(act!!.getExternalFilesDir(null), apkName)

                // ФИКС (audit, MEDIUM накопление APK): перед загрузкой нового
                // удаляем все старые .apk в externalFilesDir — иначе каждый
                // release с новым именем файла оставлял бы предыдущий навсегда.
                // keepFile = null → чистим ВСЕ .apk, новый ещё не создан.
                cleanupOldApks(target.parentFile, keepFile = null)

                // Прогресс загрузки: сообщаем каждые ~5% или 100 КБ
                setHint(hintRef, "Скачиваю $apkName…")
                httpDownload(apkUrl, target) { read, total ->
                    // total может быть -1 (Content-Length неизвестен) —
                    // в этом случае показываем только прочитанный объём.
                    val pct = if (total > 0) {
                        val p = (read * 100 / total).toInt().coerceIn(0, 100)
                        " — $p%"
                    } else ""
                    setHint(hintRef, "Скачиваю $apkName$pct · ${formatSize(read)}")
                }

                setHint(hintRef,
                    "Скачано: ${formatSize(target.length())}. Запускаю установщик…")

                mainHandler.post {
                    val a = activityRef.get()
                    if (!isAlive(a)) return@post
                    installApk(a!!, target)
                }
            } catch (e: Exception) {
                mainToast(activityRef, "Не удалось скачать: ${e.message}")
                setHint(hintRef, "Скачивание не удалось: ${e.message}")
            }
        }.start()
    }

    /**
     * Открывает системный диалог установки APK. На Android 8+ (API 26+)
     * требует разрешения на установку из неизвестных источников — если его
     * нет, отправляет пользователя в системные настройки с запросом.
     *
     * ФИКС (audit, HIGH краш): вызывается только из mainHandler.post с
     * guard isAlive(), но дублируем проверку — на случай прямого вызова
     * из других мест в будущем.
     */
    private fun installApk(activity: Activity, apkFile: File) {
        if (!isAlive(activity)) return
        if (!apkFile.exists() || apkFile.length() == 0L) {
            android.widget.Toast.makeText(
                activity, "APK файл пуст или не существует",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        // Android 8+ (API 26): проверяем разрешение на установку из
        // неизвестных источников. Правильный API — canRequestPackageInstalls()
        // (canPackageBeInstalled не существует в PackageManager).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            // canRequestPackageInstalls доступен с API 26 (Build.VERSION_CODES.O).
            // Точный путь запроса: Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
            // (на API < 26 разрешение не нужно — система просто спросит при установке).
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                activity.startActivity(intent)
                android.widget.Toast.makeText(
                    activity,
                    "Разрешите установку из этого источника, затем снова нажмите «Обновить приложение»",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            } catch (e: Exception) {
                // Если настройка недоступна — попробуем старый путь (ACTION_INSTALL_PACKAGE)
            }
        }

        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                activity, "Не удалось открыть установщик: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    // ============================================================
    // HTTP helpers
    // ============================================================

    // ФИКС (audit, MEDIUM rate-limit): флаг, что последний 403 был из-за
    // исчерпания лимита. Сбрасывается в начале каждого запроса, ставится
    // в httpGetJson при X-RateLimit-Remaining: 0.
    @Volatile
    private var rateLimitExhausted: Boolean = false

    private fun httpGetJson(url: String): JSONObject? {
        rateLimitExhausted = false
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AutoClicker-Updater")
        }
        try {
            val code = conn.responseCode
            // ФИКС (audit, MEDIUM rate-limit): GitHub возвращает 403 при
            // исчерпании лимита 60 запросов/час без токена. Заголовок
            // X-RateLimit-Remaining: 0 подтверждает причину.
            if (code == 403) {
                val remaining = conn.getHeaderField("X-RateLimit-Remaining")
                if (remaining == "0") {
                    rateLimitExhausted = true
                }
                return null
            }
            if (code != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Скачивает файл в [target] с прогрессом.
     *
     * ФИКС (audit, HIGH атомарность): пишет в target.tmp, затем atomic
     * renameTo(target). Прерывание (сеть упала, пользователь убил приложение)
     * оставляет только .tmp-файл — основной target остаётся либо старым
     * целым, либо новым целым, но никогда не полу-скачанным. tmp-файл
     * чистится в finally.
     *
     * @param onProgress (bytesRead, totalBytes) — totalBytes = -1 если
     *                   сервер не передал Content-Length. Вызывается в
     *                   фоновом потоке, не чаще ~10 раз/сек.
     */
    private fun httpDownload(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        // Подстраховка от прошлого прерванного скачивания
        if (tmp.exists()) tmp.delete()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "AutoClicker-Updater")
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != 200) {
                throw RuntimeException("HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong  // -1 если неизвестен
            var lastReportAt = 0L
            var read = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        read += n
                        // Троттлинг прогресса: не чаще 100 мс — иначе
                        // setHint на каждом чанке (8 КБ) переполнял бы
                        // очередь mainHandler на быстрых сетях.
                        val now = System.currentTimeMillis()
                        if (now - lastReportAt > 100 || n < buf.size) {
                            lastReportAt = now
                            onProgress(read, total)
                        }
                    }
                }
            }
            // Атомарный rename: на одной ФС — атомарная операция ОС.
            // Если target уже существует — renameTo его перезапишет.
            if (!tmp.renameTo(target)) {
                // renameTo может упасть на некоторых ФС (cross-device).
                // Fallback: копируем + удаляем tmp.
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } finally {
            // Чистим tmp в любом случае — успеха или неудачи
            if (tmp.exists()) tmp.delete()
            conn.disconnect()
        }
    }

    /**
     * ФИКС (audit, MEDIUM накопление APK): удаляет все .apk файлы в [dir]
     * кроме [keepFile] (если не null). Вызывается перед загрузкой нового
     * APK — старые релизы не копятся в externalFilesDir вечно.
     */
    private fun cleanupOldApks(dir: File?, keepFile: File?) {
        if (dir == null || !dir.isDirectory) return
        runCatching {
            dir.listFiles { f ->
                f.isFile && f.name.endsWith(".apk", ignoreCase = true) &&
                    f.absolutePath != keepFile?.absolutePath
            }?.forEach { it.delete() }
        }
    }

    // ============================================================
    // UI helpers (все устойчивы к уничтоженной Activity)
    // ============================================================

    /**
     * ФИКС (audit, CRITICAL утечка + HIGH краш): единственный guard для
     * всех UI-операций. Возвращает true только если Activity ещё жива
     * (не finishing и не destroyed). Все вызовы AlertDialog/Toast/
     * startActivity проходят через эту проверку.
     */
    private fun isAlive(activity: Activity?): Boolean {
        if (activity == null) return false
        return !activity.isFinishing && !activity.isDestroyed
    }

    private fun setHint(
        hintRef: WeakReference<android.widget.TextView?>,
        text: String
    ) {
        val v = hintRef.get() ?: return
        mainHandler.post { v.text = text }
    }

    private fun mainToast(activityRef: WeakReference<Activity>, text: String) {
        mainHandler.post {
            val ctx = activityRef.get() ?: return@post
            // Toast безопасен на уничтоженной Activity (использует Application
            // context для окна), но guard всё равно нужен — иначе на
            // isDestroyed окне часть прошивок роняет окно Toast.
            if (!isAlive(ctx)) return@post
            android.widget.Toast.makeText(
                ctx, text, android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openUrl(activityRef: WeakReference<Activity>, url: String) {
        mainHandler.post {
            val act = activityRef.get()
            if (!isAlive(act)) return@post
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                act!!.startActivity(intent)
            } catch (e: Exception) {
                mainToast(activityRef, "Не удалось открыть браузер: ${e.message}")
            }
        }
    }

    // ============================================================
    // Version comparison — ФИКС (audit, CRITICAL логика)
    // ============================================================

    /**
     * Сравнивает текущую версию приложения [current] (например, "2.7") с тегом
     * release [tag] (например, "v0.35_AlfaTest").
     *
     * Возвращает:
     *  - > 0 — тег НОВЕЕ текущей (есть обновление)
     *  - = 0 — версии совпадают
     *  - < 0 — тег СТАРШЕ текущей (что-то не так, обновление не предлагаем)
     *
     * Алгоритм:
     *  1. Нормализуем обе строки — убираем ведущие 'v'/'V', берём только
     *     начальную числовую часть до первого не-цифрового/не-точечного
     *     символа (v0.35_AlfaTest → "0.35", 2.7 → "2.7").
     *  2. Разбиваем по '.', парсим компоненты как Int (пропуская не-числовые).
     *  3. Сравниваем как tuple (major, minor, patch), дополняя нулями до
     *     одинаковой длины.
     *
     * ФИКС (audit, CRITICAL): старая sameVersion использовала t.contains(cur)
     * — подстроковое сравнение. Это давало ложное «совпадает» для:
     *   - current="0.5", tag="v0.55" → "0.55".contains("0.5") = true → НЕТ обновления ✗
     *   - current="0.3", tag="v0.35_AlfaTest" → contains → НЕТ обновления ✗
     *   - current="1", tag="v10" → contains → НЕТ обновления ✗
     * Пользователь пропускал реальные обновления.
     */
    private fun compareVersions(current: String, tag: String): Int {
        if (current.isBlank() || tag.isBlank()) return 0
        val curParts = parseVersionParts(current)
        val tagParts = parseVersionParts(tag)
        // Дополняем нулями до одинаковой длины: (2,7) vs (0,35) → (2,7,0) vs (0,35,0)
        val len = maxOf(curParts.size, tagParts.size)
        for (i in 0 until len) {
            val c = curParts.getOrElse(i) { 0 }
            val t = tagParts.getOrElse(i) { 0 }
            if (t != c) return t - c
        }
        return 0
    }

    /**
     * Извлекает числовые компоненты версии из строки.
     * "2.7" → [2, 7]
     * "v0.35_AlfaTest" → [0, 35]
     * "v1.2.3-beta.1" → [1, 2, 3]
     * "" → []
     */
    private fun parseVersionParts(s: String): List<Int> {
        // Нормализация: убираем ведущие 'v'/'V', оставляем только начальный
        // сегмент из цифр и точек до первого иного символа.
        val trimmed = s.trimStart('v', 'V').trim()
        if (trimmed.isEmpty()) return emptyList()
        // Берём префикс до первого НЕ (цифра/точка)
        val end = trimmed.indexOfFirst { ch -> !(ch.isDigit() || ch == '.') }
        val numericPart = if (end < 0) trimmed else trimmed.substring(0, end)
        return numericPart.split('.')
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull() }
    }

    // ============================================================
    // Misc helpers
    // ============================================================

    /** Текущая версия приложения (из BuildConfig — генерируется AGP). */
    private fun currentVersionName(ctx: Context): String =
        try {
            val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.versionName ?: "?"
            else @Suppress("DEPRECATION") pkg.versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }

    /** Текущий versionCode — для сравнения числом (если в release нет
     *  читаемого versionName, можно сравнивать по code). */
    @Suppress("unused")
    private fun currentVersionCode(ctx: Context): Long =
        try {
            val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode
            else @Suppress("DEPRECATION") pkg.versionCode.toLong()
        } catch (e: Exception) {
            0L
        }

    /**
     * ФИКС (audit, MEDIUM локаль): String.format без явной локали в ru_RU
     * давал «1,5 МБ» (запятая) — некорректный десятичный разделитель для
     * технической строки. Locale.US гарантирует точку.
     */
    private fun formatSize(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes Б"
            bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
            else -> String.format(Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0))
        }
}
