package com.example.autoclicker

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

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
            openAccessibilitySettings()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionButtons()
        // ФИКС (v26): панель возвращается при каждом открытии приложения.
        // Служба Accessibility больше не выключается кнопкой ✕ панели
        // (раньше она вызывала disableSelf() — из-за этого права Accessibility
        // приходилось выдавать заново при каждом запуске). Если панель была
        // свёрнута в пузырь — разворачивать не будем, пузырь останется
        if (ClickService.instance != null) ClickService.instance?.ensureOverlays()
    }

    // ФИКС: статус обеих кнопок обновляется при каждом возврате на экран
    // (в т.ч. из системных настроек оверлея/Accessibility) — включённая
    // служба/выданное разрешение подсвечивают кнопку зелёным
    private fun refreshPermissionButtons() {
        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk = isClickServiceEnabled()

        findViewById<Button>(R.id.btnOverlay).setBackgroundResource(
            if (overlayOk) R.drawable.btn_secondary_green else R.drawable.btn_secondary
        )
        findViewById<Button>(R.id.btnA11y).setBackgroundResource(
            if (a11yOk) R.drawable.btn_secondary_green else R.drawable.btn_secondary
        )
    }

    private fun isClickServiceEnabled(): Boolean {
        // ФИКС (v36): ручной парсинг строки Settings.Secure.ENABLED_ACCESSIBILITY_
        // SERVICES заменён на официальный API AccessibilityManager — не зависит
        // от формата хранения строки в конкретной прошивке (регистр, пробелы,
        // компонент с сокращённым именем класса) и корректен для текущего
        // пользователя/профиля
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

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openSettings(mode: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("mode", mode))
    }
}
