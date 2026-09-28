package com.cashsdk

import android.app.ActivityManager

/**
 * Whether the user can see the app, for work that should only run then: the read made at an
 * access deadline, and its retries.
 *
 * Counted from the activity lifecycle callbacks the client registers in `configure()`. That can
 * happen after the first Activity has started (Midgame, for one, configures lazily during its
 * first composition), and a start that happened before registration is never reported: a count
 * alone read "background" for the whole first session. So whenever the count is zero, the
 * process's own importance decides, which is right whether or not the start was seen. No
 * lifecycle-process dependency is needed for that.
 */
internal class ForegroundState(private val processVisible: () -> Boolean = ::processIsVisible) {
    @Volatile
    private var started = 0

    /**
     * An activity started. True when it brings the app to the foreground (time to flush and
     * refresh). Counted only from callbacks, never from the process importance: by the time a
     * returning user's activity starts, the process may already rank as foreground.
     */
    fun activityStarted(): Boolean = started++ == 0

    fun activityStopped() {
        if (started > 0) started--
    }

    val isForeground: Boolean get() = started > 0 || processVisible()
}

/** The process has a foreground or visible activity; a foreground service alone does not count. */
internal fun processIsVisible(): Boolean = runCatching {
    val info = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
        info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
}.getOrDefault(true)
