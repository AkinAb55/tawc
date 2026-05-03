package me.phie.tawc.install

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.phie.tawc.R
import me.phie.tawc.install.distro.Distro
import me.phie.tawc.install.distro.DistroRegistry
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.primaryButton
import me.phie.tawc.ui.verticalLp

/**
 * "Install new distro" screen. Shows a read-only summary of what's
 * about to be installed (distro, detected CPU arch, install path) until
 * the user taps Install, then swaps to a live progress + log view
 * bound to [InstallationService] via [OperationLogPanel].
 *
 * `am start … --es autoStart true --es id <id>` skips the form and
 * triggers the install immediately (used by the `am start` install hook
 * documented in `notes/installation.md`). The autoStart fires at most
 * once per launch (`savedInstanceState == null`); a fresh `am start`
 * delivers a new launch intent and re-fires, but a process-death
 * recreation does not. Even if the gate ever leaked, the request would
 * be refused at the [InstallationService] level — this is the UX
 * shortcut, not the safety net.
 */
class InstallActivity : AppCompatActivity() {

    private val store by lazy { InstallationStore(this) }
    private var targetId: String = Installation.DISTRO_ARCH
    private var selectedMethod: String? = null
    private var selectedDistro: String? = null

    private lateinit var formSection: LinearLayout
    private lateinit var methodGroup: RadioGroup
    private lateinit var distroGroup: RadioGroup
    private lateinit var distroSummaryLabel: TextView
    private lateinit var installButton: MaterialButton
    private lateinit var panel: OperationLogPanel

    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetId = intent?.getStringExtra(EXTRA_ID) ?: Installation.DISTRO_ARCH
        // Reject hostile `--es id` extras early — the activity is
        // exported, so any installed app can launch it. The
        // [InstallationService] gate also enforces this, but failing
        // here keeps `installationDir(id)` (which is rendered in the
        // form) out of attacker reach.
        if (!Installation.isValidId(targetId)) {
            android.util.Log.w("tawc-install", "InstallActivity: rejected invalid id '$targetId'")
            finish()
            return
        }
        // Saved state wins over the launch intent so a user's radio
        // flip survives rotation: Android re-delivers the original
        // intent on recreation, which would otherwise shadow the
        // saved selection.
        selectedMethod = savedInstanceState?.getString(KEY_METHOD)
            ?: intent?.getStringExtra(EXTRA_METHOD)
        selectedDistro = savedInstanceState?.getString(KEY_DISTRO)
            ?: intent?.getStringExtra(EXTRA_DISTRO)
        // The form is only useful against an empty slot. If the slot is
        // already in any state (INSTALLING / READY / FAILED / …) the
        // form's Install button would just be the disabled "in progress"
        // / "delete first" hint — show the live OperationLogPanel
        // instead so the user can watch the in-flight job (or see the
        // last terminal status). `started` therefore tracks "this id has
        // a job worth watching," not just "we kicked one off here."
        val occupiedOnEntry = InstallationStore(this).load(targetId) != null
        started = savedInstanceState?.getBoolean(KEY_STARTED) == true || occupiedOnEntry

        val scaffold = buildChildScreen("Install distro")

        val pad = (16 * resources.displayMetrics.density).toInt()
        formSection = buildFormSection(pad)
        scaffold.content.addView(formSection, verticalLp(MATCH_PARENT, WRAP_CONTENT))

