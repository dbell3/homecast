package io.github.yunuserdemsihhatli.homecast.browser

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yunuserdemsihhatli.homecast.capture.CandidateStore
import io.github.yunuserdemsihhatli.homecast.capture.MediaKind
import io.github.yunuserdemsihhatli.homecast.capture.VideoCandidate
import kotlinx.coroutines.CompletableDeferred
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loads a test page in a WebView configured exactly like the browser screen and checks what gets captured,
 * blocked and refused. The page is served from `localhost` and its player iframe from `127.0.0.1`: two different
 * sites served by the same MockWebServer.
 */
@RunWith(AndroidJUnit4::class)
class CaptureInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val store = CandidateStore()
    private val events = RecordingEvents()
    private lateinit var webView: WebView
    private lateinit var client: BrowserWebViewClient

    private val port get() = server.port
    private val pageUrl get() = "http://localhost:$port/index.html"
    private val playerUrl get() = "http://127.0.0.1:$port/player.html"
    private val masterUrl get() = "http://127.0.0.1:$port/stream/master"
    private val mediaUrl get() = "http://127.0.0.1:$port/hls/index.m3u8"

    private class RecordingEvents : BrowserEvents {
        val pagesStarted = CopyOnWriteArrayList<String>()
        val blocked = AtomicInteger()

        override fun onPageStarted(url: String) {
            pagesStarted += url
        }

        override fun onUrlChanged(url: String) = Unit

        override fun onPageFinished() = Unit

        override fun onPageTitle(title: String) = Unit

        override fun onNavState(
            canBack: Boolean,
            canForward: Boolean,
        ) = Unit

        override fun onBlocked() {
            blocked.incrementAndGet()
        }

        override fun onHttpAuthRequest(
            host: String,
            realm: String,
            proceed: (String, String) -> Unit,
            cancel: () -> Unit,
        ) = cancel()
    }

    @Before fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return when (request.url.encodedPath) {
                        "/index.html" -> {
                            html(asset("index.html").replace("{{PLAYER_URL}}", playerUrl))
                        }

                        "/player.html" -> {
                            html(player())
                        }

                        "/stream/master" -> {
                            master(request)
                        }

                        "/hls/index.m3u8" -> {
                            mediaPlaylist(request)
                        }

                        "/media.html" -> {
                            html(asset("media.html").replace("{{FRAME_URL}}", "http://127.0.0.1:$port/mediaframe.html"))
                        }

                        "/mediaframe.html" -> {
                            html(asset("mediaframe.html"))
                        }

                        "/media.js" -> {
                            MockResponse
                                .Builder()
                                .setHeader("Content-Type", "text/javascript")
                                .body(asset("media.js"))
                                .build()
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
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            webView = WebView(context)
            // Timers are process-wide: a browser activity stopped by an earlier test may have paused them.
            webView.resumeTimers()
            client =
                BrowserWebViewClient(
                    blocklist = CompletableDeferred(HostBlocklist(setOf("ads.test", "ads.localhost"))),
                    redirectGuard = RedirectGuard(),
                    store = store,
                    events = events,
                    userAgent = webView.settings.userAgentString,
                )
            configureBrowserWebView(
                webView = webView,
                client = client,
                chromeClient = BrowserChromeClient(client),
                captureScript = CaptureScript.load(context),
            )
        }
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { webView.destroy() }
        server.close()
    }

    @Test fun capturesExtensionlessMasterFromCrossOriginIframeAndBlocksAdsAndPopups() {
        instrumentation.runOnMainSync { webView.loadUrl(pageUrl) }

        val master = awaitCandidate { it.url == masterUrl }

        assertNotNull("master playlist was not captured; requests: ${requests.map { it.url }}", master)
        master!!
        assertEquals(MediaKind.HLS, master.kind)
        assertTrue(master.isMaster)
        assertEquals(playerUrl, master.headers.referer)
        assertEquals(pageUrl, master.pageUrl)
        assertTrue("seen by interception too", master.networkConfirmed)

        // Interception sees the .m3u8 first, without a Referer header: its fallback (top page) Referer is replaced by
        // the iframe URL that capture.js reports for the same playlist.
        val media = awaitCandidate { it.url == mediaUrl && !it.fallbackHeaders }
        assertNotNull("media playlist headers never confirmed: ${store.candidates.value}", media)
        assertEquals(playerUrl, media!!.headers.referer)

        // The unblocked script on a *.localhost alias proves such hosts reach the server, so the blocked one's
        // absence is meaningful.
        assertTrue("control script never loaded", requests.any { it.url.host == "cdn.localhost" })
        assertFalse(requests.any { it.url.host == "ads.localhost" || it.url.host == "ads.test" })
        assertTrue(events.blocked.get() >= 2)

        // window.open neither opened a window nor replaced the page.
        assertFalse(requests.any { it.url.host == "popup.localhost" })
        assertEquals(listOf(pageUrl), events.pagesStarted)
        var currentUrl: String? = null
        var safeBrowsing: Boolean? = null
        instrumentation.runOnMainSync {
            currentUrl = webView.url
            safeBrowsing = webView.settings.safeBrowsingEnabled
        }
        assertEquals(pageUrl, currentUrl)
        assertEquals(false, safeBrowsing)
    }

    @Test fun playOnTvPausesThePagesMediaInEveryFrame() {
        val mediaPage = "http://localhost:$port/media.html"
        instrumentation.runOnMainSync { webView.loadUrl(mediaPage) }
        awaitJs("main media never started", "window.mainMedia && !window.mainMedia.paused")
        // The iframe's capture.js has said hello (its media also started) before we ask it to pause.
        awaitJs("frame never loaded", "document.querySelector('iframe').contentWindow !== null")
        Thread.sleep(FRAME_SETTLE_MILLIS)

        instrumentation.runOnMainSync { client.pausePageMedia(webView) }

        awaitJs("main frame media still playing", "window.mainMedia.paused && window.pauseCalls > 0")
        awaitJs("cross-origin frame media never paused", "window.framePauses > 0")
    }

    private fun awaitJs(
        message: String,
        condition: String,
    ) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val result = java.util.concurrent.LinkedBlockingQueue<String>()
            instrumentation.runOnMainSync { webView.evaluateJavascript("!!($condition)") { result.put(it) } }
            if (result.poll(TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS) == "true") return
            Thread.sleep(100)
        }
        throw AssertionError("$message; requests: ${requests.map { it.url }}")
    }

    private fun awaitCandidate(predicate: (VideoCandidate) -> Boolean): VideoCandidate? {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            store.candidates.value
                .firstOrNull(predicate)
                ?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    private fun player() =
        asset("player.html")
            .replace("{{LOCAL_AD_URL}}", "http://ads.localhost:$port/ad.js")
            .replace("{{LOCAL_LIB_URL}}", "http://cdn.localhost:$port/lib.js")
            .replace("{{POPUP_URL}}", "http://popup.localhost:$port/")

    /** Served only to the player page, like hosts that check the Referer; extensionless like many real ones. */
    private fun master(request: RecordedRequest): MockResponse =
        if (request.headers["Referer"] == playerUrl) {
            MockResponse
                .Builder()
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .body(
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow/index.m3u8\n" +
                        "#EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720\nhigh/index.m3u8\n",
                ).build()
        } else {
            MockResponse
                .Builder()
                .code(403)
                .body("forbidden")
                .build()
        }

    private fun mediaPlaylist(request: RecordedRequest): MockResponse =
        if (request.headers["Referer"] == playerUrl) {
            MockResponse
                .Builder()
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .body("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg0.ts\n#EXT-X-ENDLIST\n")
                .build()
        } else {
            MockResponse
                .Builder()
                .code(403)
                .body("forbidden")
                .build()
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
        const val FRAME_SETTLE_MILLIS = 1_000L
    }
}
