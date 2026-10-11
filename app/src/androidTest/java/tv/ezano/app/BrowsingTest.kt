package tv.ezano.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BrowsingTest {
    @Test fun dpadFromMoviesNavigationFocusesSearchWithoutOpeningKeyboard() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val device=UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.text("Home")),10000))
            Thread.sleep(300);device.pressDPadRight();device.pressDPadRight()
            assertTrue(device.wait(Until.hasObject(By.text("Movies")),5000))
            device.pressDPadDown()
            val search=device.wait(Until.findObject(By.clazz("android.widget.EditText").focused(true)),5000)
            assertNotNull("Movies navigation Down should focus its search field",search)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val insets=WindowInsetsCompat.toWindowInsetsCompat(activity.window.decorView.rootWindowInsets,activity.window.decorView)
                assertFalse("Remote focus alone must leave the keyboard hidden",insets.isVisible(WindowInsetsCompat.Type.ime()))
            }
            device.pressDPadCenter()
            var imeVisible=false;val imeDeadline=System.currentTimeMillis()+5000
            while(!imeVisible && System.currentTimeMillis()<imeDeadline){Thread.sleep(100);scenario.onActivity {activity->imeVisible=WindowInsetsCompat.toWindowInsetsCompat(activity.window.decorView.rootWindowInsets,activity.window.decorView).isVisible(WindowInsetsCompat.Type.ime())}}
            assertTrue("Explicit Select should open text input",imeVisible)
            device.pressBack();Thread.sleep(300);device.pressDPadDown();device.pressDPadUp();Thread.sleep(300)
            scenario.onActivity {activity->val insets=WindowInsetsCompat.toWindowInsetsCompat(activity.window.decorView.rootWindowInsets,activity.window.decorView);assertFalse("Returning focus must not reopen input",insets.isVisible(WindowInsetsCompat.Type.ime()))}
        }
    }

    @Test fun familyHomeAndSectionSearchStayScoped() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val store=(context.applicationContext as EzanoApp).store
        while(!store.ready.value)Thread.sleep(50)
        val device=UiDevice.getInstance(instrumentation)
        Configurator.getInstance().waitForIdleTimeout=100
        val p=Provider(id="ui-fixture",name="Visual test",url="http://10.0.2.2:8765")
        val family=(1..6).flatMap { n->listOf(
            Channel("movie:$n",p.id,"$n","Family Movie $n","",Kind.MOVIE,"[US] Family movies",logo="file:///android_asset/bunny.jpg",backdrop="file:///android_asset/steel.jpg",year="2026",rating="8.0",language="en",country="US",certification="PG"),
            Channel("show:$n",p.id,"$n","Family Show $n","",Kind.SERIES,"[US] Kids shows",logo="file:///android_asset/bunny.jpg",backdrop="file:///android_asset/steel.jpg",year="2026",rating="8.4",language="en",country="US",certification="TV-Y")) }
        val live=(1..8).map {n->Channel("live:$n",p.id,"$n","Channel $n","",Kind.LIVE,"US Entertainment")}
        val now=System.currentTimeMillis();val start=now-now%1800000
        val guide=live.flatMap {c->(0..3).map {n->Program("${c.id}:$n",c.id,listOf("Morning stories","Around the world","Family favorites","Evening edition")[n],start+n*1800000,start+(n+1)*1800000,"Program descriptions appear here, with recording and reminder options in program details.")}}
        store.update {Library(providers=listOf(p),channels=family+live+family.first().copy(id="adult",name="Mature excluded",group="[EN] Drama",certification="R"),programs=guide,lastGuideSync=now)}
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            assertTrue(device.wait(Until.hasObject(By.text("Continue Watching")),15000))
            assertFalse(device.hasObject(By.text("Mature excluded")))
            device.takeScreenshot(File(context.filesDir,"home-012.png"))
            fun search(text:String) {val field=device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000);field.click();Thread.sleep(400);field.text=text;device.pressBack();Thread.sleep(400)}
            scenario.onActivity {it.page="Search"}
            search("Family")
            assertTrue(device.wait(Until.hasObject(By.text("12 results")),10000))
            scenario.onActivity {it.page="Live TV"}
            assertTrue(device.wait(Until.hasObject(By.text("CHANNEL GROUPS")),10000))
            device.takeScreenshot(File(context.filesDir,"live-012.png"))
            search("Channel 8")
            assertTrue(device.wait(Until.gone(By.text("Channel 1")),10000))
            assertTrue(device.wait(Until.hasObject(By.text("Channel 8")),10000))
            assertFalse(device.hasObject(By.text("Channel 1")))
            scenario.onActivity {it.page="Movies"}
            assertTrue(device.wait(Until.hasObject(By.text("7 movies in your collection")),10000))
            search("Family Movie 6")
            assertTrue(device.wait(Until.hasObject(By.textContains("1 results")),10000))
            device.takeScreenshot(File(context.filesDir,"movies-012.png"))
            scenario.onActivity {it.page="Series"}
            assertTrue(device.wait(Until.hasObject(By.text("6 series in your collection")),10000))
            search("Family Show 6")
            assertTrue(device.wait(Until.hasObject(By.textContains("1 results")),10000))
            device.takeScreenshot(File(context.filesDir,"shows-012.png"))
        }
    }
}
