package com.glasslauncher.tv.saver

import com.glasslauncher.tv.GlassApp

/** The video playlist for the system screensaver (GlassDreamService): the user's own links only. */
object AerialSource {
    fun playlist(app: GlassApp): List<String> =
        app.settings.aerialUrls.filter { it.startsWith("https://") }.distinct().shuffled()
}
