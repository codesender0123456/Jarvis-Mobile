package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.example.MainActivity

class JarvisOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingBubble: View? = null

    // addView/updateViewLayout/removeView all throw if the permission was revoked or the
    // display went away between calls, so every call is guarded and tracked.
    @Volatile
    private var viewAttached = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val bubbleSize = (56 * resources.displayMetrics.density).toInt()
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        val bubbleView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setBackgroundColor(0xFF00E5FF.toInt())
            setPadding(16, 16, 16, 16)
        }

        bubbleView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = true

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                if (event == null) return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isClick = false
                        }
                        params.x = initialX + dx
                        params.y = initialY + dy
                        if (viewAttached) {
                            runCatching {
                                windowManager?.updateViewLayout(bubbleView, params)
                            }.onFailure {
                                Log.w("JarvisOverlayService", "Could not move overlay bubble", it)
                                detachBubble()
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            val intent = Intent(this@JarvisOverlayService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                putExtra(JarvisVoiceService.EXTRA_START_LISTENING, true)
                            }
                            // This is a plain background service, so Android 10+ will throw
                            // BackgroundActivityStartRestrictedException when JARVIS is not
                            // already in the foreground.
                            runCatching { startActivity(intent) }
                                .onFailure {
                                    Log.w("JarvisOverlayService", "Could not open HUD from overlay", it)
                                }
                        }
                        return true
                    }
                }
                return false
            }
        })

        floatingBubble = bubbleView
        runCatching {
            windowManager?.addView(bubbleView, params)
            viewAttached = true
        }.onFailure {
            // Throws BadTokenException / InvalidDisplayException if the overlay grant was
            // revoked while the service was starting.
            Log.e("JarvisOverlayService", "Failed to add overlay bubble", it)
            floatingBubble = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        detachBubble()
        super.onDestroy()
    }

    private fun detachBubble() {
        val view = floatingBubble ?: return
        if (viewAttached) {
            // removeView throws IllegalArgumentException if the view is not currently attached.
            runCatching { windowManager?.removeView(view) }
                .onFailure { Log.w("JarvisOverlayService", "Overlay bubble already detached", it) }
            viewAttached = false
        }
        floatingBubble = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
