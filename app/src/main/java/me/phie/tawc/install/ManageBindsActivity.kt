package me.phie.tawc.install

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.phie.tawc.R
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.primaryButton
import me.phie.tawc.ui.tawcCard
import me.phie.tawc.ui.tonalButton
import me.phie.tawc.ui.verticalLp
import org.json.JSONArray
import java.io.File

/**
 * Add/edit/remove screen for an install's [ExternalBind] list
 * (notes/external-binds.md). Two callers, two modes:
 *
 *   - [EXTRA_INSTALL_ID] (from [DistroInfoActivity]): edits the
 *     persisted metadata directly — every mutation saves through
 *     [InstallationStore].
 *   - [EXTRA_BINDS] (from [InstallActivity], pre-install): edits an
 *     in-memory list and publishes every mutation via
 *     `setResult(RESULT_OK, [EXTRA_BINDS] = json)` so backing out at
 *     any point hands the caller the latest list.
 *
 * Guest paths are typed; host paths come from [DirectoryPickerActivity]
 * (or are typed — the picker can't reach what it can't read, e.g. a
 * path that needs the not-yet-granted all-files access).
 */
class ManageBindsActivity : AppCompatActivity() {

    private val store by lazy { InstallationStore(this) }
    private var installId: String? = null
    private val binds = mutableListOf<ExternalBind>()

    private lateinit var scaffold: me.phie.tawc.ui.Scaffold
    private lateinit var listColumn: LinearLayout
    private lateinit var grantBanner: LinearLayout

