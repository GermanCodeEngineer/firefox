/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.addons

import android.os.Parcelable
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.isVisible
import io.mockk.mockk
import io.mockk.verify
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.R
import org.mozilla.fenix.databinding.FragmentAddOnsManagementBinding
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdminModeAuthViewTest {
    private lateinit var binding: FragmentAddOnsManagementBinding
    private lateinit var controls: AdminModeAuthView
    private val onToggle: (Boolean) -> Unit = mockk(relaxed = true)
    private val onUnlock: (String) -> Unit = mockk(relaxed = true)
    private val onOpenApp: () -> Unit = mockk(relaxed = true)

    @Before
    fun setup() {
        val context = ContextThemeWrapper(testContext, R.style.NormalTheme)
        binding = FragmentAddOnsManagementBinding.inflate(LayoutInflater.from(context))
        controls = AdminModeAuthView(binding, onToggle, onUnlock, onOpenApp)
        controls.updateState(enabled = false, configured = true)
    }

    @Test
    fun `enabling the switch cannot unlock without a code`() {
        binding.adminModeSwitch.isChecked = true

        assertFalse(binding.adminModeSwitch.isChecked)
        assertFalse(controls.adminModeEnabled)
        assertTrue(binding.adminModeAuthForm.isVisible)
        verify(exactly = 0) { onToggle(any()) }
        verify(exactly = 0) { onUnlock(any()) }
    }

    @Test
    fun `only a complete code can be submitted`() {
        binding.adminModeCode.setText("12345")
        assertFalse(binding.adminModeUnlock.isEnabled)
        binding.adminModeCode.setText("123456")
        assertTrue(binding.adminModeUnlock.isEnabled)
        binding.adminModeUnlock.performClick()

        verify(exactly = 1) { onUnlock("123456") }
    }

    @Test
    fun `keyboard done submits a complete code`() {
        binding.adminModeCode.setText("123456")
        binding.adminModeCode.onEditorAction(EditorInfo.IME_ACTION_DONE)

        verify(exactly = 1) { onUnlock("123456") }
    }

    @Test
    fun `refresh after returning from another app preserves entered code`() {
        binding.adminModeCode.setText("123")
        controls.updateState(enabled = false, configured = true)

        assertEquals("123", binding.adminModeCode.text.toString())
    }

    @Test
    fun `view recreation restores code without restoring authorization`() {
        binding.adminModeCode.setText("123456")
        val state = SparseArray<Parcelable>()
        binding.root.saveHierarchyState(state)
        setup()
        binding.root.restoreHierarchyState(state)
        controls.refresh()

        assertEquals("123456", binding.adminModeCode.text.toString())
        assertTrue(binding.adminModeUnlock.isEnabled)
        assertFalse(controls.adminModeEnabled)
        assertFalse(binding.adminModeSwitch.isChecked)
        verify(exactly = 0) { onUnlock(any()) }
    }

    @Test
    fun `pending authentication prevents duplicate submissions`() {
        binding.adminModeCode.setText("123456")
        controls.setBusy(true)
        binding.adminModeUnlock.performClick()
        binding.adminModeCode.onEditorAction(EditorInfo.IME_ACTION_DONE)

        assertFalse(binding.adminModeUnlock.isEnabled)
        verify(exactly = 0) { onUnlock(any()) }
    }

    @Test
    fun `failed authentication keeps code and allows correction`() {
        binding.adminModeCode.setText("123456")
        controls.authenticationFailed()

        assertEquals("123456", binding.adminModeCode.text.toString())
        assertEquals(
            testContext.getString(R.string.admin_mode_authentication_failed),
            binding.adminModeCodeLayout.error,
        )
        assertTrue(binding.adminModeUnlock.isEnabled)
        binding.adminModeCode.setText("654321")
        assertEquals(null, binding.adminModeCodeLayout.error)
    }

    @Test
    fun `successful authentication clears code and hides the form`() {
        binding.adminModeCode.setText("123456")
        controls.updateState(enabled = true, configured = true)

        assertEquals("", binding.adminModeCode.text.toString())
        assertTrue(binding.adminModeSwitch.isChecked)
        assertTrue(controls.adminModeEnabled)
        assertFalse(binding.adminModeAuthForm.isVisible)
    }

    @Test
    fun `shortcut opens the selected app without submitting the form`() {
        binding.adminModeOpenApp.performClick()

        verify(exactly = 1) { onOpenApp() }
        verify(exactly = 0) { onUnlock(any()) }
    }
}
