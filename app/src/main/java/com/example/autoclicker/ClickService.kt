package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
// ФИЧА (v24): редактор задержки ST на плавающей панели (числовой ввод)
import android.text.InputFilter
import android.text.InputType
import android.os.Build
import android.os.Handler
import android.os.Looper
// ФИКС (v36): импорт SystemClock и поле nextActionAtMs удалены — после v35
// (убран обратный отсчёт) поле оставалось мёртвым: записывалось, но не читалось
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
// ФИЧА (v24): показ клавиатуры над оверлей-окном редактора задержки
import android.view.inputmethod.InputMethodManager
import android.widget.Button
// ФИЧА (v24): поле ввода задержки в редакторе плавающей панели
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
// ФИКС (v36): хардкод цветов заменён на палитру colors.xml (ContextCompat)
import androidx.core.content.ContextCompat
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

        // ФИКС: состояние службы (последний пресет) переживает перезапуск службы.
        const val STATE_PREFS = "service_state"
        private const val KEY_LAST_PRESET = "last_preset"
        private const val KEY_CROSSHAIR_HIDDEN = "crosshair_hidden"
        // ФИЧА (v23): панель и пузырь запоминают позицию — переживают
        // рестарт службы и сворачивание/разворачивание
        private const val KEY_PANEL_X = "panel_x"
        private const val KEY_PANEL_Y = "panel_y"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"

        // ФИКС: минимальная задержка между жестами — delay=0 превращал цикл в busy-poll
        private const val MIN_DELAY_MS = 20L

        // ФИЧА (v24): потолок задержки в редакторе плавающей панели —
        // согласован с MAX_DELAY_MS редактора в приложении (сутки)
        private const val MAX_PANEL_DELAY_MS = 24L * 60 * 60_000

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
        // ФИКС (v36): на Android 7.x–8.x (API < 28) система НЕ ставит флаг
        // инжектированным жестам → живой предпросмотр записи там отключён
        // (см. scheduleLiveReplay), иначе каждый предпросмотр записывался
        // бы как новое действие (список растёт сам)
        private const val FLAG_IS_GENERATED_GESTURE = 0x00000010

        // ФИКС: сколько подряд отменённых жестов считаем «жесты блокируются»
        private const val MAX_CONSECUTIVE_CANCELS = 5

        // ФИКС (v38, OOM): потолок записанных действий. Без него многочасовая
        // запись (пользователь ушёл, забыв остановить) росла без предела и
        // переполняла память. 5000 — достаточно для большинства реальных
        // сценариев (CLICK-игры, фарм, прокачка); на типичном пресете
        // 5000 действий × ~50 байт/запись ≈ 250 КБ — незаметно.
        private const val MAX_RECORDED_ACTIONS = 5000

        // ФИКС (v23): порог «тап или свайп» при записи — в DP, а не в сырых
        // пикселях. Было 40 px: на экранах с плотностью 3x это всего ~13dp
        // (лёгкий драг записывался тапом), на mdpi — целых 40dp (короткий
        // свайп не записывался). 20dp — одинаковое поведение на всех плотностях
        private const val SWIPE_THRESHOLD_DP = 20

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

    // ФИКС (v38, перф): кеш Vibrator. haptic() зовётся на каждое нажатие
    // кнопки панели/пузыря, на каждый drag-шаг и на долгий тап маркера.
    // getSystemService + cast на каждое нажатие — не бесплатно. Кешируем
    // один раз в onServiceConnected. null — если Vibrator недоступен
    // (странная прошивка) или getSystemService вернул null; haptic
    // тогда тихо выйдет
    private var vibrator: Vibrator? = null

    // Панель
    private var panel: View? = null
    private var toggleBtn: ImageButton? = null
    // v32: tvStatus (индикатор ● в шапке) удалён вместе с view — состояние
    // теперь читается только по иконке кнопки play (play/стоп/REC)

    // ФИЧА: счётчик кликов/таймер на панели
    private var tvCounter: TextView? = null

    // ФИЧА (v24): имя выбранного пресета на панели
    private var tvPresetName: TextView? = null

    // ФИЧА (v24): строка «Задержка: N мс» (только ST) — тап открывает редактор
    private var tvDelayValue: TextView? = null

    // ФИЧА (v24): кнопки навигации по действиям MTWS (слева/справа от play)
    private var prevBtnRef: ImageButton? = null
    private var nextBtnRef: ImageButton? = null

    // ФИЧА (v24): редактор задержки ST — отдельное ФОКУСИРУЕМОЕ окно
    // (без NOT_FOCUSABLE, иначе клавиатура не откроется)
    private var delayEditor: View? = null

    // ФИЧА (v31): невидимое окно 1×1 с FLAG_KEEP_SCREEN_ON — держит экран
    // включённым ПОКА ИДЁТ воспроизведение. Отдельное от панели окно, потому
    // что панель/пузырь могут быть скрыты (✕, сворачивание), а флаг
    // должен жить ровно столько, сколько играет пресет
    private var screenOnOverlay: View? = null

    // ФИЧА (v25): строка прогресса MTWS на панели — номер выполняемого
    // действия и обратный отсчёт до следующего (на месте бывшей паузы)
    private var tvProgress: TextView? = null

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
    private var bubbleIcon: ImageView? = null

    // ФИЧА (v25): прогресс MTWS для строки на панели — номер (1-based)
    // последнего запущенного действия. Пауза удалена (v25), обратный отсчёт
    // удалён (v35); ФИКС (v36): поле nextActionAtMs удалено как мёртвое —
    // после v35 оно только записывалось, но нигде не читалось
    private var progressActionNo = 0

    // ФИКС (v36, батарея): кэш последних текстов тикера — setText
    // одинаковой строки не вызывается (панель обновляется «событийно»,
    // а не 10 раз/сек)
    private var lastCounterText: String? = null
    private var lastProgressText: String? = null

    // ФИЧА: счётчик завершённых кликов за сессию
    private var clickCount = 0

    // ФИЧА: нумерованные точки MTWS (драг — переместить, долгий тап — удалить)
    // ФИКС (v38, перф): index — var (при переупорядочивании/добавлении
    // перенумеруется в in-place refresh без re-inflate), params хранится
    // здесь же — refreshMtwsMarkers обновляет позицию маркера через
    // тот же объект LayoutParams, что и touch-listener, без приведения типов
    private class MtwsMarker(val view: View, var index: Int, val params: WindowManager.LayoutParams)
    private val mtwsMarkers = mutableListOf<MtwsMarker>()

    // Оверлеи
    private var recordOverlay: View? = null
    private var recordParams: WindowManager.LayoutParams? = null
    // ФИЧА: стоп-кнопка в ОТДЕЛЬНОМ окне — на время живого предпросмотра окно
    // записи получает FLAG_NOT_TOUCHABLE (чтобы пропускать инжектируемый жест
    // в приложение), и кнопка «Остановить» обязана оставаться кликабельной
    private var recordStopBtn: View? = null
    // ФИЧА (v23): живой счётчик «Записано: N» на оверлее записи — прогресс
    // виден прямо на экране, без возврата в редактор
    private var recCountOverlayRef: TextView? = null
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

    // ФИЧА (v23/v25): тикер панели — таймер/счётчик и строка прогресса MTWS.
    // ФИКС (v36, батарея): интервал 100 → 250 мс (после v35 текст меняется
    // раз в секунду или при смене действия — чаще не нужно) + кэш последнего
    // текста в updateCounter/updateProgress: setText одинаковой строки больше
    // не дёргает TextView 10 раз/сек впустую. Экономия батареи на
    // многочасовом playback. Тикер безопасен при свёрнутой панели:
    // updateCounter()/updateProgress() просто выходят, если панель не показана
    // ФИКС (v38, перф, батарея): интервал 250 мс → 1000 мс. Тикер
    // обновляет секундный таймер (естественная 1-секундная гранулярность).
    // updateCounter с кэшем lastCounterText пропускает одинаковые setText —
    // но сами пробуждения хендлера 4 раза в секунду на многочасовом playback
    // всё равно будили CPU. 1 раз в секунду — достаточно.
    //
    // ФИЧА (v43): вернули updateProgress в тикер — он теперь тоже рисует
    // обратный отсчёт «· MM:SS» до следующего действия. 1-сек интервал
    // достаточен: отсчёт показывает минуты:секунды, ниже 1 секунды
    // granularity не нужна. Кэш lastProgressText пропускает одинаковые
    // setText — в ST-режиме или простое вызов дёшев (ранний return).
    private val uiTicker = object : Runnable {
        override fun run() {
            updateCounter()
            updateProgress()
            if (playing) handler.postDelayed(this, 1000L)
        }
    }

    private fun startUiTicker() {
        handler.removeCallbacks(uiTicker)
        uiTicker.run()
    }

    private fun stopUiTicker() {
        handler.removeCallbacks(uiTicker)
    }

    // Playback
    private var currentPreset: Preset? = null
    private var lastPreset: Preset? = null
    @Volatile private var playing = false
    private var actionIndex = 0
    private var cycleCount = 0
    private var startTimeMs = 0L

    // ФИЧА (v24/v27): позиция перемотки по действиям MTWS (кнопки ◀/▶ панели);
    // после остановки playback продолжает с последнего выполненного действия,
    // в простое — старт playback с выбранной этой позицией
    private var manualIndex = 0

    @Volatile private var gestureInFlight = false
    // ФИКС (v37, гонка): onCancelled/onCompleted приходят на binder-потоке,
    // а сбрасывается поле в startPlayback (главный поток) — без @Volatile
    // главный поток мог бы не увидеть инкремент от binder-потока
    @Volatile private var consecutiveCancels = 0

    // ФИКС (v38, перф): признак «tick сработал, но gestureInFlight=true —
    // ждём завершения жеста». Раньше tick в этом случае звал
    // handler.postDelayed(this, 20L) — busy-poll каждые 20 мс на длине
    // свайпа (до 5 секунд при swipeDurationMs=5000). Теперь tick ставит
    // флаг и возвращается, а gestureCallback.onCompleted/onCancelled
    // при завершении жеста постит tick снова. Доступ только из главного
    // потока (tick — главный, gestureCallback.post — главный), но
    // @Volatile для ясности и на случай будущих изменений
    @Volatile private var pendingTick = false

    // ФИЧА (v43): плановый момент СЛЕДУЮЩЕГО действия (System.currentTimeMillis()
    // + postDelayed, ставит tick при планировании) — по нему тикер панели
    // рисует обратный отсчёт в строке прогресса MTWS. Формат: «мин:сек» —
    // «Клик 3/15 · 01:05». 0 = отсчёта нет (простой/остановка/ST-режим).
    //
    // @Volatile: пишется из tick (главный поток), читается из updateProgress
    // (тоже главный, через uiTicker), но явная видимость между тактами
    // важна — иначе счётчик мог бы «залипнуть» на устаревшем значении.
    // История: поле было добавлено в v25 (строка прогресса), убрано в v35
    // (формат упрощён до «Клик N/M»), восстановлено в этом фиксе.
    @Volatile private var nextActionAtMs = 0L

    // Recording
    private var recording = false
    private var recordedActions = mutableListOf<PresetAction>()
    // ФИКС (v38, OOM): признак «достигнут потолок MAX_RECORDED_ACTIONS —
    // тост уже показан, новые действия не добавляем». Сбрасывается в
    // stopRecordingInternal. Без флага тост срабатывал бы на КАЖДЫЙ тап
    // после превышения — бесило бы пользователя
    private var recordingFullNotified = false
    // ФИКС (утечка памяти): как и pickOnDoneRef — колбэк хранится через
    // WeakReference, сильную ссылку держит SettingsActivity
    private var onRecordDoneRef: WeakReference<(List<PresetAction>) -> Unit>? = null
    // ФИКС: результат записи переживает пересоздание окна настроек —
    // забирается в SettingsActivity.onResume() через consumeRecordResult()
    private var lastRecordResult: List<PresetAction>? = null
    // ФИЧА (v39): single-action запись для вставки одного действия между
    // существующими. Колбэк получает ОДНО действие (tap или swipe —
    // определяется по движению пальца на оверлее) либо null, если
    // пользователь нажал «Остановить» без единого ACTION_UP. WeakReference
    // — сильную ссылку держит активность (pendingInsertCb), как и для
    // pick/record. recordedActions НЕ трогается — это для обычной записи,
    // single-action callers управляют своим списком сами
    private var singleActionCb: WeakReference<(PresetAction?) -> Unit>? = null
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
        // ФИКС (v38, перф): кешируем Vibrator один раз. На API 31+
        // достаём через VibratorManager.defaultVibrator (это и есть
        // Vibrator), ниже — старый VIBRATOR_SERVICE. runCatching на
        // случай странной прошивки, где cast мажет
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator
            }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching { getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }.getOrNull()
        }
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
            // ФИЧА (v24): окно редактора задержки — тоже чистим
            delayEditor?.let { runCatching { wm.removeView(it) } }
            // ФИЧА (v31): окно «экран не гаснет» — тоже чистим
            screenOnOverlay?.let { runCatching { wm.removeView(it) } }
            hideMtwsMarkers()
        }
        // ФИКС (утечка памяти): колбэки и результаты — вместе с окнами
        pickOnDoneRef = null
        onRecordDoneRef = null
        // ФИЧА (v39): то же для single-action колбэка
        singleActionCb = null
        pickEditorMode = "ST"
        editorTaskId = -1
        lastPickResult = null
        lastRecordResult = null
        panel = null; crosshair = null
        clickMarker = null; clickMarkerParams = null
        bubble = null; bubbleIcon = null
        presetList = null
        recordOverlay = null; pickOverlay = null
        recordStopBtn = null
        recCountOverlayRef = null
        delayEditor = null
        screenOnOverlay = null
        tvProgress = null
        lastCounterText = null
        lastProgressText = null
        // ФИКС (v38, перф): освобождаем кеш Vibrator — при следующем
        // onServiceConnected он пересоздастся (не обязательно, но
        // симметрично с другими полями)
        vibrator = null
        super.onDestroy()
    }

    fun isPlaying(): Boolean = playing

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

    /**
     * ФИКС (v42, баг S2): геттер для последнего активного пресета службы.
     * Служба хранит его в приватном поле lastPreset; SettingsActivity при
     * удалении пресета из PresetStorage должен проверить, не является ли
     * удалённый пресет активным в службе — и если да, очистить (иначе
     * «призрак» удалённого пресета продолжает работать после play с
     * плавающей панели).
     */
    fun getLastPreset(): Preset? = lastPreset

    /**
     * ФИКС (v42, баг S2): очистить lastPreset (и state-pref) — вызывается
     * из SettingsActivity.confirmDelete, когда пользователь удаляет пресет,
     * совпадающий с активным в службе. Без этого play с плавающей панели
     * после удаления продолжал бы играть УДАЛЁННЫЙ пресет (он переживал в
     * памяти службы + state-pref до перезапуска устройства).
     *
     * Безопасно вызывать во время playback — stopPlayback чистит оверлеи.
     * Вызов вне playback — тоже безопасно (stopPlayback выйдет рано по
     * !playing). Маркеры/крестик остаются как есть — после очистки их
     * не для чего показывать, но скрывать не обязательно: пользователь
     * увидит пустую панель при следующем открытии.
     */
    fun clearLastPreset() {
        if (playing) stopPlayback()
        lastPreset = null
        // ФИКС (v42, S5): commit, не apply — синхронная запись, чтобы
        // пользователь увидел пустую панель даже если сразу убьёт приложение
        runCatching {
            getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_LAST_PRESET).commit()
        }
        updatePanelState()
        updateBubbleIcon()
    }

    /**
     * ФИКС (v42, баг S6): обновить lastPreset, если сохранённый пресет
     * совпадает по (name, mode) с текущим активным. Без этого play с
     * плавающей панели после правки пресета в редакторе играл бы СТАРУЮ
     * версию — пользователь удивлялся, почему изменения «не применились».
     *
     * Возвращает true, если lastPreset был обновлён (совпал и заменён),
     * false — если не совпал (тогда пользователь должен явно выбрать
     * пресет через ☰ на плавающей панели).
     *
     * НЕ запускает playback, только подменяет пресет в памяти и state-pref.
     * Если playback уже идёт — обновляет currentPreset (новые параметры
     * применятся со следующего тика: задержка, координаты действий, и т.д.).
     */
    fun updateLastPresetIfMatches(preset: Preset): Boolean {
        val lp = lastPreset ?: return false
        if (lp.name != preset.name || lp.mode != preset.mode) return false
        // Присваиваем новое значение — copy с updated fields
        lastPreset = preset
        if (playing) currentPreset = preset
        persistLastPreset(preset)
        // Если playing, строки панели (progress, tvPresetName) обновятся
        // на следующем тике uiTicker'а — это нормально
        updatePanelState()
        return true
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

    /** ФИЧА: короткий клик-виброотклик (если включён HAPTIC_ENABLED).
     *  ФИКС (v38, перф): Vibrator кешируется в onServiceConnected —
     *  больше не дёргаем getSystemService и cast на каждое нажатие кнопки */
    private fun haptic() {
        if (!HAPTIC_ENABLED) return
        val v = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            else
                v.vibrate(20L)
        }
    }

    // ============================================================
    // Панель
    // ============================================================

    private fun showPanel() {
        if (panel != null) return
        // ФИЧА (v23): панель возвращается на ПОСЛЕДНЮЮ позицию — перетащенное
        // место запоминается и переживает рестарт службы
        val (px0, py0) = savedPos(KEY_PANEL_X, KEY_PANEL_Y, 40, 200)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            overlayFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px0
            y = py0
        }
        val v = runCatching { LayoutInflater.from(this).inflate(R.layout.panel, null) }
            .getOrElse { Log.e(TAG, "showPanel: inflate failed", it); return }
        panel = v
        if (runCatching { wm.addView(v, p) }.isFailure) {
            Log.e(TAG, "showPanel: addView failed")
            panel = null
            return
        }

        toggleBtn = v.findViewById(R.id.toggleBtn)
        tvCounter = v.findViewById(R.id.tvCounter)
        presetsBtnRef = v.findViewById(R.id.presetsBtn)
        crosshairBtnRef = v.findViewById(R.id.crosshairBtn)

        // ФИЧА (v24/v25): имя пресета, строка задержки, прогресс MTWS
        // и кнопки навигации
        tvPresetName = v.findViewById(R.id.tvPresetName)
        tvDelayValue = v.findViewById(R.id.tvDelayValue)
        tvProgress = v.findViewById(R.id.tvProgress)
        prevBtnRef = v.findViewById(R.id.btnPrevAction)
        nextBtnRef = v.findViewById(R.id.btnNextAction)
        // ФИКС (v27): перемотка — работает и ВО ВРЕМЯ playback (прыжок к
        // предыдущему/следующему действию), и в простое (старт с позиции)
        prevBtnRef?.setOnClickListener { haptic(); seekStep(-1) }
        nextBtnRef?.setOnClickListener { haptic(); seekStep(1) }
        tvDelayValue?.setOnClickListener { haptic(); showDelayEditor() }

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
                    // ФИЧА (v23): позиция запоминается при ОТПУСКАНИИ пальца —
                    // панель вернётся сюда после рестарта службы
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        savePos(KEY_PANEL_X, KEY_PANEL_Y, p.x, p.y)
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
                // ФИКС (v34): play стартует С ВЫБРАННОЙ ПОЗИЦИИ (перемотка
                // ◀/▶ больше не запускает скрипт — только выбирает её);
                // свежий пресет — manualIndex = 0, т.е. с первого действия
                lastPreset?.let { startPlayback(it, manualIndex) }
                    ?: toast("Сначала выберите пресет (☰)")
            }
        }

        v.findViewById<Button>(R.id.closeBtn)?.setOnClickListener {
            haptic()
            // ФИКС (v26): кнопка ✕ больше НЕ выключает службу Accessibility.
            // Раньше здесь вызывался disableSelf() — тумблер службы в системных
            // настройках сбрасывался, и при каждом запуске приложения права
            // Accessibility приходилось выдавать ЗАНОВО. Теперь панель просто
            // полностью скрывается, служба остаётся включённой; вернуть
            // панель — откройте приложение (появится автоматически)
            closeAllOverlays()
        }
        updatePanelState()
    }

    /** ФИЧА (v23): сохранённая позиция оверлея (панель/пузырь).
     *  Всё в runCatching: префы не должны ломать показ оверлея */
    private fun savedPos(keyX: String, keyY: String, defX: Int, defY: Int): Pair<Int, Int> =
        runCatching {
            val sp = getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            sp.getInt(keyX, defX) to sp.getInt(keyY, defY)
        }.getOrDefault(defX to defY)

    /** ФИЧА (v23): запомнить позицию оверлея (панель/пузырь) при перетаскивании */
    private fun savePos(keyX: String, keyY: String, x: Int, y: Int) {
        runCatching {
            getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putInt(keyX, x).putInt(keyY, y).apply()
        }
    }

    private fun updatePanelState() {
        // ФИКС (v32): раньше первой строкой был «val st = tvStatus ?: return» —
        // после удаления индикатора ● он всегда был бы null, и ВСЯ панель
        // (иконка play, имя пресета, задержка, прогресс) перестала бы
        // обновляться. Ранний return убран, состояние — только по иконке play:
        // REC = запись, стоп = играет, play = простой
        val tg = toggleBtn ?: return
        when {
            recording -> {
                // ФИЧА (v34): цветовые состояния кнопки — запись = СИНЯЯ пилюля
                tg.setBackgroundResource(R.drawable.btn_rec_blue)
                tg.setImageResource(R.drawable.ic_rec); tg.isEnabled = false
            }
            playing -> {
                // ФИЧА (v34): играет = КРАСНАЯ пилюля стоп
                tg.setBackgroundResource(R.drawable.btn_stop)
                tg.setImageResource(R.drawable.ic_stop); tg.isEnabled = true
            }
            else -> {
                // ФИЧА (v34): простой = ЗЕЛЁНАЯ пилюля play
                tg.setBackgroundResource(R.drawable.btn_play_green)
                tg.setImageResource(R.drawable.ic_play); tg.isEnabled = lastPreset != null
            }
        }
        // ФИЧА: во время работы кнопки пресетов/прицела блокируются
        val idle = !playing && !recording
        presetsBtnRef?.isEnabled = idle
        crosshairBtnRef?.isEnabled = idle
        // ФИЧА (v24): имя выбранного пресета на панели — видно, что запустит play
        tvPresetName?.text = when {
            lastPreset == null -> "Пресет не выбран"
            lastPreset?.mode == "MTWS" -> "MTWS · ${lastPreset?.name}"
            else -> lastPreset?.name ?: ""
        }
        // ФИЧА (v24): кнопки ◀/▶ навигации — только для MTWS-пресетов,
        // в простое (не playback/запись)
        val navVisible = if (lastPreset?.mode == "MTWS" &&
            lastPreset?.actions?.isNotEmpty() == true) View.VISIBLE else View.GONE
        prevBtnRef?.visibility = navVisible
        nextBtnRef?.visibility = navVisible
        // ФИКС (v27): перемотка ◀/▶ работает и ВО ВРЕМЯ playback (прыжок
        // к предыдущему/следующему действию, в простое — старт с позиции);
        // запрещена только на записи
        prevBtnRef?.isEnabled = !recording
        nextBtnRef?.isEnabled = !recording
        // ФИЧА (v24): строка «Задержка: N мс» — только для ST-пресетов;
        // редактирование разрешено и во время playback (правка применяется
        // со следующего тика), запрет — только на время записи
        val dv = tvDelayValue
        if (dv != null) {
            val pr = lastPreset
            if (pr != null && pr.mode == "ST") {
                dv.visibility = View.VISIBLE
                dv.text = if (pr.delayJitterMs > 0)
                    "Задержка: ${pr.delayMs}±${pr.delayJitterMs} мс"
                else
                    "Задержка: ${pr.delayMs} мс"
                dv.isEnabled = !recording
            } else {
                dv.visibility = View.GONE
            }
        }
        // ФИЧА (v25): строка ПРОГРЕССА MTWS — видна только для MTWS-пресетов
        // с действиями (занимает место бывшей паузы; для ST это место занимает
        // строка задержки). В playback текст обновляет тикер (250 мс, v36),
        // здесь — начальный текст и его сброс при смене состояния
        val pv = tvProgress
        if (pv != null) {
            val pr = lastPreset
            if (pr != null && pr.mode == "MTWS" && pr.actions.isNotEmpty()) {
                pv.visibility = View.VISIBLE
                updateProgress()
            } else {
                pv.visibility = View.GONE
            }
        }
        updateCrosshairButtonIcon()
        updateCounter()
    }

    /** ФИЧА: счётчик кликов + таймер сессии в шапке панели.
     *  v25: пауза удалена — время течёт, пока идёт playback.
     *  ФИКС (v36): setText только при изменении текста — тикер больше не
     *  перерисовывает TextView впустую */
    private fun updateCounter() {
        val tv = tvCounter ?: return
        val secs: Long = if (playing) (System.currentTimeMillis() - startTimeMs) / 1000 else 0L
        val s = "%d · %02d:%02d".format(clickCount, secs / 60, secs % 60)
        if (s != lastCounterText) {
            lastCounterText = s
            tv.text = s
        }
    }

    /**
     * ФИЧА (v25): строка прогресса MTWS на панели — на месте бывшей кнопки
     * паузы. ФОРМАТ (v35): «Клик 1/15» / «Свайп 3/15» — вид действия,
     * номер ТЕКУЩЕГО действия и всего записанных действий пресета.
     * В playback это выполняемое сейчас действие (progressActionNo),
     * в простое — ВЫБРАННОЕ перемоткой ◀/▶ (manualIndex; ничего не
     * выбрано — первое действие).
     * ФИЧА (v43): во время playback к строке добавлен ОБРАТНЫЙ ОТСЧЁТ
     * до следующего действия (плановый момент следующего действия ставит
     * tick при планировании, тикер обновляет каждую секунду). В v35 отсчёт
     * был убран — возвращён по просьбе. Формат: «мин:сек» —
     * «Клик 3/15 · 01:05», тот же вид %02d:%02d, что у таймера сессии
     * в шапке панели.
     * Для ST-пресетов строка скрыта целиком — её место занимает поле задержки
     */
    private fun updateProgress() {
        val pv = tvProgress ?: return
        val preset = lastPreset ?: return
        if (preset.mode != "MTWS" || preset.actions.isEmpty()) return
        val size = preset.actions.size
        // ФИЧА (v35): в playback — выполняемое действие (progressActionNo,
        // 1-based), в простое — выбранная перемоткой позиция старта
        // (manualIndex, 0-based; по умолчанию — первое действие)
        val idx = if (playing) progressActionNo.coerceIn(1, size) - 1
                  else manualIndex.coerceIn(0, size - 1)
        val kind = if (preset.actions[idx].type == "tap") "Клик" else "Свайп"
        // ФИЧА (v43): обратный отсчёт до следующего действия. Формат «мин:сек» —
        // минуты и секунды всегда двузначные, тот же вид %02d:%02d, что у
        // таймера сессии в шапке панели. coerceAtLeast(0) — действие вот-вот
        // начнётся, показываем 00:00, не отрицательное число
        val s = if (playing && nextActionAtMs > 0L) {
            val secs = ((nextActionAtMs - System.currentTimeMillis())
                .coerceAtLeast(0L) / 1000L).toInt()
            "$kind ${idx + 1}/$size · " + "%02d:%02d".format(secs / 60, secs % 60)
        } else {
            "$kind ${idx + 1}/$size"
        }
        // ФИКС (v36): setText только при изменении текста
        if (s != lastProgressText) {
            lastProgressText = s
            pv.text = s
        }
    }

    // ============================================================
    // ФИЧА (v24): ручная навигация по действиям MTWS + редактор
    // задержки ST прямо из плавающей панели
    // ============================================================

    /**
     * ФИКС (v27): ПЕРЕМОТКА MTWS-пресета кнопками ◀/▶ панели — работает
     * в двух режимах.
     * ВО ВРЕМЯ playback: ▶ немедленно выполняет следующее действие списка
     * (оставшаяся пауза пропускается), ◀ — ПРЕДЫДУЩЕЕ (только что
     * запущенное) действие; если ещё не выполнялось ничего — ◀ повторяет
     * первое; после переноса цикла — возвращает к последнему.
     * Воспроизведение НЕ останавливается: прыжок реализуется переносом
     * ближайшего тика (removeCallbacks(tick) + post(tick)), а все проверки
     * (летящий жест — цикл 20 мс, перенос цикла, лимит CYCLES) выполняет
     * сам tick — ЕДИНСТВЕННЫЙ источник исполнения действий.
     * ФИКС (v34): В ПРОСТОЕ перемотка больше НЕ ЗАПУСКАЕТ скрипт — только
     * выбирает позицию старта (manualIndex): ▶ — следующее действие,
     * ◀ — предыдущее (на первом — остаётся, после последнего — цикл к
     * первому). Выбранная позиция видна в строке прогресса («старт: N»).
     * ЗАПУСК — только с кнопки play (панель/пузырь), причём play стартует
     * С ВЫБРАННОЙ ПОЗИЦИИ — startPlayback(it, manualIndex).
     * Выбранная позиция видна в строке прогресса («Клик N/M», v35).
     * Во время ЗАПИСИ перемотка запрещена.
     */
    private fun seekStep(delta: Int) {
        val preset = lastPreset ?: return
        if (preset.mode != "MTWS" || preset.actions.isEmpty()) return
        if (recording) return
        val size = preset.actions.size
        if (playing) {
            val cp = currentPreset ?: return
            if (cp.actions.isEmpty()) return
            if (delta < 0) {
                // ◀: повторить предыдущее (только что запущенное) действие;
                // ничего ещё не выполнялось — повторить первое;
                // после переноса цикла — вернуться к последнему
                val t = if (actionIndex > 0) actionIndex - 1
                        else if (cycleCount > 0) size - 1
                        else 0
                actionIndex = t.coerceIn(0, size - 1)
                toast("Повтор действия: ${actionIndex + 1} из $size")
            } else {
                // ▶: следующим выполнится действие actionIndex; после
                // последнего списка tick сам перенесёт цикл к первому
                val n = if (actionIndex >= size) 1 else actionIndex + 1
                toast("Следующее действие: $n из $size")
            }
            // Прыжок = перенос ближайшего тика: оставшаяся пауза до следующего
            // действия пропускается; летящий жест tick дождётся через
            // gestureCallback.onCompleted (v38: busy-poll 20 мс убран,
            // ожидание полностью event-driven через pendingTick флаг)
            handler.removeCallbacks(tick)
            handler.post(tick)
        } else {
            // ФИКС (v34): перемотка в простое больше НЕ ЗАПУСКАЕТ скрипт —
            // только выбирает позицию, с которой его запустит кнопка play
            // (панель или пузырь). Раньше здесь был startPlayback(preset, idx)
            val t = if (delta < 0) {
                if (manualIndex <= 0) 0 else manualIndex - 1
            } else {
                if (manualIndex >= size - 1) 0 else manualIndex + 1
            }
            manualIndex = t.coerceIn(0, size - 1)
            updateProgress()
            toast("Выбрано действие: ${manualIndex + 1} из $size")
        }
    }

    /**
     * ФИЧА (v24): редактор задержки между кликами ST-пресета прямо из панели.
     * Отдельное ФОКУСИРУЕМОЕ окно (БЕЗ FLAG_NOT_FOCUSABLE) — иначе клавиатура
     * не откроется: основная панель всегда NOT_FOCUSABLE, чтобы не красть
     * клавиши у приложения под ней. «Сохранить» пишет значение в последний
     * пресет, в хранилище пресетов (переживает рестарт) и в идущий playback
     * (со следующего тика), «Отмена» закрывает без изменений.
     */
    private fun showDelayEditor() {
        if (delayEditor != null) return
        val preset = lastPreset ?: return
        if (preset.mode != "ST") return
        if (recording) return
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(px(14), px(12), px(14), px(14))
        }

        val title = TextView(this).apply {
            text = "Задержка между кликами"
            textSize = 14f
            setTextColor(context.getColor(R.color.text_primary))
        }
        box.addView(title)

        val subtitle = TextView(this).apply {
            text = "Пресет «${preset.name}» · миллисекунды (20 — сутки)"
            textSize = 11f
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, px(2), 0, px(8))
        }
        box.addView(subtitle)

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(8))
            setText(preset.delayMs.toString())
            // Урок v7: цвета текста/хинта задаются ЯВНО — на тёмном фоне
            // дефолтные чёрные цифры не видны
            setTextColor(context.getColor(R.color.text_primary))
            setHintTextColor(context.getColor(R.color.text_hint))
            setBackgroundResource(R.drawable.bg_input)
            setPadding(px(10), px(8), px(10), px(8))
        }
        box.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(10), 0, 0)
        }
        val okBtn = Button(this).apply {
            text = "Сохранить"
            textSize = 13f
            isAllCaps = false
            // ФИКС (v36): белый из палитры colors.xml вместо хардкода
            setTextColor(ContextCompat.getColor(context, R.color.white))
            setBackgroundResource(R.drawable.btn_primary)
            stateListAnimator = null
            setOnClickListener {
                if (applyDelayFromEditor(input)) closeDelayEditor()
            }
        }
        val cancelBtn = Button(this).apply {
            text = "Отмена"
            textSize = 13f
            isAllCaps = false
            setTextColor(context.getColor(R.color.text_primary))
            setBackgroundResource(R.drawable.btn_secondary)
            stateListAnimator = null
            setOnClickListener { closeDelayEditor() }
        }
        row.addView(okBtn, LinearLayout.LayoutParams(0, px(40), 1.5f).apply { marginEnd = px(8) })
        row.addView(cancelBtn, LinearLayout.LayoutParams(0, px(40), 1f))
        box.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // ВАЖНО: БЕЗ FLAG_NOT_FOCUSABLE — окно принимает ввод и клавиатуру
            overlayFlags(0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            width = px(248)
        }
        delayEditor = box
        if (runCatching { wm.addView(box, p) }.isFailure) {
            Log.e(TAG, "showDelayEditor: addView failed")
            delayEditor = null
            return
        }
        input.requestFocus()
        handler.post {
            runCatching {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                    as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    /** ФИЧА (v24): закрыть редактор задержки (окно + ссылка) */
    private fun closeDelayEditor() {
        delayEditor?.let { runCatching { wm.removeView(it) } }
        delayEditor = null
    }

    /** ФИЧА (v24): применить введённую задержку — в последний пресет,
     *  в хранилище пресетов (та же пара имя+режим — заменяется) и в идущий
     *  playback (currentPreset — со следующего тика). false — ввод не число */
    private fun applyDelayFromEditor(input: EditText): Boolean {
        val preset = lastPreset ?: return false
        val raw = input.text.toString().trim().toLongOrNull()
        if (raw == null) {
            toast("Введите число в миллисекундах")
            return false
        }
        val v = raw.coerceIn(MIN_DELAY_MS, MAX_PANEL_DELAY_MS)
        val updated = preset.copy(delayMs = v)
        lastPreset = updated
        if (playing) currentPreset = updated
        persistLastPreset(updated)
        PresetStorage.save(this, updated)
        updatePanelState()
        toast(if (v != raw)
            "Задержка: $v мс (потолок — сутки)"
        else
            "Задержка сохранена: $v мс")
        return true
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

    /**
     * ФИЧА: перетаскивание крестика двигает точку тапа ST-пресета.
     *
     * ФИКС (v37, баг 1.9): раньше метод звал ТОЛЬКО persistLastPreset —
     * обновлял «последний пресет» службы (state-pref), но НЕ звал
     * PresetStorage.save. В итоге правки крестика с плавающей панели
     * переживали рестарт службы, но НЕ переживали переоткрытие окна
     * настроек и выбор пресета из списка (тот читает из PresetStorage,
     * а там — старая точка). Теперь коммитим в постоянное хранилище.
     *
     * Разделено на два режима вызова, чтобы не гонять JSON-сериализатор
     * и SharedPreferences на каждом MOVE (а MOVE при драге идёт
     * десятками в секунду):
     *  - persistToStorage = false (по умолчанию): только in-memory +
     *    state-pref (apply-асинхронно, дёшево). Зовём из ACTION_MOVE.
     *  - persistToStorage = true: ещё и PresetStorage.save. Зовём из
     *    ACTION_UP — один JSON-сериализатор + SharedPreferences на драг.
     *
     * Если позиция не изменилась — early-return, но при persistToStorage
     * = true всё равно коммитим (вызов мог прийти на UP без предшествующего
     * MOVE, а в lastPreset могли быть несохранённые правки из другого места).
     */
    private fun saveCrosshairCenterToPreset(persistToStorage: Boolean = false) {
        val preset = lastPreset ?: return
        if (preset.mode != "ST" || preset.actions.isEmpty()) return
        val nx = crosshairCenterX.toInt()
        val ny = crosshairCenterY.toInt()
        val old = preset.actions[0]
        val changed = old.x1 != nx || old.y1 != ny
        if (changed) {
            lastPreset = preset.copy(actions = preset.actions.mapIndexed { i, act ->
                if (i == 0) act.copy(x1 = nx, y1 = ny) else act
            })
            persistLastPreset(lastPreset!!)
        }
        // ФИКС (v37): фикс бага 1.9 — правки крестика не теряются при
        // переоткрытии редактора и рестарте службы. Коммит в постоянное
        // хранилище — только при отпускании пальца (persistToStorage = true).
        // Идемпотентен: save перезаписывает пресет с тем же (name, mode).
        if (persistToStorage) {
            PresetStorage.save(this, lastPreset!!)
        }
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
        // ФИЧА (v24): позиция ручной навигации сбрасывается — индекс от
        // ПРЕДЫДУЩЕГО пресета может выйти за границы нового списка действий
        manualIndex = 0
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
        toggleBtn = null
        tvCounter = null
        presetsBtnRef = null
        crosshairBtnRef = null
        // ФИЧА (v24/v25): ссылки на новые элементы панели — вместе с панелью;
        // редактор задержки (отдельное окно) закрывается, иначе останется висеть
        tvPresetName = null
        tvDelayValue = null
        tvProgress = null
        prevBtnRef = null
        nextBtnRef = null
        closeDelayEditor()
        showBubble()
    }

    private fun expandFromBubble() {
        hideBubble()
        showPanel()
    }

    private fun showBubble() {
        if (bubble != null) return
        // ФИЧА (v23): пузырь возвращается на ПОСЛЕДНЮЮ позицию
        val (bx0, by0) = savedPos(KEY_BUBBLE_X, KEY_BUBBLE_Y, 48, 200)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            overlayFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bx0
            y = by0
        }
        val v = runCatching { LayoutInflater.from(this).inflate(R.layout.bubble, null) }
            .getOrElse { Log.e(TAG, "showBubble: inflate failed", it); return }
        bubble = v
        bubbleIcon = v.findViewById(R.id.bubbleIcon)
        if (runCatching { wm.addView(v, p) }.isFailure) {
            Log.e(TAG, "showBubble: addView failed")
            bubble = null
            bubbleIcon = null
            return
        }
        updateBubbleIcon()

        // ФИЧА (v33): тап — СТАРТ/СТОП выбранного пресета (как кнопка play
        // панели), УДЕРЖАНИЕ — развернуть панель обратно, драг — переместить.
        // Раньше тап разворачивал панель, а удержание (v26) скрывало все
        // оверлеи — это действие с пузыря снято, оно осталось за ✕ панели
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var longPressFired = false
        val longPressExpand = Runnable {
            longPressFired = true
            haptic()
            expandFromBubble()
        }
        v.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            var moved = false
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = p.x; sy = p.y
                        tx = e.rawX; ty = e.rawY
                        moved = false
                        longPressFired = false
                        handler.postDelayed(longPressExpand, longPressTimeout)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - tx
                        val dy = e.rawY - ty
                        if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            moved = true
                            // палец поехал — удержание отменяется
                            handler.removeCallbacks(longPressExpand)
                        }
                        if (moved) {
                            p.x = sx + dx.toInt()
                            p.y = sy + dy.toInt()
                            runCatching { wm.updateViewLayout(view, p) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longPressExpand)
                        if (!moved && !longPressFired) {
                            // ФИЧА (v33): тап по пузырю = play/стоп выбранного
                            // пресета — тот же сценарий, что у кнопки play панели
                            bubbleTogglePlayback()
                        } else if (moved) {
                            // ФИЧА (v23): позиция пузыря запоминается после драга
                            savePos(KEY_BUBBLE_X, KEY_BUBBLE_Y, p.x, p.y)
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(longPressExpand)
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
        bubbleIcon = null
    }

    /** ФИЧА (v33): тап по пузырю — тот же сценарий, что у кнопки play панели:
     *  играет → стоп; иначе старт выбранного пресета. На записи запуск
     *  запрещён (у пузыря нет isEnabled, как у кнопки панели, — поэтому
     *  честный тост вместо тихого игнора) */
    private fun bubbleTogglePlayback() {
        haptic()
        when {
            recording -> toast("Идёт запись — сначала остановите её")
            playing -> stopPlayback()
            else -> lastPreset?.let { startPlayback(it, manualIndex) }
                ?: toast("Сначала выберите пресет (☰)")
        }
    }

    /**
     * ФИКС (v26): полное скрытие плавающего интерфейса БЕЗ выключения службы
     * Accessibility. Раньше кнопка ✕ панели вызывала disableSelf() — тумблер
     * службы в системных настройках сбрасывался, и при каждом запуске
     * приложения приходилось заново выдавать права Accessibility. Теперь
     * служба живёт, пока пользователь не выключит её сам в настройках;
     * панель возвращается при открытии приложения
     * (MainActivity.onResume -> ensureOverlays).
     * Вызывается из кнопки ✕ панели и из удержания пузыря.
     * Все шаги идемпотентны: stopPlayback/stopRecordingInternal выходят
     * рано, hide* переживают повторный вызов — можно жать на что угодно.
     */
    private fun closeAllOverlays() {
        closeDelayEditor()
        stopPlayback()           // no-op, если не играет
        stopRecordingInternal()  // no-op, если не идёт запись
        hidePresetList()
        hideBubble()
        hideCrosshair()
        hideMtwsMarkers()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        toggleBtn = null
        tvCounter = null
        presetsBtnRef = null
        crosshairBtnRef = null
        tvPresetName = null
        tvDelayValue = null
        tvProgress = null
        prevBtnRef = null
        nextBtnRef = null
        toast("Панель скрыта — откройте приложение, чтобы вернуть")
    }

    /** ФИЧА (v33): иконка пузыря = play/стоп — тап запускает и останавливает
     *  выбранный пресет (раньше пузырь только разворачивал панель, и стояла
     *  иконка «развернуть» ic_expand — специально НЕ play, чтобы не путать;
     *  теперь семантика тапа совпадает с иконкой).
     *  ФИЧА (v34): цвета едины с новой схемой кнопок —
     *  ЗЕЛЁНЫЙ play — простой, КРАСНЫЙ стоп — играет,
     *  СИНИЙ REC — идёт запись (тап заблокирован) */
    private fun updateBubbleIcon() {
        val iv = bubbleIcon ?: return
        when {
            recording -> {
                iv.setImageResource(R.drawable.ic_rec)
                // ФИКС (v36): цвет из палитры colors.xml (был хардкод 0xFF5B8CFF)
                iv.setColorFilter(ContextCompat.getColor(this, R.color.accent_blue))
            }
            playing -> {
                iv.setImageResource(R.drawable.ic_stop)
                // ФИКС (v36): цвет из палитры colors.xml (был хардкод 0xFFFF4B4B)
                iv.setColorFilter(ContextCompat.getColor(this, R.color.accent_red))
            }
            else -> {
                iv.setImageResource(R.drawable.ic_play)
                // ФИКС (v36): цвет из палитры colors.xml (был хардкод 0xFF00E676)
                iv.setColorFilter(ContextCompat.getColor(this, R.color.accent_green))
            }
        }
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
                        // ФИКС (v37): только in-memory + state-pref на каждом
                        // MOVE (дёшево). Постоянное хранилище PresetStorage.save
                        // — на ACTION_UP, иначе JSON-сериализация и
                        // SharedPreferences-запись на десятках событий в секунду
                        saveCrosshairCenterToPreset(persistToStorage = false)
                        return true
                    }
                    // ФИКС (v37, баг 1.9): ACTION_UP отсутствовал — правки
                    // крестика не коммитились в постоянное хранилище. Теперь
                    // при отпускании пальца зовём PresetStorage.save.
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        saveCrosshairCenterToPreset(persistToStorage = true)
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
        // ФИКС (v36): цвет из палитры colors.xml (был хардкод 0xFF00CC44 —
        // близкий к accent_green оттенок; теперь единый зелёный палитры)
        markerText?.setTextColor(ContextCompat.getColor(this, R.color.accent_green))

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
     *
     * ФИКС (v38, перф): раньше полный re-build на каждом вызове —
     * removeView всех маркеров + inflate заново R.layout.point_marker
     * для каждого действия. На пресете из 20 точек это 20 inflations
     * за один вызов. Вызывается часто: stopPlayback, hidePickOverlay,
     * toggleCrosshair, ensureOverlays, stopRecordingInternal — то есть
     * почти из каждого пользовательского действия. Теперь если размер
     * списка действий НЕ изменился — in-place обновление позиций и
     * номеров через wm.updateViewLayout + setText, без re-inflate.
     * Полный re-build только при изменении размера (добавление/удаление
     * точки, смена пресета).
     */
    private fun refreshMtwsMarkers() {
        // Сначала все охранные выходы — они зовут hideMtwsMarkers()
        // (полный tear-down). Если их пропустить, а потом пойти в
        // in-place путь, маркеры останутся на экране, где их быть не должно.
        if (playing || recording || pickOverlay != null) {
            hideMtwsMarkers()
            return
        }
        if (crosshairHidden) {
            hideMtwsMarkers()
            return
        }
        val preset = lastPreset ?: run {
            hideMtwsMarkers()
            return
        }
        if (preset.mode != "MTWS") {
            hideMtwsMarkers()
            return
        }
        if (preset.actions.isEmpty()) {
            hideMtwsMarkers()
            return
        }

        val newSize = preset.actions.size
        val currentSize = mtwsMarkers.size
        if (newSize == currentSize && currentSize > 0) {
            // In-place: размер не изменился — двигаем окна и обновляем
            // номера. Позиции могут отличаться (пользователь перетащил
            // крестик, или пресет подменён через PresetStorage.save).
            val half = 16f * resources.displayMetrics.density // маркер 32dp
            for (i in 0 until newSize) {
                val marker = mtwsMarkers[i]
                marker.index = i
                val a = preset.actions[i]
                val p = marker.params
                p.x = (a.x1.toFloat() - half).toInt()
                p.y = (a.y1.toFloat() - half).toInt()
                marker.view.findViewById<TextView>(R.id.tvPointNum)?.text = (i + 1).toString()
                runCatching { wm.updateViewLayout(marker.view, p) }
            }
            return
        }

        // Размер изменился (добавили/удалили точку или сменили пресет) —
        // полный re-build. Перенумерация всех маркеров обязательна: при
        // удалении точки i все маркеры после i должны получить новые номера.
        hideMtwsMarkers()
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

        // ФИКС (v38, перф): params хранится в маркере, refresh обновляет
        // позицию через тот же объект — touch-listener и refresh разделяют
        // состояние без рассинхрона
        val marker = MtwsMarker(v, index, p)
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
        // ФИКС (v37, баг 1.9): раньше правка не коммитилась в постоянное
        // хранилище пресетов — переживала рестарт службы (state-pref), но
        // терялась при переоткрытии редактора (тот читает из PresetStorage).
        // Долгий тап — одноразовое событие, без нагрузки на драг/такт.
        PresetStorage.save(this, lastPreset!!)
        // ФИЧА (v24): позиция ручной навигации прижимается к новому размеру
        // списка — после удаления точки индекс может стать недействительным
        if (manualIndex >= lastPreset!!.actions.size) {
            manualIndex = lastPreset!!.actions.size - 1
        }
        if (manualIndex < 0) manualIndex = 0
        mtwsMarkers.removeAll { it.view === view }
        runCatching { wm.removeView(view) }
        toast("Точка ${marker.index + 1} удалена")
        // Пересобрать оставшиеся маркеры — они перенумеруются
        refreshMtwsMarkers()
        // ФИЧА (v25): строка прогресса показывает новый размер списка
        updateProgress()
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
        // ФИКС (v37, баг 1.9): раньше правка не коммитилась в постоянное
        // хранилище — переживала рестарт службы (state-pref), но терялась
        // при переоткрытии редактора (читает из PresetStorage). Зовётся
        // на ACTION_UP — одноразовое событие, дёргать JSON-сериализатор
        // и SharedPreferences можно.
        PresetStorage.save(this, lastPreset!!)
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
            // ФИКС (v22): помощников возвращаем с учётом кнопки ⊘ — раньше
            // крестик появлялся даже при скрытых через панель помощниках
            if (!crosshairHidden) crosshair?.visibility = View.VISIBLE
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
    // ФИКС (v27): startIndex — старт с выбранной позиции перемотки (◀/▶ в
    // простое); дефолт 0 — прежнее поведение (кнопка play, запуск из редактора)
    fun startPlayback(preset: Preset, startIndex: Int = 0): Boolean {
        if (playing || recording) return false
        if (preset.mode == "MTWS" && preset.actions.isEmpty()) return false

        // Стартовая позиция прижимается к границам списка (для ST — всегда 0)
        val sIdx = if (preset.mode == "MTWS" && preset.actions.isNotEmpty())
            startIndex.coerceIn(0, preset.actions.size - 1)
        else 0

        lastPreset = preset
        persistLastPreset(preset)
        currentPreset = preset
        actionIndex = sIdx
        cycleCount = 0
        startTimeMs = System.currentTimeMillis()
        // ФИКС (v29): «промывка» диспетчера — застрявший в очереди прошивки
        // штрих прошлой сессии отменяется новым dispatchGesture
        cancelStaleGesture()
        gestureInFlight = false
        consecutiveCancels = 0
        // ФИКС (v38, перф): сброс pendingTick — мог остаться от прошлой
        // сессии, если она завершилась через stopPlayback посреди жеста
        pendingTick = false
        clickCount = 0
        playing = true
        // ФИЧА (v31): экран не гаснет, пока играет пресет (паузы MTWS
        // бывают длинными — при затухании дисплея жесты перестают попадать)
        showScreenOnOverlay()
        // ФИЧА (v24/v27): позиция навигации = стартовая; редактор задержки
        // закрывается — фокус уходит панели
        manualIndex = sIdx
        // ФИЧА (v25/v27): строка прогресса до первого тика покажет действие,
        // с которого стартовали (1-based); при sIdx = 0 — как раньше («Клик 1/N»)
        progressActionNo = sIdx + 1
        // ФИЧА (v43): сброс обратного отсчёта — настоящий момент следующего
        // действия поставит первый tick, до него отсчёта нет (строка прогресса
        // покажет «Клик N/M» без «· MM:SS»)
        nextActionAtMs = 0L
        closeDelayEditor()

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
        // ФИЧА (v23): секундный тикер панели — таймер/счётчик обновляются
        // каждую секунду, а не только в момент клика
        startUiTicker()
        return true
    }

    /** ФИЧА (v34): resetToStart = true — остановка ПО ЕСТЕСТВЕННОМУ
     *  завершению (кончились циклы/время): следующий старт — С ПЕРВОГО
     *  действия. Ручной стоп (кнопка/ошибка жестов) сохраняет позицию
     *  для продолжения с места остановки */
    fun stopPlayback(resetToStart: Boolean = false) {
        if (!playing) return
        playing = false
        // ФИЧА (v31): экран больше не удерживается включённым
        hideScreenOnOverlay()
        // ФИКС (v29): «призрачный клик» после остановки. dispatchGesture
        // асинхронен: если инжекция совпала с физическим касанием экрана
        // (палец на кнопке панели или касание во время работы пресета),
        // часть прошивок (MIUI/HyperOS) НЕ проигрывает штрих сразу, а
        // ставит его в очередь системного ввода и проигрывает при
        // СЛЕДУЮЩЕМ касании — тап доигрывается в точке пресета уже после
        // остановки. removeCallbacks снимает только наши колбэки —
        // застрявшему в очереди СИСТЕМЫ штриху он не указ. Новый
        // dispatchGesture по контракту отменяет текущие жесты сервиса
        cancelStaleGesture()
        gestureInFlight = false
        // ФИКС (v38, перф): сброс pendingTick — мог быть выставлен tick'ом
        // перед самым stopPlayback. Даже если оставить, gestureCallback
        // посмотрит на playing==false и не постит tick — но флаг
        // очистим явно, чтобы не было «висячего» true между сессиями
        pendingTick = false
        // ФИЧА (v43): сброс обратного отсчёта — playback остановлен, никакого
        // «следующего действия» нет, строка прогресса покажет финальное
        // действие без «· MM:SS» (либо выбранную перемоткой позицию в простое)
        nextActionAtMs = 0L
        // ФИЧА (v24): ручная навигация продолжает с места остановки — последним
        // выполненным считаем действие, на котором playback остановился.
        // После полного прогона цикла (actionIndex уже обнулился) — последнее
        // действие списка; если не выполнилось ни одного — первое
        val sp = currentPreset
        if (sp != null && sp.mode == "MTWS" && sp.actions.isNotEmpty()) {
            // ФИЧА (v34): естественное завершение — сброс позиции (старт с начала);
            // ручной стоп — продолжение с места остановки (последним считаем
            // действие, на котором playback остановился)
            manualIndex = if (resetToStart) 0 else when {
                actionIndex > 0 -> actionIndex - 1
                cycleCount > 0 -> sp.actions.size - 1
                else -> 0
            }.coerceIn(0, sp.actions.size - 1)
        }
        handler.removeCallbacks(tick)
        // ФИЧА (v23): тикер панели больше не нужен
        stopUiTicker()
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

    /** ФИЧА (v31): экран НЕ гаснет во время воспроизведения.
     *  Невидимое окно 1×1 в углу экрана с FLAG_KEEP_SCREEN_ON: пока такое
     *  окно видимо, система не выключает дисплей по таймауту. Стандартный
     *  приём для оверлеев: флаг работает на уровне ОКНА (не области) —
     *  достаточно 1×1 пикселя. Окно сделано FLAG_NOT_TOUCHABLE (не мешает
     *  жестам и dispatchGesture) и FLAG_NOT_FOCUSABLE (не отбирает фокус
     *  у приложений и клавиатуры). Отдельно от панели, потому что панель
     *  может пересоздаваться/скрываться, а флаг должен держаться ровно
     *  на время playback; снимается в stopPlayback (в т.ч. из ✕) и в onDestroy */
    private fun showScreenOnOverlay() {
        if (screenOnOverlay != null) return
        if (!::wm.isInitialized) return
        runCatching {
            val v = View(this)
            val p = WindowManager.LayoutParams(
                1,
                1,
                overlayType(),
                overlayFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                ),
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            wm.addView(v, p)
            screenOnOverlay = v
        }.onFailure { Log.e(TAG, "showScreenOnOverlay failed", it) }
    }

    /** ФИЧА (v31): снять окно «экран не гаснет» (остановка playback,
     *  ✕ через closeAllOverlays -> stopPlayback, уничтожение службы) */
    private fun hideScreenOnOverlay() {
        screenOnOverlay?.let { runCatching { wm.removeView(it) } }
        screenOnOverlay = null
    }

    /** ФИКС (v29): «промывка» диспетчера жестов. Диспатчится безобидный
     *  1-мс штрих в углу экрана (1,1)->(2,2): по контракту dispatchGesture
     *  система отменяет все текущие жесты ЭТОГО сервиса, включая штрих,
     *  застрявший в очереди ввода. Отдельный колбэк БЕЗ счётчиков:
     *  отменённый/завершённый flush не должен попадать в clickCount
     *  и не должен накручивать consecutiveCancels (иначе возможны
     *  ложные «Жесты блокируются») */
    private val flushCallback = object : GestureResultCallback() {}

    private fun cancelStaleGesture() {
        if (!gestureInFlight) return
        val path = Path().apply { moveTo(1f, 1f); lineTo(2f, 2f) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 1L)
        val g = GestureDescription.Builder().addStroke(stroke).build()
        val ok = runCatching { dispatchGesture(g, flushCallback, null) }
            .getOrElse { e ->
                Log.e(TAG, "dispatchGesture(flush) error", e)
                false
            }
        dbg { "cancelStaleGesture ok=$ok" }
        gestureInFlight = false
    }

    private val gestureCallback = object : GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) {
            // ФИКС (v37, краш CalledFromWrongThreadException): колбэк
            // приходит на binder-потоке, а не на главном. Раньше здесь
            // звались clickCount++ и updateCounter(), которая пишет в
            // tvCounter.text — это нелегально с не-UI потока и роняло
            // приложение на части прошивок (особенно при быстрых тапах,
            // когда onCompleted приходил раньше, чем UI успевал уйти).
            // Флаги @Volatile обновляем синхронно — они нужны для
            // разблокировки следующего tick'а.
            gestureInFlight = false
            consecutiveCancels = 0
            // UI-мутации — через handler.post, всегда на главном
            handler.post {
                // ФИЧА: считаем только реально завершённые жесты.
                // (v27: отдельных «ручных шагов мимо playback» больше нет —
                // перемотка ◀/▶ исполняет действия через tick, поэтому КАЖДЫЙ
                // завершённый жест — клик сценария и попадает в счётчик)
                clickCount++
                updateCounter()
                // ФИКС (v38, перф): разбудить tick, если он ждал завершения
                // жеста (pendingTick=true). См. комментарий в tick — раньше
                // там был busy-poll каждые 20 мс.
                if (pendingTick) {
                    pendingTick = false
                    if (playing) handler.post(tick)
                }
            }
        }

        override fun onCancelled(g: GestureDescription?) {
            // ФИКС (v37, краш): см. onCompleted — UI-мутации через handler.post.
            // Флаги обновляем синхронно (gestureInFlight — иначе tick зависнет
            // в ожидании несуществующего «летающего» жеста, т.к. busy-poll
            // убран в v38 и ожидание стало полностью event-driven)
            gestureInFlight = false
            // ФИКС: серия подряд отменённых жестов обычно означает, что жесты
            // блокирует оверлей либо координаты вне экрана. Раньше playback
            // крутился вхолостую бесконечно — теперь останавливаемся и сообщаем.
            consecutiveCancels++
            if (playing && consecutiveCancels >= MAX_CONSECUTIVE_CANCELS) {
                Log.w(TAG, "gestures cancelled $consecutiveCancels times in a row — stopping")
                // ФИКС (v37, краш): stopPlayback трогает wm.removeView,
                // updatePanelState, refreshMtwsMarkers, toast — всё это
                // незаконно с binder-потока. Уходим на главный.
                // Несколько подряд onCancelled могут запостить несколько
                // stopPlayback — это безопасно: первый поставит playing=false,
                // остальные выйдут по раннему return в stopPlayback.
                handler.post {
                    stopPlayback()
                    toast("Жесты блокируются — воспроизведение остановлено")
                }
            } else if (playing) {
                // ФИКС (v38, перф): отмена ниже порога MAX_CONSECUTIVE_CANCELS —
                // playback продолжается, нужно разбудить tick (иначе он зависнет
                // в ожидании onCompleted, который не придёт — жест отменён)
                handler.post {
                    if (pendingTick) {
                        pendingTick = false
                        handler.post(tick)
                    }
                }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            val preset = currentPreset ?: run { stopPlayback(); return }

            val shouldStop: Boolean = when (preset.timingMode) {
                "DURATION" -> (System.currentTimeMillis() - startTimeMs) / 1000 >= preset.durationSec
                "CYCLES" -> cycleCount >= preset.cycles
                else -> false
            }
            if (shouldStop) { stopPlayback(resetToStart = true); return }

            if (gestureInFlight) {
                // ФИКС (v38, перф): РАНЬШЕ — handler.postDelayed(this, 20L),
                // busy-poll каждые 20 мс, пока летит жест. На длинных свайпах
                // (swipeDurationMs до 5000 мс) это до 250 бессмысленных
                // postDelayed в секунду. ТЕПЕРЬ — ставим pendingTick=true
                // и выходим; gestureCallback.onCompleted/onCancelled при
                // завершении жеста постит tick снова (см. handler.post в
                // gestureCallback). Если к этому моменту playing уже false
                // (стоп), tick.run выйдет по раннему return — флаг безопасен.
                pendingTick = true
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
                        stopPlayback(resetToStart = true); return
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
                // ФИЧА (v25): строка прогресса — номер только что запущенного
                // действия (actionIndex уже инкрементирован, т.е. 1-based).
                progressActionNo = actionIndex
                // ФИЧА (v43): запоминаем плановый момент следующего действия —
                // тикер рисует по нему обратный отсчёт «· MM:SS» в строке
                // прогресса. Берём nextDelay (задержка после только что
                // выполненного действия либо repeatIntervalMs в конце цикла),
                // прижатый снизу к MIN_DELAY_MS — чтобы отсчёт всегда был
                // неотрицательным. Системное время + задержка = момент
                // запуска следующего tick'а, и именно до него считаем.
                nextActionAtMs = System.currentTimeMillis() +
                    nextDelay.coerceAtLeast(MIN_DELAY_MS)
                // ФИКС (v38, перф): обновляем прогресс напрямую из tick —
                // мгновенно при смене действия. uiTicker потом доведёт
                // отсчёт до актуальной секунды (см. фикс 2.6: 1-сек интервал)
                updateProgress()
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
        // ФИКС (v38, OOM): сброс флага потолка при старте новой сессии —
        // даже если предыдущая завершилась не через stopRecordingInternal
        // (например,.onDestroy службы)
        recordingFullNotified = false
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
        updateBubbleIcon()  // v33: пузырь показывает красный REC и не запускается тапом
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
        // ФИКС (v38, OOM): сброс флага потолка — следующая запись снова
        // копит до MAX_RECORDED_ACTIONS
        recordingFullNotified = false
        // ФИКС (v39): если stopRecordingInternal зовут в single-action режиме
        // (внешний вызов ✕ панели, например), обнуляем колбэк. Сам action
        // при этом потерян — но это редакс stopRecordingInternal, а не
        // stopSingleActionRecording; если caller хочет получить действие,
        // он должен звать stopSingleActionRecording. Для ✕ панели потеря
        // приемлема — пользователь явно отменил
        singleActionCb = null
        hideRecordOverlay()
        val result = recordedActions.toList()
        // Страховка: результат переживает уничтожение окна настроек —
        // его заберёт SettingsActivity.onResume() через consumeRecordResult()
        lastRecordResult = result
        val cb = onRecordDoneRef?.get()
        onRecordDoneRef = null
        refreshMtwsMarkers()
        updatePanelState()
        updateBubbleIcon()  // v33: пузырь возвращается к серому play
        if (bringBackEditor) bringBackSettingsEditor()
        if (cb != null) {
            lastRecordResult = null
            cb.invoke(result)
        }
    }

    /**
     * ФИЧА (v39): запуск записи ОДНОГО действия (тап или свайп — система
     * определит по движению пальца на оверлее) для вставки между
     * существующими действиями пресета. Не трогает recordedActions — это
     * для обычной многошаговой записи, здесь caller управляет своим
     * списком сам (через колбэк). Запись автоматически останавливается
     * после первого ACTION_UP (см. showRecordOverlay) — пользователю не
     * нужно нажимать «Остановить». Если он всё же нажал без единого тапа —
     * колбэк получит null (отмена).
     *
     * Возвращает true, если запись реально началась. false — служба
     * занята (идёт обычная запись или playback).
     */
    fun startSingleActionRecording(onDone: (PresetAction?) -> Unit): Boolean {
        if (recording || playing) return false
        // Не трогаем recordedActions и lastRecordResult — они для обычной
        // записи. Single-action caller в колбэке сам управляет позицией
        // вставки в свой список действий.
        // lastRecTime не нужен — пауза между действиями здесь не вычисляется
        // (одно действие → нет предыдущего, до которого мерить gap).
        recordingFullNotified = false
        handler.removeCallbacks(replayRunnable)
        replayInFlight = false
        singleActionCb = WeakReference(onDone)
        if (!showRecordOverlay()) {
            singleActionCb = null
            return false
        }
        recording = true
        hideMtwsMarkers()
        hidePresetList()
        updatePanelState()
        updateBubbleIcon()
        return true
    }

    /**
     * ФИЧА (v39): остановка single-action записи. Вызывается из
     * showRecordOverlay ACTION_UP (с записанным действием) или из
     * showRecordStopButton click listener (с null — отмена). Не трогает
     * recordedActions. Колбэк получает action (или null). Один вызов —
     * одно завершение, singleActionCb обнуляется сразу.
     */
    private fun stopSingleActionRecording(action: PresetAction?, bringBackEditor: Boolean) {
        if (!recording) return
        recording = false
        // live replay мог быть отложен (REPLAY_DELAY_MS) — снимаем. Жест,
        // уже переданный системе, доиграется в приложении — безвредно
        handler.removeCallbacks(replayRunnable)
        replayInFlight = false
        recordingFullNotified = false
        hideRecordOverlay()
        val cb = singleActionCb?.get()
        singleActionCb = null
        refreshMtwsMarkers()
        updatePanelState()
        updateBubbleIcon()
        if (bringBackEditor) bringBackSettingsEditor()
        cb?.invoke(action)
    }

    /** ФИЧА: поднять окно настроек после остановки записи / выбора точки.
     *  Запуск строго ИЗ КОНТЕКСТА СЛУЖБЫ — активити из фона поднимать
     *  нельзя (ограничения Android 10+), служба с выданным
     *  SYSTEM_ALERT_WINDOW — можно.
     *  ФИКС (v30): попытки вернуть окно ПЛАВАЮЩИМ удалены (механика
     *  v19-v22 + переключатель v28): на MIUI/HyperOS задача со «свободными
     *  окнами» рисуется корректно, но мапит КАСАНИЯ со сдвигом. Редактор
     *  теперь ВСЕГДА возвращается обычным запуском: сначала ПОДНЯТЬ
     *  ЗАДАЧУ ЦЕЛИКОМ (moveTaskToFront, REORDER_TASKS), а при её
     *  отсутствии — startActivity */
    private fun bringBackSettingsEditor(mode: String = "MTWS") {
        val tid = editorTaskId
        editorTaskId = -1
        // ФИКС (v37): moveTaskToFront НЕ БРОСАЕТ исключение, если задачи
        // уже нет, и НЕ возвращает результата — метод всегда выходит
        // «успешно», даже когда по факту ничего не сделал. Раньше
        // bringTaskToFront возвращал true в любом случае, и код «после
        // moveTaskToFront ВСЕГДА дублируем запуск с REORDER_TO_FRONT» —
        // не дублировал: ранний return в if-ветке пропускал второй
        // launchEditorPlain. На части прошивок (MIUI/HyperOS) задача к
        // этому моменту уже мертва, moveTaskToMany молчит, и редактор
        // не поднимался ВООБЩЕ. Теперь оба шага выполняются всегда:
        // moveTaskToFront (если есть tid) + launchEditorPlain. Живая
        // активность получит onNewIntent (дубликата не будет, режим
        // сохранится — см. onNewIntent), уничтоженная — создастся заново.
        if (tid != -1) {
            runCatching { bringTaskToFront(tid) }
                .onFailure { Log.e(TAG, "bringTaskToFront($tid) failed", it) }
        }
        runCatching { launchEditorPlain(mode) }
            .onFailure { Log.e(TAG, "bringBackSettingsEditor: launch failed", it) }
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

    /** ФИКС (v18): поднять задачу редактора целиком — из недавних задача
     *  возвращается в том состоянии, в котором была.
     *  ФИКС (v37): убран вводящий в заблуждение Boolean-возврат —
     *  moveTaskToFront не сообщает об успехе/неудаче в принципе
     *  (молча делает ничего, если задачи нет или ОС режет), поэтому
     *  сигнализировать успех «истиной» нельзя. Вызывающий код больше
     *  не опирается на результат. */
    private fun bringTaskToFront(taskId: Int) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.moveTaskToFront(taskId, 0)
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
                    // ФИКС (v38, OOM): потолок записанных действий. Без него
                    // многочасовая запись переполняла бы память. Тост один
                    // раз (recordingFullNotified), потом тихо игнорим —
                    // recordedActions не растёт, live preview тоже не зовём.
                    if (recordedActions.size >= MAX_RECORDED_ACTIONS) {
                        if (!recordingFullNotified) {
                            recordingFullNotified = true
                            toast("Достигнут максимум — $MAX_RECORDED_ACTIONS действий. " +
                                  "Остановите запись")
                        }
                        // Сбрасываем down-точку, чтобы следующий тап не
                        // записал «длинную паузу» с момента превышения
                        lastRecTime = now
                        return@setOnTouchListener true
                    }
                    if (recordedActions.isNotEmpty()) {
                        val gap = (now - lastRecTime).coerceIn(0L, MAX_REC_GAP_MS)
                        val li = recordedActions.size - 1
                        recordedActions[li] = recordedActions[li].copy(delayMs = gap)
                    }
                    lastRecTime = now
                    // ФИКС (v23): порог «тап или свайп» — в DP (SWIPE_THRESHOLD_DP),
                    // а не в сырых пикселях: 40 px на 3x-экране это всего ~13dp
                    val swipeThresholdPx = SWIPE_THRESHOLD_DP * resources.displayMetrics.density
                    val action = if (dist < swipeThresholdPx) {
                        PresetAction("tap", downX.toInt(), downY.toInt(), 0, 0, 0L, delayMs = 0L)
                    } else {
                        PresetAction("swipe",
                            downX.toInt(), downY.toInt(),
                            upX.toInt(), upY.toInt(),
                            dt.coerceIn(50L, 5000L), delayMs = 0L)
                    }
                    // ФИЧА (v39): single-action режим — записали одно действие,
                    // дальше не идём. recordedActions не трогаем (это для
                    // обычной многошаговой записи), live replay не запускаем
                    // (одно действие — пользователь увидит результат в редакторе
                    // после возврата; предпросмотр тут избыточен и мешал бы
                    // быстрому возврату окна). Автостат и колбэк с действием.
                    if (singleActionCb != null) {
                        stopSingleActionRecording(action, bringBackEditor = true)
                        return@setOnTouchListener true
                    }
                    recordedActions.add(action)
                    // ФИЧА (v23): счётчик на оверлее записи — сразу видно прогресс
                    updateRecCountOverlay()
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
        val v = runCatching { LayoutInflater.from(this).inflate(R.layout.record_stop, null) }
            .getOrElse { Log.e(TAG, "showRecordStopButton: inflate failed", it); return false }
        recordStopBtn = v
        // ФИЧА (v23): живой счётчик «Записано: N» над кнопкой остановки
        recCountOverlayRef = v.findViewById(R.id.tvRecCountOverlay)
        updateRecCountOverlay()
        v.findViewById<View>(R.id.stopRecordBtn)?.setOnClickListener {
            // ФИЧА (v39): в single-action режиме стоп-кнопка отменяет
            // вставку — зовём stopSingleActionRecording с null. Иначе
            // это обычная многошаговая запись → stopRecordingInternal.
            // Обе функции безопасны при повторном вызове (ранний return
            // если !recording), так что гонка «тап по стоп + ACTION_UP
            // одновременно» не крашит — выигрывает тот, кто первым
            // поставит recording=false
            if (singleActionCb != null) {
                stopSingleActionRecording(null, bringBackEditor = true)
            } else {
                stopRecordingInternal(bringBackEditor = true)
            }
        }
        if (runCatching { wm.addView(v, p) }.isFailure) {
            Log.e(TAG, "showRecordStopButton: addView failed")
            recordStopBtn = null
            return false
        }
        return true
    }

    /** ФИЧА (v23): обновить счётчик записанных действий на оверлее записи */
    private fun updateRecCountOverlay() {
        recCountOverlayRef?.text = "Записано: ${recordedActions.size}"
    }

    /**
     * ФИЧА: живой предпросмотр — отложить воспроизведение записанного жеста.
     * ФИКС (v36): на Android 7.x–8.x (API 24–27) предпросмотр ОТКЛЮЧЁН.
     * Система ставит инжектированным жестам флаг FLAG_IS_GENERATED_GESTURE
     * (0x10) только с Android 9 — на старых версиях оверлей записи не
     * распознаёт предпросмотр как «свой», и каждый предпросмотр записывается
     * как НОВОЕ действие (список растёт сам). Выход ДО replayInFlight и
     * setRecordAreaTouchable — состояние записи остаётся консистентным
     * (оверлей остаётся touchable, обычная запись продолжает работать).
     */
    private fun scheduleLiveReplay(a: PresetAction) {
        if (Build.VERSION.SDK_INT < 28) return
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
        // ФИКС (v37, краш): колбэк приходит на binder-потоке, а
        // restoreRecordArea() зовёт wm.updateViewLayout через
        // setRecordAreaTouchable — с binder-потока это незаконно.
        // Уходим на главный через handler.post.
        val cb = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.post { restoreRecordArea() }
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.post { restoreRecordArea() }
            }
        }
        val ok = runCatching { dispatchGesture(g, cb, null) }
            .getOrElse { Log.e(TAG, "dispatchGesture(replay) error", it); false }
        if (!ok) {
            // Сюда падаем, если dispatchGesture сразу вернул false —
            // колбэка не будет, restoreRecordArea зовём сами. Текущий
            // поток — главный (dispatchLiveReplay бежит через replayRunnable
            // на handler), post не нужен.
            restoreRecordArea()
        }
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
        // ФИЧА (v23): счётчик оверлея записи — вместе с окном
        recCountOverlayRef = null
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
            // ФИКС (v42, баг S5): apply → commit. apply() асинхронно —
            // правка (drag крестика/маркера, изменение задержки в редакторе
            // панели) могла потеряться, если пользователь сразу убивал
            // приложение (force-stop / OOM kill) до того, как фоновый
            // writer успевал сработать. commit() синхронный — ждёт записи
            // на диск, ~5-15 мс на современных флешках, незаметно.
            //
            // persistLastPreset зовётся редко (только на правки с плавающей
            // панели), не на каждом тике — синхронная запись тут дёшево.
            // Сравните с writeDraft в SettingsActivity — там тоже commit()
            // для той же причины (пережить гибель процесса).
            getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_PRESET, preset.toJson().toString())
                .commit()
        }
    }

    private fun restoreLastPreset(): Preset? =
        runCatching {
            getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_PRESET, null)
                ?.let { Preset.fromJson(JSONObject(it)) }
        }.getOrNull()
}

