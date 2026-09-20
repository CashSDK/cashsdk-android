package com.cashsdk.net

/** One immutable header pair per request, invalidated even by A → B → A sign-ins. */
internal class RequestIdentity {
    class Snapshot(val userId: String?, val userToken: String?)

    @Volatile
    var current = Snapshot(null, null)
        private set

    fun set(userId: String?, userToken: String?) {
        current = Snapshot(userId, userToken)
    }

    fun isCurrent(snapshot: Snapshot): Boolean = current === snapshot
}
