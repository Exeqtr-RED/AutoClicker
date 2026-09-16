package com.example.autoclicker

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val STATE_PICKED_X = "pickedX"
        private const val STATE_PICKED_Y = "pickedY"
        private const val STATE_RECORDED = "recordedActions"
        private const val MIN_DELAY_MS = 20L
        private const val MAX_DELAY_MS = 60_000L
    }

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

        // ФИКС: выбранная точка и записанные действия переживают поворот экрана
        // и уничтожение активности системой (раньше терялись молча)
        if (savedInstanceState != null) {
            pickedX = savedInstanceState.getInt(STATE_PICKED_X, -1)
            pickedY = savedInstanceState.getInt(STATE_PICKED_Y, -1)
            recordedActions = parseRecordedActions(savedInstanceState.getString(STATE_RECORDED))
        }

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PICKED_X, pickedX)
        outState.putInt(STATE_PICKED_Y, pickedY)
        val arr = JSONArray()
        recordedActions.forEach { arr.put(it.toJson()) }
        outState.putString(STATE_RECORDED, arr.toString())
    }

    private fun parseRecordedActions(json: String?): MutableList<PresetAction> {
        if (json.isNullOrEmpty()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { PresetAction.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList()).toMutableList()
    }

    override fun onResume() {
        super.onResume()
        // ФИКС: если пользователь выдал разрешение на оверлей, вернувшись из
        // настроек системы, панель и крестик службы появятся сразу,
        // без переподключения службы
        ClickService.instance?.ensureOverlays()
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
        val started = svc.startPickPoint { x: Int, y: Int ->
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
        // ФИКС: честная обратная связь — раньше служба могла молча отказать
        if (!started) {
            toast("Нельзя выбирать точку во время записи/воспроизведения")
            return
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
            etCycles.text.toString().toIntOrNull() ?: 0
        else 1

        // ФИКС: валидация тайминга — раньше DURATION=0 / CYCLES=0 приводили
        // к мгновенной тихой остановке без единого действия
        if (timingMode == "DURATION" && durationSec <= 0L) {
            toast("Укажите длительность больше нуля")
            return null
        }
        if (timingMode == "CYCLES" && cycles < 1) {
            toast("Количество циклов должно быть не меньше 1")
            return null
        }

        // ФИКС: задержка ограничена — 0 мс превращало воспроизведение в busy-poll
        val delay = (etDelay.text.toString().toLongOrNull() ?: 100L)
            .coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)

        val actions: List<PresetAction> = when {
            mode == "MTWS" -> recordedActions.toList()
            pickedX >= 0 && pickedY >= 0 ->
                listOf(PresetAction("tap", pickedX, pickedY, 0, 0, 0L))
            else -> emptyList() // ST без точки — тап по текущей позиции крестика
        }

        // ФИКС: пустой MTWS-пресет нельзя ни запустить, ни сохранить —
        // раньше он сохранялся, а запуск молча ничего не делал
        if (mode == "MTWS" && actions.isEmpty()) {
            toast("Сначала запишите макрос")
            return null
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
        val started = svc.startRecording { actions ->
            runOnUiThread {
                recordedActions = actions.toMutableList()
                updateRecCount()
                toast("Записано: ${actions.size}")
            }
        }
        // ФИКС: тост только при реальном старте — раньше «Запись началась»
        // показывалась даже если служба молча отказала (уже идёт запись/воспроизведение)
        if (started) {
            toast("Запись началась. Тапайте/свайпайте по экрану. Стоп — кнопка REC сверху.")
            moveTaskToBack(true)
        } else if (svc.isPlaying()) {
            toast("Сначала остановите воспроизведение")
        } else {
            toast("Запись уже идёт")
        }
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
        // ФИКС: тост «Запущено» только если playback реально стартовал —
        // раньше он показывался даже при молча отказавшем запуске
        if (svc.startPlayback(p)) {
            syncPlaybackState()
            toast("Запущено: ${p.name}")
            moveTaskToBack(true)
        } else {
            toast("Не удалось запустить — служба занята записью или воспроизведением")
        }
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
