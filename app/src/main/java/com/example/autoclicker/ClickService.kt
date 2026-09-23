package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.random.Random

class ClickService : AccessibilityService() {

    companion object {
        private const val TAG = "ClickService"

        // ФИКС (перф/приватность): отладочный лог выключен по умолчанию —
        // координаты каждого клика (до 50 строк/сек) не пишутся в logcat.
        // Для диагностики поставьте true.
        private const val LOG_DEBUG = false

        // ФИКС: состояние службы (последний пресет) переживает перезапуск службы
        private const val STATE_PREFS = "service_state"
        private const val KEY_LAST_PRESET = "last_preset"
        private const val KEY_CROSSHAIR_HIDDEN = "crosshair_hidden"

        // ФИКС: минимальная задержка между жестами — delay=0 превращал цикл в busy-poll
        private const val MIN_DELAY_MS = 20L

        // ФИЧА: потолок записанной паузы между действиями (мс) — случайная
        // пауза «сделать кофе» во время записи не превращается в 10 минут ожидания
        // ФИКС (v13): потолок паузы ПРИ ЗАПИСИ поднят с 60 с до суток —
        // раньше любая пауза длиннее минуты обрезалась до ровно 60_000 мс
        // (пользователь тапал с интервалом ~8 мин, записывалась 1 мин).
        // Согласовано с MAX_DELAY_MS (потолок редактора задержек)
        private const val MAX_REC_GAP_MS = 24L * 60 * 60_000

        // ФИЧА (живой предпросмотр записи): после отпускания пальца записанный
        // жест мгновенно воспроизводится на экране — список реально скроллится,
        // тапы реально нажимаются, и видно, куда попадут следующие действия.
        // Пауза перед воспроизведением нужна, чтобы система успела применить
        // FLAG_NOT_TOUCHABLE к оверлею записи, иначе инжектируемый жест
        // перехватится самим оверлеем (известная ловушка dispatchGesture).
        private const val REPLAY_DELAY_MS = 120L

        // ФИКС: MotionEvent.FLAG_IS_GENERATED_GESTURE — скрытая (@hide) константа
        // SDK, в публичном API её нет → компилятор даёт "Unresolved reference".
        // Объявляем значение сами: система ставит этот флаг (0x10) жестам,
        // внедрённым через AccessibilityService.dispatchGesture, значение
        // стабильно начиная с Android 9.
        private const val FLAG_IS_GENERATED_GESTURE = 0x00000010

        // ФИКС: сколько подряд отменённых жестов считаем «жесты блокируются»
        private const val MAX_CONSECUTIVE_CANCELS = 5

        // ------------------------------------------------------------
        // ФИЧА: анимация клика. Прицел на точке тапа сжимается и
        // возвращается — визуальный отклик каждого клика.
        // Окно помечено FLAG_NOT_TOUCHABLE, поэтому НЕ перехватывает
        // касания (ни реальные, ни инжектируемые dispatchGesture) и
        // не мешает кликам. Если на вашей прошивке жесты вдруг начнут
        // отменяться (появится тост «Жесты блокируются») — поставьте false.
        // ------------------------------------------------------------
        private const val CLICK_ANIMATION_ENABLED = true
        private const val CLICK_ANIM_DOWN_MS = 60L    // сжатие
        private const val CLICK_ANIM_UP_MS = 90L      // возврат
        private const val CLICK_ANIM_MIN_SCALE = 0.55f // насколько сжимается
        private const val CLICK_ANIM_MIN_ALPHA = 0.65f // лёгкое «прожатие» прозрачностью

        // ------------------------------------------------------------
        // ФИЧА (эргономика): случайный сдвиг точки клика в пикселях —
        // жесты не ложатся пиксель-в-пиксель (анти-детект, как в
        // Quick Touch / OP Auto Clicker). 0 — выключить.
        // ------------------------------------------------------------
        private const val RANDOM_OFFSET_PX = 10

        // ФИЧА: виброотклик на старт/стоп/паузу/удаление точки
        private const val HAPTIC_ENABLED = true

        @Volatile
        var instance: ClickService? = null
            private set
    }

    private lateinit var wm: WindowManager

    // Панель
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var tvStatus: TextView? = null
    private var toggleBtn: ImageButton? = null

    // ФИЧА: счётчик кликов/таймер на панели
    private var tvCounter: TextView? = null

    // ФИЧА: кнопка паузы на панели
    private var pauseBtnRef: ImageButton? = null

    // ФИЧА: кнопка списка пресетов и переключатель прицела на панели
    private var presetsBtnRef: Button? = null
    private var crosshairBtnRef: Button? = null

    // ФИЧА: прицел можно скрыть кнопкой панели; состояние переживает рестарт
    private var crosshairHidden = false

    // ФИЧА: оверлей-список пресетов (выбор прямо из плавающей панели)
    private var presetList: View? = null

    // Крестик
    private var crosshair: View? = null
    private var crosshairParams: WindowManager.LayoutParams? = null

    // Сохранённые координаты центра крестика (абсолютные, экранные)
    private var crosshairCenterX = 140f
    private var crosshairCenterY = 540f

    // ФИЧА: анимация клика — виртуальный прицел на точке тапа
    private var clickMarker: View? = null
    private var clickMarkerParams: WindowManager.LayoutParams? = null

    // ФИЧА: пузырь — панель, свёрнутая в круглую кнопку (как Assistive Touch)
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleIcon: ImageView? = null

    // ФИЧА: пауза воспроизведения (прогресс циклов не сбрасывается)
    @Volatile private var paused = false
    private var pauseStartMs = 0L

    // ФИЧА: счётчик завершённых кликов за сессию
    private var clickCount = 0

    // ФИЧА: нумерованные точки MTWS (драг — переместить, долгий тап — удалить)
    private class MtwsMarker(val view: View, val index: Int)
    private val mtwsMarkers = mutableListOf<MtwsMarker>()

    // Оверлеи
    private var recordOverlay: View? = null
    private var recordParams: WindowManager.LayoutParams? = null
    // ФИЧА: стоп-кнопка в ОТДЕЛЬНОМ окне — на время живого предпросмотра окно
    // записи получает FLAG_NOT_TOUCHABLE (чтобы пропускать инжектируемый жест
    // в приложение), и кнопка «Остановить» обязана оставаться кликабельной
    private var recordStopBtn: View? = null
    private var recordStopParams: WindowManager.LayoutParams? = null
    private val replayRunnable = Runnable { dispatchLiveReplay() }
    @Volatile private var replayInFlight = false
    private var pickOverlay: View? = null
    // ФИКС (утечка памяти): колбэк захватывает SettingsActivity, а служба
    // живёт дольше окна. Сильную ссылку держит активность (pendingPickCb),
    // служба — только WeakReference: уничтоженное окно больше не удерживается
    private var pickOnDoneRef: WeakReference<(Int, Int) -> Unit>? = null
    // ФИЧА (v14): режим редактора, из которого начали выбор точки (ST/MTWS) —
    // чтобы после тапа открыть окно настроек с той же вкладкой
    private var pickEditorMode: String = "ST"
    // ФИКС (v18): taskId задачи редактора — активность запоминает его перед
    // moveTaskToBack. Поднимаем ЗАДАЧУ целиком через moveTaskToFront:
    // сохраняется ОКОННЫЙ РЕЖИМ (плавающее окно остаётся плавающим), тогда
    // как startActivity на части прошивок открывает окно во весь экран
    private var editorTaskId: Int = -1
    // ФИКС: результат выбора точки переживает пересоздание окна настроек —
    // забирается в SettingsActivity.onResume() через consumePickResult()
    private var lastPickResult: Pair<Int, Int>? = null

    private val handler = Handler(Looper.getMainLooper())

    // Playback
    private var currentPreset: Preset? = null
    private var lastPreset: Preset? = null
    @Volatile private var playing = false
    private var actionIndex = 0
    private var cycleCount = 0
    private var startTimeMs = 0L
    @Volatile private var gestureInFlight = false
    private var consecutiveCancels = 0

