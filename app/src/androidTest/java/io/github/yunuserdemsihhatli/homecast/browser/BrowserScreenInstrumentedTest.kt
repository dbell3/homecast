package io.github.yunuserdemsihhatli.homecast.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yunuserdemsihhatli.homecast.HomeCastApp
import io.github.yunuserdemsihhatli.homecast.MainActivity
import io.github.yunuserdemsihhatli.homecast.ROUTE_REMOTE
import io.github.yunuserdemsihhatli.homecast.service.HomeCastService
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The browser screen inside the real [MainActivity]: WebView lifetime across navigation, popups from taps (dropped
 * unless the tap was on a real link, which then opens in the same WebView) and popups without a gesture (dropped).
 */
@RunWith(AndroidJUnit4::class)
class BrowserScreenInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph = (context.applicationContext as HomeCastApp).graph
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    private val firstUrl get() = "http://127.0.0.1:${server.port}/first.html"
    private val secondUrl get() = "http://127.0.0.1:${server.port}/second.html"
    private val clipUrl get() = "http://127.0.0.1:${server.port}/media/clip.mp4"
    private val protectedUrl get() = "http://127.0.0.1:${server.port}/protected.html"

    @Before fun setUp() {
        // No first-launch permission / battery dialogs over the WebView.
        context
            .getSharedPreferences(MainActivity.ONBOARDING_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MainActivity.KEY_ONBOARDED, true)
            .commit()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return when (request.url.encodedPath) {
                        "/first.html" -> {
                            html(
                                asset("first.html").replace("{{POPUP_URL}}", "http://popup.localhost:${server.port}/"),
                            )
                        }

                        "/second.html" -> {
                            html(asset("second.html"))
                        }

                        "/newtab.html" -> {
                            html(asset("newtab.html"))
                        }

                        "/hijack.html" -> {
                            html(
                                asset("hijack.html").replace("{{POPUP_URL}}", "http://popup.localhost:${server.port}/"),
                            )
                        }

                        "/autopopup.html" -> {
                            html(asset("autopopup.html"))
                        }

                        "/protected.html" -> {
                            // HTTP Basic auth: challenge until the request carries credentials.
                            if (request.headers["Authorization"] == null) {
                                MockResponse
                                    .Builder()
                                    .code(401)
                                    .setHeader("WWW-Authenticate", "Basic realm=\"Restricted Content\"")
                                    .setHeader("Content-Type", "text/html; charset=utf-8")
                                    .body("<html><body>Unauthorized</body></html>")
                                    .build()
                            } else {
                                html("<html><head><title>In</title></head><body>Welcome</body></html>")
                            }
                        }

                        else -> {
                            MockResponse
                                .Builder()
                                .code(200)
                                .body("")
                                .build()
                        }
                    }
                }
            }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After fun tearDown() {
        server.close()
    }

    @Test fun leavingForTheRemoteAndComingBackKeepsTheSameWebViewPageAndHistory() {
        launch(firstUrl).use { scenario ->
            awaitLoaded(scenario, firstUrl)
            deliverNewIntent(scenario, viewIntent(secondUrl))
            awaitLoaded(scenario, secondUrl)
            val webView = onMain(scenario) { it.browserHost.webView }

            deliverNewIntent(
                scenario,
                Intent(context, MainActivity::class.java).putExtra(HomeCastService.EXTRA_OPEN, ROUTE_REMOTE),
            )
            awaitTrue("browser never left") { onMain(scenario) { it.browserHost.webView.parent == null } }
            onMain(scenario) { it.onBackPressedDispatcher.onBackPressed() }
            awaitTrue("browser never came back") { onMain(scenario) { it.browserHost.webView.isAttachedToWindow } }

            onMain(scenario) { activity ->
                assertSame(webView, activity.browserHost.webView)
                assertEquals(secondUrl, webView.url)
                assertTrue(webView.canGoBack())
                assertTrue(activity.browserViewModel.state.value.canGoBack)
            }
            assertEquals(1, requests.count { it.url.encodedPath == "/first.html" })
            assertEquals(1, requests.count { it.url.encodedPath == "/second.html" })
        }
    }

    @Test fun tapThatOpensACrossSiteWindowIsDroppedAndThePageKept() {
        launch(firstUrl).use { scenario ->
            awaitLoaded(scenario, firstUrl)
            awaitTrue("clip never captured") {
                graph.candidateStore.candidates.value
                    .any { it.url == clipUrl }
            }
            val blockedBefore = onMain(scenario) { it.browserViewModel.state.value.blockedCount }

            tapCenterOfWebView(scenario)

            // onCreateWindow saw the request (it counts as blocked) and refused it.
            awaitTrue("window request never reached onCreateWindow") {
                onMain(scenario) { it.browserViewModel.state.value.blockedCount } > blockedBefore
            }
            SystemClock.sleep(SETTLE_MILLIS)
            onMain(scenario) { assertEquals(firstUrl, it.browserHost.webView.url) }
            assertFalse(requests.any { it.url.host == "popup.localhost" })
            assertTrue(
                graph.candidateStore.candidates.value
                    .any { it.url == clipUrl },
            )
        }
    }

    @Test fun tappingATargetBlankLinkOpensItInTheSameWebView() {
        val newTabUrl = "http://127.0.0.1:${server.port}/newtab.html"
        launch(newTabUrl).use { scenario ->
            awaitLoaded(scenario, newTabUrl)

            tapCenterOfWebView(scenario)

            awaitLoaded(scenario, secondUrl)
            onMain(scenario) { assertTrue(it.browserHost.webView.canGoBack()) }
            assertEquals(1, requests.count { it.url.encodedPath == "/second.html" })
        }
    }

    @Test fun linkWhoseClickHandlerOpensAnotherSiteIsDroppedAndThePageKept() {
        val hijackUrl = "http://127.0.0.1:${server.port}/hijack.html"
        launch(hijackUrl).use { scenario ->
            awaitLoaded(scenario, hijackUrl)
            val blockedBefore = onMain(scenario) { it.browserViewModel.state.value.blockedCount }

            tapCenterOfWebView(scenario)

            awaitTrue("the swapped window was never refused") {
                onMain(scenario) { it.browserViewModel.state.value.blockedCount } > blockedBefore
            }
            SystemClock.sleep(SETTLE_MILLIS)
            onMain(scenario) { assertEquals(hijackUrl, it.browserHost.webView.url) }
            assertFalse(requests.any { it.url.host == "popup.localhost" })
            assertFalse(requests.any { it.url.encodedPath == "/second.html" })
        }
    }

    @Test fun windowOpenWithoutAGestureIsDropped() {
        val autoUrl = "http://127.0.0.1:${server.port}/autopopup.html"
        launch(autoUrl).use { scenario ->
            awaitLoaded(scenario, autoUrl)
            // Refused by WebView itself (scripts may not open windows on their own), before onCreateWindow.
            awaitTrue("window.open never ran") { onMain(scenario) { it.browserHost.webView.title } == "tried:null" }
            SystemClock.sleep(SETTLE_MILLIS)
            onMain(scenario) { assertEquals(autoUrl, it.browserHost.webView.url) }
            assertFalse(requests.any { it.url.encodedPath == "/second.html" })
        }
    }

    @Test fun httpBasicAuthShowsThePromptAndSubmittingCredentialsLoadsThePage() {
        launch(protectedUrl).use { scenario ->
            // The 401 challenge raises the credential prompt instead of being silently cancelled.
            awaitTrue("auth prompt never appeared") {
                onMain(scenario) { it.browserViewModel.authPrompt.value } != null
            }
            onMain(scenario) {
                assertEquals(
                    "Restricted Content",
                    it.browserViewModel.authPrompt.value
                        ?.realm,
                )
            }

            onMain(scenario) { it.browserViewModel.submitHttpAuth("alice", "s3cret") }

            // The page loads once credentials are supplied, and the prompt is gone.
            awaitLoaded(scenario, protectedUrl)
            awaitTrue("auth prompt never cleared") {
                onMain(scenario) { it.browserViewModel.authPrompt.value } == null
            }
            assertTrue(
                "no authenticated request reached the server",
                requests.any { it.url.encodedPath == "/protected.html" && it.headers["Authorization"] != null },
            )
        }
    }

    @Test fun lateReleaseOfAnOldContainerDoesNotDetachTheWebViewFromTheNewOne() {
        launch(firstUrl).use { scenario ->
            onMain(scenario) { activity ->
                val host = activity.browserHost
                val old = FrameLayout(activity)
                val new = FrameLayout(activity)
                host.attachTo(old)

                host.attachTo(new) // the browser screen came back before the old one was released
                host.detachFrom(old) // the old screen's late release

                assertSame(new, host.webView.parent)
                host.detachFrom(new)
                assertEquals(null, host.webView.parent)
            }
        }
    }

    private fun launch(url: String): ActivityScenario<MainActivity> = ActivityScenario.launch(viewIntent(url))

    private fun viewIntent(url: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** What a singleTask launch of an already running activity does (as the notification and shared links do). */
    private fun deliverNewIntent(
        scenario: ActivityScenario<MainActivity>,
        intent: Intent,
    ) = onMain(scenario) { activity ->
        // MainActivity adopts the new intent (setIntent); ActivityScenario finds its activity by the launch intent.
        val launchIntent = activity.intent
        instrumentation.callActivityOnNewIntent(activity, intent)
        activity.intent = launchIntent
    }

    private fun awaitLoaded(
        scenario: ActivityScenario<MainActivity>,
        url: String,
    ) = awaitTrue("$url never finished loading") {
        onMain(scenario) { it.browserHost.webView.url == url && it.browserHost.webView.progress == 100 }
    }

    private fun tapCenterOfWebView(scenario: ActivityScenario<MainActivity>) {
        val (x, y) =
            onMain(scenario) { activity ->
                val view = activity.browserHost.webView
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                (location[0] + view.width / 2f) to (location[1] + view.height / 2f)
            }
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + TAP_MILLIS, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun <T> onMain(
        scenario: ActivityScenario<MainActivity>,
        block: (MainActivity) -> T,
    ): T {
        var result: Result<T>? = null
        scenario.onActivity { result = runCatching { block(it) } }
        return result!!.getOrThrow()
    }

    private fun awaitTrue(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError("$message; requests: ${requests.map { it.url }}")
    }

    private fun html(body: String) =
        MockResponse
            .Builder()
            .setHeader("Content-Type", "text/html; charset=utf-8")
            .body(body)
            .build()

    private fun asset(name: String): String =
        instrumentation.context.assets
            .open("testpage/$name")
            .bufferedReader()
            .use { it.readText() }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
        const val POLL_MILLIS = 100L
        const val SETTLE_MILLIS = 1_500L
        const val TAP_MILLIS = 50L
    }
}
