package me.phie.tawc

import android.app.Application
import android.util.Log
import me.phie.tawc.dev.ExecBroker
import me.phie.tawc.install.BootstrapCache
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.install.RootfsTmpSweeper
import me.phie.tawc.install.TawcInstaller
import me.phie.tawc.ops.OperationsNotificationCenter
import me.phie.tawc.util.AppLogger
import kotlin.concurrent.thread

class TawcApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Инициализация системы логов
        AppLogger.init(this)

        Settings.init(this)
        OperationsNotificationCenter.start(this)

        thread(name = "tawc-startup", isDaemon = true) {
            try {
                val appPaths = AppPaths.from(this)
                appPaths.shareDir.mkdirs()
                appPaths.legacyAndoSocket.delete()
                AndoBrokers.refresh(this)
            } catch (t: Throwable) {
                Log.w(TAG, "ando broker start failed", t)
                AppLogger.w("Application", "ando broker start failed", t)
            }

            try {
                val n = BootstrapCache(this).sweepStale()
                if (n > 0) {
                    Log.i(TAG, "Bootstrap cache: evicted $n stale entries")
                    AppLogger.i("Application", "Bootstrap cache: evicted $n stale entries")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Bootstrap cache sweep failed", t)
                AppLogger.w("Application", "Bootstrap cache sweep failed", t)
            }

            try {
                TawcInstaller.installAll(this, InstallationStore(this))
            } catch (t: Throwable) {
                Log.w(TAG, "TawcInstaller.installAll failed", t)
                AppLogger.w("Application", "TawcInstaller.installAll failed", t)
            }

            try {
                RootfsTmpSweeper.sweepAll(InstallationStore(this))
            } catch (t: Throwable) {
                Log.w(TAG, "rootfs /tmp sweep failed", t)
                AppLogger.w("Application", "rootfs /tmp sweep failed", t)
            }
        }

        if (BuildConfig.DEBUG) {
            registerActivityLifecycleCallbacks(me.phie.tawc.dev.DevActivityTracker)
            ExecBroker.start(this)
            me.phie.tawc.install.InstallActions.registerAll()
            me.phie.tawc.dev.InputActions.registerAll()
            me.phie.tawc.dev.SettingsActions.registerAll()
            me.phie.tawc.launcher.LauncherActions.registerAll()
        }
    }

    companion object {
        private const val TAG = "tawc-install"
    }
}
