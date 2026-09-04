package com.linktomac.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Base64
import com.linktomac.net.AppInfo
import java.io.ByteArrayOutputStream

/**
 * Enumerates the phone's launchable apps for the Mac's Screen Mirroring app grid — read-only,
 * no install/uninstall/permission access, matching what a mirroring launcher grid actually
 * needs (see docs/PLAN.md's Screen Mirroring app-launcher notes).
 */
class InstalledAppsRepository(private val context: Context) {

    /** 128x128 renders crisp on the Mac's grid even at retina/HiDPI scaling (a 40px CSS icon on
     *  a 2x display needs 80 real pixels — the original 48px source was upscaled and visibly
     *  blurry). Downscaling a 128px source to display size always looks better than upscaling a
     *  smaller one. Payload cost is still modest: ~150 apps at roughly 6-10KB of PNG each comes
     *  to under 1.5MB total, fine over local WiFi in one shot. */
    private val iconSizePx = 128

    fun listLaunchableApps(): List<AppInfo> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)

        return resolved
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                val appName = info.loadLabel(pm).toString()
                val iconBase64 = try {
                    encodeIcon(info.loadIcon(pm))
                } catch (e: OutOfMemoryError) {
                    // A handful of apps ship unusually large adaptive-icon layers — skip the
                    // icon rather than let one bad app crash the whole enumeration.
                    return@mapNotNull null
                }
                AppInfo(packageName = packageName, appName = appName, iconBase64 = iconBase64)
            }
            .sortedBy { it.appName.lowercase() }
    }

    private fun encodeIcon(drawable: Drawable): String {
        val bitmap = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, iconSizePx, iconSizePx)
        drawable.draw(canvas)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
