package tv.ezano.app

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import tv.ezano.app.DeviceCheck.device
import tv.ezano.app.DeviceCheck.focused
import tv.ezano.app.DeviceCheck.focusedText
import tv.ezano.app.DeviceCheck.metric
import tv.ezano.app.DeviceCheck.note
import tv.ezano.app.DeviceCheck.route
import tv.ezano.app.DeviceCheck.shot
import tv.ezano.app.DeviceCheck.step
import tv.ezano.app.DeviceCheck.store

/**
 * A fresh install with no playlist, operated only with the remote: Home must offer "Add a playlist",
 * D-pad Down from the top navigation must reach it, and Select must open the playlist manager.
 * Run after `adb shell pm clear tv.ezano.app` (tools/device-check.sh does this).
 */
class FreshInstallTest {
    @get:Rule val failureArtifacts = FailureArtifacts()

    @Test fun freshHomeReachesAddPlaylistWithRemote() {
        DeviceCheck.fastUiAutomator()
        val checks = SoftChecks()
        val processStart = android.os.Process.getStartElapsedRealtime()
        val launchAt = SystemClock.elapsedRealtime()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val visibleAfter = DeviceCheck.waitForObject(By.text("Add a playlist"), 60_000)
            if (visibleAfter == null) {
                shot("01-fresh-home")
                note("Home texts: collection=${DeviceCheck.has(By.text("Your collection"))} loading=${DeviceCheck.has(By.textContains("Loading"))}")
                assumeTrue("Fresh install precondition: a provider is already saved (run pm clear first)", store.state.value.providers.isEmpty())
                throw AssertionError("\"Add a playlist\" did not appear within 60 s of launch on a fresh install")
            }
            val now = SystemClock.elapsedRealtime()
            metric("fresh_launch_to_add_playlist_ms", now - launchAt)
            metric("fresh_process_start_to_add_playlist_ms", now - processStart)
            checks.check(DeviceCheck.has(By.text("Your collection")), "\"Your collection\" heading missing on fresh Home")
            SystemClock.sleep(1200)
            note("initial focus on fresh Home: ${focused()}")
            shot("01-fresh-home")

            val target = focusedText("Add a playlist")
            note("\"Add a playlist\" focused on arrival: ${DeviceCheck.has(target)}")
            // Start from the top navigation, as the user did: Up until a nav tab has focus.
            val navTabs = listOf("Home", "Live TV", "Movies", "Shows", "My stuff")
            for (i in 0 until 3) { if (navTabs.any { DeviceCheck.has(focusedText(it)) }) break; DeviceCheck.press(KeyEvent.KEYCODE_DPAD_UP) }
            checks.check(navTabs.any { DeviceCheck.has(focusedText(it)) }, "Could not put focus on the top navigation with D-pad UP (focus: ${focused()})")
            note("focus before DOWN: ${focused()}")
            // A remote user presses Down from the top navigation to reach the button.
            val presses = DeviceCheck.dpadUntil(target, KeyEvent.KEYCODE_DPAD_DOWN, 3)
            val reached = presses != null
            if (reached) route("fresh: reach Add a playlist", "dpad", "$presses x DOWN")
            note("focus after DOWN presses: ${focused()}")
            shot("02-fresh-down")
            checks.check(reached, "D-pad DOWN x3 from the top navigation never focused \"Add a playlist\" (focus stayed on: ${focused()})")

            if (reached) {
                device.pressDPadCenter()
                route("fresh: open playlist dialog", "dpad", "CENTER")
            } else {
                // Keep going so the dialog itself is still verified and photographed.
                device.findObject(By.text("Add a playlist"))?.click()
                route("fresh: open playlist dialog", "touch-fallback", "D-pad could not reach the button")
            }
            val dialogAfter = DeviceCheck.waitForObject(By.text("Your playlists"), 10_000)
            checks.check(dialogAfter != null, "Playlist dialog (\"Your playlists\") did not open from \"Add a playlist\"")
            checks.check(DeviceCheck.has(By.text("Add playlist")), "Playlist dialog has no \"Add playlist\" button")
            SystemClock.sleep(600)
            note("focus in playlist dialog: ${focused()}")
            shot("03-playlist-dialog")

            // Inside the dialog the remote must be able to reach "Add playlist" and open the editor.
            val addPresses = DeviceCheck.dpadUntil(focusedText("Add playlist"), KeyEvent.KEYCODE_DPAD_DOWN, 6)
            if (addPresses != null) {
                device.pressDPadCenter(); route("fresh: open Connect your playlist", "dpad", "$addPresses x DOWN + CENTER")
            } else {
                checks.check(false, "D-pad could not focus \"Add playlist\" in the playlist dialog (focus: ${focused()})")
                device.findObject(By.text("Add playlist"))?.click(); route("fresh: open Connect your playlist", "touch-fallback")
            }
            checks.check(DeviceCheck.waitForObject(By.text("Connect your playlist"), 10_000) != null, "\"Connect your playlist\" editor did not open")
            SystemClock.sleep(600)
            note("focus in editor: ${focused()}")
            shot("03b-connect-form")
            scenario.onActivity { note("activity page=${it.page}") }
        }
        checks.assertAll()
    }
}
