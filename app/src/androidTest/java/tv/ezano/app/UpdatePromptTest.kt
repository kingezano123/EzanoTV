package tv.ezano.app

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import tv.ezano.app.DeviceCheck.context
import tv.ezano.app.DeviceCheck.device
import tv.ezano.app.DeviceCheck.focused
import tv.ezano.app.DeviceCheck.focusedOn
import tv.ezano.app.DeviceCheck.has
import tv.ezano.app.DeviceCheck.metric
import tv.ezano.app.DeviceCheck.note
import tv.ezano.app.DeviceCheck.press
import tv.ezano.app.DeviceCheck.route
import tv.ezano.app.DeviceCheck.shot
import tv.ezano.app.DeviceCheck.step
import tv.ezano.app.DeviceCheck.store
import tv.ezano.app.DeviceCheck.waitFor

/**
 * In-app updater. Debug builds read an override feed from SharedPreferences("updates")/"feed"; the device
 * fixture serves /update.json (versionCode 999, "9.9.9"). tools/device-check.sh runs each method in its own
 * `am instrument` (new app process), because the check runs once, ~6 s after the library is open.
 */
class UpdatePromptTest {
    @get:Rule val failureArtifacts = FailureArtifacts()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val prefs get() = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private fun <T> act(f: (MainActivity) -> T): T { var r: Any? = null; scenario.onActivity { r = f(it) }; @Suppress("UNCHECKED_CAST") return r as T }
    private fun page() = act { it.page }
    private fun homeVisible() = DeviceCheck.anyOf(*DeviceCheck.HOME_MARKERS) != null
    private fun prompt() = has(By.text("Update available"))
    @Suppress("DEPRECATION")
    private fun installed() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${it.versionCode})" } }.getOrDefault("?")

    /** Clears any skipped/snoozed state the app kept, then points the debug build at the fixture feed (before launch). */
    private fun useFixtureFeed() {
        val ok = prefs.edit().clear().putString("feed", FEED).commit()
        route("updater: set feed override", "direct", "SharedPreferences(\"updates\").feed=$FEED, committed=$ok")
    }
    private fun removeOverride() { prefs.edit().remove("feed").commit(); note("updater: feed override removed (keys left: ${prefs.all.keys})") }
    private fun launchToHome(): Long {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        check(waitFor(60_000, 50) { homeVisible() } != null) { "Home did not appear" }
        return SystemClock.elapsedRealtime()
    }

    /** Returns from the dialog with "Later", by remote. */
    private fun pressLater(tag: String, checks: SoftChecks): Boolean {
        var moves = 0
        for (key in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP)) {
            if (focusedOn("Later")) break
            val p = DeviceCheck.dpadToText("Later", key, 3); if (p != null) { moves += p; break }
            // Undo a wrong direction so the next try starts from "Update now" again.
            for (i in 0 until 3) { if (focusedOn("Update now")) break; press(if (key == KeyEvent.KEYCODE_DPAD_RIGHT) KeyEvent.KEYCODE_DPAD_LEFT else if (key == KeyEvent.KEYCODE_DPAD_DOWN) KeyEvent.KEYCODE_DPAD_UP else if (key == KeyEvent.KEYCODE_DPAD_LEFT) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_DOWN) }
        }
        if (focusedOn("Later")) { device.pressDPadCenter(); route("$tag: Later", "dpad", "$moves moves + CENTER") }
        else { checks.check(false, "$tag: D-pad could not reach \"Later\" in the update dialog (focus: ${focused()})"); DeviceCheck.clickText("Later"); route("$tag: Later", "touch-fallback") }
        val closed = waitFor(5_000) { !prompt() } != null
        checks.check(closed, "$tag: \"Later\" did not close the update dialog")
        return closed
    }

    @Test fun promptFromOverrideFeed() {
        DeviceCheck.fastUiAutomator()
        DeviceCheck.ensureFixture()
        val checks = SoftChecks()
        useFixtureFeed()
        try {
            val homeAt = launchToHome()
            scenario.use { DeviceCheck.capturing("UpdatePromptTest") {
                val shown = waitFor(25_000) { prompt() }
                checks.check(shown != null, "\"Update available\" did not appear within 25 s of Home with the fixture feed (versionCode 999)")
                if (shown != null) metric("update_prompt_after_home_ms", SystemClock.elapsedRealtime() - homeAt)
                if (shown == null) { shot("20-update-prompt"); return@capturing }
                SystemClock.sleep(800)
                for (b in listOf("Update now", "Later", "Skip this version")) checks.check(has(By.text(b)), "Update dialog has no \"$b\" button")
                note("update dialog: version 9.9.9 shown=${has(By.textContains("9.9.9"))}, notes shown=${has(By.textContains("Test update"))}, focus=${focused()}")
                checks.check(has(By.textContains("9.9.9")), "Update dialog does not show the new version name 9.9.9")
                checks.check(waitFor(2_000) { focusedOn("Update now") } != null, "\"Update now\" is not focused when the update dialog opens (focus: ${focused()})")
                shot("20-update-prompt")
                pressLater("updater", checks)
                SystemClock.sleep(800)
                // Home must still work with the remote.
                val before = focused()
                val pressAt = SystemClock.elapsedRealtime()
                device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
                val moved = waitFor(3_000, 50) { focused() != before }
                metric("update_after_later_dpad_response_ms", moved?.let { SystemClock.elapsedRealtime() - pressAt } ?: "no-focus-change-3s")
                checks.check(moved != null && page() == "Home", "Home did not respond to the remote after \"Later\" (page=${page()}, focus stayed: $before)")
                checks.check(!prompt(), "The update dialog came back right after \"Later\"")
                SystemClock.sleep(600)
                shot("20b-update-later")

                // Settings > App updates > Check now must offer the same update.
                DeviceCheck.openTopTab("Settings", checks) { page() == "Settings" }
                val row = DeviceCheck.dpadToText("Check now", KeyEvent.KEYCODE_DPAD_DOWN, 30)
                checks.check(has(By.text("App updates")), "Settings has no \"App updates\" row")
                if (row != null) {
                    device.pressDPadCenter(); route("updater: Settings Check now", "dpad", "$row x DOWN + CENTER")
                    checks.check(waitFor(12_000) { prompt() } != null, "Settings \"Check now\" did not show \"Update available\" for the fixture feed")
                    SystemClock.sleep(800)
                    shot("20c-update-check-now")
                    if (prompt()) pressLater("updater: Check now", checks)
                } else {
                    checks.check(false, "D-pad could not reach \"Check now\" in Settings (focus: ${focused()})")
                    shot("20c-update-check-now")
                }
            } }
        } finally { removeOverride() }
        checks.assertAll()
    }

    @Test fun noPromptDuringPlayback() {
        DeviceCheck.fastUiAutomator()
        DeviceCheck.ensureFixture()
        val checks = SoftChecks()
        useFixtureFeed()
        try {
            launchToHome()
            scenario.use { DeviceCheck.capturing("UpdatePromptPlayback") {
                // Start playback right away (before the ~4 s check fires); the check itself is the subject here.
                val channel = store.visible(Kind.LIVE).first()
                scenario.onActivity { it.play(channel) }
                route("updater: start playback before the update check", "direct", "MainActivity.play(${channel.name})")
                checks.check(waitFor(30_000, 200) { act { it.fullPlayer && it.player?.playbackState == Player.STATE_READY } } != null, "Live playback did not reach READY")
                var seen = false
                val until = SystemClock.elapsedRealtime() + 22_000
                while (SystemClock.elapsedRealtime() < until) { if (prompt()) { seen = true; break }; SystemClock.sleep(250) }
                checks.check(!seen, "\"Update available\" appeared over full-screen playback")
                shot("20d-update-not-during-playback")
                checks.check(act { it.fullPlayer && it.player != null }, "Playback stopped during the update check")
                device.pressBack()
                waitFor(8_000) { act { !it.fullPlayer && it.player == null } }
                route("updater: leave playback", "dpad", "BACK")
                val after = waitFor(20_000) { prompt() }
                note("update prompt after leaving playback: ${after?.let { "shown after $it ms" } ?: "not shown within 20 s"}")
                if (after != null) { SystemClock.sleep(600); shot("20e-update-after-playback"); pressLater("updater: after playback", checks) }
            } }
        } finally { removeOverride() }
        checks.assertAll()
    }

    @Test fun noOverrideStaysQuiet() {
        DeviceCheck.fastUiAutomator()
        DeviceCheck.ensureFixture()
        // No override, and no throttle/snooze/skip left by the tests before: this launch checks the real feed.
        prefs.edit().clear().commit()
        route("updater: clear updater state, no override", "direct", "SharedPreferences(\"updates\").clear()")
        val checks = SoftChecks()
        val homeAt = launchToHome()
        scenario.use { DeviceCheck.capturing("UpdateNoOverride") {
            SystemClock.sleep(1500)
            // Responsiveness while the real feed is being fetched (library open + ~6 s after Home).
            val samples = ArrayList<Long>()
            while (SystemClock.elapsedRealtime() - homeAt < 14_000) {
                val before = focused()
                val at = SystemClock.elapsedRealtime()
                device.pressKeyCode(if (samples.size % 2 == 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                val moved = waitFor(3_000, 30) { focused() != before }
                samples += moved?.let { SystemClock.elapsedRealtime() - at } ?: 3_000
                step("no override: D-pad at +${at - homeAt} ms moved focus in ${samples.last()} ms")
                SystemClock.sleep(600)
            }
            metric("update_none_dpad_response_max_ms", samples.maxOrNull() ?: -1)
            metric("update_none_dpad_response_avg_ms", if (samples.isEmpty()) -1 else samples.average().toLong())
            checks.check(samples.none { it >= 1_500 }, "Home was slow to answer the remote while the update feed was checked: $samples ms")
            val shown = waitFor(6_000) { prompt() } != null
            if (shown) {
                note("no override: the real update feed offered an update (installed ${installed()}); texts: ${listOf("Update now", "Later", "Skip this version").filter { has(By.text(it)) }}")
                shot("21-update-none"); pressLater("updater: real feed", checks)
            }
            val message = act { it.message }
            note("no override: message='$message' error='${store.error.value}' page=${page()}")
            checks.check(store.error.value.isBlank(), "An error is shown after the update check without override: ${store.error.value}")
            checks.check(!Regex("(?i)update|could not|failed|error").containsMatchIn(message), "A message appeared after the update check without override: $message")
            checks.check(page() == "Home" && homeVisible(), "Home is not showing after the update check (page=${page()})")
            if (!shown) { SystemClock.sleep(500); shot("21-update-none") }
        } }
        checks.assertAll()
    }

    companion object { const val FEED = "${DeviceCheck.FIXTURE_URL}/update.json" }
}
