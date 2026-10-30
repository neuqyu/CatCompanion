package com.example.catcompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class FloatingPetService : Service() {
    companion object {
        const val ACTION_START = "com.example.catcompanion.START"
        const val ACTION_REFRESH = "com.example.catcompanion.REFRESH"
        const val ACTION_PLAY = "com.example.catcompanion.PLAY"
        const val ACTION_RESET_POSITION = "com.example.catcompanion.RESET_POSITION"
        const val EXTRA_BEHAVIOR = "behavior"
        const val ACTION_STOP = "com.example.catcompanion.STOP"
        private const val CHANNEL = "cat_companion_running"
        private const val NOTIFICATION_ID = 1101
    }

    private lateinit var windows: WindowManager
    private lateinit var detector: ForegroundAppDetector
    private lateinit var powerManager: PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private var catView: PetView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var running = false
    private var shown = false
    private var direction = 1
    private var nextActionAt = 0L
    private var lastVisibilityCheck = 0L
    private var stepIndex = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        detector = ForegroundAppDetector(this)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PetSettings.setRunning(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!PermissionChecks.overlayAllowed(this)) {
            PetSettings.setRunning(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            // Always initiated from user action in MainActivity; no background FGS launch.
            startForeground(NOTIFICATION_ID, buildNotification())
            running = true
            PetSettings.setRunning(this, true)
            attachPet()
            nextActionAt = SystemClock.uptimeMillis() + 4000L
            handler.post(animationLoop)
        } else {
            if (intent?.action == ACTION_PLAY) {
                val behavior = runCatching {
                    PetView.Action.valueOf(intent.getStringExtra(EXTRA_BEHAVIOR) ?: "IDLE")
                }.getOrDefault(PetView.Action.IDLE)
                catView?.setBehavior(behavior)
                catView?.isWalking = behavior == PetView.Action.WALK
                nextActionAt = SystemClock.uptimeMillis() + 5000L
            } else if (intent?.action == ACTION_RESET_POSITION) {
                resetPosition()
            } else {
                resizePet()
                catView?.loadPhoto()
                if (!PetSettings.autoMotion(this)) {
                    catView?.isWalking = false
                    catView?.setBehavior(PetView.Action.IDLE)
                }
                updateVisibility(force = true)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL, "桌面猫咪运行状态", NotificationManager.IMPORTANCE_LOW).apply {
            description = "猫咪正在悬浮显示；点击可进入设置"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2,
            Intent(this, FloatingPetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val modeLabel = if (PetSettings.mode(this) == DisplayMode.HOME_ONLY) "仅桌面模式" else "全局陪伴模式"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle("喵在身边 · 已开启")
            .setContentText(modeLabel)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "关闭小猫", stop).build())
            .build()
    }

    private fun viewport(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windows.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val screen = android.graphics.Point().also { windows.defaultDisplay.getSize(it) }
            Pair(screen.x, screen.y)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun attachPet() {
        val side = dp(PetSettings.size(this))
        val screen = viewport()
        val layout = WindowManager.LayoutParams(
            side, side, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val existing = PetSettings.savedPosition(this@FloatingPetService)
            x = existing?.let { (it.first * max(0, screen.first - side)).toInt() }
                ?: max(0, (screen.first - side) / 2)
            y = existing?.let { (it.second * max(0, screen.second - side)).toInt() }
                ?: max(0, screen.second - side - dp(80))
        }
        val pet = PetView(this)
        installDragAndTap(pet, layout)
        try {
            windows.addView(pet, layout)
            attached = true
            shown = true
            catView = pet
            params = layout
            updateVisibility(force = true)
        } catch (e: SecurityException) {
            stopSelf()
        } catch (e: WindowManager.BadTokenException) {
            stopSelf()
        }
    }

    private fun installDragAndTap(pet: PetView, layout: WindowManager.LayoutParams) {
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val threshold = dp(9)
        pet.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = layout.x
                    startY = layout.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (abs(dx) > threshold || abs(dy) > threshold) dragging = true
                    if (dragging && attached) {
                        val screen = viewport()
                        layout.x = (startX + dx.toInt()).coerceIn(0, max(0, screen.first - layout.width))
                        layout.y = (startY + dy.toInt()).coerceIn(0, max(0, screen.second - layout.height))
                        windows.updateViewLayout(pet, layout)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) view.performClick()
                    else {
                        val (w, h) = viewport()
                        PetSettings.setPosition(this,
                            layout.x.toFloat() / max(1, w - layout.width),
                            layout.y.toFloat() / max(1, h - layout.height))
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        pet.setOnClickListener { pet.poke() }
    }

    private fun resetPosition() {
        PetSettings.clearPosition(this)
        val layout = params ?: return
        val pet = catView ?: return
        val (w, h) = viewport()
        layout.x = max(0, (w - layout.width) / 2)
        layout.y = max(0, h - layout.height - dp(80))
        if (attached) windows.updateViewLayout(pet, layout)
    }

    private fun resizePet() {
        val layout = params ?: return
        val pet = catView ?: return
        val side = dp(PetSettings.size(this))
        layout.width = side
        layout.height = side
        val (w, h) = viewport()
        layout.x = layout.x.coerceIn(0, max(0, w - side))
        layout.y = layout.y.coerceIn(0, max(0, h - side))
        if (attached) windows.updateViewLayout(pet, layout)
    }

    private fun updateVisibility(force: Boolean = false) {
        val pet = catView ?: return
        if (!force && SystemClock.uptimeMillis() - lastVisibilityCheck < 1000) return
        lastVisibilityCheck = SystemClock.uptimeMillis()
        val shouldShow = powerManager.isInteractive &&
            (PetSettings.mode(this) == DisplayMode.EVERYWHERE || detector.isHomeScreen())
        if (shown != shouldShow) {
            shown = shouldShow
            pet.visibility = if (shouldShow) View.VISIBLE else View.GONE
        }
    }

    private val animationLoop = object : Runnable {
        override fun run() {
            if (!running) return
            val pet = catView
            val layout = params
            updateVisibility()
            if (shown && pet != null && layout != null) {
                val now = SystemClock.uptimeMillis()
                val auto = PetSettings.autoMotion(this@FloatingPetService)
                val level = PetSettings.activity(this@FloatingPetService)
                if (!auto && pet.isWalking && !pet.isExcited()) {
                    pet.isWalking = false
                    pet.setBehavior(PetView.Action.IDLE)
                }
                if (auto && now >= nextActionAt && !pet.isExcited()) {
                    val behavior = when (Random.nextInt(12)) {
                        0, 1 -> PetView.Action.SIT
                        2 -> PetView.Action.SLEEP
                        3 -> PetView.Action.STRETCH
                        4 -> PetView.Action.WAVE
                        5 -> PetView.Action.JUMP
                        6 -> PetView.Action.IDLE
                        else -> PetView.Action.WALK
                    }
                    pet.setBehavior(behavior)
                    pet.isWalking = behavior == PetView.Action.WALK
                    val multiplier = (6 - level).toLong()
                    nextActionAt = now + Random.nextLong(1000L + multiplier * 400, 3000L + multiplier * 950)
                }
                if (auto && pet.isWalking && !pet.isExcited()) {
                    stepIndex++
                    if (stepIndex % (10 - level * 2) == 0) {
                        val available = max(0, viewport().first - layout.width)
                        layout.x = (layout.x + direction * dp(1)).coerceIn(0, available)
                        if (layout.x >= available || layout.x <= 0) direction *= -1
                        pet.facingRight = direction > 0
                        if (attached) windows.updateViewLayout(pet, layout)
                    }
                }
                pet.invalidate()
            }
            handler.postDelayed(this,
                if (!shown) 500L
                else if (PetSettings.autoMotion(this@FloatingPetService)) 55L
                else 150L)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resizePet()
    }

    override fun onDestroy() {
        running = false
        PetSettings.setRunning(this, false)
        handler.removeCallbacks(animationLoop)
        catView?.let { if (attached) runCatching { windows.removeView(it) } }
        catView = null
        attached = false
        super.onDestroy()
    }
}
