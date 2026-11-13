/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.addons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.fonts.FontStyle.FONT_WEIGHT_MEDIUM
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as materialR
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.engine.gecko.await
import mozilla.components.concept.engine.webextension.InstallationMethod
import mozilla.components.feature.addons.Addon
import mozilla.components.feature.addons.AddonManager
import mozilla.components.feature.addons.AddonManagerException
import mozilla.components.feature.addons.R as addonsR
import mozilla.components.feature.addons.ui.AddonsManagerAdapter
import org.mozilla.fenix.R
import org.mozilla.fenix.databinding.FragmentAddOnsManagementBinding
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.openToBrowser
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.runIfFragmentIsAttached
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.settings.SupportUtils.AMO_HOMEPAGE_FOR_ANDROID
import org.mozilla.fenix.theme.ThemeManager
import org.mozilla.gecko.util.GeckoBundle
import org.mozilla.geckoview.AdminModeController
import org.mozilla.geckoview.ExperimentalGeckoViewApi

private const val TOTP_QR_CODE_SIZE = 512
private const val TOTP_QR_IMAGE_SIZE = 224

/** Fragment use for managing add-ons. */
@Suppress("TooManyFunctions", "LargeClass")
@androidx.annotation.OptIn(ExperimentalGeckoViewApi::class)
class AddonsManagementFragment : Fragment(R.layout.fragment_add_ons_management), SystemInsetsPaddedFragment {

    private var binding: FragmentAddOnsManagementBinding? = null

    private var addons: List<Addon> = emptyList()

    private var adapter: AddonsManagerAdapter? = null

    private var adminModeAuthView: AdminModeAuthView? = null
    private var adminModeRequestInFlight = false

