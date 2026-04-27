package me.phie.tawc.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.phie.tawc.MainActivity

/**
 * Foreground service that runs install / uninstall jobs in a coroutine.
 *
 * Both UI (ManageInstallationsActivity binding to the service) and
 * command-line callers (InstallationCommandReceiver firing
 * `am startservice` intents) drop work onto the same paths, so the
 * progress/log streams below are the single source of truth for any
 * surface watching the operation.
 */
class InstallationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null
    private val binder = LocalBinder()

    private val _progress = MutableStateFlow(
        InstallProgress(InstallStage.IDLE, "Idle")
    )
    val progress: StateFlow<InstallProgress> = _progress.asStateFlow()

    private val _log = MutableSharedFlow<String>(replay = 200, extraBufferCapacity = 1024)
    val log: SharedFlow<String> = _log.asSharedFlow()

    inner class LocalBinder : Binder() {
        val service: InstallationService get() = this@InstallationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification("tawc"))
        when (intent?.action) {
            ACTION_INSTALL -> startInstall(intent.getStringExtra(EXTRA_ID) ?: Installation.DISTRO_ARCH)
            ACTION_UNINSTALL -> startUninstall(intent.getStringExtra(EXTRA_ID) ?: Installation.DISTRO_ARCH)
            else -> Log.w(TAG, "InstallationService started without a known action")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Begin an install for [id]. No-op if a job is already running. */
    fun startInstall(id: String) {
        if (currentJob?.isActive == true) {
            appendLog("[skip] Job already running")
            return
        }
        currentJob = scope.launch {
            val installer = ArchInstaller(InstallationStore(applicationContext), id)
            try {
                installer.install(::publishProgress, ::appendLog)
            } catch (t: Throwable) {
                Log.e(TAG, "install failed", t)
                appendLog("FAILED: ${t.message}")
                publishProgress(
                    InstallProgress(
                        InstallStage.FAILED,
                        "Install failed",
                        errorMessage = t.message,
                    )
                )
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    /** Begin an uninstall for [id]. No-op if a job is already running. */
    fun startUninstall(id: String) {
        if (currentJob?.isActive == true) {
            appendLog("[skip] Job already running")
            return
        }
        currentJob = scope.launch {
            val installer = ArchInstaller(InstallationStore(applicationContext), id)
            try {
                installer.uninstall(::publishProgress, ::appendLog)
            } catch (t: Throwable) {
                Log.e(TAG, "uninstall failed", t)
                appendLog("FAILED: ${t.message}")
                publishProgress(
                    InstallProgress(
                        InstallStage.FAILED,
                        "Uninstall failed",
                        errorMessage = t.message,
                    )
                )
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun publishProgress(p: InstallProgress) {
        _progress.value = p
        // Re-issue the foreground notification so its text stays current.
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(p.message))
        // Stage transitions are also useful in logcat for CLI workflows.
        appendLog("[stage:${p.stage}] ${p.message}")
    }

    private fun appendLog(line: String) {
        Log.d(TAG, line)
        _log.tryEmit(line)
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Installation",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Long-running install / uninstall jobs"
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // android.R.drawable.stat_sys_download is always present and matches
        // the "background data" feel of an install operation.
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("tawc installation")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }

    companion object {
        private const val TAG = "tawc-install"
        private const val CHANNEL_ID = "tawc-install"
        private const val NOTIFICATION_ID = 0xA001

        const val ACTION_INSTALL = "me.phie.tawc.install.SERVICE_INSTALL"
        const val ACTION_UNINSTALL = "me.phie.tawc.install.SERVICE_UNINSTALL"
        const val EXTRA_ID = "id"

        fun startInstall(context: Context, id: String) {
            val i = Intent(context, InstallationService::class.java)
                .setAction(ACTION_INSTALL)
                .putExtra(EXTRA_ID, id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun startUninstall(context: Context, id: String) {
            val i = Intent(context, InstallationService::class.java)
                .setAction(ACTION_UNINSTALL)
                .putExtra(EXTRA_ID, id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
