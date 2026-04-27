package me.phie.tawc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import me.phie.tawc.compositor.CompositorService

/**
 * Home screen for the tawc app. Starts the [CompositorService] (which
 * spawns the Rust compositor thread + Wayland socket) and shows a
 * status page. There is no longer a "primary" CompositorActivity — each
 * Wayland toplevel from the chroot is its own per-document
 * `CompositorActivity` spawned by the compositor's policy. Without any
 * windows, the service runs alongside this launcher and the recents
 * list shows just one tawc card (this one).
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the compositor as soon as the user opens the app. The
        // service is START_STICKY + foreground, so it survives this
        // Activity being closed and any chroot client's connection
        // outlasts MainActivity dying.
        startForegroundService(Intent(this, CompositorService::class.java))

        val padding = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        val title = TextView(this).apply {
            text = "tawc"
            textSize = 32f
            gravity = Gravity.CENTER
        }

        val status = TextView(this).apply {
            text = "Compositor running.\n" +
                "Open a Wayland app from the chroot to see it here."
            gravity = Gravity.CENTER
        }

        root.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            bottomMargin = padding
        })
        root.addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        setContentView(root)
    }
}
