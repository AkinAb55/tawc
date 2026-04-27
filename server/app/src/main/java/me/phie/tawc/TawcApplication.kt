package me.phie.tawc

import android.app.Application
import android.util.Log
import me.phie.tawc.install.BootstrapCache
import kotlin.concurrent.thread

/**
 * Process-wide entry point. Currently used solely to sweep stale
 * bootstrap-tarball cache entries on every cold start (see
 * [BootstrapCache.sweepStale]) — the OS only evicts `cacheDir` under
 * storage pressure, so without our own TTL a 200 MB tarball can squat
 * on disk for months after the user finished installing.
 *
 * Runs on a background thread because the sweep stat()s + unlink()s
 * files; we don't want even tens of milliseconds of disk I/O blocking
 * the launcher / install activity / compositor service onCreate.
 */
class TawcApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        thread(name = "tawc-bootstrap-cache-sweep", isDaemon = true) {
            try {
                val n = BootstrapCache(this).sweepStale()
                if (n > 0) Log.i(TAG, "Bootstrap cache: evicted $n stale entries")
            } catch (t: Throwable) {
                Log.w(TAG, "Bootstrap cache sweep failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "tawc-install"
    }
}
