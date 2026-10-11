package tv.ezano.app

/** Pure scheduling rule for automatic guide refreshes. */
internal object GuideRefreshPolicy {
    fun due(
        enabled: Boolean,
        blocked: Boolean,
        lastAttempt: Long,
        lastSuccess: Long,
        intervalHours: Int,
        now: Long,
    ): Boolean = enabled && !blocked &&
        now - maxOf(lastAttempt, lastSuccess) > intervalHours * 3_600_000L
}
