package me.phie.tawc.install

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import me.phie.tawc.R
import me.phie.tawc.install.distro.Distro
import me.phie.tawc.install.distro.DistroRegistry
import me.phie.tawc.ops.LogScreenActivity
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.primaryButton
import me.phie.tawc.ui.tonalButton
import me.phie.tawc.ui.verticalLp
import me.phie.tawc.util.AppLogger

/**
 * "Install new distro" screen. Form-only: distro / label / method /
 * cache-proxy controls plus an Install button. Tapping Install kicks
 * off [InstallationService] and hands the user off to
 * [LogScreenActivity] for the live progress view, then finishes itself
 * — so the back stack is `home → log`, not `home → form → log`.
 *
 * Mutating an installation never happens as a side-effect of opening
 * this screen. The button press is the only trigger; CLI install /
 * uninstall lives on the dev exec broker (see [InstallActions]).
 * This was the
 * `install-uninstall-trigger-via-activity-launch` issue's resolution.
 */
class InstallActivity : AppCompatActivity() {
    private val store by lazy { InstallationStore(this) }
    private var selectedMethod: String? = null
    private var selectedDistro: String? = null
    private var labelEdited: Boolean = false

    /**
     * External-storage binds the install starts with (see
     * notes/external-binds.md). Starts empty, edited via
     * [ManageBindsActivity], passed to the service as JSON. Only
     * meaningful for tawcroot installs — the row hides (and
     * [beginInstall] passes null) for other methods.
     */
    private val pendingBinds = mutableListOf<ExternalBind>()
    private var bindsRow: LinearLayout? = null
    private var bindsCountLabel: TextView? = null
    