    // Recording
    private var recording = false
    private var recordedActions = mutableListOf<PresetAction>()
    // ФИКС (утечка памяти): как и pickOnDoneRef — колбэк хранится через
    // WeakReference, сильную ссылку держит SettingsActivity
    private var onRecordDoneRef: WeakReference<(List<PresetAction>) -> Unit>? = null
    // ФИКС: результат записи переживает пересоздание окна настроек —
    // забирается в SettingsActivity.onResume() через consumeRecordResult()
    private var lastRecordResult: List<PresetAction>? = null
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    // ФИЧА: момент последнего записанного действия — для измерения реальных
    // пауз между действиями (потом редактируются в настройках MTWS)
    private var lastRecTime = 0L

    // ============================================================
    // Lifecycle
    // ============================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        lastPreset = restoreLastPreset()
        crosshairHidden = runCatching {
            getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CROSSHAIR_HIDDEN, false)
        }.getOrDefault(false)
        ensureOverlays()
    }

    /**
     * ФИКС (краш BadTokenException): оверлеи добавляются только при выданном
     * разрешении SYSTEM_ALERT_WINDOW. Раньше включение службы без разрешения
     * роняло приложение в showPanel()/showCrosshair().
     * Также вызывается из SettingsActivity.onResume() — когда пользователь
     * вернулся с экрана выдачи разрешения, панель появляется без
     * переподключения службы.
     */
    fun ensureOverlays(): Boolean {
        if (!::wm.isInitialized) return false
        if (!Settings.canDrawOverlays(this)) return false
        // ФИКС: если панель свёрнута в пузырь — не показываем её развёрнутой
        // копией поверх (раньше onResume окна настроек разворачивал панель,
        // оставляя пузырь висеть рядом)
        if (bubble == null) showPanel()
        // ФИЧА: прицел опционален — показываем, если пользователь его не скрыл.
        // Для MTWS-пресета крестик не нужен — его роль играют нумерованные точки
        // ФИКС: во время playback/записи крестик НЕ возвращаем — touchable-
        // оверлей блокирует dispatchGesture (раньше onResume окна настроек
        // во время ST-воспроизведения возвращал крестик и ломал клики)
        if (!playing && !recording && !crosshairHidden && lastPreset?.mode != "MTWS") {
            syncCrosshairToPreset()
            showCrosshair()
        }
        refreshMtwsMarkers()
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        if (::wm.isInitialized) {
            panel?.let { runCatching { wm.removeView(it) } }
            crosshair?.let { runCatching { wm.removeView(it) } }
            clickMarker?.let { runCatching { wm.removeView(it) } }
            bubble?.let { runCatching { wm.removeView(it) } }
            presetList?.let { runCatching { wm.removeView(it) } }
            recordOverlay?.let { runCatching { wm.removeView(it) } }
            // ФИЧА: отдельное окно стоп-кнопки оверлея записи — тоже чистим
            recordStopBtn?.let { runCatching { wm.removeView(it) } }
            pickOverlay?.let { runCatching { wm.removeView(it) } }
            hideMtwsMarkers()
        }
        // ФИКС (утечка памяти): колбэки и результаты — вместе с окнами
        pickOnDoneRef = null
        onRecordDoneRef = null
        pickEditorMode = "ST"
        editorTaskId = -1
        lastPickResult = null
        lastRecordResult = null
        panel = null; crosshair = null
        clickMarker = null; clickMarkerParams = null
        bubble = null; bubbleParams = null; bubbleIcon = null
        presetList = null
        recordOverlay = null; pickOverlay = null
        recordStopBtn = null; recordStopParams = null
        super.onDestroy()
    }

    fun isPlaying(): Boolean = playing
    fun isRecording(): Boolean = recording

    /**
     * ФИКС (утечка + потеря данных): результаты записи и выбора точки
     * забираются окном настроек в onResume(). Если окно было пересоздано,
     * пока шла запись/выбор точки, доставить колбэк некому — результат
     * дожидается здесь и не теряется.
     */
    fun consumePickResult(): Pair<Int, Int>? {
        val r = lastPickResult
        lastPickResult = null
        return r
    }

    /** ФИКС (v18): активность сообщает свой taskId перед сворачиванием —
     * служба поднимет ЗАДАЧУ целиком (moveTaskToFront), а не будет запускать
     * активность заново. Задача сохраняет оконный режим и размер окна */
    fun noteEditorTask(taskId: Int) {
        editorTaskId = taskId
    }

    /** ФИКС (v18): текущая позиция крестика для редактора. Раньше ST-пресет
     * без явной точки сохранялся с пустыми actions, и тик тапал по этой
     * глобальной позиции — ОДИНАКОВОЙ для всех таких пресетов. Теперь
     * редактор запекает её в пресет при сохранении */
    fun currentCrosshairPoint(): Pair<Int, Int> =
        crosshairCenterX.toInt() to crosshairCenterY.toInt()

    fun consumeRecordResult(): List<PresetAction>? {
        val r = lastRecordResult
        lastRecordResult = null
        return r
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    /**
     * ФИКС («клики не совпадают с выбранным местом»): TYPE_APPLICATION_OVERLAY
     * по умолчанию раскладывается в области НИЖЕ статус-бара — p.x/p.y были
     * координатами «полезной области», тогда как e.rawX/rawY (запись/выбор
     * точки) и GestureDescription (performTap/performSwipe) — АБСОЛЮТНЫЕ
     * экранные координаты. Смешение двух систем давало сдвиг ровно на высоту
     * статус-бара: перетаскиваемый крестик/маркеры сохранялись в пресет со
     * сдвигом и кликали выше видимого положения, зелёный маркер рисовался ниже
     * реального тапа. FLAG_LAYOUT_IN_SCREEN переводит x/y ВСЕХ оверлеев в
     * единую абсолютную систему (origin — левый верхний угол дисплея), общую
     * с rawX/rawY и dispatchGesture.
     */
    private fun overlayFlags(base: Int): Int =
        base or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun toast(s: String) {
        runCatching { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }

    /** Отладочный лог (см. LOG_DEBUG): inline — когда выключен, строки даже
     *  не собираются, ноль расходов на каждый тик */
    private inline fun dbg(msg: () -> String) {
        if (LOG_DEBUG) Log.d(TAG, msg())
    }

    /** ФИЧА: короткий клик-виброотклик (если включён HAPTIC_ENABLED) */
    private fun haptic() {
        if (!HAPTIC_ENABLED) return
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            else
                vib.vibrate(20L)
        }
    }

    // ============================================================
    // Панель
    // ============================================================

    private fun showPanel() {
        if (panel != null) return
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            overlayFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }
        panelParams = p
        // ФИКС (краш службы «keeps stopping»): ошибка inflate больше не роняет
        // процесс — пишем в лог и работаем без этого оверлея
        val v = runCatching { LayoutInflater.from(this).inflate(R.layout.panel, null) }
            .getOrElse { Log.e(TAG, "showPanel: inflate failed", it); return }
        panel = v
        if (runCatching { wm.addView(v, p) }.isFailure) {
            Log.e(TAG, "showPanel: addView failed")
            panel = null
            return
        }

        tvStatus = v.findViewById(R.id.tvStatus)
        toggleBtn = v.findViewById(R.id.toggleBtn)
        tvCounter = v.findViewById(R.id.tvCounter)
        pauseBtnRef = v.findViewById(R.id.pauseBtn)
        presetsBtnRef = v.findViewById(R.id.presetsBtn)
        crosshairBtnRef = v.findViewById(R.id.crosshairBtn)

        // ФИЧА: пауза — прогресс циклов не сбрасывается
        pauseBtnRef?.setOnClickListener { togglePause() }

        // ФИЧА: свернуть панель в пузырь
        v.findViewById<TextView>(R.id.minimizeBtn)?.setOnClickListener {
            haptic()
            collapseToBubble()
        }

        // ФИЧА: выбор пресета прямо из панели
        v.findViewById<Button>(R.id.presetsBtn)?.setOnClickListener {
            haptic()
            togglePresetList()
        }

        // ФИЧА: скрыть/показать прицел
        v.findViewById<Button>(R.id.crosshairBtn)?.setOnClickListener {
            haptic()
            toggleCrosshair()
        }

        val handle = v.findViewById<View>(R.id.dragHandle)
        handle?.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            override fun onTouch(handleView: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = p.x; sy = p.y
                        tx = e.rawX; ty = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = sx + (e.rawX - tx).toInt()
                        p.y = sy + (e.rawY - ty).toInt()
                        // ФИКС: окно двигаем через КОРНЕВОЙ view панели (v),
                        // а не через handle. Раньше в updateViewLayout уходил
                        // дочерний view — метод бросал IllegalArgumentException
                        // (глотался runCatching) и панель не двигалась вообще.
                        runCatching { wm.updateViewLayout(v, p) }
                        return true
                    }
                }
                return false
            }
        })

        toggleBtn?.setOnClickListener {
            haptic()
            if (playing) {
                stopPlayback()
            } else {
                lastPreset?.let { startPlayback(it) }
                    ?: toast("Сначала выберите пресет (☰)")
            }
        }

        v.findViewById<Button>(R.id.closeBtn)?.setOnClickListener {
            haptic()
            stopPlayback()
            stopRecordingInternal()
            hidePresetList()
            panel?.let { runCatching { wm.removeView(it) } }
            crosshair?.let { runCatching { wm.removeView(it) } }
            panel = null; crosshair = null
            disableSelf()
        }
        updatePanelState()
    }

    private fun updatePanelState() {
        val st = tvStatus ?: return
        val tg = toggleBtn ?: return
        when {
            recording -> {
                st.text = "●"; st.setTextColor(0xFFFFAA00.toInt())
                tg.setImageResource(R.drawable.ic_rec); tg.isEnabled = false
                pauseBtnRef?.isEnabled = false
            }
            playing -> {
                if (paused) {
                    st.text = "II"; st.setTextColor(0xFFFFB300.toInt())
                    tg.setImageResource(R.drawable.ic_stop); tg.isEnabled = true
                    pauseBtnRef?.setImageResource(R.drawable.ic_play); pauseBtnRef?.isEnabled = true
                } else {
                    st.text = "●"; st.setTextColor(0xFF00CC44.toInt())
                    tg.setImageResource(R.drawable.ic_stop); tg.isEnabled = true
                    pauseBtnRef?.setImageResource(R.drawable.ic_pause); pauseBtnRef?.isEnabled = true
                }
            }
            else -> {
                st.text = "●"; st.setTextColor(0xFFFF2222.toInt())
                tg.setImageResource(R.drawable.ic_play); tg.isEnabled = lastPreset != null
                pauseBtnRef?.setImageResource(R.drawable.ic_pause); pauseBtnRef?.isEnabled = false
            }
        }
        // ФИЧА: во время работы кнопки пресетов/прицела блокируются
        val idle = !playing && !recording
        presetsBtnRef?.isEnabled = idle
        crosshairBtnRef?.isEnabled = idle
        updateCrosshairButtonIcon()
        updateCounter()
    }

    /** ФИЧА: счётчик кликов + таймер сессии в шапке панели */
    private fun updateCounter() {
        val tv = tvCounter ?: return
        val secs: Long = when {
            playing && !paused -> (System.currentTimeMillis() - startTimeMs) / 1000
            playing && paused -> (pauseStartMs - startTimeMs) / 1000
            else -> 0L
        }
        tv.text = "%d · %02d:%02d".format(clickCount, secs / 60, secs % 60)
    }

    /** ФИЧА: пауза — tick засыпает, прогресс циклов и таймер сохраняются */
    private fun togglePause() {
        if (!playing) return
        paused = !paused
        if (paused) {
            pauseStartMs = System.currentTimeMillis()
        } else {
            // сдвигаем старт, чтобы DURATION-таймер не тикал в паузе
            startTimeMs += System.currentTimeMillis() - pauseStartMs
            // ФИКС: в паузе цепочка tick остановлена совсем — здесь запускаем
            // заново (removeCallbacks снимает возможные «хвосты», чтобы цикл
            // не задвоился)
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
        haptic()
        updatePanelState()
        dbg { "paused=$paused" }
    }

    // ============================================================
    // ФИЧА: прицел — показ/скрытие и синхронизация с пресетом
    // ============================================================

    /**
     * Кнопка панели: скрыть/показать визуальных помощников ТЕКУЩЕГО режима —
     * крестик в ST, нумерованные маркеры в MTWS. Состояние сохраняется.
     * ФИКС: раньше кнопка управляла ТОЛЬКО крестиком — в MTWS крестика
     * на экране нет (его роль играют маркеры), нажатие визуально ничего
     * не меняло («кнопка не работает»), а «показать» выводило чужой
     * ST-крестик поверх маркеров.
     */
    private fun toggleCrosshair() {
        crosshairHidden = !crosshairHidden
        runCatching {
            getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_CROSSHAIR_HIDDEN, crosshairHidden).apply()
        }
        val mtws = lastPreset?.mode == "MTWS"
        if (crosshairHidden) {
            hideCrosshair()
            // ФИКС: в MTWS скрываем именно маркеры — они и есть «прицел» этого режима
            hideMtwsMarkers()
            toast(if (mtws) "Маркеры скрыты" else "Прицел скрыт")
        } else {
            // ФИКС: во время playback/записи/выбора точки визуальных помощников
            // НЕ возвращаем — touchable-оверлеи блокируют dispatchGesture
            if (!playing && !recording && pickOverlay == null) {
                if (mtws) {
                    refreshMtwsMarkers()
                } else {
                    syncCrosshairToPreset()
                    showCrosshair()
                }
            }
            toast(if (mtws) "Маркеры показаны" else "Прицел показан")
        }
        updateCrosshairButtonIcon()
    }

    private fun updateCrosshairButtonIcon() {
        crosshairBtnRef?.text = if (crosshairHidden) "⊘" else "⊕"
    }

    /**
     * ФИКС («после остановки прицел появляется в другом месте»): крестик
     * ST-режима синхронизирован с точкой тапа последнего пресета. Раньше он
     * возвращался на старую позицию, а клики шли по координатам пресета —
     * прицел казался посторонним и бесполезным.
     */
    private fun syncCrosshairToPreset() {
        val p = lastPreset ?: return
        if (p.mode != "ST" || p.actions.isEmpty()) return
        crosshairCenterX = p.actions[0].x1.toFloat()
        crosshairCenterY = p.actions[0].y1.toFloat()
        val cv = crosshair ?: return
        val cp = crosshairParams ?: return
        val half = 24f * resources.displayMetrics.density
        cp.x = (crosshairCenterX - half).toInt()
        cp.y = (crosshairCenterY - half).toInt()
        runCatching { wm.updateViewLayout(cv, cp) }
    }

    /** ФИЧА: перетаскивание крестика двигает точку тапа ST-пресета (и сохраняет её) */
    private fun saveCrosshairCenterToPreset() {
        val preset = lastPreset ?: return
        if (preset.mode != "ST" || preset.actions.isEmpty()) return
        val nx = crosshairCenterX.toInt()
        val ny = crosshairCenterY.toInt()
        val old = preset.actions[0]
        if (old.x1 == nx && old.y1 == ny) return
        lastPreset = preset.copy(actions = preset.actions.mapIndexed { i, act ->
            if (i == 0) act.copy(x1 = nx, y1 = ny) else act
        })
        persistLastPreset(lastPreset!!)
    }

    // ============================================================
    // ФИЧА: список пресетов из плавающей панели
    // ============================================================

    private fun togglePresetList() {
        if (presetList != null) {
            hidePresetList()
            return
        }
        if (playing || recording) {
            toast("Сначала остановите кликер")
            return
        }
        val presets = PresetStorage.getAll(this)
        if (presets.isEmpty()) {
            toast("Нет сохранённых пресетов")
            return
        }

        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(px(12), px(10), px(12), px(12))
        }

        val title = TextView(this).apply {
            text = "Пресеты"
            textSize = 14f
            setTextColor(context.getColor(R.color.text_primary))
            setPadding(0, 0, 0, px(8))
        }
        container.addView(title)

        presets.forEach { preset ->
            val label = if (preset.mode == "ST") preset.name else "MTWS · ${preset.name}"
            val b = Button(this).apply {
                text = label
                textSize = 14f
                isAllCaps = false
                setTextColor(context.getColor(R.color.text_primary))
                setBackgroundResource(R.drawable.btn_secondary)
                stateListAnimator = null
                setOnClickListener {
                    haptic()
                    applyPresetFromPanel(preset)
                }
            }
            container.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(42)
            ).apply { topMargin = px(6) })
        }

        val scroll = ScrollView(this).apply { addView(container) }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            overlayFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
            // много пресетов — ограничиваем высоту окна, содержимое скроллится
            if (presets.size > 6) {
                height = (resources.displayMetrics.heightPixels * 0.6f).toInt()
            }
        }

        presetList = scroll
        if (runCatching { wm.addView(scroll, lp) }.isFailure) {
            Log.e(TAG, "togglePresetList: addView failed")
            presetList = null
            return
        }
    }

    private fun applyPresetFromPanel(preset: Preset) {
        hidePresetList()
        lastPreset = preset
        persistLastPreset(preset)
        if (preset.mode == "ST") {
            hideMtwsMarkers()
            if (!crosshairHidden) {
                syncCrosshairToPreset()
                if (crosshair == null) showCrosshair()
            }
        } else {
            // MTWS: крестик не нужен — его роль играют нумерованные точки
            hideCrosshair()
            refreshMtwsMarkers()
        }
        updatePanelState()
        toast("Пресет: ${preset.name}")
    }

    private fun hidePresetList() {
        presetList?.let { runCatching { wm.removeView(it) } }
        presetList = null
    }

    // ============================================================
    // Пузырь: панель, свёрнутая в круглую кнопку
    // ============================================================

    private fun collapseToBubble() {
        if (bubble != null) return
        hidePresetList()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        tvStatus = null
        toggleBtn = null
        tvCounter = null
        pauseBtnRef = null
        presetsBtnRef = null
        crosshairBtnRef = null
        showBubble()
    }

    private fun expandFromBubble() {
        hideBubble()
        showPanel()
    }

    private fun showBubble() {
        if (bubble != null) return
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            overlayFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 48
            y = 200
        }
        bubbleParams = p
        val v = runCatching { LayoutInflater.from(this).inflate(R.layout.bubble, null) }
            .getOrElse { Log.e(TAG, "showBubble: inflate failed", it); return }
        bubble = v
        bubbleIcon = v.findViewById(R.id.bubbleIcon)
        if (runCatching { wm.addView(v, p) }.isFailure) {
            Log.e(TAG, "showBubble: addView failed")
            bubble = null
            bubbleParams = null
            bubbleIcon = null
            return
        }
        updateBubbleIcon()

        // Тап (без движения) — развернуть панель, драг — переместить пузырь
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        v.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            var moved = false
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = p.x; sy = p.y
                        tx = e.rawX; ty = e.rawY
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - tx
                        val dy = e.rawY - ty
                        if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) moved = true
                        if (moved) {
                            p.x = sx + dx.toInt()
                            p.y = sy + dy.toInt()
                            runCatching { wm.updateViewLayout(view, p) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            haptic()
                            expandFromBubble()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun hideBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleParams = null
        bubbleIcon = null
    }

    /** ФИЧА: иконка пузыря = «развернуть панель» (двойная диагональная стрелка).
     *  Раньше стоял глиф «▶» — он выглядел как кнопка play, и пользователь мог
     *  подумать, что тап запустит скрипт, хотя пузырь только разворачивает панель.
     *  Форма теперь всегда означает «развернуть», а состояние лишь подсвечивается
     *  цветом: красный — стоит, зелёный — играет */
    private fun updateBubbleIcon() {
        val iv = bubbleIcon ?: return
        iv.setImageResource(R.drawable.ic_expand)
        iv.setColorFilter(if (playing) 0xFF00CC44.toInt() else 0xFFFF4B4B.toInt())
    }

    // ============================================================
    // Крестик
    // ============================================================

    private fun showCrosshair() {
        if (crosshair != null) return
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            overlayFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // ФИКС: view размером 48dp — смещение считаем в dp, а не в «сырых» пикселях
            val half = 24f * resources.displayMetrics.density
            x = (crosshairCenterX - half).toInt()
            y = (crosshairCenterY - half).toInt()
        }
        crosshairParams = p
        val v = runCatching { LayoutInflater.from(this).inflate(R.layout.crosshair, null) }
            .getOrElse { Log.e(TAG, "showCrosshair: inflate failed", it); return }
        crosshair = v
        if (runCatching { wm.addView(v, p) }.isFailure) {
            Log.e(TAG, "showCrosshair: addView failed")
            crosshair = null
            crosshairParams = null
            return
        }

        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateCrosshairCenter(p, v)
        }

        v.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = p.x; sy = p.y
                        tx = e.rawX; ty = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = sx + (e.rawX - tx).toInt()
                        p.y = sy + (e.rawY - ty).toInt()
                        runCatching { wm.updateViewLayout(view, p) }
                        updateCrosshairCenter(p, view)
                        saveCrosshairCenterToPreset()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun updateCrosshairCenter(p: WindowManager.LayoutParams, v: View) {
        if (v.width <= 0 || v.height <= 0) return
        crosshairCenterX = p.x + v.width / 2f
        crosshairCenterY = p.y + v.height / 2f
    }

    /** Полностью убирает окно крестика — критично для работы dispatchGesture */
    private fun hideCrosshair() {
        crosshair?.let { runCatching { wm.removeView(it) } }
        crosshair = null
        crosshairParams = null
    }

    // ============================================================
    // ФИЧА: анимация клика (виртуальный прицел на точке тапа)
    // ============================================================

    /**
     * Показывает «прицел» на точке клика. Это то же окно ⊕, что и крестик,
     * но с двумя ключевыми отличиями:
     *  1. FLAG_NOT_TOUCHABLE — окно прозрачно для касаний, поэтому НЕ может
     *     перехватить инжектируемый dispatchGesture жест (именно из-за
     *     перехвата обычный крестик убирается на время playback);
     *  2. оно перекрашено в зелёный — цвет состояния «идёт воспроизведение».
     * В ST-режиме стоит на месте, в MTWS — переставляется под каждое действие.
     */
    private fun showClickMarker(x: Float, y: Float) {
        if (!CLICK_ANIMATION_ENABLED) return
        val half = 24f * resources.displayMetrics.density

        val existing = clickMarker
        if (existing != null) {
            // Уже показан — просто переставляем (MTWS: у каждого действия своя точка)
            val p = clickMarkerParams ?: return
            p.x = (x - half).toInt()
            p.y = (y - half).toInt()
            runCatching { wm.updateViewLayout(existing, p) }
            return
        }

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            overlayFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // центр окна = точка тапа
            this.x = (x - half).toInt()
            this.y = (y - half).toInt()
        }
        val v = runCatching { LayoutInflater.from(this).inflate(R.layout.crosshair, null) }
            .getOrElse { Log.e(TAG, "showClickMarker: inflate failed", it); return }
        // Перекрашиваем ⊕ в зелёный, чтобы не путать с перетаскиваемым
        // красным крестиком. crosshair.xml: FrameLayout -> TextView
        // ФИКС (ошибка 365:46 "Incomplete code"): безопасный вызов ?. нельзя
        // цеплять сразу за as?-приведением — парсер читает «TextView?» как
        // тип и спотыкается о «.setTextColor». Выносим приведение в переменную.
        val markerText = (v as? ViewGroup)?.getChildAt(0) as? TextView
        markerText?.setTextColor(0xFF00CC44.toInt())

        clickMarkerParams = p
        clickMarker = v
        if (runCatching { wm.addView(v, p) }.isFailure) {
            Log.e(TAG, "showClickMarker: addView failed")
            clickMarker = null
            clickMarkerParams = null
        }
    }

    /**
     * Импульс клика: прицел сжимается к центру (центр = точка тапа) и
     * возвращается обратно. Сжатие чуть короче возврата — так анимация
     * читается как «нажал-отпустил».
     */
    private fun animateClick() {
        val v = clickMarker ?: return
        v.animate().cancel()
        v.scaleX = 1f
        v.scaleY = 1f
        v.alpha = 1f
        v.animate()
            .scaleX(CLICK_ANIM_MIN_SCALE)
            .scaleY(CLICK_ANIM_MIN_SCALE)
            .alpha(CLICK_ANIM_MIN_ALPHA)
            .setDuration(CLICK_ANIM_DOWN_MS)
            .withEndAction {
                v.animate()
                    .scaleX(1f).scaleY(1f)
                    .alpha(1f)
                    .setDuration(CLICK_ANIM_UP_MS)
                    .start()
            }
            .start()
    }

    private fun hideClickMarker() {
        clickMarker?.let { runCatching { wm.removeView(it) } }
        clickMarker = null
        clickMarkerParams = null
    }

    // ============================================================
    // ФИЧА: нумерованные точки MTWS
    // ============================================================

    /**
     * Показывает пронумерованные точки последнего MTWS-пресета — виден
     * порядок обхода (как во всех популярных кликерах). Точка
     * перетаскивается — новые координаты сохраняются в пресет;
     * долгий тап — удаляет точку. Во время playback/записи/выбора точки
     * маркеры скрываются: это touchable-оверлеи, они блокировали бы
     * dispatchGesture (та же причина, по которой прячется крестик).
     * ФИКС: уважает и кнопку ⊘ панели — если пользователь скрыл
     * визуальных помощников, маркеры не возвращаются (рестарт службы,
     * остановка playback, закрытие оверлеев).
     */
    private fun refreshMtwsMarkers() {
        hideMtwsMarkers()
        if (playing || recording || pickOverlay != null) return
        // ФИКС: кнопка ⊘ панели скрывает помощников — не возвращаем их за её спиной
        if (crosshairHidden) return
        val preset = lastPreset ?: return
        if (preset.mode != "MTWS") return
        preset.actions.forEachIndexed { i, a ->
            addMtwsMarker(i, a.x1.toFloat(), a.y1.toFloat())
        }
    }

    private fun hideMtwsMarkers() {
        if (mtwsMarkers.isEmpty()) return
        val copy = mtwsMarkers.toList()
        mtwsMarkers.clear()
        copy.forEach { m -> runCatching { wm.removeView(m.view) } }
    }

    private fun addMtwsMarker(index: Int, x: Float, y: Float) {
        val half = 16f * resources.displayMetrics.density // маркер 32dp
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            overlayFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // центр окна = точка действия
            this.x = (x - half).toInt()
            this.y = (y - half).toInt()
        }
        val v = runCatching { LayoutInflater.from(this).inflate(R.layout.point_marker, null) }
            .getOrElse { Log.e(TAG, "addMtwsMarker: inflate failed", it); return }
        v.findViewById<TextView>(R.id.tvPointNum)?.text = (index + 1).toString()

        val marker = MtwsMarker(v, index)
        mtwsMarkers.add(marker)
        if (runCatching { wm.addView(v, p) }.isFailure) {
            mtwsMarkers.remove(marker)
            Log.e(TAG, "addMtwsMarker: addView failed")
            return
        }

        // Перетаскивание; долгий тап без движения — удалить точку
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        v.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            var moved = false
            var longFired = false
            var pendingLong: Runnable? = null

            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = p.x; sy = p.y
                        tx = e.rawX; ty = e.rawY
                        moved = false
                        longFired = false
                        val captured = view
                        pendingLong = Runnable {
                            if (!moved && !longFired && mtwsMarkers.any { it.view === captured }) {
                                longFired = true
                                removeMtwsPoint(captured)
                            }
                        }
                        handler.postDelayed(pendingLong!!, 600L)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - tx
                        val dy = e.rawY - ty
                        if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            moved = true
                            pendingLong?.let { handler.removeCallbacks(it) }
                        }
                        if (moved) {
                            p.x = sx + dx.toInt()
                            p.y = sy + dy.toInt()
                            runCatching { wm.updateViewLayout(view, p) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        pendingLong?.let { handler.removeCallbacks(it) }
                        if (moved) saveMarkerPosition(view, p)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun removeMtwsPoint(view: View) {
        val marker = mtwsMarkers.firstOrNull { it.view === view } ?: return
        val preset = lastPreset ?: return
        if (preset.actions.size <= 1) {
            toast("Нельзя удалить последнюю точку")
            return
        }
        haptic()
        lastPreset = preset.copy(actions = preset.actions.filterIndexed { i, _ -> i != marker.index })
        currentPreset = lastPreset
        persistLastPreset(lastPreset!!)
        mtwsMarkers.removeAll { it.view === view }
        runCatching { wm.removeView(view) }
        toast("Точка ${marker.index + 1} удалена")
        // Пересобрать оставшиеся маркеры — они перенумеруются
        refreshMtwsMarkers()
    }

    private fun saveMarkerPosition(view: View, p: WindowManager.LayoutParams) {
        val marker = mtwsMarkers.firstOrNull { it.view === view } ?: return
        val preset = lastPreset ?: return
        if (view.width <= 0 || view.height <= 0) return
        val cx = p.x + view.width / 2f
        val cy = p.y + view.height / 2f
        lastPreset = preset.copy(actions = preset.actions.mapIndexed { i, a ->
            if (i == marker.index) a.copy(x1 = cx.toInt(), y1 = cy.toInt()) else a
        })
        currentPreset = lastPreset
        persistLastPreset(lastPreset!!)
    }

    // ============================================================
    // Выбор точки (ST)
    // ============================================================

    /**
     * ФИКС: возвращает Boolean и отказывает, если идёт playback или запись.
     * Раньше выбор точки во время MTWS-воспроизведения приводил к тому,
     * что hidePickOverlay() возвращал крестик на экран и блокировал жесты.
     */
    fun startPickPoint(onDone: (Int, Int) -> Unit, editorMode: String = "ST"): Boolean {
        if (pickOverlay != null) return false
        if (playing || recording) return false

        pickOnDoneRef = WeakReference(onDone)
        pickEditorMode = editorMode
        crosshair?.visibility = View.GONE
        panel?.visibility = View.GONE
        hideMtwsMarkers()

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            overlayFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val v = runCatching { LayoutInflater.from(this).inflate(R.layout.pick_point_overlay, null) }
            .getOrElse { Log.e(TAG, "startPickPoint: inflate failed", it); return false }
        pickOverlay = v
        if (runCatching { wm.addView(v, p) }.isFailure) {
            Log.e(TAG, "startPickPoint: addView failed")
            pickOverlay = null
            pickOnDoneRef = null
            crosshair?.visibility = View.VISIBLE
            panel?.visibility = View.VISIBLE
            refreshMtwsMarkers()
            return false
        }

        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val x = e.rawX.toInt()
                val y = e.rawY.toInt()
                // ФИКС (v20): координаты передаются в hidePickOverlay —
                // прицел встанет на ВЫБРАННУЮ точку, а не на старую
                hidePickOverlay(x, y)
                // Сначала результат — в страховочное хранилище: если живой
                // получатель не найдётся (окно уничтожено), его заберёт
                // SettingsActivity.onResume() и выбор точки не потеряется
                lastPickResult = x to y
                val cb = pickOnDoneRef?.get()
                pickOnDoneRef = null
                if (cb != null) {
                    lastPickResult = null
                    cb.invoke(x, y)
                }
                // ФИЧА (v13): после тапа ПОДНИМАЕМ окно настроек на передний план.
                // Раньше это делала сама активность (startActivity из фона) —
                // современные Android блокируют запуск активити из фона, и
                // приложение оставалось свёрнутым. Теперь поднимаем ИЗ СЛУЖБЫ —
                // так же, как при возврате после записи MTWS (bringBackSettingsEditor:
                // NEW_TASK-флаг, работает на всех прошивках). Если колбэк был жив —
                // он уже обновил подпись точки; если нет — точку заберёт
                // SettingsActivity.onResume() через consumePickResult()
                bringBackSettingsEditor(pickEditorMode)
            }
            true
        }
        return true
    }

    private fun hidePickOverlay(pickedX: Int = Int.MIN_VALUE, pickedY: Int = Int.MIN_VALUE) {
        pickOverlay?.let { runCatching { wm.removeView(it) } }
        pickOverlay = null
        panel?.visibility = View.VISIBLE
        // ФИКС: крестик возвращаем только вне playback.
        // Во время ST-воспроизведения крестика вообще нет (removeView),
        // во время MTWS он должен остаться скрытым.
        // ФИКС (v20): раньше крестик безусловно всплывал на СТАРОМ месте
        // (точка прежнего пресета или дефолт) — при настройке нового пресета
        // это вводило в заблуждение. Теперь:
        //  — ST: прицел встаёт на ВЫБРАННУЮ точку (подсказка «там появится
        //    прицел» больше не врёт); кнопка ⊘ уважается — при скрытых
        //    помощниках прицел не создаётся и не возвращается;
        //  — MT: крестик не возвращаем — его роль играют нумерованные
        //    точки, их вернёт refreshMtwsMarkers() ниже.
        if (!playing && !crosshairHidden && pickEditorMode == "ST" &&
            pickedX != Int.MIN_VALUE && pickedY != Int.MIN_VALUE
        ) {
            moveCrosshairTo(pickedX.toFloat(), pickedY.toFloat())
        } else if (!playing && pickEditorMode == "ST") {
            crosshair?.visibility = View.VISIBLE
        }
        refreshMtwsMarkers()
    }

    /** ФИКС (v20): ставит крестик центром в точку (x, y). Если окна крестика
     *  нет — создаёт его (showCrosshair читает crosshairCenterX/Y); если
     *  есть — переставляет через updateViewLayout */
    private fun moveCrosshairTo(x: Float, y: Float) {
        crosshairCenterX = x
        crosshairCenterY = y
        val v = crosshair
        val p = crosshairParams
        if (v == null || p == null) {
            showCrosshair()
            return
        }
        val half = 24f * resources.displayMetrics.density
        p.x = (x - half).toInt()
        p.y = (y - half).toInt()
        runCatching { wm.updateViewLayout(v, p) }
        v.visibility = View.VISIBLE
    }

    // ============================================================
    // Playback
    // ============================================================

    /** Возвращает true, если воспроизведение реально запущено */
    fun startPlayback(preset: Preset): Boolean {
        if (playing || recording) return false
        if (preset.mode == "MTWS" && preset.actions.isEmpty()) return false

        lastPreset = preset
        persistLastPreset(preset)
        currentPreset = preset
        actionIndex = 0
        cycleCount = 0
        startTimeMs = System.currentTimeMillis()
        gestureInFlight = false
        consecutiveCancels = 0
        clickCount = 0
        paused = false
        playing = true

        // ФИЧА: нумерованные точки — touchable-оверлеи, на время playback
        // убираем вместе с крестиком, чтобы не блокировали dispatchGesture
        hideMtwsMarkers()
        hidePresetList()

        if (preset.mode == "ST") {
            // ВАЖНО: во время ST-воспроизведения перетаскиваемый крестик
            // полностью убираем — иначе touchable-overlay блокирует
            // dispatchGesture на некоторых прошивках.
            // Его роль берёт clickMarker: он FLAG_NOT_TOUCHABLE, жестам
            // не мешает и показывает анимацию клика.
            hideCrosshair()
        } else {
            crosshair?.visibility = View.GONE
        }
        updatePanelState()
        updateBubbleIcon()
        dbg { "startPlayback mode=${preset.mode} timing=${preset.timingMode} delay=${preset.delayMs}" }
        // ФИКС: снимаем возможные «хвосты» tick — цепочка цикла всегда одна
        handler.removeCallbacks(tick)
        handler.post(tick)
        return true
    }

    fun stopPlayback() {
        if (!playing) return
        playing = false
        paused = false
        gestureInFlight = false
        handler.removeCallbacks(tick)
        hideClickMarker()
        if (currentPreset?.mode == "ST") {
            // ФИКС: крестик возвращается ровно на точку тапа пресета
            // (и только если пользователь его не скрыл кнопкой ⊘)
            if (!crosshairHidden && crosshair == null) {
                syncCrosshairToPreset()
                showCrosshair()
            }
        }
        // MTWS: крестик не возвращаем — его роль играют нумерованные точки
        // ФИЧА: вернуть нумерованные точки (если последний пресет MTWS)
        refreshMtwsMarkers()
        updatePanelState()
        updateBubbleIcon()
        dbg { "stopPlayback" }
    }

    private val gestureCallback = object : GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) {
            gestureInFlight = false
            consecutiveCancels = 0
            // ФИЧА: считаем только реально завершённые жесты
            clickCount++
            updateCounter()
        }

        override fun onCancelled(g: GestureDescription?) {
            gestureInFlight = false
            // ФИКС: серия подряд отменённых жестов обычно означает, что жесты
            // блокирует оверлей либо координаты вне экрана. Раньше playback
            // крутился вхолостую бесконечно — теперь останавливаемся и сообщаем.
            consecutiveCancels++
            if (playing && consecutiveCancels >= MAX_CONSECUTIVE_CANCELS) {
                Log.w(TAG, "gestures cancelled $consecutiveCancels times in a row — stopping")
                stopPlayback()
                toast("Жесты блокируются — воспроизведение остановлено")
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            // ФИЧА + ФИКС (перф): в паузе цикл засыпает совсем, без опроса
            // каждые 100 мс. Возобновление в togglePause() запускает цепочку
            // заново; прогресс циклов и таймер при этом сохраняются
            if (paused) return
            val preset = currentPreset ?: run { stopPlayback(); return }

            val shouldStop: Boolean = when (preset.timingMode) {
                "DURATION" -> (System.currentTimeMillis() - startTimeMs) / 1000 >= preset.durationSec
                "CYCLES" -> cycleCount >= preset.cycles
                else -> false
            }
            if (shouldStop) { stopPlayback(); return }

            if (gestureInFlight) {
                handler.postDelayed(this, 20L)
                return
            }

            if (preset.mode == "ST") {
                val tx: Float
                val ty: Float
                if (preset.actions.isNotEmpty()) {
                    tx = preset.actions[0].x1.toFloat()
                    ty = preset.actions[0].y1.toFloat()
                } else {
                    tx = crosshairCenterX
                    ty = crosshairCenterY
                }
                // ФИЧА: визуальный отклик — прицел ставится на точку тапа
                // и сжимается в момент клика
                showClickMarker(tx, ty)
                animateClick()
                performTap(tx, ty)
                cycleCount++
                updateCounter()
                // ФИКС: задержка ограничена снизу — пресет с delay=0 больше не
                // превращает цикл в busy-poll.
                // ФИЧА: разброс — реальная пауза выбирается случайно из
                // [delay − jitter; delay + jitter] (анти-детект: клики
                // не идут метрономом с фиксированным интервалом)
                handler.postDelayed(this, stDelayWithJitter(
                    preset.delayMs, preset.delayJitterMs
                ).coerceAtLeast(MIN_DELAY_MS))
            } else {
                if (preset.actions.isEmpty()) { stopPlayback(); return }
                if (actionIndex >= preset.actions.size) {
                    actionIndex = 0
                    cycleCount++
                    if (preset.timingMode == "CYCLES" && cycleCount >= preset.cycles) {
                        stopPlayback(); return
                    }
                }
                val a = preset.actions[actionIndex]
                if (a.type == "tap") {
                    showClickMarker(a.x1.toFloat(), a.y1.toFloat())
                    animateClick()
                    performTap(a.x1.toFloat(), a.y1.toFloat())
                } else {
                    // Для свайпа — импульс в стартовой точке
                    showClickMarker(a.x1.toFloat(), a.y1.toFloat())
                    animateClick()
                    performSwipe(a.x1.toFloat(), a.y1.toFloat(),
                        a.x2.toFloat(), a.y2.toFloat(), a.swipeDurationMs)
                }
                actionIndex++
                updateCounter()
                // ФИЧА: индивидуальная задержка каждого действия — пауза после
                // текущего действия перед следующим (записывается при записи и
                // редактируется в MTWS-вкладке). После последнего действия цикла
                // применяется ПЕРИОДИЧНОСТЬ — пауза между полными прогонами пресета.
                // ФИКС (v13): СМЕЩЕНИЕ ПАУЗ — при записи gap между действиями k и
                // k+1 кладётся в delayMs[k] («задержка ПОСЛЕ него», как в
                // подсказке редактора), а здесь читался delayMs[k+1] — каждая
                // пауза применялась на одно действие ПОЗЖЕ (реальная пауза
                // A→B играла как пауза B→C). Читаем delayMs только что
                // выполненного действия: actionIndex уже инкрементирован,
                // поэтому actionIndex - 1
                val nextDelay: Long = if (actionIndex >= preset.actions.size)
                    preset.repeatIntervalMs
                else
                    preset.actions[actionIndex - 1].delayMs
                handler.postDelayed(this, nextDelay.coerceAtLeast(MIN_DELAY_MS))
            }
        }
    }

    /** ФИЧА (анти-детект): случайный сдвиг координаты в пределах ±RANDOM_OFFSET_PX */
    private fun jitterCoord(v: Float): Float =
        if (RANDOM_OFFSET_PX > 0)
            v + Random.nextInt(-RANDOM_OFFSET_PX, RANDOM_OFFSET_PX + 1)
        else
            v

    /** ФИЧА: пауза ST со случайным разбросом ±jitterMs (включается чекбоксом
     *  «Разброс» в настройках Single Target). Равномерный выбор из
     *  [delayMs − jitterMs; delayMs + jitterMs]; при jitterMs <= 0 пауза
     *  постоянная. Нижняя граница прижата к MIN_DELAY_MS, чтобы разброс
     *  больше задержки не мог дать нулевую/отрицательную паузу */
    private fun stDelayWithJitter(delayMs: Long, jitterMs: Long): Long {
        if (jitterMs <= 0L) return delayMs
        val lo = (delayMs - jitterMs).coerceAtLeast(MIN_DELAY_MS)
        val hi = delayMs + jitterMs
        return if (hi <= lo) lo else Random.nextLong(lo, hi + 1L)
    }

    private fun performTap(x: Float, y: Float) {
        // ФИЧА: случайный сдвиг — клики не ложатся пиксель-в-пиксель
        val tx = jitterCoord(x)
        val ty = jitterCoord(y)
        val path = Path().apply { moveTo(tx, ty) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val g = GestureDescription.Builder().addStroke(stroke).build()
        gestureInFlight = true
        val ok = runCatching {
            dispatchGesture(g, gestureCallback, null)
        }.getOrElse { e ->
            Log.e(TAG, "dispatchGesture(tap) error", e)
            false
        }
        dbg { "tap ($tx, $ty) ok=$ok" }
        if (!ok) gestureInFlight = false
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        // ФИЧА: случайный сдвиг стартовой и конечной точек свайпа
        val jx1 = jitterCoord(x1)
        val jy1 = jitterCoord(y1)
        val jx2 = jitterCoord(x2)
        val jy2 = jitterCoord(y2)
        val path = Path().apply {
            moveTo(jx1, jy1)
            lineTo(jx2, jy2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(50L))
        val g = GestureDescription.Builder().addStroke(stroke).build()
        gestureInFlight = true
        val ok = runCatching {
            dispatchGesture(g, gestureCallback, null)
        }.getOrElse { e ->
            Log.e(TAG, "dispatchGesture(swipe) error", e)
            false
        }
        dbg { "swipe ($jx1,$jy1)->($jx2,$jy2) ok=$ok" }
        if (!ok) gestureInFlight = false
    }

    // ============================================================
    // Recording
    // ============================================================

    /** Возвращает true, если запись реально началась */
    fun startRecording(onDone: (List<PresetAction>) -> Unit): Boolean {
        if (recording || playing) return false
        recordedActions = mutableListOf()
        lastRecTime = System.currentTimeMillis()
        lastRecordResult = null
        // ФИЧА: новая сессия записи начинается без хвостов предпросмотра
        handler.removeCallbacks(replayRunnable)
        replayInFlight = false
        onRecordDoneRef = WeakReference(onDone)
        if (!showRecordOverlay()) {
            onRecordDoneRef = null
            return false
        }
        recording = true
        hideMtwsMarkers()
        hidePresetList()
        updatePanelState()
        return true
    }

    /**
     * ФИЧА: bringBackEditor = true (остановка кнопкой «Остановить» на оверлее)
     * возвращает окно настроек на экран, чтобы пользователь сразу отредактировал
     * записанные действия. При остановке через ✕ панели окно не поднимается.
     */
    fun stopRecordingInternal(bringBackEditor: Boolean = false) {
        if (!recording) return
        recording = false
        // ФИЧА: снять отложенный предпросмотр; жест, УЖЕ переданный системе,
        // доиграется в приложении — это безвредно (предпросмотр и так виден)
        handler.removeCallbacks(replayRunnable)
        replayInFlight = false
        hideRecordOverlay()
        val result = recordedActions.toList()
        // Страховка: результат переживает уничтожение окна настроек —
        // его заберёт SettingsActivity.onResume() через consumeRecordResult()
        lastRecordResult = result
        val cb = onRecordDoneRef?.get()
        onRecordDoneRef = null
        refreshMtwsMarkers()
        updatePanelState()
        if (bringBackEditor) bringBackSettingsEditor()
        if (cb != null) {
            lastRecordResult = null
            cb.invoke(result)
        }
    }

    /** ФИЧА: поднять окно настроек после остановки записи / выбора точки.
     *  Запуск строго ИЗ КОНТЕКСТА СЛУЖБЫ — активити из фона поднимать
     *  нельзя (ограничения Android 10+), служба с выданным
     *  SYSTEM_ALERT_WINDOW — можно.
     *  ФИКС (v19): если перед сворачиванием окно было ОКОННЫМ, оно
     *  поднимается startActivity'ем с СОХРАНЁННЫМИ ГРАНИЦАМИ
     *  (ActivityOptions.setLaunchBounds) — плавающее окно возвращается
     *  плавающим, с прежним размером и позицией, даже если систему
     *  задачу/активность убила прошивка. Для полноэкранного окна —
     *  прежний путь v18: ПОДНЯТЬ ЗАДАЧУ ЦЕЛИКОМ (moveTaskToFront,
     *  REORDER_TASKS), а при её отсутствии — startActivity */
    private fun bringBackSettingsEditor(mode: String = "MTWS") {
        val tid = editorTaskId
        editorTaskId = -1
        // ФИКС (v19): окно было оконным — поднимаем с сохранёнными
        // границами. REORDER_TO_FRONT поднимет живую активность,
        // убитая системой создастся заново (поля вернёт черновик) —
        // в обоих случаях границы применяются к задаче. На прошивках
        // без поддержки свободных окон bounds молча игнорируются — безопасно
        val geom = readEditorWindowGeometry()
        if (geom != null && runCatching { launchEditorWithBounds(geom, mode) }
                .onFailure { Log.e(TAG, "bringBackSettingsEditor: bounded launch failed", it) }
                .getOrDefault(false)) {
            return
        }
        // ФИКС (v18→v19): подъём задачи целиком сохранён для полноэкранного
        // случая. Нюанс: moveTaskToFront НЕ БРОСАЕТ исключение, если задачи
        // уже нет — раньше метод просто выходил, и окно не возвращалось
        // НИКОГДА. Теперь после moveTaskToFront ВСЕГДА дублируем запуск с
        // REORDER_TO_FRONT: живая активность получит onNewIntent (дубликата
        // не будет), уничтоженная — создастся заново с черновиком
        if (tid != -1 && runCatching { bringTaskToFront(tid) }
                .onFailure { Log.e(TAG, "bringTaskToFront($tid) failed", it) }
                .getOrDefault(false)) {
            runCatching { launchEditorPlain(mode) }
                .onFailure { Log.e(TAG, "bringBackSettingsEditor: plain launch failed", it) }
            return
        }
        runCatching { launchEditorPlain(mode) }
            .onFailure { Log.e(TAG, "bringBackSettingsEditor: failed", it) }
    }

    /** ФИКС (v19): границы окна редактора, сохранённые активностью перед
     *  сворачиванием (преф win_geom в editor_draft_v1, формат "l,t,r,b"),
     *  либо null — окно было во весь экран / геометрия неизвестна.
     *  Всё в runCatching: битое значение не должно ломать возврат */
    private fun readEditorWindowGeometry(): Rect? {
        return runCatching {
            // имя файла префов совпадает с SettingsActivity.DRAFT_PREFS
            // (константа там private — дублируем литералом)
            val s = getSharedPreferences("editor_draft_v1", Context.MODE_PRIVATE)
                .getString("win_geom", null) ?: return null
            val p = s.split(',')
            if (p.size != 4) return null
            val r = Rect(
                p[0].trim().toIntOrNull() ?: return null,
                p[1].trim().toIntOrNull() ?: return null,
                p[2].trim().toIntOrNull() ?: return null,
                p[3].trim().toIntOrNull() ?: return null
            )
            if (r.width() < 100 || r.height() < 100) return null
            r
        }.getOrNull()
    }

    /** ФИКС (v19): интент редактора — общий для обоих путей запуска */
    private fun editorIntent(mode: String): Intent =
        Intent(this, SettingsActivity::class.java).apply {
            // NEW_TASK обязателен: startActivity идёт из контекста службы
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("mode", mode)
        }

    /** Прежний путь: запуск без границ (полный экран / поведение системы) */
    private fun launchEditorPlain(mode: String): Boolean {
        startActivity(editorIntent(mode))
        return true
    }

    /** ФИКС (v19): запуск с восстановлением границ окна — вернёт окно
     *  в тот же оконный режим, размер и позицию, что были перед сворачиванием */
    private fun launchEditorWithBounds(r: Rect, mode: String): Boolean {
        val opts = ActivityOptions.makeBasic()
        opts.setLaunchBounds(r)
        startActivity(editorIntent(mode), opts.toBundle())
        return true
    }

    /** ФИКС (v18): поднять задачу как есть — из недавних так сохраняется
     *  и оконный режим, и размер окна. Ошибка (задачи нет / отказ ОС)
     *  вернёт false — вызывающий уйдёт в fallback через startActivity */
    private fun bringTaskToFront(taskId: Int): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.moveTaskToFront(taskId, 0)
        return true
    }

    private fun showRecordOverlay(): Boolean {
        if (recordOverlay != null) return true
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            overlayFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        recordParams = p
        val v = runCatching { LayoutInflater.from(this).inflate(R.layout.record_overlay, null) }
            .getOrElse { Log.e(TAG, "showRecordOverlay: inflate failed", it); return false }
        recordOverlay = v
        if (runCatching { wm.addView(v, p) }.isFailure) {
            Log.e(TAG, "showRecordOverlay: addView failed")
            recordOverlay = null
            return false
        }

        val area = v.findViewById<View>(R.id.recordArea)
        area?.setOnTouchListener { _, e ->
            // ФИЧА: инжектируемые жесты (наш живой предпросмотр) не записываем
            // обратно — иначе предпросмотр свайпа запишется как новое действие
            if (e.flags and FLAG_IS_GENERATED_GESTURE != 0) {
                return@setOnTouchListener true
            }
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val upX = e.rawX; val upY = e.rawY
                    val dt = System.currentTimeMillis() - downTime
                    val dx = upX - downX; val dy = upY - downY
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    // ФИЧА: реальная пауза между действиями записывается как задержка
                    // ПРЕДЫДУЩЕГО действия (потом её можно отредактировать в
                    // настройках). Новое действие добавляется с задержкой 0
                    val now = System.currentTimeMillis()
                    if (recordedActions.isNotEmpty()) {
                        val gap = (now - lastRecTime).coerceIn(0L, MAX_REC_GAP_MS)
                        val li = recordedActions.size - 1
                        recordedActions[li] = recordedActions[li].copy(delayMs = gap)
                    }
                    lastRecTime = now
                    val action = if (dist < 40f) {
                        PresetAction("tap", downX.toInt(), downY.toInt(), 0, 0, 0L, delayMs = 0L)
                    } else {
                        PresetAction("swipe",
                            downX.toInt(), downY.toInt(),
                            upX.toInt(), upY.toInt(),
                            dt.coerceIn(50L, 5000L), delayMs = 0L)
                    }
                    recordedActions.add(action)
                    // ФИЧА: живой предпросмотр — жест тут же выполняется на экране
                    scheduleLiveReplay(action)
                    true
                }
                else -> true
            }
        }
        // ФИЧА: стоп-кнопка вынесена в отдельное окно — остаётся кликабельной,
        // пока окно записи переведено в FLAG_NOT_TOUCHABLE для предпросмотра
        showRecordStopButton()
        return true
    }

    /** ФИЧА: отдельное компактное окно стоп-кнопки поверх оверлея записи */
    private fun showRecordStopButton(): Boolean {
        if (recordStopBtn != null) return true
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            overlayFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48f * resources.displayMetrics.density).toInt()
        }
        recordStopParams = p
        val v = runCatching { LayoutInflater.from(this).inflate(R.layout.record_stop, null) }
            .getOrElse { Log.e(TAG, "showRecordStopButton: inflate failed", it); return false }
        recordStopBtn = v
        v.findViewById<View>(R.id.stopRecordBtn)?.setOnClickListener {
            stopRecordingInternal(bringBackEditor = true)
        }
        if (runCatching { wm.addView(v, p) }.isFailure) {
            Log.e(TAG, "showRecordStopButton: addView failed")
            recordStopBtn = null
            recordStopParams = null
            return false
        }
        return true
    }

    /** ФИЧА: живой предпросмотр — отложить воспроизведение записанного жеста */
    private fun scheduleLiveReplay(a: PresetAction) {
        if (replayInFlight) return // не наслаивать предпросмотры друг на друга
        replayInFlight = true
        // Прячем окно записи от касаний: иначе инжектируемый жест попадёт
        // в сам оверлей, а не в приложение под ним
        setRecordAreaTouchable(false)
        handler.postDelayed(replayRunnable, REPLAY_DELAY_MS)
    }

    private fun dispatchLiveReplay() {
        if (!recording) { restoreRecordArea(); return }
        val last = recordedActions.lastOrNull()
        if (last == null) { restoreRecordArea(); return }
        val g = runCatching { buildReplayGesture(last) }
            .getOrElse { Log.e(TAG, "buildReplayGesture failed", it); restoreRecordArea(); return }
        val cb = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { restoreRecordArea() }
            override fun onCancelled(gestureDescription: GestureDescription?) { restoreRecordArea() }
        }
        val ok = runCatching { dispatchGesture(g, cb, null) }
            .getOrElse { Log.e(TAG, "dispatchGesture(replay) error", it); false }
        if (!ok) restoreRecordArea()
    }

    private fun restoreRecordArea() {
        if (recording) setRecordAreaTouchable(true)
        replayInFlight = false
    }

    private fun setRecordAreaTouchable(touchable: Boolean) {
        val v = recordOverlay ?: return
        val p = recordParams ?: return
        p.flags = if (touchable)
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, p) }
            .onFailure { Log.e(TAG, "setRecordAreaTouchable failed", it) }
    }

    /** Жест для предпросмотра: точная копия записанного действия (без джиттера —
     *  пользователь должен видеть, куда реально попадёт клик) */
    private fun buildReplayGesture(a: PresetAction): GestureDescription {
        val path = Path().apply {
            moveTo(a.x1.toFloat(), a.y1.toFloat())
            if (a.type == "swipe") lineTo(a.x2.toFloat(), a.y2.toFloat())
        }
        val dur = if (a.type == "swipe") a.swipeDurationMs.coerceAtLeast(50L) else 50L
        val stroke = GestureDescription.StrokeDescription(path, 0L, dur)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    private fun hideRecordOverlay() {
        recordOverlay?.let { runCatching { wm.removeView(it) } }
        recordOverlay = null
        recordParams = null
        recordStopBtn?.let { runCatching { wm.removeView(it) } }
        recordStopBtn = null
        recordStopParams = null
    }

    // ============================================================
    // Последний пресет (переживает перезапуск службы)
    // ============================================================

    /**
     * ФИКС: последний пресет хранится в SharedPreferences — после перезапуска
     * службы (disableSelf / убийство системой) кнопка «Старт» на панели снова
     * работает сразу после включения службы. Туда же пишутся правки точек
     * MTWS (перетаскивание/удаление нумерованных маркеров).
     */
    private fun persistLastPreset(preset: Preset) {
        runCatching {
            getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_PRESET, preset.toJson().toString())
                .apply()
        }
    }

    private fun restoreLastPreset(): Preset? =
        runCatching {
            getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_PRESET, null)
                ?.let { Preset.fromJson(JSONObject(it)) }
        }.getOrNull()
}

