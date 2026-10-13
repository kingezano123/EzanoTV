package tv.ezano.app

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import tv.ezano.app.DeviceCheck.device
import tv.ezano.app.DeviceCheck.focused
import tv.ezano.app.DeviceCheck.focusedAmong
import tv.ezano.app.DeviceCheck.focusedOn
import tv.ezano.app.DeviceCheck.focusedTexts
import tv.ezano.app.DeviceCheck.has
import tv.ezano.app.DeviceCheck.note
import tv.ezano.app.DeviceCheck.press
import tv.ezano.app.DeviceCheck.route
import tv.ezano.app.DeviceCheck.shot
import tv.ezano.app.DeviceCheck.store
import tv.ezano.app.DeviceCheck.topMost
import tv.ezano.app.DeviceCheck.visibleAmong
import tv.ezano.app.DeviceCheck.waitFor

/**
 * Favorites, remote only: "Add to favorites" in a movie's and a show's details toggles to "In favorites";
 * long-press on a Live TV channel row opens "Add favorite?" and confirms. The top bar then has
 * "Favorite movies/shows" (movie under "Movies", show under "Shows", no channel) and "Favorite channels"
 * (the channel, no movie or show); "My stuff" is gone. Uses the 8769 device fixture.
 */
class FavoritesTest {
    @get:Rule val failureArtifacts = FailureArtifacts()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private fun <T> act(f: (MainActivity) -> T): T { var r: Any? = null; scenario.onActivity { r = f(it) }; @Suppress("UNCHECKED_CAST") return r as T }
    private fun page() = act { it.page }
    private fun detailsOpen() = has(By.text("Add to favorites")) || has(By.text("In favorites")) || has(By.text("Add to my list")) || has(By.text("In my list"))

    /** Opens [tab] from the top bar, moves Down onto a poster of [kind] and opens its details; favorites it; returns the title. */
    private fun favoriteFromCatalog(tab: String, pageName: String, kind: Kind, loaded: () -> Boolean, shotName: String, checks: SoftChecks): Channel? {
        val titles = store.visible(kind).associateBy { HomeRecommendations.title(it) }
        DeviceCheck.openTopTab(tab, checks) { page() == pageName }
        checks.check(waitFor(20_000) { loaded() } != null, "$tab page did not load")
        SystemClock.sleep(1200)
        val poster = DeviceCheck.dpadUntil({ focusedAmong(titles.keys) != null }, KeyEvent.KEYCODE_DPAD_DOWN, 8)
        if (poster != null) { device.pressDPadCenter(); route("favorites: open a ${kind.name.lowercase()} from $tab", "dpad", "$poster x DOWN to ${focusedAmong(titles.keys)} + CENTER") }
        else {
            checks.check(false, "D-pad DOWN never focused a poster on $tab (focus: ${focused()})")
            visibleAmong(titles.keys).firstOrNull()?.let { device.findObject(By.text(it))?.click() }
            route("favorites: open a ${kind.name.lowercase()} from $tab", "touch-fallback")
        }
        if (waitFor(10_000) { detailsOpen() } == null) { checks.check(false, "$tab: details dialog did not open"); return null }
        SystemClock.sleep(1200)
        checks.check(has(By.text("Add to favorites")) || has(By.text("In favorites")),
            "$tab details: no \"Add to favorites\" button (buttons: ${listOf("Add to my list", "In my list").filter { has(By.text(it)) }})")
        val ids = titles.values.map { it.id }.toSet()
        val before = store.state.value.favorites
        if (has(By.text("In favorites"))) {
            note("$tab: the title was already a favorite; not pressing the button (it would remove it)")
        } else {
            val label = if (has(By.text("Add to favorites"))) "Add to favorites" else "Add to my list"
            val presses = DeviceCheck.dpadToText(label, KeyEvent.KEYCODE_DPAD_RIGHT, 4)
            if (presses != null) { device.pressDPadCenter(); route("favorites: $tab details $label", "dpad", "$presses x RIGHT + CENTER") }
            else {
                checks.check(false, "D-pad could not reach \"$label\" in the $tab details (focus: ${focused()})")
                DeviceCheck.clickText(label); route("favorites: $tab details $label", "touch-fallback")
            }
            checks.check(waitFor(4_000) { has(By.text("In favorites")) } != null, "$tab details: \"Add to favorites\" did not toggle to \"In favorites\"")
            SystemClock.sleep(500)
            checks.check(focusedOn("In favorites"), "$tab details: focus did not stay on the toggled \"In favorites\" button (focus: ${focused()})")
        }
        waitFor(3_000) { store.state.value.favorites.any { it in ids && it !in before } }
        val id = store.state.value.favorites.firstOrNull { it in ids && it !in before } ?: store.state.value.favorites.firstOrNull { it in ids }
        val channel = titles.values.firstOrNull { it.id == id }
        checks.check(channel != null, "$tab: favorites hold no ${kind.name.lowercase()} after \"Add to favorites\" (favorites: ${store.state.value.favorites})")
        note("$tab: favorited \"${channel?.let { HomeRecommendations.title(it) }}\" (${channel?.id})")
        shot(shotName)
        device.pressBack()
        checks.check(waitFor(5_000) { !detailsOpen() } != null, "$tab: Back did not close the details dialog")
        route("favorites: close $tab details", "dpad", "BACK")
        SystemClock.sleep(800)
        return channel
    }

