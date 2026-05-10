package me.phie.tawc.compositor

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Hosts the Rust Wayland compositor on a SurfaceView. All interaction
 * with the native compositor (surface lifecycle, touch, IME) lives in
 * this package.
 *
 * The Activity binds to [CompositorService] (which owns the compositor
 * thread + Wayland socket) and forwards its `SurfaceView` lifecycle and
 * input events to native, tagged with its `activityId`. The id comes
 * from `intent.data?.lastPathSegment` of a `tawc://activity/<id>` URI;
 * the only path that launches this Activity is the `spawnActivity`
 * reverse-JNI call from the compositor's policy.
 */
class CompositorActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    /** Set in onCreate from intent.data. Always non-null at runtime —
     *  the only path that creates this Activity is the spawnActivity
     *  reverse-JNI call, which always sets a `tawc://activity/<id>` URI. */
    private lateinit var activityId: String
    /** False until onCreate finished its full setup — guards onDestroy
     *  cleanup against the early-return path when intent.data is missing. */
    private var initialized = false

    private var compositorService: CompositorService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            compositorService = (binder as CompositorService.LocalBinder).getService()
            compositorService?.registerActivity(activityId, this@CompositorActivity)
            Log.d(TAG, "Bound to CompositorService for $activityId")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            compositorService = null
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The only legitimate launch path is the spawnActivity reverse-JNI
        // call, which always sets a `tawc://activity/<uuid>` data URI.
        // Anything else (system relaunches an old Intent without data,
        // a stray `am start` from the user) is an orphaned task; finish
        // immediately so the recents card disappears.
        val id = intent?.data?.lastPathSegment
        if (id.isNullOrEmpty()) {
            Log.w(TAG, "CompositorActivity launched without tawc:// activityId — finishing")
            finishAndRemoveTask()
            return
        }
        activityId = id
        Log.d(TAG, "CompositorActivity onCreate activityId=$activityId")

        // Start and bind the CompositorService. The Service owns the
        // compositor thread (and runs xkb-data extraction) and survives
        // this Activity's lifetime.
        val serviceIntent = Intent(this, CompositorService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

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

        initialized = true
    }

    override fun onDestroy() {
        if (initialized) {
            NativeBridge.nativeOnActivityDestroyed(activityId)
            compositorService?.unregisterActivity(activityId)
            try {
                unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                // Service was never successfully bound — safe to ignore.
            }
        }
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!initialized) return
        // Use the SurfaceFrame for the registration size — the holder
        // already knows the buffer geometry. The compositor falls back
        // to ANativeWindow_get{Width,Height} if these come in as 0.
        val frame = holder.surfaceFrame
        NativeBridge.nativeRegisterActivitySurface(
            activityId, holder.surface, frame.width(), frame.height()
        )
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!initialized) return
        NativeBridge.nativeOnActivitySurfaceChanged(activityId, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!initialized) return
        NativeBridge.nativeOnActivitySurfaceDestroyed(activityId)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!initialized) return
        NativeBridge.nativeOnActivityFocusChanged(activityId, hasFocus)
    }

    /**
     * Custom SurfaceView that provides our InputConnection to the IME.
     * This makes the view act as a text input target for Gboard.
     */
    private class TawcSurfaceView(context: Context) : SurfaceView(context) {
        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            val (inputType, extraFlags) = NativeBridge.imeEditorInfo
            outAttrs.inputType = inputType
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_ACTION_NONE or extraFlags
            return TawcInputConnection(this)
        }
    }

    private fun dispatchTouchToCompositor(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                NativeBridge.nativeOnTouchEvent(
                    activityId, actionMasked, event.getPointerId(idx),
                    event.getX(idx), event.getY(idx), event.eventTime
                )
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    NativeBridge.nativeOnTouchEvent(
                        activityId, actionMasked, event.getPointerId(i),
                        event.getX(i), event.getY(i), event.eventTime
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                NativeBridge.nativeOnTouchEvent(
                    activityId, actionMasked, event.getPointerId(idx),
                    event.getX(idx), event.getY(idx), event.eventTime
                )
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until event.pointerCount) {
                    NativeBridge.nativeOnTouchEvent(
                        activityId, MotionEvent.ACTION_UP, event.getPointerId(i),
                        event.getX(i), event.getY(i), event.eventTime
                    )
                }
            }
        }
        return true
    }

    companion object {
        private const val TAG = "tawc"
    }
}
