package com.example.autoclicker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionButtons()
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
        val expected = "$packageName/${ClickService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        // Список включённых служб хранится как «пакет/класс:пакет/класс:…»
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun openSettings(mode: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("mode", mode))
    }
}
