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
import java.net.HttpURLConnection
import java.net.URL

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
     * Проверяет последний release на GitHub. Если версия новее текущей —
     * показывает диалог и в колбэке onUserConfirm скачивает APK и открывает
     * системный установщик. Всё в фоне; UI-мутации через Handler(mainLooper).
     *
     * @param activity нужна для показа AlertDialog и запуска Intent'ов
     * @param hintView TextView для живой обратной связи (статус проверки).
     *                 Может быть null — тогда только Toast (через Activity).
     */
    fun checkForUpdate(activity: Activity, hintView: android.widget.TextView? = null) {
        val cur = currentVersionName(activity)
        setHint(hintView, activity, "Проверяю обновления (сейчас: v$cur)…")

        Thread {
            try {
                val resp = httpGetJson(API_LATEST) ?: run {
                    mainToast(activity, "Не удалось связаться с GitHub. Проверьте интернет.")
                    setHint(hintView, activity, "Проверка не удалась — попробуйте позже")
                    return@Thread
                }

                val tag = resp.optString("tag_name", "")   // например "v0.35_AlfaTest"
                val name = resp.optString("name", tag)      // заголовок release
                val htmlUrl = resp.optString("html_url",
                    "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases")
                val body = resp.optString("body", "")      // описание release

                if (tag.isEmpty()) {
                    mainToast(activity, "На GitHub нет опубликованных релизов.")
                    setHint(hintView, activity, "Релизов нет — обновление вручную через GitHub")
                    return@Thread
                }

                // Сравнение версий: простой подход — если tag != текущей версии,
                // считаем что новее. Точное сравнение по SemVer тут избыточно
                // (теги у вас произвольные: v0.34_AlfaTest, v0.35_AlfaTest и т.д.).
                val isNewer = !sameVersion(cur, tag)

                if (!isNewer) {
                    mainToast(activity, "Установлена последняя версия (v$cur).")
                    setHint(hintView, activity, "Установлена последняя версия: v$cur")
                    return@Thread
                }

                // Найдено обновление — ищем .apk asset
                val assets = resp.optJSONArray("assets") ?: run {
                    setHint(hintView, activity,
                        "Найден release $tag, но без APK — открываю GitHub")
                    openUrl(activity, htmlUrl)
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
                    setHint(hintView, activity,
                        "Release $tag без APK-файла — открываю GitHub")
                    openUrl(activity, htmlUrl)
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

                Handler(Looper.getMainLooper()).post {
                    AlertDialog.Builder(activity)
                        .setTitle("Найдено обновление: $name")
                        .setMessage(message)
                        .setPositiveButton("Скачать и установить") { _, _ ->
                            setHint(hintView, activity,
                                "Скачиваю $apkName ($sizeStr)…")
                            downloadAndInstall(activity, apkUrl!!, apkName!!, hintView)
                        }
                        .setNegativeButton("Открыть в браузере") { _, _ ->
                            openUrl(activity, htmlUrl)
                        }
                        .setNeutralButton("Отмена", null)
                        .show()
                }
            } catch (e: Exception) {
                mainToast(activity, "Ошибка проверки: ${e.message}")
                setHint(hintView, activity, "Ошибка: ${e.message}")
            }
        }.start()
    }

    /**
     * Скачивает APK во externalFilesDir, затем открывает системный установщик.
     * Перед установкой проверяет/запрашивает REQUEST_INSTALL_PACKAGES.
     */
    private fun downloadAndInstall(
        activity: Activity,
        apkUrl: String,
        apkName: String,
        hintView: android.widget.TextView?
    ) {
        Thread {
            try {
                val target = File(activity.getExternalFilesDir(null), apkName)
                setHint(hintView, activity, "Скачиваю $apkName…")
                httpDownload(apkUrl, target)
                setHint(hintView, activity,
                    "Скачано: ${formatSize(target.length())}. Запускаю установщик…")

                Handler(Looper.getMainLooper()).post {
                    installApk(activity, target)
                }
            } catch (e: Exception) {
                mainToast(activity, "Не удалось скачать: ${e.message}")
                setHint(hintView, activity, "Скачивание не удалось: ${e.message}")
            }
        }.start()
    }

    /**
     * Открывает системный диалог установки APK. На Android 8+ (API 26+)
     * требует разрешения на установку из неизвестных источников — если его
     * нет, отправляет пользователя в системные настройки с запросом.
     */
    private fun installApk(activity: Activity, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            android.widget.Toast.makeText(
                activity, "APK файл пуст или не существует",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        // Android 8+ (API 26): проверяем разрешение на установку
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canPackageBeInstalled(packageName = activity.packageName)
        ) {
            // canPackageBeInstalled доступен только с API 26, на 26-27 сигнатура
            // может отличаться — используем общий fallback через UnknownSources.
            // Точный путь: Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES.
            // (на API < 26 разрешение не нужно — система просто спросит при установке)
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

    // ─── HTTP helpers ───────────────────────────────────────────────────────

    private fun httpGetJson(url: String): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AutoClicker-Updater")
        }
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpDownload(url: String, target: File) {
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
            conn.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // ─── UI helpers ─────────────────────────────────────────────────────────

    private fun setHint(
        hintView: android.widget.TextView?,
        ctx: Context,
        text: String
    ) {
        if (hintView == null) return
        Handler(Looper.getMainLooper()).post { hintView.text = text }
    }

    private fun mainToast(ctx: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                ctx, text, android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openUrl(activity: Activity, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (e: Exception) {
            mainToast(activity, "Не удалось открыть браузер: ${e.message}")
        }
    }

    // ─── version comparison ─────────────────────────────────────────────────

    /**
     * Сравнивает текущую версию (например, "2.7") с тегом release
     * (например, "v0.35_AlfaTest"). Считаем, что версии совпадают, если
     * тег содержит текущую версию как подстроку — это простое правило
     * хорошо работает для вашего тег-стиля (v0.34_AlfaTest, v0.35_AlfaTest...):
     * текущая версия 2.7 не содержится в теге → обновление есть.
     *
     * Когда текущая версия будет совпадать с тегом (например, текущая 0.35,
     * тег v0.35_AlfaTest) — обновления нет.
     */
    private fun sameVersion(current: String, tag: String): Boolean {
        if (current.isBlank() || tag.isBlank()) return false
        // Нормализуем — убираем ведущий 'v', всё в нижний регистр
        val cur = current.trimStart('v', 'V').lowercase()
        val t = tag.trimStart('v', 'V').lowercase()
        if (cur.isEmpty()) return false
        // Если тег содержит текущую версию как подстроку — считаем что совпадает
        return t.contains(cur)
    }

    private fun formatSize(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes Б"
            bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
            else -> String.format("%.1f МБ", bytes / (1024.0 * 1024.0))
        }
}
