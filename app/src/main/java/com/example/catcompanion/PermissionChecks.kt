package com.example.catcompanion

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.provider.Settings

internal object PermissionChecks {
    fun overlayAllowed(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun usageAllowed(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
}
