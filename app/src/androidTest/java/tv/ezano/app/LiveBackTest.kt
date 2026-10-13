package tv.ezano.app

import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import tv.ezano.app.DeviceCheck.barFocused
import tv.ezano.app.DeviceCheck.device
import tv.ezano.app.DeviceCheck.focused
import tv.ezano.app.DeviceCheck.focusedAmong
import tv.ezano.app.DeviceCheck.focusedOn
import tv.ezano.app.DeviceCheck.focusedTexts
import tv.ezano.app.DeviceCheck.has
import tv.ezano.app.DeviceCheck.metric
import tv.ezano.app.DeviceCheck.note
import tv.ezano.app.DeviceCheck.press
import tv.ezano.app.DeviceCheck.route
import tv.ezano.app.DeviceCheck.shot
import tv.ezano.app.DeviceCheck.step
import tv.ezano.app.DeviceCheck.store
import tv.ezano.app.DeviceCheck.topMost
import tv.ezano.app.DeviceCheck.visibleAmong
import tv.ezano.app.DeviceCheck.waitFor

/**
 * Live TV guide, remote only: Select a channel (full-screen playback), Back must land on the same guide spot:
 * same group/search filter, list scrolled to the channel, focus on that channel's row (not the top of the list,
 * not the top bar). Also after zapping with number keys in the player: Back lands on the channel zapped to.
 * Uses the 8769 device fixture (14 live channels, so the target row sits below the fold).
 */
class LiveBackTest {
    @get:Rule val failureArtifacts = FailureArtifacts()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var names: List<String>
    private lateinit var groupOf: Map<String, String>
    private lateinit var programTitles: Set<String>

    private fun <T> act(f: (MainActivity) -> T): T { var r: Any? = null; scenario.onActivity { r = f(it) }; @Suppress("UNCHECKED_CAST") return r as T }
    private fun page() = act { it.page }
    private fun playerReady() = act { it.fullPlayer && it.player?.playbackState == Player.STATE_READY }
    private fun playingName() = act { it.playing?.name }
    private fun browsing() = act { !it.fullPlayer && it.player == null }
    private fun focusedChannel() = focusedAmong(names)
    private fun onProgramCell() = focusedTexts().any { it in programTitles || it == "Guide unavailable" }
    private fun searchFieldFocused() = runCatching { device.findObject(By.clazz("android.widget.EditText").focused(true)) != null }.getOrDefault(false)

    /** D-pad into the guide's channel column (Left out of a program cell when the move lands in the grid). */
    private fun intoChannelRows(label: String, checks: SoftChecks, key: Int): String? {
        for (i in 0 until 12) {
            focusedChannel()?.let { route(label, "dpad", "$i presses, focus: $it"); return it }
            press(if (onProgramCell()) KeyEvent.KEYCODE_DPAD_LEFT else key, 500)
        }
        checks.check(false, "$label: the D-pad never reached a channel row (focus: ${focused()})")
        return null
    }

    /** Up/Down in the channel column until [target] has focus. */
    private fun moveToChannel(target: String, order: List<String>, checks: SoftChecks): Boolean {
        for (i in 0 until order.size + 4) {
            val now = focusedChannel()
            if (now == null) { checks.check(false, "Focus left the channel column while moving to \"$target\" (focus: ${focused()})"); return false }
            if (now == target) return true
            press(if (order.indexOf(now) in 0 until order.indexOf(target)) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP, 450)
        }
        checks.check(false, "D-pad could not reach the channel \"$target\" (focus: ${focused()})")
        return false
    }