    /** Host-path field of the currently open add/edit dialog, where the
     * directory picker's result lands. Null when no dialog is open. */
    private var pickerTargetField: EditText? = null
    private val pickHostDir = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val path = result.data?.getStringExtra(DirectoryPickerActivity.EXTRA_PATH)
        if (result.resultCode == Activity.RESULT_OK && path != null) {
            pickerTargetField?.setText(path)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installId = intent?.getStringExtra(EXTRA_INSTALL_ID)

        binds.clear()
        binds.addAll(loadInitialBinds(savedInstanceState?.getString(KEY_BINDS)))

        scaffold = buildChildScreen(getString(R.string.title_manage_binds))
        val pad = (16 * resources.displayMetrics.density).toInt()

        scaffold.content.addView(
            TextView(this).apply {
                text = getString(R.string.manage_binds_intro)
                textSize = 14f
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
        )

        grantBanner = buildGrantBanner(pad)
        scaffold.content.addView(grantBanner, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        listColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(listColumn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        scaffold.content.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        scaffold.content.addView(
            primaryButton(getString(R.string.manage_binds_add)) { showEditDialog(null) },
            verticalLp(MATCH_PARENT, WRAP_CONTENT),
        )

        setContentView(scaffold.root)
        renderList()
    }

    override fun onResume() {
        super.onResume()
        // Grant state may have flipped during a settings round-trip.
        updateGrantBanner()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_BINDS, ExternalBind.toJsonArray(binds).toString())
    }

    private fun loadInitialBinds(savedJson: String?): List<ExternalBind> {
        if (savedJson != null) {
            return runCatching { ExternalBind.fromJsonArray(JSONArray(savedJson)) }
                .getOrDefault(emptyList())
        }
        installId?.let { id -> return store.load(id)?.externalBinds ?: emptyList() }
        val json = intent?.getStringExtra(EXTRA_BINDS) ?: return emptyList()
        return runCatching { ExternalBind.fromJsonArray(JSONArray(json)) }
            .getOrDefault(emptyList())
    }

    /** Persist/publish [binds] after a mutation, then re-render. */
    private fun commit() {
        val id = installId
        if (id != null) {
            // Re-check state at write time, not just when DistroInfo
            // rendered the button: a service job (broker-initiated
            // install/uninstall) may have started while this screen was
            // open, and its metadata writes must not interleave with
            // ours. A tiny load→save window remains (see the
            // InstallationStore.save doc); both writers are in-process
            // and atomic-rename, so the loser's edit is dropped, never
            // torn.
            val current = store.load(id)
            if (current == null ||
                (current.state != Installation.State.READY &&
                    current.state != Installation.State.FAILED)
            ) {
                // Uninstalled or mid-mutation underneath us — bail
                // rather than fight the service over the file.
                finish()
                return
            }
            store.save(current.copy(externalBinds = binds.toList()))
        } else {
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(EXTRA_BINDS, ExternalBind.toJsonArray(binds).toString()),
            )
        }
        renderList()
        updateGrantBanner()
    }

    private fun buildGrantBanner(pad: Int): LinearLayout {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(
            TextView(this).apply {
                text = getString(R.string.manage_binds_grant_banner)
                textSize = 14f
                setTextColor(getColor(R.color.tawc_danger))
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2),
        )
        column.addView(
            tonalButton(getString(R.string.manage_binds_grant_button)) {
                AllFilesAccess.openSettings(this)
            },
            verticalLp(WRAP_CONTENT, WRAP_CONTENT),
        )
        val card = tawcCard().apply { addView(column) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(card, verticalLp(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun updateGrantBanner() {
        val show = AllFilesAccess.requiresGrant(binds) && !AllFilesAccess.granted()
        grantBanner.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun renderList() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        listColumn.removeAllViews()
        if (binds.isEmpty()) {
            listColumn.addView(
                TextView(this).apply {
                    text = getString(R.string.manage_binds_empty)
                    textSize = 14f
                },
                verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2),
            )
            return
        }
        for ((index, bind) in binds.withIndex()) {
            listColumn.addView(bindCard(bind, index, pad), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
        }
    }

    private fun bindCard(bind: ExternalBind, index: Int, pad: Int): android.view.View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        if (bind.label != null) {
            column.addView(TextView(this).apply {
                text = bind.label
                textSize = 14f
            })
        }
        column.addView(TextView(this).apply {
            text = bind.guestPath
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        column.addView(TextView(this).apply {
            text = "⇐ ${bind.hostPath}"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setTextIsSelectable(true)
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(tonalButton(getString(R.string.action_edit)) { showEditDialog(index) })
        buttons.addView(
            tonalButton(getString(R.string.action_remove)) {
                binds.removeAt(index)
                commit()
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = pad / 2 },
        )
        column.addView(buttons, verticalLp(MATCH_PARENT, WRAP_CONTENT))
        return tawcCard().apply { addView(column) }
    }

    /** Add (`editIndex == null`) or edit (`editIndex` set) one bind. */
    private fun showEditDialog(editIndex: Int?) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val existing = editIndex?.let { binds[it] }

        fun pathField(initial: String?): EditText = EditText(this).apply {
            setText(initial ?: "")
            isSingleLine = true
            typeface = Typeface.MONOSPACE
            textSize = 14f
            // Same no-autocorrect trick as the run dialog: Gboard only
            // honours it via VISIBLE_PASSWORD.
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }

        val guestField = pathField(existing?.guestPath)
        val hostField = pathField(existing?.hostPath)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.manage_binds_guest_label)
            textSize = 14f
        })
        column.addView(guestField, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
        column.addView(TextView(this).apply {
            text = getString(R.string.manage_binds_host_label)
            textSize = 14f
        })
        column.addView(hostField, verticalLp(MATCH_PARENT, WRAP_CONTENT))
        column.addView(
            tonalButton(getString(R.string.manage_binds_browse)) {
                pickerTargetField = hostField
                pickHostDir.launch(
                    DirectoryPickerActivity.intentFor(this, hostField.text.toString().trim().ifEmpty { null })
                )
            },
            verticalLp(WRAP_CONTENT, WRAP_CONTENT),
        )

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(
                if (existing == null) R.string.manage_binds_add_title else R.string.manage_binds_edit_title
            ))
            .setView(column)
            .setNegativeButton(getString(R.string.action_cancel), null)
            .setPositiveButton(getString(R.string.action_save), null)
            .show()
        // Validation needs to keep the dialog open on failure, so the
        // positive button is wired manually instead of via the builder
        // (whose listener always dismisses).
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            val candidate = ExternalBind(
                hostPath = hostField.text.toString().trim().let {
                    if (it.length > 1) it.trimEnd('/') else it
                },
                guestPath = guestField.text.toString().trim().let {
                    if (it.length > 1) it.trimEnd('/') else it
                },
                label = existing?.label,
            )
            val problem = validate(candidate, editIndex)
            if (problem != null) {
                Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (editIndex == null) binds.add(candidate) else binds[editIndex] = candidate
            commit()
            dialog.dismiss()
        }
    }

    private fun validate(candidate: ExternalBind, editIndex: Int?): String? {
        candidate.validationError()?.let { return it }
        if (editIndex == null && binds.size >= ExternalBind.MAX_BINDS) {
            return getString(R.string.manage_binds_too_many, ExternalBind.MAX_BINDS)
        }
        if (binds.withIndex().any { (i, b) -> i != editIndex && b.guestPath == candidate.guestPath }) {
            return getString(R.string.manage_binds_duplicate_guest, candidate.guestPath)
        }
        // Sources are never auto-created, so a typo here would
        // otherwise surface only at launch time. Shared-storage paths
        // are exempt when the grant is missing — they can't be stat'd
        // yet, and blocking the edit would force users to grant before
        // configuring.
        val needsUncheckableGrant =
            AllFilesAccess.requiresGrant(candidate.hostPath) && !AllFilesAccess.granted()
        if (!needsUncheckableGrant && !File(candidate.hostPath).isDirectory) {
            return getString(R.string.manage_binds_host_missing, candidate.hostPath)
        }
        return null
    }

    companion object {
        /** Edit the persisted bind list of an existing install. */
        const val EXTRA_INSTALL_ID = "installId"

        /** Edit a JSON bind list in memory; result carries the same key. */
        const val EXTRA_BINDS = "binds"

        private const val KEY_BINDS = "tawc.managebinds.binds"

        fun intentForInstall(context: Context, installId: String): Intent =
            Intent(context, ManageBindsActivity::class.java)
                .putExtra(EXTRA_INSTALL_ID, installId)

        fun intentForResult(context: Context, bindsJson: String): Intent =
            Intent(context, ManageBindsActivity::class.java)
                .putExtra(EXTRA_BINDS, bindsJson)
    }
}
