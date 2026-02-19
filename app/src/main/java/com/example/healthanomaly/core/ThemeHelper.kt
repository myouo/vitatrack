package com.example.healthanomaly.core

import android.app.Activity
import android.content.Context
import com.example.healthanomaly.R

/**
 * Helper class for managing app theme.
 */
object ThemeHelper {

    /**
     * Apply the saved theme to an activity.
     */
    fun applyTheme(activity: Activity, preferencesManager: PreferencesManager) {
        val isDarkTheme = preferencesManager.isDarkThemeEnabled()
        activity.setTheme(
            if (isDarkTheme) R.style.Theme_HealthAnomaly_Dark
            else R.style.Theme_HealthAnomaly_Light
        )
    }

    /**
     * Get the current theme resource ID.
     */
    fun getThemeResId(context: Context, preferencesManager: PreferencesManager): Int {
        val isDarkTheme = preferencesManager.isDarkThemeEnabled()
        return if (isDarkTheme) R.style.Theme_HealthAnomaly_Dark
        else R.style.Theme_HealthAnomaly_Light
    }

    /**
     * Check if dark theme is currently enabled.
     */
    fun isDarkTheme(preferencesManager: PreferencesManager): Boolean {
        return preferencesManager.isDarkThemeEnabled()
    }
}