    /**
     * Select on the focused row, wait for READY, optionally zap with a number key, Back, and check the guide
     * returned to the channel that was playing last. Returns that channel's name.
     */
    private fun playAndBack(channel: String, tag: String, checks: SoftChecks, zapTo: Int? = null): String {
        val at = SystemClock.elapsedRealtime()
        device.pressDPadCenter()
        val ready = waitFor(30_000, 200) { playerReady() }
        checks.check(ready != null, "$tag: Select on \"$channel\" did not reach playback READY within 30 s")
        if (ready != null) metric("${tag}_select_to_ready_ms", SystemClock.elapsedRealtime() - at)
        checks.check(playingName() == channel, "$tag: Select on the \"$channel\" row played \"${playingName()}\"")
        route("$tag: play $channel", "dpad", "CENTER")
        var expected = channel
        if (zapTo != null) {
            SystemClock.sleep(1500)
            expected = names[zapTo - 1]
            device.pressKeyCode(KeyEvent.KEYCODE_0 + zapTo)
            step("$tag: pressed number key $zapTo in the player (expects \"$expected\")")
            val zapped = waitFor(20_000, 200) { playingName() == expected && playerReady() }
            checks.check(zapped != null, "$tag: number key $zapTo did not switch playback to \"$expected\" (playing: ${playingName()})")
            route("$tag: zap to channel $zapTo", "dpad", "KEYCODE_$zapTo")
            if (zapped == null) expected = playingName() ?: channel
        }
        SystemClock.sleep(1500)
        device.pressBack()
        val back = waitFor(8_000) { browsing() }
        checks.check(back != null, "$tag: Back did not leave the player")
        route("$tag: Back from player", "dpad", "BACK")
        val refocused = waitFor(5_000) { focusedChannel() == expected }
        metric("${tag}_back_to_row_focus_ms", refocused ?: "not-focused-5s")
        SystemClock.sleep(800)
        val now = focused()
        note("$tag: after Back page=${page()} focus=$now visible channels=${visibleAmong(names)}")
        checks.check(page() == "Live TV", "$tag: Back from the player left Live TV (page=${page()})")
        checks.check(focusedChannel() == expected, "$tag: after Back focus is not on the \"$expected\" row (focus: $now${barFocused()?.let { ", top bar tab $it" } ?: ""})")
        val row = runCatching { device.findObject(By.focused(true))?.visibleBounds }.getOrNull()
        checks.check(row != null && row.height() > 0 && row.bottom <= device.displayHeight, "$tag: the focused \"$expected\" row is not on screen (bounds $row)")
        return expected
    }