        panel = OperationLogPanel(this)
        panel.view.visibility = if (started) View.VISIBLE else View.GONE
        if (started) formSection.visibility = View.GONE
        // Cancel during install requires a confirm: it triggers a
        // follow-up uninstall (INSTALLING → FAILED → UNINSTALLING),
        // which wipes the freshly-laid-down rootfs. There's no user
        // data at risk yet (gate guarantees an empty slot at install
        // start) but the time loss alone is worth a confirm tap.
        //
        // After a cancelled install, the service flips into
        // UNINSTALLING for the follow-up wipe — at that point a
        // second tap of the still-visible Cancel button should behave
        // like UninstallActivity's Cancel: no confirm dialog, just
        // abort the wipe directly. Dispatch on the service's current
        // job kind so we do the right thing in both phases.
        panel.onCancelClicked = { dispatchCancel() }
        scaffold.content.addView(panel.view, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(scaffold.root)

        // Fire autoStart only on the very first onCreate of this
        // activity instance. Re-creations restore [started]=true from
        // savedInstanceState and skip this path. A fresh `am start`
        // produces a null savedInstanceState (Android creates a new
        // activity instance), so the CLI keeps working.
        if (savedInstanceState == null && intent.requestsAutoStart()) {
            beginInstall()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newId = intent.getStringExtra(EXTRA_ID) ?: targetId
        if (!Installation.isValidId(newId)) {
            android.util.Log.w("tawc-install", "InstallActivity: rejected invalid id '$newId' on re-intent")
            return
        }
        targetId = newId
        if (intent.requestsAutoStart()) {
            beginInstall()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_STARTED, started)
        selectedMethod?.let { outState.putString(KEY_METHOD, it) }
        selectedDistro?.let { outState.putString(KEY_DISTRO, it) }
    }

    override fun onStart() {
        super.onStart()
        panel.bindToService()
    }

    override fun onStop() {
        super.onStop()
        panel.unbind()
    }

    private fun buildFormSection(pad: Int): LinearLayout {
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // List the distros that match the host's primary ABI. Empty
        // list means no Distro supports this device; render an
        // explanatory line rather than a dead radio group, and let
        // the service-level gate refuse the install if the user taps
        // anyway. The `--es distro …` extra / saved state nudges the
        // initial selection.
        val available = DistroRegistry.availableForHost()
        val initialDistro = (selectedDistro ?: available.firstOrNull()?.key)
        selectedDistro = initialDistro

        s.addView(buildDistroPicker(available, pad), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))

        // Read-only summary that updates when the distro radio
        // flips. Architecture comes from the picked Distro's
        // linuxArch (not the raw host ABI) so e.g. ALARM shows
        // "aarch64" and not "arm64-v8a".
        distroSummaryLabel = TextView(this).apply { textSize = 14f }
        refreshDistroSummary(available)
        s.addView(formRow("Architecture:", distroSummaryLabel), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))

        s.addView(
            formRow("Install location:", store.installationDir(targetId).absolutePath),
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
        )

        // Method picker. The radio default reflects the host: chroot if
        // `su` is available, proot otherwise. The `--es method ...`
        // intent extra (or saved instance state) overrides.
        s.addView(buildMethodPicker(), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        installButton = primaryButton("Install") { beginInstall() }
        // Mirror the service-level gate so the form makes the refusal
        // obvious before the user taps. The service is still the
        // source of truth — the button is just a hint.
        val current = store.load(targetId)
        if (current != null) {
            installButton.isEnabled = false
            installButton.text = when (current.state) {
                Installation.State.READY -> "Install (already installed — delete first)"
                Installation.State.INSTALLING -> "Install (in progress — delete to abort)"
                Installation.State.UNINSTALLING -> "Install (delete in progress)"
                Installation.State.FAILED -> "Install (failed — delete first)"
            }
        }
        s.addView(installButton, verticalLp(MATCH_PARENT, WRAP_CONTENT))
        return s
    }

