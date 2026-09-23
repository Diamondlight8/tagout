package com.example.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.data.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppInfoProvider(private val context: Context) {

    suspend fun getInstalledLauncherApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val currentPackage = context.packageName

        val appList = mutableListOf<InstalledApp>()
        val seenPackages = mutableSetOf<String>()

        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName
            if (pkg == currentPackage || seenPackages.contains(pkg)) continue
            seenPackages.add(pkg)

            val label = info.loadLabel(pm).toString()
            val icon = try {
                info.loadIcon(pm)
            } catch (_: Exception) {
                null
            }

            appList.add(
                InstalledApp(
                    packageName = pkg,
                    label = label,
                    icon = icon
                )
            )
        }

        appList.sortedBy { it.label.lowercase() }
    }
}
