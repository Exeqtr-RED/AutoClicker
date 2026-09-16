package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.TextView

class ClickService : AccessibilityService() {

    companion object {
        private const val TAG = "ClickService"
        @Volatile
        var instance: ClickService? = null
            private set
    }

    private lateinit var wm: WindowManager

    // Панель
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var tvStatus: TextView? = null
    private var toggleBtn: Button? = null

    // Крестик
    private var crosshair: View? = null
    private var crosshairParams: WindowManager.LayoutParams? = null

    // Сохранённые координаты центра крестика (абсолютные, экранные)
    private var crosshairCenterX = 140f
    private var crosshairCenterY = 540f

    // Оверлеи
    private var recordOverlay: View? = null
    private var recordParams: WindowManager.LayoutParams? = null
    private var pickOverlay: View? = null
    private var pickOnDone: ((Int, Int) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    // Playback
    private var currentPreset: Preset? = null
    private var lastPreset: Preset? = null
    @Volatile private var playing = false
    private var actionIndex = 0
    private var cycleCount = 0
    private var startTimeMs = 0L
    @Volatile private var gestureInFlight = false

    // Recording
    private var recording = false
    private var recordedActions = mutableListOf<PresetAction>()
    private var onRecordDone: ((List<PresetAction>) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    // ============================================================
    // Lifecycle
    // ============================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showPanel()
        showCrosshair()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        if (::wm.isInitialized) {
            panel?.let { runCatching { wm.removeView(it) } }
            crosshair?.let { runCatching { wm.removeView(it) } }
            recordOverlay?.let { runCatching { wm.removeView(it) } }
            pickOverlay?.let { runCatching { wm.removeView(it) } }
        }
        panel = null; crosshair = null
        recordOverlay = null; pickOverlay = null
        super.onDestroy()
    }

    fun isPlaying(): Boolean = playing

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    // ============================================================
    // Панель
    // ============================================================

    private fun showPanel() {
        if (panel != null) return
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }
        panelParams = p
        val v = LayoutInflater.from(this).inflate(R.layout.panel, null)
        panel = v
        wm.addView(v, p)

        tvStatus = v.findViewById(R.id.tvStatus)
        toggleBtn = v.findViewById(R.id.toggleBtn)

        val handle = v.findViewById<View>(R.id.dragHandle)
        handle.setOnTouchListener(object : View.OnTouchListener {
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
                        return true
                    }
                }
                return false
            }
        })

        toggleBtn?.setOnClickListener {
            if (playing) stopPlayback() else lastPreset?.let { startPlayback(it) }
        }

        v.findViewById<Button>(R.id.closeBtn).setOnClickListener {
            stopPlayback()
            stopRecordingInternal()
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
                tg.text = "REC"; tg.isEnabled = false
            }
            playing -> {
                st.text = "●"; st.setTextColor(0xFF00CC44.toInt())
                tg.text = "Стоп"; tg.isEnabled = true
            }
            else -> {
                st.text = "●"; st.setTextColor(0xFFFF2222.toInt())
                tg.text = "Старт"; tg.isEnabled = lastPreset != null
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (crosshairCenterX - 24).toInt()
            y = (crosshairCenterY - 24).toInt()
        }
        crosshairParams = p
        val v = LayoutInflater.from(this).inflate(R.layout.crosshair, null)
        crosshair = v
        wm.addView(v, p)

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
    // Выбор точки (ST)
    // ============================================================

    fun startPickPoint(onDone: (Int, Int) -> Unit) {
        if (pickOverlay != null) return
        pickOnDone = onDone
        crosshair?.visibility = View.GONE
        panel?.visibility = View.GONE

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val v = LayoutInflater.from(this).inflate(R.layout.pick_point_overlay, null)
        pickOverlay = v
        wm.addView(v, p)

        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val x = e.rawX.toInt()
                val y = e.rawY.toInt()
                hidePickOverlay()
                val cb = pickOnDone
                pickOnDone = null
                cb?.invoke(x, y)
            }
            true
        }
    }

    private fun hidePickOverlay() {
        pickOverlay?.let { runCatching { wm.removeView(it) } }
        pickOverlay = null
        crosshair?.visibility = View.VISIBLE
        panel?.visibility = View.VISIBLE
    }

    // ============================================================
    // Playback
    // ============================================================

    fun startPlayback(preset: Preset) {
        if (playing || recording) return
        if (preset.mode == "MTWS" && preset.actions.isEmpty()) return

        lastPreset = preset
        currentPreset = preset
        actionIndex = 0
        cycleCount = 0
        startTimeMs = System.currentTimeMillis()
        gestureInFlight = false
        playing = true

        if (preset.mode == "ST") {
            // ВАЖНО: во время ST-воспроизведения крестик полностью убираем —
            // иначе overlay блокирует dispatchGesture на некоторых прошивках.
            hideCrosshair()
        } else {
            crosshair?.visibility = View.GONE
        }
        updatePanelState()
        Log.d(TAG, "startPlayback mode=${preset.mode} timing=${preset.timingMode} delay=${preset.delayMs}")
        handler.post(tick)
    }

    fun stopPlayback() {
        if (!playing) return
        playing = false
        gestureInFlight = false
        handler.removeCallbacks(tick)
        if (currentPreset?.mode == "ST") {
            if (crosshair == null) showCrosshair()
        } else {
            crosshair?.visibility = View.VISIBLE
        }
        updatePanelState()
        Log.d(TAG, "stopPlayback")
    }

    private val gestureCallback = object : GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) { gestureInFlight = false }
        override fun onCancelled(g: GestureDescription?) { gestureInFlight = false }
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
                performTap(tx, ty)
                cycleCount++
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
                    performTap(a.x1.toFloat(), a.y1.toFloat())
                } else {
                    performSwipe(a.x1.toFloat(), a.y1.toFloat(),
                        a.x2.toFloat(), a.y2.toFloat(), a.swipeDurationMs)
                }
                actionIndex++
            }

            handler.postDelayed(this, preset.delayMs)
        }
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val g = GestureDescription.Builder().addStroke(stroke).build()
        gestureInFlight = true
        val ok = runCatching {
            dispatchGesture(g, gestureCallback, null)
        }.getOrElse { e ->
            Log.e(TAG, "dispatchGesture(tap) error", e)
            false
        }
        Log.d(TAG, "tap ($x, $y) ok=$ok")
        if (!ok) gestureInFlight = false
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
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
        Log.d(TAG, "swipe ($x1,$y1)->($x2,$y2) ok=$ok")
        if (!ok) gestureInFlight = false
    }

    // ============================================================
    // Recording
    // ============================================================

    fun startRecording(onDone: (List<PresetAction>) -> Unit) {
        if (recording || playing) return
        recordedActions = mutableListOf()
        onRecordDone = onDone
        recording = true
        showRecordOverlay()
        updatePanelState()
    }

    fun stopRecordingInternal() {
        if (!recording) return
        recording = false
        hideRecordOverlay()
        val result = recordedActions.toList()
        val cb = onRecordDone
        onRecordDone = null
        updatePanelState()
        cb?.invoke(result)
    }

    private fun showRecordOverlay() {
        if (recordOverlay != null) return
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        recordParams = p
        val v = LayoutInflater.from(this).inflate(R.layout.record_overlay, null)
        recordOverlay = v
        wm.addView(v, p)

        val area = v.findViewById<View>(R.id.recordArea)
        area.setOnTouchListener { _, e ->
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
                    if (dist < 40f) {
                        recordedActions.add(
                            PresetAction("tap", downX.toInt(), downY.toInt(), 0, 0, 0L)
                        )
                    } else {
                        recordedActions.add(
                            PresetAction("swipe",
                                downX.toInt(), downY.toInt(),
                                upX.toInt(), upY.toInt(),
                                dt.coerceIn(50L, 5000L))
                        )
                    }
                    true
                }
                else -> true
            }
        }
        v.findViewById<View>(R.id.stopRecordBtn).setOnClickListener {
            stopRecordingInternal()
        }
    }

    private fun hideRecordOverlay() {
        recordOverlay?.let { runCatching { wm.removeView(it) } }
        recordOverlay = null
        recordParams = null
    }
}