package tv.ezano.app

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/**
 * Shared helpers for the real-device check (.github/workflows/device.yml, tools/device-check.sh).
 * Everything is reported through logcat tag "DeviceCheck" (STEP/METRIC/ROUTE/NOTE/SHOT/FAIL lines),
 * which the host script collects into the published summary. Screenshots are written by the shell
 * user to /data/local/tmp/device-check so they survive `pm clear` and app crashes.
 */
object DeviceCheck {
    const val TAG = "DeviceCheck"
    const val FIXTURE_URL = "http://10.0.2.2:8769"
    const val SHOT_DIR = "/data/local/tmp/device-check"
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    val context get() = instrumentation.targetContext
    val store get() = (context.applicationContext as EzanoApp).store
    fun fixtureProvider() = Provider(id = "device-fixture", name = "Device fixture", url = FIXTURE_URL, user = "test", password = "test")

    /** Compose keeps animating (hero rotation, progress); never wait 10 s for an idle UI. */
    fun fastUiAutomator() { Configurator.getInstance().waitForIdleTimeout = 50; Configurator.getInstance().waitForSelectorTimeout = 0 }

    private fun emit(kind: String, message: String) {
        Log.i(TAG, "$kind $message")
        runCatching { File(context.getExternalFilesDir(null), "device-check/events.txt").apply { parentFile?.mkdirs() }.appendText("$kind $message\n") }
    }
    fun step(message: String) = emit("STEP", message)
    fun note(message: String) = emit("NOTE", message)
    fun metric(key: String, value: Any) = emit("METRIC", "$key=$value")
    /** How a user-facing step was performed: "dpad" (remote), "touch-fallback" or "direct" (activity/store API). */
    fun route(stepName: String, how: String, detail: String = "") = emit("ROUTE", "$stepName via=$how${if (detail.isNotBlank()) " ($detail)" else ""}")
    fun failure(message: String) = emit("FAIL", message.replace('\n', ' '))

    fun shot(name: String) {
        SystemClock.sleep(150)
        val out = device.executeShellCommand("screencap -p $SHOT_DIR/$name.png")
        emit("SHOT", "$name.png${if (out.isNotBlank()) " ($out)" else ""}")
    }

    fun dumpHierarchy(name: String) {
        runCatching {
            val file = File(context.getExternalFilesDir(null), "device-check/$name.xml").apply { parentFile?.mkdirs() }
            file.outputStream().use { device.dumpWindowHierarchy(it) }
            emit("NOTE", "hierarchy saved: $name.xml")
        }.onFailure { emit("NOTE", "hierarchy dump failed: $it") }
    }