    @Test fun backReturnsToTheSameChannelRow() {
        DeviceCheck.fastUiAutomator()
        DeviceCheck.ensureFixture()
        val live = store.visible(Kind.LIVE)
        names = live.map { it.name }
        groupOf = live.associate { it.name to it.group }
        programTitles = store.state.value.programs.map { it.title }.toSet()
        note("guide order: ${names.joinToString()}; groups: ${live.map { it.group }.distinct()}")
        val checks = SoftChecks()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.use { DeviceCheck.capturing("LiveBackTest") {
            check(waitFor(60_000) { DeviceCheck.anyOf(*DeviceCheck.HOME_MARKERS) != null } != null) { "Home did not appear" }
            SystemClock.sleep(1500)
            DeviceCheck.openTopTab("Live TV", checks) { page() == "Live TV" && has(By.text("CHANNEL GROUPS")) }
            waitFor(60_000) { !store.guideBusy.value }
            check(waitFor(20_000) { visibleAmong(names.take(3)).isNotEmpty() } != null) { "Live TV guide shows no channel rows" }
            SystemClock.sleep(1000)

            // ---- 1. Plain list: a channel several rows down ----
            val first = intoChannelRows("live: top bar -> channel rows", checks, KeyEvent.KEYCODE_DPAD_DOWN)
                ?: throw AssertionError("Could not reach the guide's channel rows with the D-pad (focus: ${focused()})")
            val target = names[(names.indexOf(first) + 7).coerceAtMost(names.size - 1)]
            moveToChannel(target, names, checks)
            route("live: move to $target", "dpad", "DOWN from $first")
            SystemClock.sleep(600); shot("16a-live-before-play")
            val plain = playAndBack(target, "live_back", checks)
            shot("16-live-back")

            // ---- 2. Number-key zapping in the player, then Back ----
            val zapTo = if (names.indexOf(plain) == 1) 3 else 2
            if (focusedChannel() == plain) {
                playAndBack(plain, "live_back_zap", checks, zapTo = zapTo)
                shot("16b-live-back-zapped")
            } else note("zap check skipped: focus is not on \"$plain\" after the first Back")

            // ---- 3. A channel group chosen in the left column ----
            val group = live.groupBy { it.group }.maxByOrNull { it.value.size }!!.key
            val members = names.filter { groupOf[it] == group }
            val groupLabels = live.map { it.group }.distinct() + listOf("All channels", "Favorites")
            if (focusedChannel() == null) intoChannelRows("live: back into channel rows", checks, KeyEvent.KEYCODE_DPAD_DOWN)
            for (i in 0 until 4) { if (focusedAmong(groupLabels) != null) break; press(KeyEvent.KEYCODE_DPAD_LEFT, 500) }
            val groupFocusY = runCatching { device.findObject(By.focused(true)).visibleBounds.centerY() }.getOrNull()
            val groupY = topMost(group)?.visibleBounds?.centerY()
            val key = if (groupFocusY != null && groupY != null && groupY < groupFocusY) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
            if (focusedAmong(groupLabels) != null && DeviceCheck.dpadToText(group, key, 8) != null) {
                device.pressDPadCenter(); route("live: choose group $group", "dpad", "LEFT into groups, ${DeviceCheck.keyName(key)} + CENTER")
            } else {
                checks.check(false, "D-pad could not reach the channel group \"$group\" in the left column (focus: ${focused()})")
                topMost(group)?.click(); route("live: choose group $group", "touch-fallback")
            }
            checks.check(waitFor(6_000) { visibleAmong(names).let { v -> v.isNotEmpty() && v.all { it in members } } } != null,
                "Choosing the group \"$group\" did not filter the guide (visible: ${visibleAmong(names)})")
            SystemClock.sleep(600)
            intoChannelRows("live: group -> channel rows", checks, KeyEvent.KEYCODE_DPAD_RIGHT)
            val groupTarget = members[6.coerceAtMost(members.size - 1)]
            moveToChannel(groupTarget, members, checks)
            playAndBack(groupTarget, "live_back_group", checks)
            visibleAmong(names).let { v -> checks.check(v.all { it in members }, "live_back_group: the \"$group\" filter was lost after Back (visible: $v)") }
            shot("17a-live-back-group")

            // ---- 4. Plus a search typed in "Search channels" ----
            val query = "UK"
            val expected = members.filter { it.contains(query, ignoreCase = true) }
            var moves = 0
            while (!searchFieldFocused() && moves < 14) {
                moves++
                when {
                    barFocused() != null -> press(KeyEvent.KEYCODE_DPAD_DOWN, 500)
                    focusedOn("Channels") || focusedOn("Find event") -> press(KeyEvent.KEYCODE_DPAD_LEFT, 500)
                    else -> press(KeyEvent.KEYCODE_DPAD_UP, 500)
                }
            }
            if (searchFieldFocused()) { device.pressDPadCenter(); route("live: Search channels", "dpad", "$moves moves + CENTER") }
            else {
                checks.check(false, "D-pad could not reach the \"Search channels\" field (focus: ${focused()})")
                device.findObject(By.clazz("android.widget.EditText"))?.click(); route("live: Search channels", "touch-fallback")
            }
            SystemClock.sleep(700)
            val field = runCatching { device.findObject(By.clazz("android.widget.EditText").focused(true)) }.getOrNull() ?: device.findObject(By.clazz("android.widget.EditText"))
                ?: throw AssertionError("No \"Search channels\" field on the Live TV page")
            field.text = query
            SystemClock.sleep(400)
            if (DeviceCheck.imeShown()) { device.pressBack(); SystemClock.sleep(600) }
            checks.check(page() == "Live TV", "Back that closed the keyboard also left Live TV (page=${page()})")
            route("live: type \"$query\"", "dpad", "setText, Back closes the keyboard")
            checks.check(waitFor(6_000) { visibleAmong(names).let { v -> v.isNotEmpty() && v.all { it in expected } } } != null,
                "Search \"$query\" in group \"$group\" did not filter the guide to $expected (visible: ${visibleAmong(names)})")
            intoChannelRows("live: search -> channel rows", checks, KeyEvent.KEYCODE_DPAD_DOWN)
            val searchTarget = expected.getOrElse(1) { expected.first() }
            moveToChannel(searchTarget, expected, checks)
            playAndBack(searchTarget, "live_back_filtered", checks)
            val fieldText = runCatching { device.findObjects(By.clazz("android.widget.EditText")).mapNotNull { it.text } }.getOrDefault(emptyList())
            checks.check(fieldText.any { it == query }, "live_back_filtered: the search \"$query\" was cleared after Back (fields show: $fieldText)")
            visibleAmong(names).let { v -> checks.check(v.isNotEmpty() && v.all { it in expected }, "live_back_filtered: group/search filter lost after Back (visible: $v, expected only: $expected)") }
            shot("17-live-back-filtered")
        } }
        checks.assertAll()
    }
}
