/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package at.saltyy.switchly.feature.usage

import android.app.Activity
import android.content.Intent
import at.saltyy.switchly.R
import at.saltyy.switchly.feature.premium.PremiumInfoActivity
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.ui.dialog.showAccented
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal object StatsPremiumGate {
    fun isPremium(activity: Activity): Boolean = PremiumManager.isPremium(activity)

    fun canUseExtendedStats(activity: Activity): Boolean = isPremium(activity)

    fun show(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.usage_details_premium_title)
            .setMessage(R.string.usage_details_premium_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.usage_details_premium_action) { _, _ ->
                activity.startActivity(Intent(activity, PremiumInfoActivity::class.java))
            }
            .showAccented()
    }
}