    private val appShortcut by lazy { AdminModeAppShortcut(requireContext().applicationContext) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentAddOnsManagementBinding.bind(view)
        bindAdminModeControls()
        bindRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(R.string.preferences_extensions))
        refreshAdminModeState()
        updateShortcutButton()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        adminModeAuthView?.refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // letting go of the resources to avoid memory leak.
        adapter = null
        adminModeAuthView = null
        adminModeRequestInFlight = false
        binding = null
    }

    @Suppress("CognitiveComplexMethod")
    private fun bindRecyclerView() {
        val managementView =
            AddonsManagementView(
                navController = findNavController(),
                onInstallButtonClicked = ::installAddon,
                onMoreAddonsButtonClicked = ::openAMO,
                isInstallEnabled = { adminModeAuthView?.adminModeEnabled == true },
                onLearnMoreClicked = { link, addon ->
                    binding?.root?.openLearnMoreLink(link, addon)
                },
            )

        val addonManager = provideAddonManager()
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                addons = withContext(IO) { addonManager.getAddons() }
                val safeBinding = binding ?: return@launch
                adapter =
                    AddonsManagerAdapter(
                            addonsManagerDelegate = managementView,
                            addons = addons,
                            style = createAddonStyle(context),
                            store = requireComponents.core.store,
                        )
                        .apply {
                            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
                        }
                safeBinding.addOnsProgressBar.isVisible = false
                safeBinding.addOnsEmptyMessage.isVisible = false

                safeBinding.addOnsList.apply {
                    layoutManager = LinearLayoutManager(context)
                    adapter = this@AddonsManagementFragment.adapter
                    accessibilityDelegate =
                        object : View.AccessibilityDelegate() {
                            override fun onInitializeAccessibilityNodeInfo(
                                host: View,
                                info: AccessibilityNodeInfo,
                            ) {
                                super.onInitializeAccessibilityNodeInfo(host, info)
                                this@AddonsManagementFragment.adapter?.let {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        info.collectionInfo =
                                            AccessibilityNodeInfo.CollectionInfo(
                                                it.itemCount,
                                                1,
                                                false,
                                            )
                                    } else {
                                        @Suppress("DEPRECATION")
                                        info.collectionInfo =
                                            AccessibilityNodeInfo.CollectionInfo.obtain(
                                                it.itemCount,
                                                1,
                                                false,
                                            )
                                    }
                                }
                            }
                        }
                }
            } catch (e: AddonManagerException) {
                binding?.let {
                    showSnackBar(it.root, getString(addonsR.string.mozac_feature_addons_failed_to_load_extensions))
                    it.addOnsProgressBar.isVisible = false
                    it.addOnsEmptyMessage.isVisible = true
                }
            }
        }
    }

    private fun createAddonStyle(context: Context): AddonsManagerAdapter.Style {
        val sectionsTypeFace =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Typeface.create(Typeface.DEFAULT, FONT_WEIGHT_MEDIUM, false)
            } else {
                Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

        return AddonsManagerAdapter.Style(
            sectionsTextColor = ThemeManager.resolveAttribute(materialR.attr.colorOnSurface, context),
            addonNameTextColor = ThemeManager.resolveAttribute(materialR.attr.colorOnSurface, context),
            addonSummaryTextColor = ThemeManager.resolveAttribute(materialR.attr.colorOnSurfaceVariant, context),
            sectionsTypeFace = sectionsTypeFace,
            addonAllowPrivateBrowsingLabelDrawableRes = R.drawable.ic_add_on_private_browsing_label,
        )
    }

    @VisibleForTesting
    internal fun provideAddonManager(): AddonManager {
        return requireContext().components.addonManager
    }

    internal fun installAddon(addon: Addon) {
        if (adminModeAuthView?.adminModeEnabled != true) {
            binding?.root?.let {
                showSnackBar(it, getString(R.string.admin_mode_authentication_required))
            }
            return
        }
        binding?.addonProgressOverlay?.overlayCardView?.visibility = View.VISIBLE

        if (requireComponents.appStore.state.mode.isPrivate) {
            binding
                ?.addonProgressOverlay
                ?.overlayCardView
                ?.setBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.fx_mobile_private_layer_color_3,
                    )
                )
        }

        val installOperation =
            provideAddonManager()
                .installAddon(
                    url = addon.downloadUrl,
                    installationMethod = InstallationMethod.MANAGER,
                    onSuccess = {
                        runIfFragmentIsAttached {
                            adapter?.updateAddon(it)
                            binding?.addonProgressOverlay?.overlayCardView?.visibility = View.GONE
                        }
                    },
                    onError = { _ ->
                        binding?.addonProgressOverlay?.overlayCardView?.visibility = View.GONE
                    },
                )
        binding?.addonProgressOverlay?.cancelButton?.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                val safeBinding = binding
                // Hide the installation progress overlay once cancellation is successful.
                if (installOperation.cancel().await()) {
                    safeBinding?.addonProgressOverlay?.overlayCardView?.visibility = View.GONE
                }
            }
        }
    }

    private fun openAMO() {
        findNavController().openToBrowser()
        requireComponents.useCases.fenixBrowserUseCases.loadUrlOrSearch(
            searchTermOrURL = AMO_HOMEPAGE_FOR_ANDROID,
            newTab = true,
        )
    }

    private fun bindAdminModeControls() {
        adminModeAuthView = binding?.let {
            AdminModeAuthView(
                binding = it,
                onToggle = { enabled -> setAdminMode(enabled, "") },
                onUnlock = { code -> setAdminMode(true, code) },
                onOpenApp = ::openShortcutApp,
            )
        }
        binding?.adminModeTotpSetup?.setOnClickListener { beginTotpSetup() }
        binding?.adminModeRecovery?.setOnClickListener { showEmergencyRecoveryPrompt() }
        binding?.adminModeShortcutApp?.setOnClickListener { chooseShortcutApp() }
    }

    private fun refreshAdminModeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            val state = runCatching { AdminModeController.getState().await() }.getOrNull() ?: return@launch
            ensureActive()
            adminModeAuthView?.updateState(
                enabled = state.getBoolean("enabled"),
                configured = state.getBoolean("totpConfigured"),
                inFlight = adminModeRequestInFlight,
            )
            adapter?.updateAddons(addons)
        }
    }

    private fun setAdminMode(enabled: Boolean, code: String) {
        if (adminModeRequestInFlight) {
            return
        }
        adminModeRequestInFlight = true
        adminModeAuthView?.setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val changed = runCatching { AdminModeController.setEnabled(enabled, code).await() }.getOrDefault(false)
            ensureActive()
            adminModeRequestInFlight = false
            adminModeAuthView?.setBusy(false)
            refreshAdminModeState()
            if (changed != true) {
                if (enabled) {
                    adminModeAuthView?.authenticationFailed()
                } else
                    binding?.root?.let {
                        showSnackBar(it, getString(R.string.admin_mode_authentication_failed))
                    }
            }
        }
    }

    private fun openShortcutApp() {
        if (appShortcut.selectedComponent != null) {
            if (appShortcut.launch(requireActivity())) {
                return
            }
            Toast.makeText(requireContext(), R.string.admin_mode_app_unavailable, Toast.LENGTH_LONG).show()
        }
        chooseShortcutApp()
    }

    private fun shortcutButtonLabel() =
        if (appShortcut.selectedComponent == null) {
            R.string.admin_mode_choose_app
        } else {
            R.string.admin_mode_open_app
        }

    private fun updateShortcutButton() {
        binding?.adminModeOpenApp?.setText(shortcutButtonLabel())
    }

    private fun chooseShortcutApp() {
        val shortcut = appShortcut
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = withContext(IO) { shortcut.availableApps() }
            if (binding == null) {
                return@launch
            }
            val builder =
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.admin_mode_choose_app)
                    .setNegativeButton(android.R.string.cancel, null)
            if (apps.isEmpty()) {
                builder.setMessage(R.string.admin_mode_no_apps)
            } else {
                builder.setSingleChoiceItems(
                    apps.map { it.label }.toTypedArray(),
                    apps.indexOfFirst { it.component == appShortcut.selectedComponent },
                ) { dialog, position ->
                    appShortcut.selectedComponent = apps[position].component
                    updateShortcutButton()
                    dialog.dismiss()
                }
            }
            if (appShortcut.selectedComponent != null) {
                builder.setNeutralButton(R.string.admin_mode_remove_shortcut) { _, _ ->
                    appShortcut.selectedComponent = null
                    updateShortcutButton()
                }
            }
            builder.show()
        }
    }

    private fun beginTotpSetup() {
        viewLifecycleOwner.lifecycleScope.launch {
            val setup = runCatching { AdminModeController.beginTotpSetup().await() }.getOrNull()
            ensureActive()
            if (setup == null) {
                return@launch
            }
            showTotpSetupDialog(setup)
        }
    }

    private fun createTotpQrCode(context: Context, uri: String): ImageView {
        return ImageView(context).apply {
            val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, TOTP_QR_CODE_SIZE, TOTP_QR_CODE_SIZE)
            val bitmap = Bitmap.createBitmap(TOTP_QR_CODE_SIZE, TOTP_QR_CODE_SIZE, Bitmap.Config.ARGB_8888)
            for (x in 0 until TOTP_QR_CODE_SIZE) {
                for (y in 0 until TOTP_QR_CODE_SIZE) {
                    bitmap.setPixel(
                        x,
                        y,
                        if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
                    )
                }
            }
            setImageBitmap(bitmap)
            contentDescription = getString(R.string.admin_mode_setup_title)
            layoutParams = LinearLayout.LayoutParams(TOTP_QR_IMAGE_SIZE, TOTP_QR_IMAGE_SIZE)
        }
    }

    private fun showTotpSetupDialog(setup: GeckoBundle) {
        val context = requireContext()
        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(32, 12, 32, 0)
            }
        val qr = createTotpQrCode(context, setup.getString("otpauthURI").orEmpty())
        content.addView(qr)
        content.addView(
            TextView(context).apply {
                text =
                    getString(R.string.admin_mode_setup_instructions) +
                        "\n\n" +
                        getString(
                            R.string.admin_mode_recovery_code_label,
                            setup.getString("recoveryCode", ""),
                        )
            }
        )
        val code =
            EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = getString(R.string.admin_mode_enter_code)
            }
        content.addView(code)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.admin_mode_setup_title)
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val success = runCatching {
                                AdminModeController.completeTotpSetup(
                                        setup.getString("secret", ""),
                                        setup.getString("recoveryCode", ""),
                                        code.text.toString(),
                                    )
                                    .await()
                            }
                                .getOrDefault(false)
                            ensureActive()
                            if (success == true) {
                                dialog.dismiss()
                                refreshAdminModeState()
                                binding?.root?.let {
                                    showSnackBar(it, getString(R.string.admin_mode_setup_success))
                                }
                            } else {
                                code.error = getString(R.string.admin_mode_authentication_failed)
                            }
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun showEmergencyRecoveryPrompt() {
        val context = requireContext()
        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 12, 32, 0)
            }
        content.addView(TextView(context).apply { text = getString(R.string.admin_mode_recovery_warning) })
        val promise = CheckBox(context).apply { text = getString(R.string.admin_mode_recovery_promise) }
        content.addView(promise)
        val code =
            EditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = getString(R.string.admin_mode_emergency_recovery)
            }
        content.addView(code)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.admin_mode_emergency_recovery)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    val action = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    action.isEnabled = false
                    promise.setOnCheckedChangeListener { _, checked -> action.isEnabled = checked }
                    action.setOnClickListener {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val recovered = runCatching {
                                AdminModeController.recover(code.text.toString(), promise.isChecked).await()
                            }
                                .getOrDefault(false)
                            ensureActive()
                            if (recovered == true) {
                                dialog.dismiss()
                                refreshAdminModeState()
                            } else {
                                code.error = getString(R.string.admin_mode_authentication_failed)
                            }
                        }
                    }
                }
                dialog.show()
            }
    }
}