    private val manageBinds = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val json = result.data?.getStringExtra(ManageBindsActivity.EXTRA_BINDS)
        if (result.resultCode == RESULT_OK && json != null) {
            pendingBinds.clear()
            pendingBinds.addAll(
                runCatching { ExternalBind.fromJsonArray(org.json.JSONArray(json)) }
                    .getOrDefault(emptyList())
            )
            updateBindsRow()
        }
    }

    /**
     * Tri-state for the "Use cache proxy" checkbox:
     * - null: not yet initialised (will be seeded from build type).
     * - true / false: user-overridden value, persisted across rotations.
     */
    private var useCacheProxy: Boolean? = null
    private lateinit var cacheProxyCheckbox: CheckBox

    /** ando (notes/ando.md) toggle. Off by default, shown for all
     * methods; persisted across rotations. */
    private var andoEnabled: Boolean = false

    private lateinit var formScroll: ScrollView
    private lateinit var formSection: LinearLayout
    private lateinit var methodGroup: RadioGroup
    private lateinit var distroGroup: RadioGroup
    private lateinit var labelField: EditText
    private lateinit var locationLabel: TextView
    private lateinit var installButton: MaterialButton
    private lateinit var scaffold: me.phie.tawc.ui.Scaffold
    
    // Кастомный метод распаковки
    private lateinit var customMethod: CustomTarballMethod

    // Launcher для выбора кастомного архива дистрибутива
    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleCustomFile(it) }
    }

    /**
     * Resolved id for the Install button. Tracks (label → slug → unique)
     * so the service-call site doesn't have to re-derive it; null when
     * the label is empty / unslugifiable / collides with an existing
     * install (Install button is also disabled in that state).
     */
    private var resolvedId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        customMethod = CustomTarballMethod(this)
        
        selectedMethod = savedInstanceState?.getString(KEY_METHOD)
        selectedDistro = savedInstanceState?.getString(KEY_DISTRO)
        labelEdited = savedInstanceState?.getBoolean(KEY_LABEL_EDITED) == true
        
        useCacheProxy = when {
            savedInstanceState?.containsKey(KEY_USE_PROXY) == true ->
                savedInstanceState.getBoolean(KEY_USE_PROXY)
            // Dev build default: on. Production: off (and the row is
            // hidden anyway, see buildCacheProxyRow).
            me.phie.tawc.BuildConfig.DEBUG -> true
            else -> false
        }
        andoEnabled = savedInstanceState?.getBoolean(KEY_ANDO) == true

        pendingBinds.clear()
        savedInstanceState?.getString(KEY_BINDS)?.let { savedBinds ->
            pendingBinds.addAll(
                runCatching { ExternalBind.fromJsonArray(org.json.JSONArray(savedBinds)) }
                    .getOrDefault(emptyList())
            )
        }

        scaffold = buildChildScreen(getString(R.string.title_install))
        val pad = (16 * resources.displayMetrics.density).toInt()
        
        formSection = buildFormSection(pad, savedInstanceState?.getString(KEY_LABEL_TEXT))

        // Wrap the form in a ScrollView so the soft keyboard can lift
        // the EditText into view without ever covering the Install
        // button on a small phone.
        formScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(formSection, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        
        scaffold.content.addView(formScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(scaffold.root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LABEL_EDITED, labelEdited)
        if (::labelField.isInitialized) {
            outState.putString(KEY_LABEL_TEXT, labelField.text.toString())
        }
        selectedMethod?.let { outState.putString(KEY_METHOD, it) }
        selectedDistro?.let { outState.putString(KEY_DISTRO, it) }
        useCacheProxy?.let { outState.putBoolean(KEY_USE_PROXY, it) }
        outState.putBoolean(KEY_ANDO, andoEnabled)
        outState.putString(KEY_BINDS, ExternalBind.toJsonArray(pendingBinds).toString())
    }

    private fun buildFormSection(pad: Int, savedLabelText: String?): LinearLayout {
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val available = DistroRegistry.availableForHost()
        val initialDistro = (selectedDistro ?: available.firstOrNull()?.key)
        selectedDistro = initialDistro

        s.addView(buildDistroPicker(available, pad), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))
        s.addView(buildInstallDirField(available, savedLabelText), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        if (!EnabledMethods.onlyOne) {
            s.addView(buildMethodPicker(), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
            s.addView(
                MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.install_help_methods)
                    setTextColor(getColor(R.color.tawc_accent))
                    setOnClickListener {
                        startActivity(Intent(this@InstallActivity, InstallMethodInfoActivity::class.java))
                    }
                },
                verticalLp(WRAP_CONTENT, WRAP_CONTENT, bottomMargin = pad),
            )
        } else {
            selectedMethod = EnabledMethods.keys.single()
        }

        s.addView(buildAndoRow(), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        if (AllFilesAccess.declared(this)) {
            val row = buildBindsRow()
            bindsRow = row
            s.addView(row, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))
            updateBindsRow()
        }

        if (me.phie.tawc.BuildConfig.DEBUG) {
            s.addView(buildCacheProxyRow(), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))
        }

        // Кнопка для выбора и установки из локального tarball-архива
        val customButton = tonalButton("📁 Выбрать кастомный архив (*.tar.xz)") {
            AppLogger.i("Install", "Opening file picker for custom distro")
            pickFileLauncher.launch("*/*")
        }
        s.addView(customButton, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))

        installButton = primaryButton(getString(R.string.action_install)) { beginInstall() }
        s.addView(installButton, verticalLp(MATCH_PARENT, WRAP_CONTENT))

        revalidate()
        return s
    }

    private fun buildDistroPicker(available: List<Distro>, pad: Int): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = getString(R.string.install_distro_label); textSize = 14f }
        container.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        if (available.isEmpty()) {
            val msg = TextView(this).apply {
                text = getString(R.string.install_no_supported_distro)
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

        val initialKey = selectedDistro?.takeIf { k -> available.any { it.key == k } } ?: available.first().key
        selectedDistro = initialKey
        idsByKey.entries.firstOrNull { it.value == initialKey }?.let { distroGroup.check(it.key) }

        distroGroup.setOnCheckedChangeListener { _, checkedId ->
            idsByKey[checkedId]?.let {
                selectedDistro = it
                if (!labelEdited) {
                    val d = available.firstOrNull { it.key == selectedDistro }
                    if (d != null) setLabelTextSilently(d.defaultLabel)
                }
                revalidate()
            }
        }
        return container
    }

    private fun buildInstallDirField(available: List<Distro>, savedLabelText: String?): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = getString(R.string.install_label_label); textSize = 14f }
        container.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        val initialDefault = available.firstOrNull { it.key == selectedDistro }?.defaultLabel ?: ""
        labelField = EditText(this).apply {
            setText(savedLabelText ?: initialDefault)
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!suppressEditedFlag) labelEdited = true
                    revalidate()
                }
            })
        }
        container.addView(labelField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        locationLabel = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        container.addView(locationLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return container
    }

    private var suppressEditedFlag = false
    private fun setLabelTextSilently(text: String) {
        suppressEditedFlag = true
        try {
            labelField.setText(text)
        } finally {
            suppressEditedFlag = false
        }
    }

    private fun revalidate() {
        if (!::labelField.isInitialized) return
        val rawLabel = labelField.text.toString().trim()
        val slug = if (rawLabel.isEmpty()) null else Installation.slugifyLabel(rawLabel)
        val collides = slug != null && store.installationDir(slug).exists()
        resolvedId = slug?.takeUnless { collides }

        if (::locationLabel.isInitialized) {
            locationLabel.text = when {
                rawLabel.isEmpty() -> getString(R.string.install_label_empty)
                slug == null -> getString(R.string.install_label_invalid)
                collides -> getString(R.string.install_already_installed_at, store.installationDir(slug).absolutePath)
                else -> store.installationDir(slug).absolutePath
            }
            val colorAttr = if (resolvedId == null) {
                com.google.android.material.R.attr.colorError
            } else {
                com.google.android.material.R.attr.colorOnSurfaceVariant
            }
            locationLabel.setTextColor(MaterialColors.getColor(locationLabel, colorAttr))
        }

        if (::installButton.isInitialized) {
            installButton.isEnabled = (resolvedId != null)
            installButton.text = getString(R.string.action_install)
        }
    }

    private fun buildBindsRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val count = TextView(this).apply { textSize = 14f }
        bindsCountLabel = count
        row.addView(count, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(tonalButton(getString(R.string.action_manage)) {
            manageBinds.launch(
                ManageBindsActivity.intentForResult(
                    this, ExternalBind.toJsonArray(pendingBinds).toString(),
                )
            )
        })
        return row
    }

    private fun updateBindsRow() {
        bindsCountLabel?.text = getString(R.string.install_external_binds_label, pendingBinds.size)
        bindsRow?.visibility =
            if (selectedMethod == TawcrootMethod.KEY) View.VISIBLE else View.GONE
    }

    private fun buildAndoRow(): LinearLayout =
        buildAndoToggleRow(this, andoEnabled) { _, checked -> andoEnabled = checked }

    private fun buildCacheProxyRow(): CheckBox {
        cacheProxyCheckbox = CheckBox(this).apply {
            text = getString(R.string.install_use_cache_proxy)
            isChecked = useCacheProxy ?: true  
            setOnCheckedChangeListener { _, checked -> useCacheProxy = checked }
        }
        return cacheProxyCheckbox
    }

    private fun buildMethodPicker(): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = getString(R.string.install_method_label); textSize = 14f }
        container.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        
        methodGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rootAvailable = Su.rootAvailable()
        val idByKey = mutableMapOf<String, Int>()
        val keyById = mutableMapOf<Int, String>()

        for (key in EnabledMethods.keys) {
            val rid = View.generateViewId()
            idByKey[key] = rid
            keyById[rid] = key
            val rb = RadioButton(this).apply {
                id = rid
                text = when (key) {
                    TawcrootMethod.KEY -> getString(R.string.install_method_tawcroot_recommended)
                    ProotMethod.KEY -> getString(R.string.install_method_proot)
                    ChrootMethod.KEY -> getString(R.string.install_method_chroot_requires_root)
                    else -> key
                }
                if (key == ChrootMethod.KEY) isEnabled = rootAvailable
            }
            methodGroup.addView(rb, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        container.addView(methodGroup, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val initial = selectedMethod?.takeIf { idByKey.containsKey(it) } ?: EnabledMethods.keys.first()
        idByKey[initial]?.let { methodGroup.check(it) }
        selectedMethod = initial

        methodGroup.setOnCheckedChangeListener { _, checkedId ->
            keyById[checkedId]?.let { selectedMethod = it; updateBindsRow() }
        }
        return container
    }

    private fun handleCustomFile(uri: Uri) {
        val userLabel = labelField.text.toString().trim()
        val label = if (userLabel.isNotEmpty()) {
            Installation.slugifyLabel(userLabel) ?: "custom-${System.currentTimeMillis()}"
        } else {
            "custom-${System.currentTimeMillis()}"
        }

        InstallProgress.show(this, "Установка кастомного дистрибутива") { progress ->
            val success = customMethod.installFromUri(uri, label, progress)
            if (success) {
                AppLogger.i("Install", "Custom distro installed: $label")
                finish()
            }
        }
    }

    private fun beginInstall() {
        val methodKey = selectedMethod ?: EnabledMethods.keys.firstOrNull() ?: TawcrootMethod.KEY
        if (methodKey == ChrootMethod.KEY && !Su.rootAvailable()) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.install_root_unavailable),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }

        val targetId = resolvedId ?: return
        val distroKey = selectedDistro
        val labelText = labelField.text.toString().trim().takeIf { it.isNotEmpty() }
        val mirrorProxyUrl = if (useCacheProxy == true) DEFAULT_PROXY_URL else null
        
        val bindsJson = if (methodKey == TawcrootMethod.KEY && AllFilesAccess.declared(this)) {
            ExternalBind.toJsonArray(pendingBinds).toString()
        } else {
            null
        }

        val launch = {
            InstallationService.startInstall(
                this, targetId, methodKey, distroKey, labelText, mirrorProxyUrl, bindsJson, andoEnabled,
            )
            startActivity(LogScreenActivity.intentFor(this, "install:$targetId"))
            finish()
        }

        if (bindsJson != null && AllFilesAccess.requiresGrant(pendingBinds) && !AllFilesAccess.granted()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.install_binds_grant_title))
                .setMessage(getString(R.string.install_binds_grant_message))
                .setNegativeButton(getString(R.string.install_binds_grant_anyway)) { _, _ -> launch() }
                .setPositiveButton(getString(R.string.install_binds_grant_grant)) { _, _ ->
                    AllFilesAccess.openSettings(this)
                }
                .show()
            return
        }
        launch()
    }

    companion object {
        private const val DEFAULT_PROXY_URL = "http://127.0.0.1:8080/proxy/"
        private const val KEY_METHOD = "tawc.install.method"
        private const val KEY_DISTRO = "tawc.install.distro"
        private const val KEY_LABEL_EDITED = "tawc.install.labelEdited"
        private const val KEY_LABEL_TEXT = "tawc.install.labelText"
        private const val KEY_USE_PROXY = "tawc.install.useCacheProxy"
        private const val KEY_BINDS = "tawc.install.externalBinds"
        private const val KEY_ANDO = "tawc.install.ando"   
    }
}
