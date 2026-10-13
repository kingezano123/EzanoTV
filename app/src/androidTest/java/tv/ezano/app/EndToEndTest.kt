package tv.ezano.app

import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import org.junit.Rule
import org.junit.Test
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
import tv.ezano.app.DeviceCheck.waitForObject

/**
 * End to end from a fresh install, operated like a remote wherever possible: add the fixture
 * playlist through the real editor, browse Home/Movies/Shows/Live TV, open details, play a movie,
 * change caption background and return to browsing. Every user step records its route
 * (dpad / touch-fallback / direct) and a numbered screenshot. Uses tools/device-fixture.cjs.
 */
class EndToEndTest {
    @get:Rule val failureArtifacts = FailureArtifacts()
    private val navLabels = setOf("Home", "Live TV", "Movies", "Shows", "My stuff", "Search", "Playlists", "Recordings", "Settings")
    private val heroButtons = setOf("Play", "Resume", "More info")
    private val homeMarkers = arrayOf("More info", "Popular kids movies", "Family picks", "Kids & family shows")

    private fun page(scenario: ActivityScenario<MainActivity>): String {var p="";scenario.onActivity {p=it.page};return p}

    /** Reach a top-navigation tab with the remote: Up into the bar, then Left/Right to the tab, then Select. */
    private fun openTab(scenario: ActivityScenario<MainActivity>, label: String, pageName: String, checks: SoftChecks): Boolean {
        if (!focusedOn(label)) {
            // Up until any nav tab has focus.
            for (i in 0 until 8) { if (navLabels.any { focusedOn(it) }) break; press(KeyEvent.KEYCODE_DPAD_UP) }
        }
        val order = listOf("Home", "Live TV", "Movies", "Shows", "My stuff")
        val current = order.firstOrNull { focusedOn(it) }
        val key = if (current != null && order.indexOf(current) > order.indexOf(label)) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val presses = DeviceCheck.dpadToText(label, key, 6)
        if (presses != null) {
            device.pressDPadCenter()
            if (waitFor(5_000) { page(scenario) == pageName } != null) { route("open $label", "dpad", "focus was ${current ?: "elsewhere"}, $presses x ${DeviceCheck.keyName(key)} + CENTER"); return true }
        }
        checks.check(false, "D-pad could not open the \"$label\" tab (focus: ${focused()})")
        scenario.onActivity { it.page = pageName }
        route("open $label", "direct", "activity.page")
        return false
    }

    /** Names hero texts drawn inside the floating top bar's band (Home scrolled partly under the bar). */
    private fun heroUnderTopBar(): List<String> {
        val barBottom = device.displayHeight * 84 / 540
        return listOf("Play", "Resume", "More info", "FEATURED").filter { label ->
            val sel = if (label == "FEATURED") By.textStartsWith(label) else By.text(label)
            runCatching { device.findObjects(sel).any { val b = it.visibleBounds; b.height() > 0 && b.top < barBottom } }.getOrDefault(false)
        }
    }

    /** From wherever focus is on Home, press Down until a poster in a content row is focused. */
    private fun downIntoRows(checks: SoftChecks, label: String): Boolean {
        for (i in 0..5) {
            if (DeviceCheck.anythingFocused() && (navLabels + heroButtons).none { focusedOn(it) }) { route(label, "dpad", "$i x DOWN, focus: ${focused()}"); return true }
            if (i < 5) press(KeyEvent.KEYCODE_DPAD_DOWN, 700)
        }
        checks.check(false, "$label: D-pad DOWN never moved focus into a Home row (focus: ${focused()})")
        return false
    }

