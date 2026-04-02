package me.phie.tawc

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView

    @Suppress("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        surfaceView = TawcSurfaceView(this)
        setContentView(surfaceView)
        surfaceView.holder.addCallback(this)
        surfaceView.isFocusable = true
        surfaceView.isFocusableInTouchMode = true
        surfaceView.setOnTouchListener { _, event -> dispatchTouchToCompositor(event) }
        NativeBridge.inputView = surfaceView
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        NativeBridge.nativeOnSurfaceCreated(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NativeBridge.nativeOnSurfaceChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NativeBridge.nativeOnSurfaceDestroyed()
    }

    /**
     * Custom SurfaceView that provides our InputConnection to the IME.
     * This makes the view act as a text input target for Gboard.
     */
    private class TawcSurfaceView(context: Context) : SurfaceView(context) {
        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
            return TawcInputConnection(this)
        }
    }

    private fun dispatchTouchToCompositor(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                NativeBridge.nativeOnTouchEvent(
                    actionMasked, event.getPointerId(idx),
                    event.getX(idx), event.getY(idx), event.eventTime
                )
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    NativeBridge.nativeOnTouchEvent(
                        actionMasked, event.getPointerId(i),
                        event.getX(i), event.getY(i), event.eventTime
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                NativeBridge.nativeOnTouchEvent(
                    actionMasked, event.getPointerId(idx),
                    event.getX(idx), event.getY(idx), event.eventTime
                )
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until event.pointerCount) {
                    NativeBridge.nativeOnTouchEvent(
                        MotionEvent.ACTION_UP, event.getPointerId(i),
                        event.getX(i), event.getY(i), event.eventTime
                    )
                }
            }
        }
        return true
    }
}
