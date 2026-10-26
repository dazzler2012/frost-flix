package com.glasslauncher.tv.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast

/**
 * Opens a title inside NuvioTV, Nuvio Legacy or Nuvio Re-Shaped, whichever is installed.
 * NuvioTV has two packages: the GitHub build (com.nuvio.tv) and the Google Play build (com.nuvio.app).
 * "auto" (default) tries NuvioTV first (GitHub, then Play), then Legacy, then Re-Shaped. A fixed choice is tried first
 * and falls back to the same order when that app is not installed.
 */
object NuvioLaunch {
    const val NUVIOTV_PKG = "com.nuvio.tv"
    /** NuvioTV's "playstore" flavor: same nuvio:// links, different applicationId. */
    const val NUVIOTV_PLAY_PKG = "com.nuvio.app"
    const val LEGACY_PKG = "space.nuvio.nativelegacy"
    const val RESHAPED_PKG = "com.nuvioreshaped.tv"

    private val NUVIOTV_PKGS = listOf(NUVIOTV_PKG, NUVIOTV_PLAY_PKG)
    private val AUTO_ORDER = NUVIOTV_PKGS + listOf(LEGACY_PKG, RESHAPED_PKG)

    private fun preferred(s: Settings) = when (s.nuvioTarget) {
        "nuviotv" -> NUVIOTV_PKGS
        "legacy" -> listOf(LEGACY_PKG)
        "reshaped" -> listOf(RESHAPED_PKG)
        else -> emptyList()
    }

    private fun installed(ctx: Context, pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    /** The Nuvio package a title opens in, or null when none is installed. */
    fun packageName(ctx: Context, s: Settings): String? =
        (preferred(s) + AUTO_ORDER).distinct().firstOrNull { installed(ctx, it) }

    fun intent(pkg: String, type: String, id: String): Intent {
        // No CLEAR_TASK: a running Nuvio is brought forward and opens the title at once, without
        // restarting (no splash). If Nuvio is not running it starts normally.
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val uri = if (pkg == LEGACY_PKG) {
            val t = if (type.equals("movie", true)) "movie" else "series"
            Uri.Builder().scheme("nuvio-legacy").authority("meta").appendPath(t).appendPath(id.trim()).build()
        } else {
            // NuvioTV (both builds) and Re-Shaped: nuvio://meta?type=movie|series&id=tt123 (NuvioTV DeepLinkParser).
            Uri.Builder().scheme("nuvio").authority("meta")
                .appendQueryParameter("type", type.lowercase()).appendQueryParameter("id", id.trim()).build()
        }
        return Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(flags)
    }

    fun open(ctx: Context, s: Settings, item: HeroItem) {
        val pkg = packageName(ctx, s)
        if (pkg == null) {
            Toast.makeText(ctx, "Nuvio is not installed", Toast.LENGTH_LONG).show()
            return
        }
        try {
            ctx.startActivity(intent(pkg, item.type, item.id))
        } catch (e: Exception) {
            // Installed but no title link (e.g. an older build): just open the app.
            val pm = ctx.packageManager
            val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            try {
                ctx.startActivity(launch!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(ctx, "Nuvio can't open this title directly, opened Nuvio instead", Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {
                Toast.makeText(ctx, "Nuvio can't open this title", Toast.LENGTH_LONG).show()
            }
        }
    }
}