    @Test fun addPlaylistBrowsePlayAndReturn() {
        DeviceCheck.fastUiAutomator()
        val checks = SoftChecks()
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> DeviceCheck.capturing("EndToEndTest") {
            // ---- Add the fixture playlist through the real UI ----
            check(waitForObject(By.text("Add a playlist"), 60_000) != null) { "Fresh Home without \"Add a playlist\" (is app data cleared?)" }
            SystemClock.sleep(800)
            if (DeviceCheck.dpadToText("Add a playlist", KeyEvent.KEYCODE_DPAD_DOWN, 3) != null) {
                device.pressDPadCenter(); route("e2e: Add a playlist", "dpad")
            } else {
                checks.check(false, "D-pad could not reach \"Add a playlist\" (focus: ${focused()})")
                DeviceCheck.clickText("Add a playlist"); route("e2e: Add a playlist", "touch-fallback")
            }
            check(waitForObject(By.text("Your playlists"), 10_000) != null) { "Playlist manager did not open" }
            if (DeviceCheck.dpadToText("Add playlist", KeyEvent.KEYCODE_DPAD_DOWN, 6) != null) {
                device.pressDPadCenter(); route("e2e: Add playlist", "dpad")
            } else {
                checks.check(false, "D-pad could not reach \"Add playlist\" (focus: ${focused()})")
                DeviceCheck.clickText("Add playlist"); route("e2e: Add playlist", "touch-fallback")
            }
            check(waitForObject(By.text("Connect your playlist"), 10_000) != null) { "Playlist editor did not open" }
            SystemClock.sleep(500)
            note("editor shows ${device.findObjects(By.clazz("android.widget.EditText")).size} text fields; focus=${focused()}")
            var touched = false
            var keyboardTraps = 0
            /** Remote flow per field: Down until the field has focus, Select to start typing, enter text, Back to close the keyboard. */
            fun fill(label: String, value: String) {
                var moves = 0
                while (!focusedOn(label, prefix = true) && moves < 4) {
                    if (DeviceCheck.imeShown()) { keyboardTraps++; device.pressBack(); SystemClock.sleep(500); step("keyboard open while moving -> Back; focus: ${focused()}") }
                    else { press(KeyEvent.KEYCODE_DPAD_DOWN, 600); moves++ }
                }
                val byRemote = focusedOn(label, prefix = true)
                if (byRemote) device.pressDPadCenter()
                else {
                    checks.check(false, "D-pad DOWN could not move focus to the \"$label\" field (focus: ${focused()})")
                    (device.findObjects(By.clazz("android.widget.EditText")).firstOrNull { it.hasObject(By.textStartsWith(label)) } ?: throw AssertionError("No \"$label\" field on screen")).click()
                    touched = true
                }
                SystemClock.sleep(600)
                val keyboardOpened = DeviceCheck.imeShown()
                val field = runCatching { device.findObject(By.clazz("android.widget.EditText").focused(true)) }.getOrNull()
                    ?: device.findObjects(By.clazz("android.widget.EditText")).firstOrNull { it.hasObject(By.textStartsWith(label)) }
                    ?: throw AssertionError("No editable \"$label\" field")
                field.text = value
                SystemClock.sleep(300)
                val typed = runCatching { field.text }.getOrNull()
                checks.check(typed != null && typed.isNotEmpty() && typed != label, "Text did not stick in the \"$label\" field after Select (field shows: $typed)")
                val editorOpen = { has(By.clazz("android.widget.EditText")) }
                if (DeviceCheck.imeShown()) { device.pressBack(); SystemClock.sleep(500) }
                checks.check(!DeviceCheck.imeShown(), "Back did not close the keyboard on \"$label\"")
                checks.check(editorOpen(), "Back on \"$label\" closed the playlist editor")
                // Leaving the field: one Down should move on. If focus stays put while no keyboard shows, the field is
                // still in typing mode (arrow keys move the cursor) - the user would need another Back.
                val before = focused()
                press(KeyEvent.KEYCODE_DPAD_DOWN, 600)
                if (focused() == before && !DeviceCheck.imeShown() && editorOpen()) {
                    checks.check(false, "After typing in \"$label\" and closing the keyboard with Back, Down did not leave the field (still in typing mode); a second Back was needed")
                    device.pressBack(); SystemClock.sleep(500)
                    checks.check(editorOpen(), "The second Back on \"$label\" closed the playlist editor")
                    press(KeyEvent.KEYCODE_DPAD_DOWN, 600)
                }
                route("e2e: field $label", if (byRemote) "dpad" else "touch-fallback", "${if (byRemote) "$moves x DOWN + CENTER" else "tap"}; keyboard opened on Select: $keyboardOpened; text set with UiObject2.setText; Back")
            }
            fill("Playlist name", "Device fixture")
            fill("Server URL", DeviceCheck.FIXTURE_URL)
            fill("Username", "test")
            fill("Password", "test")
            runCatching { UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Connect & import") }
            shot("03b-connect-form")
            val connectAt: Long
            for (i in 0 until 6) {
                if (focusedOn("Connect & import")) break
                if (DeviceCheck.imeShown()) { keyboardTraps++; device.pressBack(); SystemClock.sleep(500) } else press(KeyEvent.KEYCODE_DPAD_DOWN, 600)
            }
            if (keyboardTraps > 0) checks.check(false, "Moving Down through the playlist form opened the on-screen keyboard $keyboardTraps time(s) without Select")
            if (focusedOn("Connect & import")) {
                connectAt = SystemClock.elapsedRealtime(); device.pressDPadCenter(); route("e2e: Connect & import", "dpad", "DOWN + CENTER")
            } else {
                checks.check(false, "D-pad could not reach \"Connect & import\" (focus: ${focused()})")
                DeviceCheck.hideIme(); runCatching { UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Connect & import") }
                connectAt = SystemClock.elapsedRealtime(); DeviceCheck.clickText("Connect & import"); route("e2e: Connect & import", "touch-fallback")
                touched = true
            }
            val touchedConnect = touched
            val validation = { has(By.textStartsWith("Give your playlist")) || has(By.textStartsWith("Enter your")) }
            waitFor(180_000, 200) { (!store.busy.value && store.state.value.channels.size >= 25) || (!store.busy.value && validation()) }
            val imported = if (!store.busy.value && store.state.value.channels.size >= 25) 0L else null
            if (imported == null) shot("03c-import-not-finished")
            check(imported != null) { "Fixture import did not finish: error='${store.error.value}' channels=${store.state.value.channels.size} editor error text=${device.findObjects(By.textStartsWith("Give your")).map { it.text } + device.findObjects(By.textStartsWith("Enter")).map { it.text }}" }
            metric("e2e_import_ms", SystemClock.elapsedRealtime() - connectAt)
            // Since 5f05f00 a successful import opens Home with the hero's Play focused (earlier builds opened Live TV).
            checks.check(waitFor(15_000) { !has(By.text("Connect your playlist")) && !has(By.text("Your playlists")) } != null, "Playlist dialogs did not close after import")
            checks.check(waitFor(15_000) { page(scenario) == "Home" } != null, "After import the app did not open Home (page=${page(scenario)})")
            val playFocused = waitFor(10_000) { focusedOn("Play") || focusedOn("Resume") }
            if (touchedConnect) note("post-import focus not judged: the test had to tap in the form, which puts the app in touch mode (focus=${focused()})")
            else checks.check(playFocused != null, "After import the hero Play button is not focused (focus: ${focused()})")
            SystemClock.sleep(1500)
            note("focus after import: ${focused()}")
            shot("03c-after-import")

            // ---- Home with data ----
            if (page(scenario) != "Home") {
                device.pressBack()
                val homeOk = waitFor(5_000) { page(scenario) == "Home" } != null
                if (homeOk) route("e2e: back to Home", "dpad", "BACK") else { scenario.onActivity { it.page = "Home" }; route("e2e: back to Home", "direct") }
            } else route("e2e: Home after import", "dpad", "app opened Home itself")
            val heroAfter = waitFor(30_000) { DeviceCheck.anyOf(*homeMarkers) != null }
            checks.check(heroAfter != null, "Home shows no hero/rows with the fixture library")
            checks.check(has(By.text("Popular kids movies")) || has(By.text("Family picks")), "Home has no \"Popular kids movies\" row")
            SystemClock.sleep(2500)
            note("Home focus: ${focused()}")
            shot("04-home")
            downIntoRows(checks, "e2e: Home DOWN into rows")
            SystemClock.sleep(800)
            heroUnderTopBar().let { if (it.isNotEmpty()) note("hero ${it.joinToString()} lie under the top bar band with focus in the first row (check 05-home-rows.png: hidden by a solid bar, or overlapping?)") }
            shot("05-home-rows")

            // ---- Movies, Shows, Live TV ----
            openTab(scenario, "Movies", "Movies", checks)
            checks.check(waitFor(20_000) { has(By.text("Popular movies")) || has(By.textContains("movies in your collection")) } != null, "Movies page did not load")
            SystemClock.sleep(2000); note("Movies focus: ${focused()}"); shot("06-movies")
            openTab(scenario, "Shows", "Series", checks)
            checks.check(waitFor(20_000) { has(By.text("Popular shows")) || has(By.textContains("series in your collection")) } != null, "Shows page did not load")
            SystemClock.sleep(2000); note("Shows focus: ${focused()}"); shot("07-shows")
            openTab(scenario, "Live TV", "Live TV", checks)
            checks.check(waitForObject(By.text("CHANNEL GROUPS"), 20_000) != null, "Live TV guide did not load")
            waitFor(60_000) { !store.guideBusy.value }
            checks.check(store.state.value.programs.isNotEmpty(), "Guide has no programs after import (${store.guideStatus.value})")
            SystemClock.sleep(1500); shot("08-live")
            press(KeyEvent.KEYCODE_DPAD_DOWN); press(KeyEvent.KEYCODE_DPAD_DOWN); press(KeyEvent.KEYCODE_DPAD_RIGHT)
            SystemClock.sleep(800); note("Live TV focus after DOWN,DOWN,RIGHT: ${focused()}"); shot("08b-live-dpad")

            // ---- Details from a Home row ----
            for (i in 0 until 6) { if (page(scenario) == "Home") break; device.pressBack(); SystemClock.sleep(600) }
            if (page(scenario) == "Home") route("e2e: Live TV -> Home", "dpad", "BACK") else { scenario.onActivity { it.page = "Home" }; route("e2e: Live TV -> Home", "direct") }
            waitFor(10_000) { DeviceCheck.anyOf(*homeMarkers) != null }
            SystemClock.sleep(1000)
            val inRow = downIntoRows(checks, "e2e: Home row for details")
            if (inRow) { device.pressDPadCenter(); route("e2e: open details", "dpad", "CENTER") }
            else {
                val poster = device.findObject(By.textStartsWith("Inside Out 2")) ?: device.findObject(By.textStartsWith("Moana 2"))
                checks.check(poster != null, "No fixture poster on Home to open"); poster?.click(); route("e2e: open details", "touch-fallback")
            }
            checks.check(waitFor(10_000) { has(By.text("Add to my list")) || has(By.text("In my list")) } != null, "Details dialog did not open")
            SystemClock.sleep(1800); note("details focus: ${focused()}"); shot("09-details")

            // ---- Playback ----
            val watchFocused = focusedOn("Watch") || focusedOn("Resume")
            checks.check(watchFocused, "Details did not focus Watch initially (focus: ${focused()})")
            val playAt = SystemClock.elapsedRealtime()
            if (watchFocused) { device.pressDPadCenter(); route("e2e: Watch", "dpad", "CENTER") }
            else { (device.findObject(By.text("Watch")) ?: device.findObject(By.text("Resume")))?.click(); route("e2e: Watch", "touch-fallback") }
            val ready = waitFor(30_000, 200) { var r = false; scenario.onActivity { r = it.fullPlayer && it.player?.playbackState == Player.STATE_READY }; r }
            checks.check(ready != null, "Playback did not reach Player.STATE_READY within 30 s")
            if (ready != null) metric("e2e_select_to_player_ready_ms", SystemClock.elapsedRealtime() - playAt)
            SystemClock.sleep(2500)
            scenario.onActivity { note("player: state=${it.player?.playbackState} position=${it.player?.currentPosition} playing=${it.playing?.name} controls=${it.playerControlsVisible}") }
            shot("10-playback")

            // ---- Audio & subtitles ----
            var controls = false; scenario.onActivity { controls = it.playerControlsVisible }
            if (!controls) press(KeyEvent.KEYCODE_DPAD_UP)
            waitForObject(By.text("Audio & subtitles"), 5_000)
            if (DeviceCheck.dpadToText("Audio & subtitles", KeyEvent.KEYCODE_DPAD_RIGHT, 6) != null) { device.pressDPadCenter(); route("e2e: Audio & subtitles", "dpad") }
            else {
                checks.check(false, "D-pad could not reach \"Audio & subtitles\" (focus: ${focused()})")
                DeviceCheck.clickText("Audio & subtitles"); route("e2e: Audio & subtitles", "touch-fallback")
            }
            checks.check(waitForObject(By.textStartsWith("Caption background"), 8_000) != null, "Audio & subtitles dialog did not open")
            SystemClock.sleep(800); note("audio/subs focus: ${focused()}"); shot("11-audio-subs")
            if (DeviceCheck.dpadToText("Caption background", KeyEvent.KEYCODE_DPAD_DOWN, 4, prefix = true) != null) { device.pressDPadCenter(); route("e2e: Caption background", "dpad") }
            else {
                checks.check(false, "D-pad could not reach \"Caption background\" (focus: ${focused()})")
                DeviceCheck.clickText("Caption background", prefix = true); route("e2e: Caption background", "touch-fallback")
            }
            checks.check(waitForObject(By.text("Caption background: Black"), 5_000) != null, "Caption background did not switch to Black")
            checks.check(store.state.value.prefs.subtitleBackground, "prefs.subtitleBackground is still false")
            device.pressBack(); route("e2e: close Audio & subtitles", "dpad", "BACK")
            val cues = waitFor(25_000, 250) { var c = false; scenario.onActivity { a -> c = a.player?.currentCues?.cues?.isNotEmpty() == true }; c }
            checks.check(cues != null, "No subtitle cue rendered from the provider's English VTT within 25 s")
            // With controls showing, captions sit above the letterbox, so the black box is visible on the video.
            SystemClock.sleep(600); shot("12-caption-black")
            waitFor(12_000) { var hidden = false; scenario.onActivity { hidden = !it.playerControlsVisible }; hidden }
            var stillPlaying = false; scenario.onActivity { stillPlaying = it.fullPlayer && it.player != null }
            checks.check(stillPlaying, "Closing the Audio & subtitles dialog with Back also left the player")
            SystemClock.sleep(800); shot("12b-caption-black-no-controls")

            // ---- Back to browsing ----
            device.pressBack()
            val back = waitFor(8_000) { var b = false; scenario.onActivity { b = !it.fullPlayer && it.player == null }; b }
            checks.check(back != null, "Back did not leave the player")
            if (back != null) route("e2e: Back from player", "dpad", "BACK")
            SystemClock.sleep(1500)
            note("after Back: page=${page(scenario)} focus=${focused()}")
            checks.check(page(scenario) == "Home", "Back from the player did not return to Home (page=${page(scenario)})")
            checks.check(!focused().startsWith("<nothing"), "Nothing has remote focus after leaving the player")
            heroUnderTopBar().let { if (it.isNotEmpty()) note("hero ${it.joinToString()} lie under the top bar band after Back (check 13-after-back.png)") }
            shot("13-after-back")
            waitFor(60_000) { !store.guideBusy.value }
        } }
        checks.assertAll()
    }
}
