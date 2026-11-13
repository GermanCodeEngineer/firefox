/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.addons

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.edit

/** Remembers and opens the app selected for the Admin Mode unlock shortcut. */
internal class AdminModeAppShortcut(private val context: Context) {
    private val preferences = context.getSharedPreferences("admin_mode_app_shortcut", Context.MODE_PRIVATE)

    var selectedComponent: ComponentName?
        get() = preferences.getString("component", null)?.let(ComponentName::unflattenFromString)
        set(value) {
            preferences.edit { putString("component", value?.flattenToString()) }
        }

    data class App(val component: ComponentName, val label: String)

    fun availableApps(): List<App> {
        val packageManager = context.packageManager
        return packageManager.queryIntentActivities(launcherIntent(), 0)
            .filter {
                it.activityInfo.exported && it.activityInfo.enabled &&
                    it.activityInfo.applicationInfo.enabled && it.activityInfo.packageName != context.packageName
            }
            .map {
                App(
                    ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                    it.loadLabel(packageManager).toString(),
                )
            }
            .distinctBy { it.component }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun launch(activity: Activity): Boolean {
        val component = selectedComponent?.takeUnless { it.packageName == context.packageName } ?: return false
        return try {
            activity.startActivity(launcherIntent().setComponent(component))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
}
