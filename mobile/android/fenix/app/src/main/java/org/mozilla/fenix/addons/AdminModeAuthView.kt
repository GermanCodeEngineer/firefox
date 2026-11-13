/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.addons

import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import org.mozilla.fenix.R
import org.mozilla.fenix.databinding.FragmentAddOnsManagementBinding

private const val AUTH_CODE_LENGTH = 6

/** Keeps the inline authentication controls consistent with the verified Admin Mode state. */
internal class AdminModeAuthView(
    private val binding: FragmentAddOnsManagementBinding,
    private val onToggle: (Boolean) -> Unit,
    private val onUnlock: (String) -> Unit,
    onOpenApp: () -> Unit,
) {
    var adminModeEnabled = false
        private set

    private var totpConfigured = false
    private var busy = true
    private var updating = false

    init {
        binding.adminModeSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!updating && !busy) {
                refresh()
                if (enabled && totpConfigured) {
                    binding.adminModeCode.requestFocus()
                } else {
                    onToggle(enabled)
                }
            }
        }
        binding.adminModeCode.doAfterTextChanged {
            binding.adminModeCodeLayout.error = null
            refresh()
        }
        binding.adminModeUnlock.setOnClickListener {
            if (canUnlock()) {
                onUnlock(binding.adminModeCode.text.toString())
            }
        }
        binding.adminModeCode.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE && canUnlock()) {
                binding.adminModeUnlock.performClick()
                true
            } else {
                false
            }
        }
        binding.adminModeOpenApp.setOnClickListener { onOpenApp() }
        refresh()
    }

    fun updateState(enabled: Boolean, configured: Boolean, inFlight: Boolean = false) {
        adminModeEnabled = enabled
        totpConfigured = configured
        busy = inFlight
        if (enabled) {
            binding.adminModeCode.text?.clear()
            binding.adminModeCodeLayout.error = null
        }
        refresh()
    }

    fun setBusy(value: Boolean) {
        busy = value
        refresh()
    }

    fun authenticationFailed() {
        busy = false
        binding.adminModeCodeLayout.error = binding.root.context.getString(R.string.admin_mode_authentication_failed)
        refresh()
    }

    fun refresh() {
        updating = true
        binding.adminModeSwitch.isChecked = adminModeEnabled
        updating = false
        binding.adminModeSwitch.isEnabled = !busy
        binding.adminModeAuthForm.isVisible = !adminModeEnabled && totpConfigured
        binding.adminModeCode.isEnabled = !busy
        binding.adminModeUnlock.isEnabled = canUnlock()
        binding.adminModeTotpSetup.isEnabled = !busy && adminModeEnabled
        binding.adminModeRecovery.isEnabled = !busy && totpConfigured
    }

    private fun canUnlock(): Boolean {
        val code = binding.adminModeCode.text.toString()
        return !busy &&
            !adminModeEnabled &&
            totpConfigured &&
            code.length == AUTH_CODE_LENGTH &&
            code.all { it in '0'..'9' }
    }
}
