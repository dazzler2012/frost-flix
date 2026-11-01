package com.glasslauncher.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/**
 * Home button → Frost-flix.
 *
 * Google TV sends Home to its own launcher no matter which app is the default home. This service
 * (switched on by the user under Settings > Accessibility) receives short-press Home keys *before* the system
 * does and consumes them, so the stock launcher never appears and there is no flash.
 * Long-press / repeated Home events are yielded back to the OS to preserve native NVIDIA Shield behavior
 * and avoid race conditions with Projectivy Launcher.
 *
 * Cost: with key filtering on, Android hands every key press of the whole TV to this service first and
 * waits for the answer. So the filter is only requested while "Home button opens Frost-flix" is on, and
 * [onKeyEvent] is one comparison: every key except HOME is passed straight through, untouched.
 */
class HomeKeyService : AccessibilityService() {
    private var lastLaunch = 0L
    private var homeDownAt = 0L
    private var homeYieldedAt = 0L
    private var homeLongPress = false

    private fun showGlass() {
        val now = System.currentTimeMillis()
        if (now - lastLaunch < DEBOUNCE_MS) return
        lastLaunch = now
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) {
            // Never crash the service (that would turn it off). The Home press is lost this once.
            android.util.Log.w("FrostHome", "can't bring Frost-flix to the front: ${e.javaClass.simpleName}")
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME || !intercept) return false
        val now = System.currentTimeMillis()
        // Only consume single short-press HOME events (repeatCount == 0); yield all long-presses and repeats
        // back to the OS so native NVIDIA Shield shortcuts and Projectivy Launcher never race or break.
        if (event.repeatCount == 0 && !event.isLongPress) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    homeDownAt = now
                    homeLongPress = false
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    val canceled = (event.flags and KeyEvent.FLAG_CANCELED) != 0
                    val heldLong = (event.eventTime - event.downTime) >= ViewConfiguration.getLongPressTimeout()
                    val yielded = homeLongPress || canceled || heldLong
                    homeLongPress = false
                    if (yielded) {
                        homeYieldedAt = now
                        return false
                    }
                    showGlass()
                    return true
                }
            }
            return false
        }
        homeLongPress = true
        homeYieldedAt = now
        if (event.action == KeyEvent.ACTION_UP) homeLongPress = false
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !intercept) return
        if (homeLongPress) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.google.android.apps.tv.launcherx" && pkg != "com.google.android.tvlauncher") return
        val now = System.currentTimeMillis()
        // Do not hijack window transitions during or right after a yielded long-press or handled key event,
        // or when key filtering is active and Projectivy / system UI is handling a shortcut.
        if (now - homeYieldedAt < LONG_PRESS_COOLDOWN_MS || now - homeDownAt < 1500L) return
        val filteringKeys = (serviceInfo?.flags ?: 0) and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
        if (filteringKeys && projectivyInstalled()) return
        showGlass()
    }

    private fun projectivyInstalled(): Boolean = runCatching {
        packageManager.getPackageInfo(PROJECTIVY_PKG, 0) != null
    }.getOrDefault(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        applyFilter()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance?.get() === this) instance = null
        return super.onUnbind(intent)
    }

    /** Asks for key events only while interception is on; otherwise the TV's keys never come through here. */
    private fun applyFilter() {
        val info = serviceInfo ?: return
        val want = intercept
        val has = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
        if (want == has) return
        info.flags = if (want) info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            else info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        serviceInfo = info
    }

    override fun onInterrupt() {}

    companion object {
        /** Second Home presses closer together than this are ignored (one press can arrive twice). */
        private const val DEBOUNCE_MS = 400L
        private const val LONG_PRESS_COOLDOWN_MS = 2500L
        private const val PROJECTIVY_PKG = "com.spocky.projengmenu"

        /** Mirror of Settings.interceptHome, read on every key press without touching preferences. */
        @Volatile var intercept = false
            private set
        private var instance: WeakReference<HomeKeyService>? = null

        /**
         * True when the user has the Frost-flix accessibility service switched on. Public APIs only: the
         * services AccessibilityManager reports as enabled, and the ENABLED_ACCESSIBILITY_SERVICES secure
         * setting (read only; Frost-flix can't change it). Android turns the service off when a sideloaded app
         * is updated, so this is checked on every start.
         */
        fun isEnabled(ctx: android.content.Context): Boolean {
            if (instance?.get() != null) return true
            val name = HomeKeyService::class.java.name
            val am = ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            val listed = runCatching {
                am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty().any {
                    val si = it.resolveInfo?.serviceInfo
                    si != null && si.packageName == ctx.packageName && si.name == name
                }
            }.getOrDefault(false)
            if (listed) return true
            val secure = runCatching {
                android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            }.getOrNull().orEmpty()
            return secure.split(':').any { android.content.ComponentName.unflattenFromString(it.trim())?.let { c -> c.packageName == ctx.packageName && c.className == name } == true }
        }

        /** Called at app start and when "Home button opens Frost-flix" changes. */
        fun setIntercept(on: Boolean) {
            intercept = on
            instance?.get()?.let { s -> android.os.Handler(android.os.Looper.getMainLooper()).post { s.applyFilter() } }
        }
    }
}
