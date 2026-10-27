package io.github.yunuserdemsihhatli.homecast.browser

import io.github.yunuserdemsihhatli.homecast.capture.CandidateStore
import io.github.yunuserdemsihhatli.homecast.capture.MediaKind
import io.github.yunuserdemsihhatli.homecast.capture.SubtitleFormat
import io.github.yunuserdemsihhatli.homecast.capture.SubtitleSource
import io.github.yunuserdemsihhatli.homecast.capture.UpstreamHeaders
import io.github.yunuserdemsihhatli.homecast.cast.CastDevice
import io.github.yunuserdemsihhatli.homecast.cast.ConnectionState
import io.github.yunuserdemsihhatli.homecast.cast.DeviceStore
import io.github.yunuserdemsihhatli.homecast.cast.FakeCastRemote
import io.github.yunuserdemsihhatli.homecast.cast.LoadRequest
import io.github.yunuserdemsihhatli.homecast.playback.PlaybackController
import io.github.yunuserdemsihhatli.homecast.playback.PlaybackError
import io.github.yunuserdemsihhatli.homecast.playback.WifiAddressProvider
import io.github.yunuserdemsihhatli.homecast.proxy.ProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelTest {
    private val upstream = MockWebServer()
    private val proxy = ProxyServer(OkHttpClient())
    private val cast = FakeCastRemote()
    private val store = CandidateStore()
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val headers =
        UpstreamHeaders(referer = "https://page.example/", origin = null, userAgent = "UA", cookie = null)

    private val wifi =
        object : WifiAddressProvider {
            override val address = MutableStateFlow<String?>("127.0.0.1")
        }

    private val devices =
        object : DeviceStore {
            override val saved: StateFlow<List<CastDevice>> = MutableStateFlow(emptyList())

            override fun save(device: CastDevice) = Unit

            override fun remove(device: CastDevice) = Unit

            override fun lastUsed(): CastDevice? = null
        }

    private val playback =
        PlaybackController(
            scope = controllerScope,
            proxy = proxy,
            cast = cast,
            wifi = wifi,
            devices = devices,
            serviceStarter = {},
            serviceStopper = {},
            proxyBindHost = "127.0.0.1",
        )

    @Before fun setUp() {
        // Real time (not a test scheduler): playOnTv does real network I/O with real timeouts.
        Dispatchers.setMain(Dispatchers.Unconfined)
        upstream.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse
                        .Builder()
                        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .body("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg.ts\n#EXT-X-ENDLIST\n")
                        .build()
            }
        upstream.start()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        controllerScope.cancel()
        proxy.stop()
        upstream.close()
    }

    @Test fun addressInputNormalisation() {
        val vm = BrowserViewModel(store, playback)

        assertEquals("https://a.b/x", vm.onAddressSubmitted("https://a.b/x"))
        assertEquals("http://a.b/x", vm.onAddressSubmitted("http://a.b/x"))
        assertEquals("https://a.com", vm.onAddressSubmitted("a.com"))
        assertEquals("https://a.com/path?q=1", vm.onAddressSubmitted("  a.com/path?q=1 "))
        assertEquals("https://duckduckgo.com/?q=eski+film", vm.onAddressSubmitted("eski film"))
        assertEquals("https://duckduckgo.com/?q=film", vm.onAddressSubmitted("film"))
        assertEquals("https://duckduckgo.com/?q=a%26b%3Dc", vm.onAddressSubmitted("a&b=c"))
    }

    @Test fun pageStartedWithNewUrlClearsCandidates() {
        val vm = BrowserViewModel(store, playback)
        vm.onPageStarted("https://page.example/1")
        store.addVideo("https://cdn.example/v.m3u8", MediaKind.HLS, headers, "https://page.example/1", "One")
        vm.onBlocked()

        vm.onPageStarted("https://page.example/1") // reload of the same page keeps what was found
        assertEquals(1, vm.state.value.candidates.size)

        vm.onPageStarted("https://page.example/2")

        assertEquals("https://page.example/2", vm.state.value.url)
        assertTrue(vm.state.value.loading)
        assertTrue(
            vm.state.value.candidates
                .isEmpty(),
        )
        assertTrue(store.candidates.value.isEmpty())
        assertEquals(0, vm.state.value.blockedCount)
    }

    @Test fun httpAuthRequestRaisesAPrompt() {
        val vm = BrowserViewModel(store, playback)
        assertEquals(null, vm.authPrompt.value)

        vm.onHttpAuthRequest("example.com", "Restricted Content", proceed = { _, _ -> }, cancel = {})

        assertEquals(HttpAuthPrompt("example.com", "Restricted Content"), vm.authPrompt.value)
    }

    @Test fun submitHttpAuthForwardsCredentialsAndClearsThePrompt() {
        val vm = BrowserViewModel(store, playback)
        var proceeded: Pair<String, String>? = null
        var cancelled = false
        vm.onHttpAuthRequest(
            host = "example.com",
            realm = "realm",
            proceed = { u, p -> proceeded = u to p },
            cancel = { cancelled = true },
        )

        vm.submitHttpAuth("alice", "s3cret")

        assertEquals("alice" to "s3cret", proceeded)
        assertTrue(!cancelled)
        assertEquals(null, vm.authPrompt.value)
    }

    @Test fun cancelHttpAuthCancelsTheChallengeAndClearsThePrompt() {
        val vm = BrowserViewModel(store, playback)
        var proceeded = false
        var cancelled = false
        vm.onHttpAuthRequest(
            host = "example.com",
            realm = "realm",
            proceed = { _, _ -> proceeded = true },
            cancel = { cancelled = true },
        )

        vm.cancelHttpAuth()

        assertTrue(cancelled)
        assertTrue(!proceeded)
        assertEquals(null, vm.authPrompt.value)
    }

    @Test fun aSecondHttpAuthRequestCancelsTheFirst() {
        val vm = BrowserViewModel(store, playback)
        var firstCancelled = false
        vm.onHttpAuthRequest("a.example", "r1", proceed = { _, _ -> }, cancel = { firstCancelled = true })

        vm.onHttpAuthRequest("b.example", "r2", proceed = { _, _ -> }, cancel = {})

        assertTrue(firstCancelled)
        assertEquals(HttpAuthPrompt("b.example", "r2"), vm.authPrompt.value)
    }

    @Test fun candidatesFlowIntoUiState() {
        val vm = BrowserViewModel(store, playback)

        store.addVideo("https://cdn.example/a.mp4", MediaKind.FILE, headers, "https://page.example/", "Title")
        store.addVideo(
            "https://cdn.example/master.m3u8",
            MediaKind.HLS,
            headers,
            "https://page.example/",
            "Title",
            isMaster = true,
        )
        vm.onPageTitle("Title")
        vm.onNavState(canBack = true, canForward = false)
        vm.onBlocked()
        vm.onBlocked()

        val state = vm.state.value
        assertEquals(
            listOf("https://cdn.example/master.m3u8", "https://cdn.example/a.mp4"),
            state.candidates.map { it.url },
        )
        assertEquals("Title", state.title)
        assertTrue(state.canGoBack)
        assertEquals(false, state.canGoForward)
        assertEquals(2, state.blockedCount)
    }

    @Test fun inPageUrlChangeKeepsWhatWasCaptured() {
        val vm = BrowserViewModel(store, playback)
        vm.onPageStarted("https://page.example/watch/1")
        store.addVideo("https://cdn.example/v.m3u8", MediaKind.HLS, headers, "https://page.example/watch/1", "One")

        vm.onUrlChanged("https://page.example/watch/1-canonical-slug?t=123") // history.replaceState

        assertEquals("https://page.example/watch/1-canonical-slug?t=123", vm.state.value.url)
        assertEquals(
            listOf("https://cdn.example/v.m3u8"),
            vm.state.value.candidates
                .map { it.url },
        )
    }

    @Test fun afterAnInPageRouteChangeTheNewEpisodeRanksFirst() {
        val vm = BrowserViewModel(store, playback)
        vm.onPageStarted("https://page.example/watch/1")
        store.addVideo(
            "https://cdn.example/ep1/master",
            MediaKind.HLS,
            headers,
            "https://page.example/watch/1",
            "",
            isMaster = true,
        )

        vm.onUrlChanged("https://page.example/watch/2") // history.pushState to the next episode
        store.addVideo(
            "https://cdn.example/ep2/master",
            MediaKind.HLS,
            headers,
            "https://page.example/watch/2",
            "",
            isMaster = true,
        )

        assertEquals(
            listOf("https://cdn.example/ep2/master", "https://cdn.example/ep1/master"),
            vm.state.value.candidates
                .map { it.url },
        )
    }

    @Test fun playOnTvActivatesTheSubtitleShowingOnThePage() {
        cast.connection.value = ConnectionState.Connected(CastDevice(id = "tv", name = "TV", host = "192.168.1.20"))
        val vm = BrowserViewModel(store, playback)
        store.addVideo(upstream.url("/vod.m3u8").toString(), MediaKind.HLS, headers, "https://page.example/", "Episode")
        store.addSubtitle(inlineSubtitle("en", showing = false))
        store.addSubtitle(inlineSubtitle("tr", showing = true))

        val load = playAndAwaitLoad(vm)

        assertEquals(listOf(load.subtitles[1].trackId), load.activeTrackIds)
    }

    @Test fun playOnTvLeavesSubtitlesOffWhenNoneIsShowing() {
        cast.connection.value = ConnectionState.Connected(CastDevice(id = "tv", name = "TV", host = "192.168.1.20"))
        val vm = BrowserViewModel(store, playback)
        store.addVideo(upstream.url("/vod.m3u8").toString(), MediaKind.HLS, headers, "https://page.example/", "Episode")
        store.addSubtitle(inlineSubtitle("en", showing = false))

        val load = playAndAwaitLoad(vm)

        assertEquals(1, load.subtitles.size)
        assertEquals(emptyList<Int>(), load.activeTrackIds)
    }

    private fun inlineSubtitle(
        lang: String,
        showing: Boolean,
    ) = SubtitleSource(
        id = lang,
        label = lang,
        language = lang,
        format = SubtitleFormat.VTT,
        inlineVtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n",
        showing = showing,
    )

    private fun playAndAwaitLoad(vm: BrowserViewModel): LoadRequest =
        runBlocking {
            vm.playOnTv(
                vm.state.value.candidates
                    .single(),
            )
            withTimeout(10_000) { while (cast.loadRequests.isEmpty()) delay(20) }
            cast.loadRequests.single()
        }

    @Test fun playOnTvDelegatesToController() =
        runBlocking {
            cast.connection.value = ConnectionState.Connected(CastDevice(id = "tv", name = "TV", host = "192.168.1.20"))
            val vm = BrowserViewModel(store, playback)
            store.addVideo(
                upstream.url("/vod.m3u8").toString(),
                MediaKind.HLS,
                headers,
                "https://page.example/",
                "Episode",
            )

            val errors = mutableListOf<PlaybackError>()
            val collector =
                controllerScope.launch(
                    start = CoroutineStart.UNDISPATCHED,
                ) { playback.errors.toList(errors) }

            vm.playOnTv(
                vm.state.value.candidates
                    .single(),
            )

            withTimeout(10_000) { while (cast.loadRequests.isEmpty() && errors.isEmpty()) delay(20) }
            collector.cancel()
            assertEquals(emptyList<PlaybackError>(), errors)
            val load = cast.loadRequests.single()
            assertEquals("Episode", load.title)
            assertTrue(load.url, load.url.startsWith("http://127.0.0.1:"))
        }
}
