package me.phie.tawc.compositor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.lang.ref.WeakReference

/**
 * Foreground service that owns the Rust Wayland compositor thread for the
 * lifetime of the tawc process. The compositor outlives any single
 * [CompositorActivity], which is the prerequisite for the multi-window
 * design (see notes/multi-activity.md).
 *
 * Activities bind to this service to register themselves; the service
 * tracks them by `activityId` so reverse-JNI calls (keyboard show/hide,
 * future per-host operations) can find the right Activity's view.
 *
 * The service is `START_STICKY`: if Android kills it under memory
 * pressure, the OS recreates it (without the original Intent), and our
 * `onCreate` re-spawns the compositor thread. Wayland clients connected
 * over the chroot socket will see a brief disconnect and reconnect.
 */
class CompositorService : Service() {

    private val binder = LocalBinder()
    private val activities = mutableMapOf<String, WeakReference<CompositorActivity>>()

    /** State-query broadcast lives on the service (always alive) rather
     *  than on a CompositorActivity (only exists when there's a window).
     *  Tests poll this before any chroot client has connected. */
    private val queryStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            NativeBridge.nativeQueryState()
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): CompositorService = this@CompositorService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CompositorService onCreate")

        ensureNotificationChannel()
        // Foreground type "specialUse" is the correct fit on Android 14+ —
        // none of the standard types (mediaPlayback, dataSync, etc.) match
        // a desktop compositor. The app declares the corresponding
        // PROPERTY_SPECIAL_USE_FGS_SUBTYPE in the manifest.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // Hand the application context + service to NativeBridge so its
        // reverse-JNI spawnActivity/finishActivity entry points work even
        // when no Activity is currently in the foreground.
        NativeBridge.attachService(this)
        NativeBridge.nativeStartCompositor()

        @Suppress("UnspecifiedRegisterReceiverFlag")
        registerReceiver(
            queryStateReceiver,
            IntentFilter("me.phie.tawc.QUERY_STATE"),
            RECEIVER_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: the system recreates the service after a kill so the
        // compositor comes back even if every Activity has been destroyed.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.d(TAG, "CompositorService onDestroy — stopping compositor")
        try { unregisterReceiver(queryStateReceiver) } catch (_: IllegalArgumentException) {}
        NativeBridge.nativeStopCompositor()
        NativeBridge.detachService()
        activities.clear()
        super.onDestroy()
    }

    fun registerActivity(activityId: String, activity: CompositorActivity) {
        activities[activityId] = WeakReference(activity)
        Log.d(TAG, "Registered activity $activityId (count=${activities.size})")
    }

    fun unregisterActivity(activityId: String) {
        activities.remove(activityId)
        Log.d(TAG, "Unregistered activity $activityId (count=${activities.size})")
    }

    /** Look up an alive Activity by id. Returns null if it was GC'd. */
    fun getActivity(activityId: String): CompositorActivity? {
        val ref = activities[activityId] ?: return null
        val activity = ref.get()
        if (activity == null) {
            // Weak ref expired — clean up the dead entry.
            activities.remove(activityId)
        }
        return activity
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Compositor", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background notification for the running Wayland compositor."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("tawc compositor")
            .setContentText("Wayland compositor is running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "tawc"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "tawc_compositor"
    }
}