    /**
     * Build the distro picker. Lists every [Distro] whose Android ABI
     * matches the host. Defaults to the first ABI-matching entry
     * unless `--es distro …` / saved state nudges otherwise. Hidden
     * when only one distro matches (the first-class case before
     * Manjaro) so we don't render a single-choice radio group.
     */
    private fun buildDistroPicker(available: List<Distro>, pad: Int): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = "Distro:"; textSize = 14f }
        container.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        if (available.isEmpty()) {
            val msg = TextView(this).apply {
                text = "(no supported distro for this device)"
                textSize = 14f
                typeface = Typeface.MONOSPACE
            }
            container.addView(msg, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return container
        }

        distroGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val idsByKey = mutableMapOf<Int, String>()
        for (d in available) {
            val rid = View.generateViewId()
            idsByKey[rid] = d.key
            val rb = RadioButton(this).apply {
                id = rid
                text = d.displayName
            }
            distroGroup.addView(rb, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        container.addView(distroGroup, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val initialKey = selectedDistro?.takeIf { k -> available.any { it.key == k } }
            ?: available.first().key
        selectedDistro = initialKey
        idsByKey.entries.firstOrNull { it.value == initialKey }?.let { distroGroup.check(it.key) }

        distroGroup.setOnCheckedChangeListener { _, checkedId ->
            idsByKey[checkedId]?.let {
                selectedDistro = it
                refreshDistroSummary(available)
            }
        }
        return container
    }

    private fun refreshDistroSummary(available: List<Distro>) {
        if (!::distroSummaryLabel.isInitialized) return
        val d = available.firstOrNull { it.key == selectedDistro }
        distroSummaryLabel.text = d?.linuxArch ?: "(unknown)"
    }

    /**
     * Build the method picker (chroot / proot / tawcroot radio group).
     * Default follows host capability — `su` available picks chroot,
     * otherwise proot (the established rootless path). tawcroot is
     * exposed as an opt-in for now: phase-2 host validation passes but
     * real-Android coverage is still landing, so we don't auto-default
     * onto it. The intent extra `method` and saved-instance state both
     * override the default; the user can still flip the radio after.
     */
    private fun buildMethodPicker(): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = "Install method:"; textSize = 14f }
        container.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        methodGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rootAvailable = Su.rootAvailable()

        // Use generateViewId() rather than hand-picked constants —
        // any literal we'd reach for in the AAPT range collides with
        // future R.id.* once we add a layout XML.
        val chrootId = View.generateViewId()
        val prootId = View.generateViewId()
        val tawcrootId = View.generateViewId()

        val chroot = RadioButton(this).apply {
            id = chrootId
            text = "chroot (root)"
            // The chroot path needs su; greyed-out on devices without
            // root makes the limitation visible without surprising
            // the user mid-install.
            isEnabled = rootAvailable
        }
        val proot = RadioButton(this).apply {
            id = prootId
            text = "proot (rootless)"
        }
        val tawcroot = RadioButton(this).apply {
            id = tawcrootId
            text = "tawcroot (systrap, rootless)"
        }
        methodGroup.addView(chroot, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = 16 })
        methodGroup.addView(proot, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = 16 })
        methodGroup.addView(tawcroot, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        container.addView(methodGroup, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Initial selection: explicit override → use it; else default
        // for host (chroot if su works, proot if not).
        val initial = selectedMethod
            ?: if (rootAvailable) ChrootMethod.KEY else ProotMethod.KEY
        methodGroup.check(when (initial) {
            ChrootMethod.KEY    -> chrootId
            TawcrootMethod.KEY  -> tawcrootId
            else                -> prootId
        })
        selectedMethod = initial

        methodGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedMethod = when (checkedId) {
                chrootId    -> ChrootMethod.KEY
                prootId     -> ProotMethod.KEY
                tawcrootId  -> TawcrootMethod.KEY
                else        -> selectedMethod
            }
        }
        return container
    }

    private fun formRow(label: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val l = TextView(this).apply { text = label; textSize = 14f }
        val v = TextView(this).apply { text = value; textSize = 14f; typeface = Typeface.MONOSPACE }
        row.addView(l, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = 16 })
        row.addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return row
    }

    /**
     * formRow variant that takes a pre-built TextView as the value
     * cell so callers can keep a reference and update its text later
     * (e.g. when the distro radio flips). Same layout as the string
     * overload.
     */
    private fun formRow(label: String, value: TextView): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val l = TextView(this).apply { text = label; textSize = 14f }
        value.typeface = Typeface.MONOSPACE
        row.addView(l, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = 16 })
        row.addView(value, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return row
    }

    private fun beginInstall() {
        // Only the chroot path needs `su`. Proot is rootless by
        // definition, so a missing-root device fails this check only
        // if the user picked chroot anyway. We surface the "no su"
        // message specifically; other "method not available" reasons
        // are caught at service-gate level.
        val methodKey = selectedMethod ?: ChrootMethod.KEY
        if (methodKey == ChrootMethod.KEY && !Su.rootAvailable()) {
            formSection.visibility = View.GONE
            panel.view.visibility = View.VISIBLE
            // Also append to the log: bindToService() will overwrite
            // the status text with the service's StateFlow ("Idle") as
            // soon as onStart fires, so a status-only error vanishes
            // and the user sees a misleading "Idle" with no
            // explanation. The log line is sticky.
            val msg = "ERROR: root (su) not available — pick proot, or grant Magisk root."
            panel.setStatus(msg)
            panel.appendLog(msg)
            return
        }
        formSection.visibility = View.GONE
        panel.view.visibility = View.VISIBLE
        // [InstallationService] is the authoritative gate; we just hand
        // off and let it decide whether to run or reject. `started` only
        // tracks UI state (form vs panel) so a process-death recreate
        // restores the panel view.
        val distroKey = selectedDistro
        panel.appendLog(
            (if (started) "[ui] re-requesting install of '$targetId' via $methodKey"
             else "[ui] starting install of '$targetId' via $methodKey")
                + (distroKey?.let { " (distro=$it)" } ?: "")
        )
        started = true
        InstallationService.startInstall(this, targetId, methodKey, distroKey)
    }

    private fun dispatchCancel() {
        val service = panel.boundService
        if (service == null) {
            panel.appendLog("[ui] cancel ignored: service not bound yet")
            return
        }
        when (service.currentKind) {
            InstallationService.JobKind.INSTALL -> confirmCancelInstall(service)
            InstallationService.JobKind.UNINSTALL -> {
                // Follow-up uninstall phase from a previous cancel-
                // install (or the user opened the install activity
                // while an uninstall was already in flight). Match
                // UninstallActivity's behaviour: no confirm.
                panel.appendLog("[ui] cancelling in-flight uninstall")
                service.cancelUninstall(targetId)
            }
            null -> panel.appendLog("[ui] cancel: no active job")
        }
    }

    private fun confirmCancelInstall(service: InstallationService) {
        // Note: the "no data will be lost" wording is correct because
        // the install gate only runs against an empty slot (see
        // notes/installation.md). If a future refactor adds in-place
        // reconfigure/migration the message must be revisited.
        val message = "Cancelling will stop the install and remove the partially " +
            "extracted rootfs at\n${store.installationDir(targetId).absolutePath}.\n" +
            "Nothing of yours has been written there yet, so no data will be lost."
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Cancel install of '$targetId'?")
            .setMessage(message)
            .setNegativeButton("Keep installing", null)
            .setPositiveButton("Cancel install") { _, _ ->
                panel.appendLog("[ui] user confirmed cancel")
                service.cancelInstallAndUninstall(targetId)
            }
            .show()
        // Match the destructive-action coloring on DistroInfoActivity:
        // accent red on the destructive option, neutral on the keep-
        // going one so it doesn't compete.
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.tawc_danger))
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.let { btn ->
            btn.setTextColor(
                MaterialColors.getColor(btn, com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_METHOD = "method"
        const val EXTRA_DISTRO = "distro"
        private const val KEY_STARTED = "tawc.install.started"
        private const val KEY_METHOD = "tawc.install.method"
        private const val KEY_DISTRO = "tawc.install.distro"
    }
}
