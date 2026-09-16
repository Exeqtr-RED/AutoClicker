package com.example.autoclicker

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var mode: String
    private var recordedActions = mutableListOf<PresetAction>()

    // точка, выбранная через кнопку «Выберите место для тапа»
    private var pickedX = -1
    private var pickedY = -1

    private lateinit var etName: EditText
    private lateinit var etDelay: EditText
    private lateinit var etHours: EditText
    private lateinit var etMinutes: EditText
    private lateinit var etSeconds: EditText
    private lateinit var etCycles: EditText
    private lateinit var rgTiming: RadioGroup
    private lateinit var llDuration: View
    private lateinit var llCycles: View
    private lateinit var llRecord: View
    private lateinit var tvRecCount: TextView
    private lateinit var tvPickedPoint: TextView
    private lateinit var llPresets: LinearLayout
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        mode = intent.getStringExtra("mode") ?: "ST"

        etName = findViewById(R.id.etName)
        etDelay = findViewById(R.id.etDelay)
        etHours = findViewById(R.id.etHours)
        etMinutes = findViewById(R.id.etMinutes)
        etSeconds = findViewById(R.id.etSeconds)
        etCycles = findViewById(R.id.etCycles)
        rgTiming = findViewById(R.id.rgTiming)
        llDuration = findViewById(R.id.llDuration)
        llCycles = findViewById(R.id.llCycles)
        llRecord = findViewById(R.id.llRecord)
        tvRecCount = findViewById(R.id.tvRecCount)
        tvPickedPoint = findViewById(R.id.tvPickedPoint)
        llPresets = findViewById(R.id.llPresets)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        findViewById<TextView>(R.id.tvTitle).text =
            if (mode == "ST") "Single Target — настройки"
            else "Multi Target + Swipe — настройки"

        // Кнопка записи и счётчик — только для MTWS
        if (mode == "MTWS") llRecord.visibility = View.VISIBLE

        // Кнопка выбора точки и подпись — только для ST
        val btnPickPoint = findViewById<Button>(R.id.btnPickPoint)
        if (mode == "ST") {
            btnPickPoint.visibility = View.VISIBLE
            tvPickedPoint.visibility = View.VISIBLE
        } else {
            btnPickPoint.visibility = View.GONE
            tvPickedPoint.visibility = View.GONE
        }

        rgTiming.setOnCheckedChangeListener { _, checkedId ->
            llDuration.visibility = if (checkedId == R.id.rbDuration) View.VISIBLE else View.GONE
            llCycles.visibility = if (checkedId == R.id.rbCycles) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { savePreset() }
        findViewById<Button>(R.id.btnRecord).setOnClickListener { startRecording() }
        findViewById<Button>(R.id.btnClearActions).setOnClickListener {
            recordedActions.clear()
            updateRecCount()
        }
        btnStart.setOnClickListener { startPlayback() }
        btnStop.setOnClickListener { stopPlayback() }
        btnPickPoint.setOnClickListener { pickPoint() }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        refreshPresets()
        updateRecCount()
        updatePickedPointLabel()
    }

    override fun onResume() {
        super.onResume()
        syncPlaybackState()
    }

    // ---------- Выбор точки тапа ----------

    private fun pickPoint() {
        val svc: ClickService = ClickService.instance ?: run {
            toast("Служба Accessibility не включена")
            return
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            toast("Нет разрешения на оверлей")
            return
        }
        svc.startPickPoint { x: Int, y: Int ->
            pickedX = x
            pickedY = y
            runOnUiThread {
                updatePickedPointLabel()
                val i = Intent(this, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    putExtra("mode", mode)
                }
                startActivity(i)
                toast("Точка: $x, $y")
            }
        }
        moveTaskToBack(true)
    }

    private fun updatePickedPointLabel() {
        tvPickedPoint.text = if (pickedX >= 0 && pickedY >= 0)
            "Точка: $pickedX, $pickedY"
        else
            "Точка не выбрана"
    }

    // ---------- Пресеты ----------

    private fun refreshPresets() {
        llPresets.removeAllViews()
        val list = PresetStorage.getByMode(this, mode)
        if (list.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Пока нет пресетов"
                setPadding(16, 16, 16, 16)
            }
            llPresets.addView(tv)
            return
        }
        list.forEach { preset ->
            val btn = Button(this).apply {
                text = preset.name
                setOnClickListener { loadPreset(preset) }
                setOnLongClickListener {
                    confirmDelete(preset)
                    true
                }
            }
            llPresets.addView(btn)
        }
    }

    private fun loadPreset(p: Preset) {
        etName.setText(p.name)
        etDelay.setText(p.delayMs.toString())
        when (p.timingMode) {
            "INFINITE" -> rgTiming.check(R.id.rbInfinite)
            "DURATION" -> {
                rgTiming.check(R.id.rbDuration)
                val total = p.durationSec
                etHours.setText((total / 3600).toString())
                etMinutes.setText(((total % 3600) / 60).toString())
                etSeconds.setText((total % 60).toString())
            }
            "CYCLES" -> {
                rgTiming.check(R.id.rbCycles)
                etCycles.setText(p.cycles.toString())
            }
        }

        // В ST-режиме: если в пресете сохранён тап — это и есть выбранная точка
        if (mode == "ST" && p.actions.isNotEmpty()) {
            pickedX = p.actions[0].x1
            pickedY = p.actions[0].y1
        } else {
            pickedX = -1
            pickedY = -1
        }
        updatePickedPointLabel()

        recordedActions = p.actions.toMutableList()
        updateRecCount()
    }

    private fun confirmDelete(p: Preset) {
        AlertDialog.Builder(this)
            .setTitle("Удалить пресет?")
            .setMessage(p.name)
            .setPositiveButton("Удалить") { _, _ ->
                PresetStorage.delete(this, p.name, p.mode)
                refreshPresets()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun buildPreset(): Preset? {
        val name = etName.text.toString().trim()
        if (name.isEmpty()) {
            toast("Введите название пресета")
            return null
        }
        val timingMode = when (rgTiming.checkedRadioButtonId) {
            R.id.rbDuration -> "DURATION"
            R.id.rbCycles -> "CYCLES"
            else -> "INFINITE"
        }
        val durationSec = if (timingMode == "DURATION")
            (etHours.text.toString().toLongOrNull() ?: 0L) * 3600 +
                    (etMinutes.text.toString().toLongOrNull() ?: 0L) * 60 +
                    (etSeconds.text.toString().toLongOrNull() ?: 0L)
        else 0L
        val cycles = if (timingMode == "CYCLES")
            etCycles.text.toString().toIntOrNull() ?: 1
        else 1
        val delay = etDelay.text.toString().toLongOrNull() ?: 100L

        val actions: List<PresetAction> = when {
            mode == "MTWS" -> recordedActions.toList()
            pickedX >= 0 && pickedY >= 0 ->
                listOf(PresetAction("tap", pickedX, pickedY, 0, 0, 0L))
            else -> emptyList()
        }

        return Preset(
            name = name,
            mode = mode,
            timingMode = timingMode,
            durationSec = durationSec,
            cycles = cycles,
            delayMs = delay,
            actions = actions
        )
    }

    private fun savePreset() {
        val p = buildPreset() ?: return
        PresetStorage.save(this, p)
        refreshPresets()
        toast("Пресет сохранён: ${p.name}")
    }

    // ---------- Запись ----------

    private fun startRecording() {
        val svc = ClickService.instance
        if (svc == null) {
            toast("Служба Accessibility не включена")
            return
        }
        toast("Запись началась. Тапайте/свайпайте по экрану. Стоп — кнопка REC сверху.")
        svc.startRecording { actions ->
            runOnUiThread {
                recordedActions = actions.toMutableList()
                updateRecCount()
                toast("Записано: ${actions.size}")
            }
        }
        moveTaskToBack(true)
    }

    private fun updateRecCount() {
        tvRecCount.text = "Записано действий: ${recordedActions.size}"
    }

    // ---------- Playback ----------

    private fun startPlayback() {
        val svc = ClickService.instance
        if (svc == null) {
            toast("Служба Accessibility не включена")
            return
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            toast("Нет разрешения на оверлей")
            return
        }
        val p = buildPreset() ?: return
        svc.startPlayback(p)
        syncPlaybackState()
        toast("Запущено: ${p.name}")
        moveTaskToBack(true)
    }

    private fun stopPlayback() {
        ClickService.instance?.stopPlayback()
        syncPlaybackState()
    }

    private fun syncPlaybackState() {
        val playing = ClickService.instance?.isPlaying() == true
        btnStart.visibility = if (playing) View.GONE else View.VISIBLE
        btnStop.visibility = if (playing) View.VISIBLE else View.GONE
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}