    /** Polls every [stepMs]; returns elapsed ms when [condition] became true, or null on timeout. */
    fun waitFor(timeoutMs: Long, stepMs: Long = 100, condition: () -> Boolean): Long? {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            if (runCatching(condition).getOrDefault(false)) return SystemClock.elapsedRealtime() - start
            if (SystemClock.elapsedRealtime() - start > timeoutMs) return null
            SystemClock.sleep(stepMs)
        }
    }
    fun has(selector: BySelector) = runCatching { device.hasObject(selector) }.getOrDefault(false)
    fun waitForObject(selector: BySelector, timeoutMs: Long) = waitFor(timeoutMs) { has(selector) }
    fun anyOf(vararg texts: String): String? = texts.firstOrNull { has(By.text(it)) }

    fun describe(o: UiObject2?): String {
        if (o == null) return "<nothing focused>"
        val own = listOfNotNull(o.text, o.contentDescription).filter { it.isNotBlank() }
        if (own.isNotEmpty()) return own.joinToString(" / ")
        val texts = ArrayList<String>()
        fun walk(node: UiObject2, depth: Int) {
            if (depth > 6 || texts.size > 3) return
            runCatching { node.children }.getOrDefault(emptyList()).forEach { child ->
                listOfNotNull(child.text, child.contentDescription).filter { it.isNotBlank() }.forEach { texts += it }
                walk(child, depth + 1)
            }
        }
        walk(o, 0)
        return if (texts.isEmpty()) "<${o.className} ${o.visibleBounds}>" else texts.joinToString(" / ") + " ${o.visibleBounds}"
    }
    fun focused(): String = runCatching { describe(device.findObject(By.focused(true))) }.getOrElse { "<focus lookup failed: $it>" }

    fun keyName(key: Int) = KeyEvent.keyCodeToString(key).removePrefix("KEYCODE_")
    fun press(key: Int, settleMs: Long = 450): String {
        device.pressKeyCode(key)
        SystemClock.sleep(settleMs)
        val now = focused()
        step("press ${keyName(key)} -> focus: $now")
        return now
    }

    /**
     * True when the focused node shows [text] itself or in a descendant. Compose often puts focus on
     * a container (button row, poster column) whose label is a child node, so By.text(..).focused(true)
     * alone misses it.
     */
    fun focusedOn(text: String, prefix: Boolean = false): Boolean = runCatching {
        val sel = if (prefix) By.textStartsWith(text) else By.text(text)
        device.findObjects(By.focused(true)).any { f ->
            val own = f.text ?: f.contentDescription
            (own != null && (if (prefix) own.startsWith(text) else own == text)) || f.hasObject(sel) ||
                (f.contentDescription == null && f.hasObject(if (prefix) By.descStartsWith(text) else By.desc(text)))
        }
    }.getOrDefault(false)
    fun anythingFocused() = runCatching { device.hasObject(By.focused(true)) }.getOrDefault(false)

    /** Presses [key] up to [maxPresses] times until [reached]; returns presses used, or null. */
    fun dpadUntil(reached: () -> Boolean, key: Int, maxPresses: Int, settleMs: Long = 600): Int? {
        for (i in 0..maxPresses) {
            if (waitFor(if (i == 0) 300 else settleMs) { reached() } != null) return i
            if (i == maxPresses) break
            press(key, 0)
        }
        return null
    }
    fun dpadToText(text: String, key: Int, maxPresses: Int, prefix: Boolean = false) = dpadUntil({ focusedOn(text, prefix) }, key, maxPresses)

    fun imeShown() = runCatching { device.executeShellCommand("dumpsys input_method").contains("mInputShown=true") }.getOrDefault(false)
    /** Closes the on-screen keyboard with Back if it is showing; returns whether it was. */
    fun hideIme(): Boolean {
        if (!imeShown()) return false
        device.pressBack(); SystemClock.sleep(500); note("closed the on-screen keyboard with Back")
        return true
    }
    fun clickText(text: String, prefix: Boolean = false) {
        val o = device.findObject(if (prefix) By.textStartsWith(text) else By.text(text))
            ?: throw AssertionError("\"$text\" is not on screen (focus: ${focused()})")
        o.click()
    }

    /** Runs [body]; on failure screenshots and dumps the UI *before* the activity closes, then rethrows. */
    fun <T> capturing(name: String, body: () -> T): T = try { body() } catch (t: Throwable) {
        runCatching { shot("FAIL-$name") }
        dumpHierarchy("FAIL-$name")
        throw t
    }
}

/** Collects non-fatal check failures so a run continues and screenshots every later step. */
class SoftChecks {
    val failures = ArrayList<String>()
    fun check(condition: Boolean, message: String): Boolean {
        if (!condition) { failures += message; DeviceCheck.failure(message) }
        return condition
    }
    fun assertAll() { if (failures.isNotEmpty()) throw AssertionError("${failures.size} device check(s) failed:\n- " + failures.joinToString("\n- ")) }
}

/** Logs every test failure (the in-test [DeviceCheck.capturing] wrapper takes the screenshots). */
class FailureArtifacts : TestWatcher() {
    override fun failed(e: Throwable?, description: Description) {
        DeviceCheck.failure("${description.testClass?.simpleName}#${description.methodName}: ${e?.toString()?.take(600)}")
    }
}