    private fun barBottom() = topMost("Home")?.visibleBounds?.bottom ?: (device.displayHeight * 84 / 540)
    /** Section headings ("Movies", "Shows", "Channels") drawn below the top bar, top to bottom. */
    private fun headings(vararg labels: String): List<Pair<String, Int>> = labels.flatMap { l ->
        runCatching { device.findObjects(By.text(l)) }.getOrDefault(emptyList()).map { it.visibleBounds }.filter { it.height() > 0 && it.top > barBottom() }.map { l to it.top }
    }.sortedBy { it.second }
    private fun nodeTop(text: String): Int? = runCatching { device.findObjects(By.text(text)).map(UiObject2::getVisibleBounds).filter { it.height() > 0 && it.top > barBottom() }.minOfOrNull { it.top } }.getOrNull()
    private fun sectionOf(text: String, heads: List<Pair<String, Int>>): String? { val top = nodeTop(text) ?: return null; return heads.lastOrNull { it.second < top }?.first }

    @Test fun favoriteTitlesAndChannelsInTheirOwnTabs() {
        DeviceCheck.fastUiAutomator()
        DeviceCheck.ensureFixture()
        store.update { it.copy(favorites = emptySet()) }
        runBlocking { store.flush() }
        route("favorites: start with no favorites", "direct", "store.update")
        val liveNames = store.visible(Kind.LIVE).map { it.name }
        val programTitles = store.state.value.programs.map { it.title }.toSet()
        val checks = SoftChecks()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.use { DeviceCheck.capturing("FavoritesTest") {
            check(waitFor(60_000) { DeviceCheck.anyOf(*DeviceCheck.HOME_MARKERS) != null } != null) { "Home did not appear" }
            SystemClock.sleep(1500)
            val tabs = DeviceCheck.NAV_TABS.filter { has(By.text(it)) }
            note("top bar tabs: $tabs")
            checks.check(has(By.text("Favorite channels")), "Top bar has no \"Favorite channels\" tab (tabs: $tabs)")
            checks.check(has(By.text("Favorite movies/shows")), "Top bar has no \"Favorite movies/shows\" tab (tabs: $tabs)")
            checks.check(!has(By.text("My stuff")), "Top bar still shows \"My stuff\"")

            val movie = favoriteFromCatalog("Movies", "Movies", Kind.MOVIE, { has(By.text("Popular movies")) || has(By.textContains("movies in your collection")) }, "18a-fav-movie-details", checks)
            val show = favoriteFromCatalog("Shows", "Series", Kind.SERIES, { has(By.text("Popular shows")) || has(By.textContains("series in your collection")) }, "18b-fav-show-details", checks)

            // ---- Long-press a Live TV channel row ----
            DeviceCheck.openTopTab("Live TV", checks) { page() == "Live TV" && has(By.text("CHANNEL GROUPS")) }
            waitFor(20_000) { visibleAmong(liveNames.take(3)).isNotEmpty() }
            SystemClock.sleep(1000)
            for (i in 0 until 12) {
                if (focusedAmong(liveNames) != null) break
                press(if (focusedTexts().any { it in programTitles || it == "Guide unavailable" }) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_DOWN, 500)
            }
            press(KeyEvent.KEYCODE_DPAD_DOWN); press(KeyEvent.KEYCODE_DPAD_DOWN)
            val channelName = focusedAmong(liveNames)
            checks.check(channelName != null, "D-pad could not focus a Live TV channel row (focus: ${focused()})")
            val channel = store.visible(Kind.LIVE).firstOrNull { it.name == channelName }
            if (channelName != null) {
                route("favorites: focus channel $channelName", "dpad", "DOWN")
                DeviceCheck.longPressCenter()
                val prompt = waitFor(5_000) { has(By.text("Add favorite?")) } != null
                checks.check(prompt, "Long-press on the \"$channelName\" row did not open \"Add favorite?\" (playing: ${act { it.fullPlayer }}, focus: ${focused()})")
                if (prompt) {
                    route("favorites: long-press $channelName", "dpad", "CENTER held 1 s")
                    SystemClock.sleep(600)
                    shot("18c-fav-channel-prompt")
                    for (i in 0 until 5) {
                        if (focusedOn("Favorite")) break
                        press(if (focusedOn("Cancel")) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_DOWN, 450)
                    }
                    if (focusedOn("Favorite")) { device.pressDPadCenter(); route("favorites: confirm Favorite", "dpad", "CENTER") }
                    else { checks.check(false, "D-pad could not reach \"Favorite\" in the \"Add favorite?\" dialog (focus: ${focused()})"); DeviceCheck.clickText("Favorite"); route("favorites: confirm Favorite", "touch-fallback") }
                    checks.check(waitFor(4_000) { !has(By.text("Add favorite?")) } != null, "\"Add favorite?\" dialog did not close after Favorite")
                } else {
                    if (act { it.fullPlayer }) { device.pressBack(); SystemClock.sleep(1000) }
                    if (has(By.text("Add favorite?")).not() && channel != null) { store.favorite(channel); route("favorites: favorite $channelName", "direct", "store.favorite, so the tabs can still be checked") }
                }
                checks.check(waitFor(3_000) { channel != null && channel.id in store.state.value.favorites } != null, "Channel \"$channelName\" is not in favorites after confirming")
            }

            // ---- Favorite movies/shows ----
            val movieTitle = movie?.let { HomeRecommendations.title(it) }
            val showTitle = show?.let { HomeRecommendations.title(it) }
            val livePage = page()
            DeviceCheck.openTopTab("Favorite movies/shows", checks) { page() != livePage && (has(By.textContains("No favorite")) || listOfNotNull(movieTitle, showTitle).any { nodeTop(it) != null }) }
            waitFor(6_000) { listOfNotNull(movieTitle, showTitle).all { nodeTop(it) != null } }
            SystemClock.sleep(1000)
            val titlesPage = page()
            val heads = headings("Movies", "Shows", "Channels", "Favorite channels")
            note("Favorite movies/shows: page=$titlesPage headings=$heads movie=\"$movieTitle\"@${movieTitle?.let(::nodeTop)} show=\"$showTitle\"@${showTitle?.let(::nodeTop)}")
            if (movieTitle != null) {
                checks.check(nodeTop(movieTitle) != null, "Favorite movies/shows does not list the favorited movie \"$movieTitle\"")
                checks.check(sectionOf(movieTitle, heads) == "Movies", "\"$movieTitle\" is not under a \"Movies\" heading (under: ${sectionOf(movieTitle, heads)})")
            }
            if (showTitle != null) {
                checks.check(nodeTop(showTitle) != null, "Favorite movies/shows does not list the favorited show \"$showTitle\"")
                checks.check(sectionOf(showTitle, heads) == "Shows", "\"$showTitle\" is not under a \"Shows\" heading (under: ${sectionOf(showTitle, heads)})")
            }
            visibleAmong(liveNames).let { checks.check(it.isEmpty(), "Favorite movies/shows shows live channel(s) $it") }
            val reach = DeviceCheck.dpadUntil({ focusedAmong(listOfNotNull(movieTitle, showTitle)) != null }, KeyEvent.KEYCODE_DPAD_DOWN, 4)
            checks.check(reach != null, "D-pad DOWN from the top bar did not reach a favorite poster (focus: ${focused()})")
            if (reach != null) route("favorites: top bar -> favorite poster", "dpad", "$reach x DOWN to ${focusedAmong(listOfNotNull(movieTitle, showTitle))}")
            SystemClock.sleep(600)
            shot("18-fav-titles")

            // ---- Favorite channels ----
            DeviceCheck.openTopTab("Favorite channels", checks) { page() != titlesPage && (has(By.textContains("No favorite")) || (channelName != null && nodeTop(channelName) != null)) }
            if (channelName != null) waitFor(6_000) { nodeTop(channelName) != null }
            SystemClock.sleep(1000)
            note("Favorite channels: page=${page()} channels=${visibleAmong(liveNames)} chips Favorites=${has(By.text("Favorites"))}")
            if (channelName != null) checks.check(nodeTop(channelName) != null, "Favorite channels does not list the favorited channel \"$channelName\"")
            listOfNotNull(movieTitle, showTitle).filter { nodeTop(it) != null }.let { checks.check(it.isEmpty(), "Favorite channels shows movie/show title(s) $it") }
            visibleAmong(liveNames).filter { it != channelName }.let { checks.check(it.isEmpty(), "Favorite channels shows channels that are not favorites: $it") }
            shot("19-fav-channels")
        } }
        store.update { it.copy(favorites = emptySet()) }
        runBlocking { store.flush() }
        checks.assertAll()
    }
}
