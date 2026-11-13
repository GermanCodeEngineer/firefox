/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.addons

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdminModeAppShortcutTest {
    private val context: Context = mockk()
    private val packageManager: PackageManager = mockk()
    private val activity: Activity = mockk()
    private lateinit var shortcut: AdminModeAppShortcut
    private val component = ComponentName("example.authenticator", "example.authenticator.MainActivity")

    @Before
    fun setup() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val preferences = application.getSharedPreferences("shortcut_test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        every { context.getSharedPreferences(any(), Context.MODE_PRIVATE) } returns preferences
        every { context.packageName } returns "org.mozilla.fenix"
        every { context.packageManager } returns packageManager
        every { activity.startActivity(any()) } just Runs
        shortcut = AdminModeAppShortcut(context)
    }

    @Test
    fun `selection survives recreating the shortcut and can be removed`() {
        assertNull(shortcut.selectedComponent)
        shortcut.selectedComponent = component

        val recreated = AdminModeAppShortcut(context)
        assertEquals(component, recreated.selectedComponent)

        recreated.selectedComponent = null
        assertNull(AdminModeAppShortcut(context).selectedComponent)
    }

    @Test
    fun `launch opens the selected component without creating or clearing a task`() {
        shortcut.selectedComponent = component

        assertTrue(shortcut.launch(activity))

        val intent = slot<Intent>()
        verify(exactly = 1) { activity.startActivity(capture(intent)) }
        assertEquals(component, intent.captured.component)
        assertEquals(Intent.ACTION_MAIN, intent.captured.action)
        assertEquals(setOf(Intent.CATEGORY_LAUNCHER), intent.captured.categories)
        assertEquals(0, intent.captured.flags)
        assertNull(intent.captured.extras)
    }

    @Test
    fun `launch without a selection does not open an activity`() {
        assertFalse(shortcut.launch(activity))

        verify(exactly = 0) { activity.startActivity(any()) }
    }

    @Test
    fun `launch does not open Firefox itself`() {
        shortcut.selectedComponent = ComponentName(context.packageName, "HomeActivity")

        assertFalse(shortcut.launch(activity))

        verify(exactly = 0) { activity.startActivity(any()) }
    }

    @Test
    fun `an uninstalled app fails without losing the selection`() {
        shortcut.selectedComponent = component
        every { activity.startActivity(any()) } throws ActivityNotFoundException()

        assertFalse(shortcut.launch(activity))
        assertEquals(component, shortcut.selectedComponent)
    }

    @Test
    fun `an app that can no longer be launched fails safely`() {
        shortcut.selectedComponent = component
        every { activity.startActivity(any()) } throws SecurityException()

        assertFalse(shortcut.launch(activity))
    }

    @Test
    fun `app chooser lists sorted distinct enabled external launcher activities`() {
        val authenticator = resolveInfo(component.packageName, component.className, "Authenticator")
        val calculator = resolveInfo("example.calculator", "CalculatorActivity", "calculator")
        val firefox = resolveInfo(context.packageName, "HomeActivity", "Firefox")
        val privateActivity = resolveInfo("example.private", "PrivateActivity", "Private").apply {
            activityInfo.exported = false
        }
        val disabled = resolveInfo("example.disabled", "DisabledActivity", "Disabled").apply {
            activityInfo.enabled = false
        }
        val disabledApp = resolveInfo("example.disabledapp", "MainActivity", "Disabled app").apply {
            activityInfo.applicationInfo.enabled = false
        }
        every { packageManager.queryIntentActivities(any<Intent>(), 0) } returns listOf(
            calculator, firefox, privateActivity, authenticator, disabled, disabledApp, authenticator,
        )

        val apps = shortcut.availableApps()

        assertEquals(listOf("Authenticator", "calculator"), apps.map { it.label })
        assertEquals(component, apps.first().component)
        verify {
            packageManager.queryIntentActivities(
                match { it.action == Intent.ACTION_MAIN && it.hasCategory(Intent.CATEGORY_LAUNCHER) },
                0,
            )
        }
    }

    private fun resolveInfo(packageName: String, name: String, label: String) = ResolveInfo().apply {
        nonLocalizedLabel = label
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            this.name = name
            exported = true
            enabled = true
            applicationInfo = ApplicationInfo().apply { enabled = true }
        }
    }
}
