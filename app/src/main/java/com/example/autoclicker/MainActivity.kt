package com.example.autoclicker

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // ФИКС (v37): повторная проверка статуса после возврата на экран.
    // Система подключает службу АСИНХРОННО: если пользователь включил
    // тумблер и мгновенно вернулся, одиночная проверка в onResume может
    // увидеть «ещё не запущена» и показать ложную тревогу. Две отложенные
    // проверки дают статусу устаканиться
    private val recheckHandler = Handler(Looper.getMainLooper())
    private val recheckRunnable = Runnable { refreshPermissionButtons() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnSingle).setOnClickListener {
            openSettings("ST")
        }
        findViewById<Button>(R.id.btnMulti).setOnClickListener {
            openSettings("MTWS")
        }

        // ФИКС: проверка Build.VERSION.SDK_INT >= M удалена —
        // minSdk = 24, canDrawOverlays() доступна на всех поддерживаемых API
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            } else {
                Toast.makeText(this, "Разрешение уже выдано", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            // ФИКС (v37): если служба включена в настройках, но НЕ запущена —
            // подсказываем, что именно нужно сделать (раньше кнопка молча
            // вела в настройки, где тумблер уже включён — тупик)
            if (!isClickServiceRunning() && isClickServiceEnabledInSettings()) {
                Toast.makeText(
                    this,
                    "Служба включена, но не запущена — выключите и снова включите тумблер Sekira Cliker",
                    Toast.LENGTH_LONG
                ).show()
            }
            openAccessibilitySettings()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionButtons()
        // ФИКС (v37): отложенные повторные проверки — покрывают и гонку с
        // асинхронным подключением службы, и медленные устройства
        recheckHandler.removeCallbacks(recheckRunnable)
        recheckHandler.postDelayed(recheckRunnable, 1500)
        recheckHandler.postDelayed(recheckRunnable, 3000)
        // ФИКС (v26): панель возвращается при каждом открытии приложения.
        // Служба Accessibility больше не выключается кнопкой ✕ панели
        // (раньше она вызывала disableSelf() — из-за этого права Accessibility
        // приходилось выдавать заново при каждом запуске). Если панель была
        // свёрнута в пузырь — разворачивать не будем, пузырь останется
        if (ClickService.instance != null) ClickService.instance?.ensureOverlays()
    }

    override fun onDestroy() {
        super.onDestroy()
        // ФИКС (v37): без removeCallbacks Runnable удерживал бы Activity
        // (утечка) и мог бы обновить UI после уничтожения
        recheckHandler.removeCallbacks(recheckRunnable)
    }

    // ФИКС: статус обеих кнопок обновляется при каждом возврате на экран
    // (в т.ч. из системных настроек оверлея/Accessibility) — включённая
    // служба/выданное разрешение подсвечивают кнопку зелёным
    // ФИКС (v37): три состояния вместо двух. Раньше кнопка была либо
    // зелёной («запущена»), либо серой («всё остальное»), и состояние
    // «включена в настройках, но не запущена» выглядело как «выключена» —
    // пользователь не понимал, почему плавающая панель не появляется.
    // Теперь под кнопкой текст, объясняющий фактическое состояние
    private fun refreshPermissionButtons() {
        val overlayOk = Settings.canDrawOverlays(this)
        val running = isClickServiceRunning()
        // Служба может числиться включённой в системных настройках, но
        // фактически не работать (см. комментарий к isClickServiceEnabledInSettings)
        val enabledInSettings = running || isClickServiceEnabledInSettings()

        findViewById<Button>(R.id.btnOverlay).setBackgroundResource(
            if (overlayOk) R.drawable.btn_secondary_green else R.drawable.btn_secondary
        )
        // Зелёным подсвечиваем только РЕАЛЬНО работающую службу:
        // зелёная кнопка = панель должна быть на экране
        findViewById<Button>(R.id.btnA11y).setBackgroundResource(
            if (running) R.drawable.btn_secondary_green else R.drawable.btn_secondary
        )

        findViewById<TextView>(R.id.tvA11yHint).apply {
            text = when {
                running && overlayOk ->
                    "Служба работает (Accessibility активна)"
                running ->
                    "Служба работает, но без разрешения «Поверх других окон» плавающая панель не появится — нажмите «Разрешение на оверлей»"
                enabledInSettings ->
                    "Служба включена в настройках, но НЕ запущена: выключите и снова включите тумблер Sekira Cliker в настройках Accessibility. Если не поможет — перезагрузите телефон"
                else ->
                    "Служба выключена — нажмите кнопку выше и включите Sekira Cliker в списке служб"
            }
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (running && overlayOk) R.color.text_secondary else R.color.accent_orange
                )
            )
        }
    }

    private fun isClickServiceRunning(): Boolean {
        // ФИКС (v36): официальный API AccessibilityManager вместо ручного
        // парсинга строки настроек — не зависит от формата хранения в
        // конкретной прошивке (регистр, пробелы, сокращённое имя класса)
        // и корректен для текущего пользователя/профиля.
        // ВАЖНО (v37): этот API возвращает только службы, которые СЕЙЧАС
        // запущены (система держит их подключёнными). Служба с включённым
        // тумблером, но не перезапущенная после убийства процесса,
        // обновления APK или принудительной остановки, в список НЕ
        // попадает — это не сбой проверки, а реальное состояние
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        // FEEDBACK_ALL_MASK — «все службы независимо от типа обратной связи»
        return am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { info ->
            val si = info.resolveInfo?.serviceInfo ?: return@any false
            si.packageName == packageName && si.name == ClickService::class.java.name
        }
    }

    /**
     * ФИКС (v37): «включена» и «запущена» — разные вещи. После принудительной
     * остановки («Остановить» в информации о приложении), сворачивания задачи
     * в недавних на некоторых прошивках или установки обновления APK Android
     * оставляет тумблер службы ВКЛЮЧЁННЫМ, но саму службу не перезапускает —
     * до ручного выключения/включения тумблера или перезагрузки. В итоге
     * тумблер включён, а плавающей панели нет.
     *
     * Эта функция читает исходную строку
     * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES — служба числится в ней
     * включённой независимо от того, запущена ли она фактически. Сравнение с
     * isClickServiceRunning() различает «включена и работает» и «включена,
     * но лежит».
     *
     * Ручной парсинг здесь осознан (в отличие от проверки «запущена», где
     * используется AccessibilityManager): формат строки — «пакет/класс»,
     * компоненты разделены «:», класс может быть записан полностью
     * («pkg/com.example.autoclicker.ClickService») или сокращённо
     * («pkg/.ClickService») — учитываем оба варианта.
     */
    private fun isClickServiceEnabledInSettings(): Boolean {
        val raw = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cls = ClickService::class.java.name          // …autoclicker.ClickService
        val shortCls = cls.substringAfterLast('.')       // ClickService
        return raw.split(':').any { entry ->
            val pkg = entry.substringBefore('/').trim()
            val name = entry.substringAfter('/', "").trim()
            pkg.equals(packageName, ignoreCase = true) &&
                (name.equals(cls, ignoreCase = true) || name.equals(".$shortCls", ignoreCase = true))
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openSettings(mode: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("mode", mode))
    }
}
