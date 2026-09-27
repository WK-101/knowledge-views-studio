package com.wkhan.hexis.util

import android.util.Log

/**
 * Best-effort side-effect guard. Runs [block]; on any [Throwable] it swallows the failure — so a
 * non-critical side-effect (a widget refresh, an FTS rebuild, a startup self-heal, a file cleanup)
 * can never take down the caller — but, unlike a bare `runCatching { }`, it leaves a breadcrumb.
 *
 * The codebase has hundreds of these fire-and-forget guards and only a handful ever logged, so a
 * genuine bug hiding inside one used to be completely invisible. This turns the silent swallow into
 * one WARN line naming [context] and the exception's CLASS — never its message, and never any
 * user-derived string (a note title, a file path) — so nothing sensitive reaches logcat (the same
 * privacy stance as the crash mirror in App.kt). Logged in every build: one line per (rare) failure
 * is cheap, and it's exactly the signal you want when something silently stops working on-device.
 *
 * Returns the block's result, or `null` if it threw — a drop-in for the `runCatching { }.getOrNull()`
 * and fire-and-forget `runCatching { }` shapes already in use.
 */
inline fun <T> runCatchingLogged(context: String, block: () -> T): T? =
    try {
        block()
    } catch (t: Throwable) {
        Log.w("Hexis", "swallowed[$context]: ${t.javaClass.simpleName}")
        null
    }
