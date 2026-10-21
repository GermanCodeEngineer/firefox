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
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.R as materialR
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
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
import org.mozilla.geckoview.AdminModeController
import org.mozilla.geckoview.ExperimentalGeckoViewApi
import org.mozilla.gecko.util.GeckoBundle

/** Fragment use for managing add-ons. */
@Suppress("TooManyFunctions", "LargeClass")
@androidx.annotation.OptIn(ExperimentalGeckoViewApi::class)
class AddonsManagementFragment : Fragment(R.layout.fragment_add_ons_management), SystemInsetsPaddedFragment {

    private var binding: FragmentAddOnsManagementBinding? = null

    private var addons: List<Addon> = emptyList()

    private var adapter: AddonsManagerAdapter? = null

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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // letting go of the resources to avoid memory leak.
        adapter = null
        binding = null
    }

    @Suppress("CognitiveComplexMethod")
    private fun bindRecyclerView() {
        val managementView =
            AddonsManagementView(
                navController = findNavController(),
                onInstallButtonClicked = ::installAddon,
                onMoreAddonsButtonClicked = ::openAMO,
                isInstallEnabled = { binding?.adminModeSwitch?.isChecked == true },
                onLearnMoreClicked = { link, addon ->
                    binding?.root?.openLearnMoreLink(link, addon)
                },
            )

        val recyclerView = binding?.addOnsList
        recyclerView?.layoutManager = LinearLayoutManager(requireContext())
        val shouldRefresh = adapter != null

        lifecycleScope.launch(IO) {
            try {
                addons = requireContext().components.addonManager.getAddons()
                lifecycleScope.launch(Dispatchers.Main) {
                    runIfFragmentIsAttached {
                        if (!shouldRefresh) {
                            adapter =
                                AddonsManagerAdapter(
                                    addonsManagerDelegate = managementView,
                                    addons = addons,
                                    style = createAddonStyle(requireContext()),
                                    store = requireComponents.core.store,
                                )
                        }
                        binding?.addOnsProgressBar?.isVisible = false
                        binding?.addOnsEmptyMessage?.isVisible = false

                        recyclerView?.adapter = adapter
                        recyclerView?.accessibilityDelegate =
                            object : View.AccessibilityDelegate() {
                                override fun onInitializeAccessibilityNodeInfo(
                                    host: View,
                                    info: AccessibilityNodeInfo,
                                ) {
                                    super.onInitializeAccessibilityNodeInfo(host, info)

                                    adapter?.let {
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

                        if (shouldRefresh) {
                            adapter?.updateAddons(addons)
                        }
                    }
                }
            } catch (e: AddonManagerException) {
                lifecycleScope.launch(Dispatchers.Main) {
                    runIfFragmentIsAttached {
                        binding?.let {
                            showSnackBar(
                                it.root,
                                getString(addonsR.string.mozac_feature_addons_failed_to_load_extensions),
                            )
                        }
                        binding?.addOnsProgressBar?.isVisible = false
                        binding?.addOnsEmptyMessage?.isVisible = true
                    }
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
        if (binding?.adminModeSwitch?.isChecked != true) {
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
            lifecycleScope.launch(Dispatchers.Main) {
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
        binding?.adminModeSwitch?.setOnCheckedChangeListener { _, enabled ->
            if (enabled) {
                lifecycleScope.launch {
                    val state = runCatching { AdminModeController.getState().await() }.getOrNull()
                    if (state?.getBoolean("totpConfigured") == true) {
                        promptForAuthenticatorCode { code -> setAdminMode(enabled, code) }
                    } else {
                        setAdminMode(enabled, "")
                    }
                }
            } else {
                setAdminMode(false, "")
            }
        }
        binding?.adminModeTotpSetup?.setOnClickListener { beginTotpSetup() }
        binding?.adminModeRecovery?.setOnClickListener { showEmergencyRecoveryPrompt() }
    }

    private fun refreshAdminModeState() {
        lifecycleScope.launch {
            val state = runCatching { AdminModeController.getState().await() }.getOrNull() ?: return@launch
            val safeBinding = binding ?: return@launch
            safeBinding.adminModeSwitch.setOnCheckedChangeListener(null)
            safeBinding.adminModeSwitch.isChecked = state.getBoolean("enabled")
            safeBinding.adminModeSwitch.setOnCheckedChangeListener { _, enabled ->
                if (enabled && state.getBoolean("totpConfigured")) {
                    promptForAuthenticatorCode { code -> setAdminMode(enabled, code) }
                } else {
                    setAdminMode(enabled, "")
                }
            }
            safeBinding.adminModeTotpSetup.isEnabled = state.getBoolean("enabled")
            safeBinding.adminModeRecovery.isEnabled = state.getBoolean("totpConfigured")
            adapter?.updateAddons(addons)
        }
    }

    private fun setAdminMode(enabled: Boolean, code: String) {
        binding?.adminModeSwitch?.isEnabled = false
        lifecycleScope.launch {
            val changed = runCatching { AdminModeController.setEnabled(enabled, code).await() }.getOrDefault(false)
            refreshAdminModeState()
            binding?.adminModeSwitch?.isEnabled = true
            if (changed != true) {
                binding?.root?.let {
                    showSnackBar(it, getString(R.string.admin_mode_authentication_failed))
                }
            }
        }
    }

    private fun promptForAuthenticatorCode(onCode: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.admin_mode_enter_code)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.admin_mode_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel) { _, _ -> refreshAdminModeState() }
            .setPositiveButton(android.R.string.ok, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        onCode(input.text.toString())
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun beginTotpSetup() {
        lifecycleScope.launch {
            val setup = runCatching { AdminModeController.beginTotpSetup().await() }.getOrNull()
            if (setup == null) {
                return@launch
            }
            showTotpSetupDialog(setup)
        }
    }

    private fun showTotpSetupDialog(setup: GeckoBundle) {
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 12, 32, 0)
        }
        val qr = ImageView(context).apply {
            val matrix = QRCodeWriter().encode(setup.getString("otpauthURI"), BarcodeFormat.QR_CODE, 512, 512)
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            for (x in 0 until 512) {
                for (y in 0 until 512) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            setImageBitmap(bitmap)
            contentDescription = getString(R.string.admin_mode_setup_title)
            layoutParams = LinearLayout.LayoutParams(224, 224)
        }
        content.addView(qr)
        content.addView(TextView(context).apply {
            text =
                getString(R.string.admin_mode_setup_instructions) + "\n\n" +
                    getString(
                        R.string.admin_mode_recovery_code_label,
                        setup.getString("recoveryCode", ""),
                    )
        })
        val code = EditText(context).apply {
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
                        lifecycleScope.launch {
                            val success = runCatching {
                                AdminModeController.completeTotpSetup(
                                    setup.getString("secret", ""),
                                    setup.getString("recoveryCode", ""),
                                    code.text.toString(),
                                ).await()
                            }.getOrDefault(false)
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
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 12, 32, 0)
        }
        content.addView(TextView(context).apply { text = getString(R.string.admin_mode_recovery_warning) })
        val promise = CheckBox(context).apply { text = getString(R.string.admin_mode_recovery_promise) }
        content.addView(promise)
        val code = EditText(context).apply {
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
                        lifecycleScope.launch {
                            val recovered = runCatching {
                                AdminModeController.recover(code.text.toString(), promise.isChecked).await()
                            }.getOrDefault(false)
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
