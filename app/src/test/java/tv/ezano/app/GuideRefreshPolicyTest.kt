package tv.ezano.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideRefreshPolicyTest {
    private val sixHours = 6L * 3_600_000L

    @Test fun recentFailedAttemptStaysBlockedEvenWhenLastSuccessIsOld() {
        val oldSuccess = 30L * 24 * 3_600_000L
        val failedAt = oldSuccess + 1_000L

        assertFalse(
            GuideRefreshPolicy.due(
                enabled = true,
                blocked = true,
                lastAttempt = failedAt,
                lastSuccess = 1_000L,
                intervalHours = 6,
                now = failedAt + 30L * 24 * 3_600_000L,
            ),
        )
    }

    @Test fun persistedFailureRemainsBlockedAfterStoreIsReopened() {
        // These values represent the attempt and block flag loaded from persistent storage.
        val persistedAttempt = 1_000_000L
        val persistedBlocked = true

        assertFalse(
            GuideRefreshPolicy.due(
                enabled = true,
                blocked = persistedBlocked,
                lastAttempt = persistedAttempt,
                lastSuccess = 0L,
                intervalHours = 6,
                now = persistedAttempt + 90L * 24 * 3_600_000L,
            ),
        )
    }

    @Test fun successfulProviderBecomesDueOnlyAfterConfiguredInterval() {
        val success = 10_000L

        assertFalse(
            GuideRefreshPolicy.due(
                enabled = true,
                blocked = false,
                lastAttempt = success,
                lastSuccess = success,
                intervalHours = 6,
                now = success + sixHours,
            ),
        )
        assertTrue(
            GuideRefreshPolicy.due(
                enabled = true,
                blocked = false,
                lastAttempt = success,
                lastSuccess = success,
                intervalHours = 6,
                now = success + sixHours + 1L,
            ),
        )
    }

    @Test fun disabledProviderIsNeverAutomaticallyDue() {
        assertFalse(
            GuideRefreshPolicy.due(
                enabled = false,
                blocked = false,
                lastAttempt = 0L,
                lastSuccess = 0L,
                intervalHours = 1,
                now = 24L * 3_600_000L,
            ),
        )
    }
}
