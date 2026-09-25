package com.example.autoclicker

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val STATE_PICKED_X = "pickedX"
        private const val STATE_PICKED_Y = "pickedY"
        private const val STATE_RECORDED = "recordedActions"
        // ФИКС (v18): черновик редактора — переживает УНИЧТОЖЕНИЕ активности
        // системой, пока окно свёрнуто (выбор точки / запись). Поля ввода
        // восстанавливаются сами только при повороте; при агрессивной
        // зачистке фона (MIUI, «Не сохранять действия») окно открывается
        // пустым — черновик это чинит
        private const val DRAFT_PREFS = "editor_draft_v1"
        private const val MIN_DELAY_MS = 20L
        // ФИЧА: потолок поднят с 60 с до суток — общий предел для поля в мс (ST)
        // и для полей мин+сек у действий MTWS (до 1440 мин = 24 ч)
        private const val MAX_DELAY_MS = 24L * 60 * 60_000
        // ФИЧА: потолок периодичности запуска (минуты+секунды) — сутки
        private const val MAX_REPEAT_INTERVAL_MS = 24L * 60 * 60_000
        // ФИЧА: потолок величины разброса задержки ST (мс) — 1 минута.
        // Разброс больше не имеет смысла: интервал [delay − jitter; delay + jitter]
        private const val MAX_JITTER_MS = 60_000L
    }

    private lateinit var mode: String
    private var recordedActions = mutableListOf<PresetAction>()

    // точка, выбранная через кнопку «Выберите место для тапа»
    private var pickedX = -1
    private var pickedY = -1

    private lateinit var etName: EditText
    // ST: задержка между кликами в МИЛЛИСЕКУНДАХ (Task 24: пользователь вернул
    // ST на мс; мин+сек — только в MTWS). В MTWS эта строка скрыта
    private lateinit var etDelay: EditText
    private lateinit var etHours: EditText
    private lateinit var etMinutes: EditText
    private lateinit var etSeconds: EditText
    private lateinit var etCycles: EditText
    private lateinit var rgTiming: RadioGroup
    private lateinit var llDuration: View
    private lateinit var llCycles: View
    private lateinit var llRecord: View
    private lateinit var llActions: LinearLayout
    private lateinit var llDelayRow: View
    private lateinit var llRepeatRow: View
    private lateinit var tvRecCount: TextView
    // ФИЧА: периодичность запуска MTWS — два поля (минуты и секунды)
    private lateinit var etRepeatMin: EditText
    private lateinit var etRepeatSec: EditText
    private lateinit var tvPickedPoint: TextView
    private lateinit var llPresets: LinearLayout
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    // ФИЧА: разброс задержки ST — чекбокс + поле величины в мс.
    // Включён → реальная задержка случайно из [delay − jitter; delay + jitter]
    private lateinit var llJitterRow: View
    private lateinit var cbJitter: CheckBox
    private lateinit var etJitter: EditText
    // ФИЧА: кнопка «Назад» внизу экрана — видна только когда окно
    // приложения НЕ во весь экран (в полноэкранном режиме системный
    // жест/кнопка «Назад» доступны, в плавающем окне — нет)
    private lateinit var btnBack: Button

    // ФИКС (утечка памяти): сильные ссылки на колбэки держит АКТИВНОСТЬ,
    // служба хранит только WeakReference. Пока окно живо — колбэк будет
    // доставлен; когда окно уничтожено — колбэк собирается GC и активность
    // не удерживается службой
    private var pendingPickCb: ((Int, Int) -> Unit)? = null
    private var pendingRecordCb: ((List<PresetAction>) -> Unit)? = null

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
        llActions = findViewById(R.id.llActions)
        llDelayRow = findViewById(R.id.llDelayRow)
        llRepeatRow = findViewById(R.id.llRepeatRow)
        tvRecCount = findViewById(R.id.tvRecCount)
        etRepeatMin = findViewById(R.id.etRepeatMin)
        etRepeatSec = findViewById(R.id.etRepeatSec)
        tvPickedPoint = findViewById(R.id.tvPickedPoint)
        llPresets = findViewById(R.id.llPresets)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        llJitterRow = findViewById(R.id.llJitterRow)
        cbJitter = findViewById(R.id.cbJitter)
        etJitter = findViewById(R.id.etJitter)
        btnBack = findViewById(R.id.btnBack)

        findViewById<TextView>(R.id.tvTitle).text =
            if (mode == "ST") "Single Target — настройки"
            else "Multi Target + Swipe — настройки"

        // Блок записи, список действий и периодичность — только для MTWS;
        // глобальная «Задержка» — только для ST (в MTWS задержка задаётся
        // индивидуально у каждого записанного действия)
        if (mode == "MTWS") {
            llRecord.visibility = View.VISIBLE
            llRepeatRow.visibility = View.VISIBLE
            llDelayRow.visibility = View.GONE
            // ФИЧА: блок «Разброс» — только для ST: в MTWS ритм задаётся
            // индивидуальными задержками каждого действия
            llJitterRow.visibility = View.GONE
        } else {
            llRecord.visibility = View.GONE
            llRepeatRow.visibility = View.GONE
            llDelayRow.visibility = View.VISIBLE
            llJitterRow.visibility = View.VISIBLE
        }

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
            rebuildActionsList()
        }
        btnStart.setOnClickListener { startPlayback() }
        btnStop.setOnClickListener { stopPlayback() }
        btnPickPoint.setOnClickListener { pickPoint() }

        // ФИЧА: чекбокс «Разброс» открывает/блокирует поле величины;
        // «Назад» возвращает в предыдущее меню (главный экран)
        cbJitter.setOnCheckedChangeListener { _, _ -> updateJitterFieldState() }
        updateJitterFieldState()
        btnBack.setOnClickListener { finish() }

        refreshPresets()
        updateRecCount()
        rebuildActionsList()
        updatePickedPointLabel()

        // ФИКС (v18): свежее окно после уничтожения в фоне — восстанавливаем
        // черновик (все введённые поля + выбранная точка). При повороте
        // savedInstanceState != null: состояние восстанавливает система
        if (savedInstanceState == null) restoreDraft()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // ФИКС: правки задержек из полей ввода попадают в recordedActions
        // до сериализации — иначе при повороте теряются
        commitDelaysFromEditors()
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
        // ФИЧА: видимость кнопки «Назад» пересчитывается при каждом возврате
        // на экран (пользователь мог развернуть окно на весь экран или,
        // наоборот, вывести из полноэкранного режима). post{} — ждём
        // завершения раскладки: сразу после onResume decorView ещё 0×0,
        // и сравнивать его с экраном рано
        window.decorView.post { updateBackButtonVisibility() }

        // ФИКС: если пользователь выдал разрешение на оверлей, вернувшись из
        // настроек системы, панель и крестик службы появятся сразу,
        // без переподключения службы
        ClickService.instance?.ensureOverlays()

        // ФИКС: если окно пересоздали, пока шла запись/выбор точки, колбэк
        // доставить было некому — забираем результаты из службы здесь
        ClickService.instance?.consumePickResult()?.let { (x, y) ->
            pickedX = x
            pickedY = y
            updatePickedPointLabel()
            toast("Точка: $x, $y")
        }
        ClickService.instance?.consumeRecordResult()?.let { actions ->
            recordedActions = actions.toMutableList()
            updateRecCount()
            rebuildActionsList()
            toast("Записано: ${actions.size} — отредактируйте задержки")
        }
        syncPlaybackState()
    }

    override fun onDestroy() {
        super.onDestroy()
        // ФИКС (утечка памяти): окно уничтожается — снимаем сильные ссылки на
        // колбэки; WeakReference в службе очистится, утечки активности нет
        pendingPickCb = null
        pendingRecordCb = null
        // ФИКС (v18): явный выход («Назад») — черновик не нужен;
        // уничтожение СИСТЕМОЙ в фоне — сохраняем введённое для восстановления
        if (isFinishing) clearDraft() else writeDraft()
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
        val cb: (Int, Int) -> Unit = { x: Int, y: Int ->
            pendingPickCb = null
            pickedX = x
            pickedY = y
            runOnUiThread {
                updatePickedPointLabel()
                // ФИКС (v13): ПОДЪЁМ ОКНА ИЗ КОЛБЭКА УБРАН. Раньше здесь был
                // startActivity(REORDER_TO_FRONT) — но активность в этот момент
                // в фоне, и современные Android (10+, особенно MIUI/Samsung)
                // молча блокируют такой запуск: приложение оставалось свёрнутым,
                // пресет нельзя было сохранить. Теперь окно поднимает СЛУЖБА
                // (ClickService.bringBackSettingsEditor — NEW_TASK из контекста
                // службы с SYSTEM_ALERT_WINDOW, та же рабочая схема, что при
                // возврате после записи MTWS). Здесь остаётся только обновить
                // подпись выбранной точки и показать тост
                toast("Точка: $x, $y")
            }
        }
        pendingPickCb = cb
        val started = svc.startPickPoint(cb, mode)
        // ФИКС: честная обратная связь — раньше служба могла молча отказать
        if (!started) {
            toast("Нельзя выбирать точку во время записи/воспроизведения")
            return
        }
        // ФИКС (v18): запоминаем задачу — служба поднимёт её ЦЕЛИКОМ,
        // и пишем черновик — окно может быть
        // уничтожено системой, пока мы в фоне
        svc.noteEditorTask(taskId)
        writeDraft()
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
                // ФИКС (Task 25): явный цвет — без него на светлой системной
                // теме текст получался чёрным на тёмном фоне
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
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
        // ФИЧА: восстановление разброса задержки: чекбокс включён, только если
        // в пресете сохранена ненулевая величина; поле всегда показывает
        // сохранённое значение (в выключенном состоянии — серое)
        cbJitter.isChecked = p.delayJitterMs > 0L
        etJitter.setText(p.delayJitterMs.toString())
        updateJitterFieldState()
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
        // ФИЧА: периодичность хранится в мс, показывается как минуты + секунды
        etRepeatMin.setText((p.repeatIntervalMs / 60_000L).toString())
        etRepeatSec.setText(((p.repeatIntervalMs % 60_000L) / 1000L).toString())
        updateRecCount()
        rebuildActionsList()
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

        // ФИКС: задержка ограничена — 0 мс превращало воспроизведение в busy-poll.
        // ST: ввод в МИЛЛИСЕКУНДАХ (Task 24 — возвращено по просьбе пользователя)
        val delay = (etDelay.text.toString().toLongOrNull() ?: 100L)
            .coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)

        // ФИЧА: разброс задержки ST — используется только при включённом
        // чекбоксе; итоговый интервал [delay − jitter; delay + jitter],
        // служба дополнительно прижимает нижнюю границу к MIN_DELAY_MS
        val jitter = if (mode == "ST" && cbJitter.isChecked)
            (etJitter.text.toString().toLongOrNull() ?: 0L).coerceIn(0L, MAX_JITTER_MS)
        else 0L

        val actions: List<PresetAction> = when {
            mode == "MTWS" -> {
                // ФИКС: коммитим задержки из полей ввода до сборки пресета —
                // иначе сохранится старый ритм, а не то, что ви́дит пользователь
                commitDelaysFromEditors()
                recordedActions.toList()
            }
            pickedX >= 0 && pickedY >= 0 ->
                listOf(PresetAction("tap", pickedX, pickedY, 0, 0, 0L))
            else -> {
                // ФИКС (v18): пресет без явной точки раньше сохранялся с ПУСТЫМ
                // actions, и воспроизведение тапало по ТЕКУЩЕЙ позиции крестика —
                // глобальному состоянию службы, ОДИНАКОВОМУ для всех таких
                // пресетов («одна позиция на все пресеты»). Теперь позиция
                // прицела ЗАПЕКАЕТСЯ в пресет при сохранении — у каждого
                // пресета своя точка
                val (cx, cy) = ClickService.instance?.currentCrosshairPoint()
                    ?: (-1 to -1)
                if (cx >= 0 && cy >= 0) {
                    pickedX = cx
                    pickedY = cy
                    updatePickedPointLabel()
                    toast("Точка не выбрана — записана позиция прицела: $cx, $cy")
                    listOf(PresetAction("tap", cx, cy, 0, 0, 0L))
                } else emptyList()
            }
        }

        // ФИЧА: периодичность запуска (MTWS) — пауза между полными прогонами
        // пресета, вводится как минуты + секунды. 0 = начинать следующий
        // прогон сразу
        val repeatIntervalMs = if (mode == "MTWS") {
            val min = etRepeatMin.text.toString().toLongOrNull() ?: 0L
            val sec = etRepeatSec.text.toString().toLongOrNull() ?: 0L
            (min * 60_000L + sec * 1000L).coerceIn(0L, MAX_REPEAT_INTERVAL_MS)
        } else 0L

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
            delayJitterMs = jitter,
            repeatIntervalMs = repeatIntervalMs,
            actions = actions
        )
    }

    private fun savePreset() {
        val p = buildPreset() ?: return
        PresetStorage.save(this, p)
        refreshPresets()
        // ФИКС (v18): черновик сохранённого пресета больше не нужен
        clearDraft()
        toast("Пресет сохранён: ${p.name}")
    }

    // ---------- Черновик редактора (переживает уничтожение окна в фоне) ----------

    private fun draftKey(): String = "draft_$mode"

    /** Снимок всех введённых полей + выбранной точки в SharedPreferences.
     *  commit(), а не apply(): снимок обязан пережить даже гибель процесса */
    private fun writeDraft() {
        runCatching {
            val arr = JSONArray()
            recordedActions.forEach { arr.put(it.toJson()) }
            val o = org.json.JSONObject()
            o.put("name", etName.text.toString())
            o.put("delay", etDelay.text.toString())
            o.put("jitterOn", cbJitter.isChecked)
            o.put("jitter", etJitter.text.toString())
            o.put(
                "timing", when (rgTiming.checkedRadioButtonId) {
                    R.id.rbDuration -> "DURATION"
                    R.id.rbCycles -> "CYCLES"
                    else -> "INFINITE"
                }
            )
            o.put("hours", etHours.text.toString())
            o.put("minutes", etMinutes.text.toString())
            o.put("seconds", etSeconds.text.toString())
            o.put("cycles", etCycles.text.toString())
            o.put("repeatMin", etRepeatMin.text.toString())
            o.put("repeatSec", etRepeatSec.text.toString())
            o.put("pickedX", pickedX)
            o.put("pickedY", pickedY)
            o.put("actions", arr.toString())
            val ed = getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE).edit()
            ed.putString(draftKey(), o.toString())
            ed.commit()
        }
    }

    /** Применяет черновик к полям. Вызывается ТОЛЬКО для свежего окна
     *  (savedInstanceState == null): при повороте состояние восстанавливает
     *  сама система. Возвращает true, если черновик найден и применён.
     *  Всё в runCatching: битый/чужой черновик не должен ронять редактор */
    private fun restoreDraft(): Boolean {
        val s = runCatching {
            getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE)
                .getString(draftKey(), null)
        }.getOrNull() ?: return false
        val o = runCatching { org.json.JSONObject(s) }.getOrNull() ?: return false
        return runCatching {
            etName.setText(o.optString("name"))
            etDelay.setText(o.optString("delay", "100"))
            cbJitter.isChecked = o.optBoolean("jitterOn", false)
            etJitter.setText(o.optString("jitter", "0"))
            updateJitterFieldState()
            when (o.optString("timing", "INFINITE")) {
                "DURATION" -> rgTiming.check(R.id.rbDuration)
                "CYCLES" -> rgTiming.check(R.id.rbCycles)
                else -> rgTiming.check(R.id.rbInfinite)
            }
            etHours.setText(o.optString("hours", "0"))
            etMinutes.setText(o.optString("minutes", "0"))
            etSeconds.setText(o.optString("seconds", "0"))
            etCycles.setText(o.optString("cycles", "1"))
            etRepeatMin.setText(o.optString("repeatMin", "0"))
            etRepeatSec.setText(o.optString("repeatSec", "0"))
            pickedX = o.optInt("pickedX", -1)
            pickedY = o.optInt("pickedY", -1)
            updatePickedPointLabel()
            recordedActions = parseRecordedActions(o.optString("actions"))
            updateRecCount()
            rebuildActionsList()
            true
        }.getOrDefault(false)
    }

    private fun clearDraft() {
        runCatching {
            getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE)
                .edit().remove(draftKey()).commit()
        }
    }

    // ---------- Запись ----------

    private fun startRecording() {
        val svc = ClickService.instance
        if (svc == null) {
            toast("Служба Accessibility не включена")
            return
        }
        val cb: (List<PresetAction>) -> Unit = { actions ->
            pendingRecordCb = null
            runOnUiThread {
                recordedActions = actions.toMutableList()
                updateRecCount()
                rebuildActionsList()
                toast("Записано: ${actions.size}. Окно снова открыто — отредактируйте задержки")
            }
        }
        pendingRecordCb = cb
        val started = svc.startRecording(cb)
        // ФИКС: тост только при реальном старте — раньше «Запись началась»
        // показывалась даже если служба молча отказала (уже идёт запись/воспроизведение)
        if (started) {
            toast("Запись началась. Сделайте тапы и свайпы, затем нажмите «Остановить» внизу экрана")
            // ФИКС (v18): задача для возврата + черновик на случай уничтожения окна
            svc.noteEditorTask(taskId)
            writeDraft()
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

    // ---------- Редактируемый список записанных действий ----------

    /** Пересобирает список действий: описание + поле задержки + удаление */
    private fun rebuildActionsList() {
        llActions.removeAllViews()
        recordedActions.forEachIndexed { i, a -> llActions.addView(makeActionRow(i, a)) }
    }

    private fun makeActionRow(index: Int, a: PresetAction): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val tvDesc = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = if (a.type == "tap")
                "${index + 1}. Тап (${a.x1}, ${a.y1})"
            else
                "${index + 1}. Свайп (${a.x1},${a.y1}) → (${a.x2},${a.y2})"
            textSize = 13f
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
        row.addView(tvDesc)

        // ФИЧА: индивидуальная задержка действия — два поля (минуты и секунды).
        // Поля помечены тегами: commitDelaysFromEditors ищет их по тегу,
        // а не по позиции, поэтому раскладка строки может свободно меняться
        // ВАЖНО: параметр назван hintText, а не hint — иначе он затеняет
        // свойство EditText.hint, и «hint = hint» внутри apply даёт
        // ошибку компиляции "'val' cannot be reassigned"
        fun delayField(tag: String, hintText: String, maxLen: Int, value: Long): EditText =
            EditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dp(48), LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
                inputType = InputType.TYPE_CLASS_NUMBER
                gravity = Gravity.CENTER
                hint = hintText
                setText(value.toString())
                textSize = 13f
                filters = arrayOf(android.text.InputFilter.LengthFilter(maxLen))
                setBackgroundResource(R.drawable.bg_input)
                setPadding(dp(6), dp(8), dp(6), dp(8))
                // ФИКС (Task 25): «чёрные цифры на тёмном фоне не видны» —
                // у программного поля не было явного цвета: при светлой
                // системной теме Material давал чёрный текст. Теперь текст
                // и подсказка всегда светлые, независимо от темы системы
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(context, R.color.text_hint))
                this.tag = tag
            }

        // v13: maxLen=4 у «Мин» — сутки = 1440 мин не влезают в 3 цифры;
        // иначе записанная длинная пауза при показе обрезалась до 999
        val etMin = delayField("min", "Мин", 4, a.delayMs / 60_000L)
        row.addView(etMin)

        val tvMin = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
            text = "мин"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }
        row.addView(tvMin)

        val etSec = delayField("sec", "Сек", 2, (a.delayMs % 60_000L) / 1000L)
        row.addView(etSec)

        val tvSec = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4); marginEnd = dp(2) }
            text = "сек"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }
        row.addView(tvSec)

        val btnDel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            text = "✕"
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.accent_red))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                recordedActions.removeAt(index)
                updateRecCount()
                rebuildActionsList()
            }
        }
        row.addView(btnDel)
        return row
    }

    /** Коммитит задержки из полей ввода в recordedActions (перед сохранением/запуском).
     *  Поля ищутся по тегам min/sec — привязка к позициям детей не нужна */
    private fun commitDelaysFromEditors() {
        if (mode != "MTWS") return
        val n = minOf(llActions.childCount, recordedActions.size)
        for (i in 0 until n) {
            val row = llActions.getChildAt(i) as? LinearLayout ?: continue
            val etMin = row.findViewWithTag<EditText>("min") ?: continue
            val etSec = row.findViewWithTag<EditText>("sec") ?: continue
            val min = etMin.text.toString().toLongOrNull() ?: 0L
            val sec = etSec.text.toString().toLongOrNull() ?: 0L
            val v = min * 60_000L + sec * 1000L
            recordedActions[i] = recordedActions[i].copy(delayMs = v.coerceIn(0L, MAX_DELAY_MS))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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

    // ---------- Разброс задержки (только ST) ----------

    /** ФИЧА: поле величины разброса активно только при включённом чекбоксе.
     *  Явные цвета текста (урок Task 25): выключенное поле с системной
     *  темой Material рисуется чёрным и «исчезает» на тёмном фоне */
    private fun updateJitterFieldState() {
        val enabled = cbJitter.isChecked
        etJitter.isEnabled = enabled
        etJitter.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.text_primary else R.color.text_hint
            )
        )
        etJitter.alpha = if (enabled) 1f else 0.55f
    }

    // ---------- Кнопка «Назад» (видна только не во весь экран) ----------

    /** ФИЧА: кнопка «Назад» нужна там, где системного «Назада» может не быть
     *  (плавающее окно, сплит-скрин, эмуляторы). В полноэкранном режиме —
     *  скрыта, чтобы не занимать место */
    private fun updateBackButtonVisibility() {
        btnBack.visibility = if (isWindowFullscreen()) View.GONE else View.VISIBLE
    }

    /** Окно считается полноэкранным, если закрывает ≥90% ширины и ≥85% высоты
     *  физического экрана (запас на статус-бар и панель навигации).
     *  Ошибка определения трактуется как «во весь экран» — кнопка не мешает */
    private fun isWindowFullscreen(): Boolean {
        return try {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val realW: Int
            val realH: Int
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val b = wm.maximumWindowMetrics.bounds
                realW = b.width()
                realH = b.height()
            } else {
                @Suppress("DEPRECATION")
                val m = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(m)
                realW = m.widthPixels
                realH = m.heightPixels
            }
            window.decorView.width >= realW * 0.9f &&
                    window.decorView.height >= realH * 0.85f
        } catch (e: Exception) {
            true
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
