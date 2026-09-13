package com.spop.poverlay.zone

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import timber.log.Timber

/**
 * Works out what the rider is actually looking at, so the curtain can stay off screens it has
 * no business covering.
 *
 * An overlay window draws over everything by design; nothing in the window system will tell an
 * app what is underneath it. The only way to know is to ask UsageStatsManager, which needs the
 * Usage access grant - a special permission the rider has to give by hand in Android's
 * settings. Without it this reports nothing and the curtain behaves exactly as it did before,
 * which is the right way round: a missing grant must not be able to break enforcement.
 */
class ForegroundAppMonitor(private val context: Context) {

    companion object {
        /**
         * How far back to look for a foreground change. Generous, because the query only
         * returns events and a rider can sit on one screen for a long time.
         */
        private const val LookbackMs = 60_000L
    }

    private val usageStats =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /** Anything that can act as the home screen. Resolved once; it will not change mid-ride. */
    private val launcherPackages: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        runCatching {
            context.packageManager
                .queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
                .mapTo(mutableSetOf()) { it.activityInfo.packageName }
        }.getOrDefault(emptySet()).also {
            Timber.i("Launcher packages the curtain will stay off: %s", it)
        }
    }

    @Volatile
    var foregroundPackage: String? = null
        private set

    /** True when the rider is on a home screen or app drawer rather than something to watch. */
    val isLauncherForeground: Boolean
        get() = foregroundPackage?.let { it in launcherPackages } ?: false

    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Polls for the latest foreground change. Cheap enough for once a second. */
    fun refresh() {
        val manager = usageStats ?: return
        val now = System.currentTimeMillis()
        val events = runCatching { manager.queryEvents(now - LookbackMs, now) }.getOrNull() ?: return
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                latest = event.packageName
            }
        }
        // Keep the last known package when the window held no events: the rider has simply not
        // switched apps, not vanished.
        if (latest != null) foregroundPackage = latest
    }
}
