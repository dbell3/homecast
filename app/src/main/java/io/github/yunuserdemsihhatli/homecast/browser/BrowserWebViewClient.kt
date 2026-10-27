package io.github.yunuserdemsihhatli.homecast.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.yunuserdemsihhatli.homecast.capture.CandidateStore
import io.github.yunuserdemsihhatli.homecast.capture.Classification
import io.github.yunuserdemsihhatli.homecast.capture.MediaClassifier
import io.github.yunuserdemsihhatli.homecast.capture.MediaKind
import io.github.yunuserdemsihhatli.homecast.capture.SubtitleFormat
import io.github.yunuserdemsihhatli.homecast.capture.SubtitleSource
import io.github.yunuserdemsihhatli.homecast.capture.UpstreamHeaders
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Page events the browser screen reacts to. [onBlocked] arrives on WebView network threads; the rest on main. */
interface BrowserEvents {
    fun onPageStarted(url: String)

    /** The main frame's URL changed without a new page load (history.pushState, fragment) or a load committed. */
    fun onUrlChanged(url: String)

    fun onPageFinished()

    fun onPageTitle(title: String)

    fun onNavState(
        canBack: Boolean,
        canForward: Boolean,
    )

    fun onBlocked()

    /**
     * The page (host [host], realm [realm]) asked for HTTP authentication. Call [proceed] with the credentials the
     * user entered, or [cancel] to dismiss the challenge (the page then shows its unauthorised response). Exactly one
     * of the two must be called. Arrives on the main thread.
     */
    fun onHttpAuthRequest(
        host: String,
        realm: String,
        proceed: (user: String, password: String) -> Unit,
        cancel: () -> Unit,
    )
}

/**
 * Decides what the WebView may load and records the media it sees.
 *
 * Requests to [blocklist] hosts get an empty 204 response. Every other request is classified by URL and, when it is
 * a video or subtitle, captured into [store] with the headers the media server expects (request headers, falling back
 * to the page URL as Referer, marked as a fallback, the WebView user agent and the page's cookies). Main-frame
 * navigations go through [redirectGuard]. Messages from `capture.js` arrive in [onCaptureMessage]; a media URL they
 * report is "network confirmed", and its headers may replace a guessed Referer, only when [RequestLedger] ties it to
 * a request made for the sending frame's origin (see there for the residual risk).
 *
 * The bundled blocklist loads off the main thread. Until it is ready, [shouldInterceptRequest] (which runs on a
 * WebView network thread) waits for it once, up to [BLOCKLIST_WAIT_MILLIS]; if it is still not ready, requests load
 * unblocked rather than stalling the page. A blocklist that fails to load is treated as empty.
 *
 * The primary constructor suppresses MissingOnRenderProcessGone, the narrowest scope covering the `WebViewClient()`
 * super-constructor call that lint flags ("WebViewClient should implement `onRenderProcessGone` to handle render
 * process crashes or out-of-memory errors. Otherwise, the app will crash when the render process is shut down."),
 * although [onRenderProcessGone] is implemented.
 */
class BrowserWebViewClient
    @SuppressLint("MissingOnRenderProcessGone")
    constructor(
        private val blocklist: Deferred<HostBlocklist>,
        private val redirectGuard: RedirectGuard,
        private val store: CandidateStore,
        private val events: BrowserEvents,
        private val userAgent: String,
        private val cookies: (String) -> String? = { CookieManager.getInstance().getCookie(it) },
        private val onRendererGone: () -> Unit = {},
    ) : WebViewClient() {
        @Volatile private var pageUrl: String? = null

        @Volatile private var pageTitle: String = ""

        @Volatile private var loadedBlocklist: HostBlocklist? = null

        @Volatile private var waitedForBlocklist = false

        /** A video opened directly as the page: captured once its page has started (starting clears the store). */
        @Volatile private var pendingMainFrameVideo: (() -> Unit)? = null

        /** Recent requests of the current document, to tie what page script reports to what was fetched. */
        private val ledger = RequestLedger()

        /** How to reach each frame's `capture.js` (newest last, bounded: frames of old pages simply ignore it). */
        private val frameBridges = LinkedHashSet<(String) -> Unit>()

        /** Set when document-start scripts are unsupported: injected into the main frame on every page start. */
        @Volatile var pageStartScript: String? = null

        /**
         * The user asked for a navigation (address bar, history, shared link) to [targetUrl] (null for history): its
         * redirects may pass the guard.
         */
        fun onUserNavigation(targetUrl: String? = null) = redirectGuard.onUserNavigation(targetUrl)

        /** A navigation became a download (nothing is downloaded): its redirect allowance ends. */
        fun onDownloadStarted() = redirectGuard.onNavigationEnded()

        /** A frame's `capture.js` can be messaged through [post] (its JavaScriptReplyProxy). Main thread. */
        fun onFrameBridge(post: (String) -> Unit) {
            if (!frameBridges.add(post)) return
            if (frameBridges.size > MAX_FRAME_BRIDGES) frameBridges.remove(frameBridges.first())
        }

        /** Pauses every `<video>`/`<audio>` of the page in [view], in all frames; the TV plays the video now. */
        fun pausePageMedia(view: WebView) {
            frameBridges.toList().forEach { post ->
                try {
                    post(CaptureScript.PAUSE_MESSAGE)
                } catch (e: IllegalStateException) {
                    Log.d(TAG, "Frame gone before it could be paused", e)
                }
            }
            view.evaluateJavascript(CaptureScript.PAUSE_SCRIPT, null)
        }

        /** Touches on the WebView count as user gestures for [RedirectGuard]. */
        fun onUserGesture() = redirectGuard.onUserGesture()

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url.toString()
            val httpUrl = url.toHttpUrlOrNull() ?: return null
            if (currentBlocklist()?.isBlocked(httpUrl.host) == true) {
                events.onBlocked()
                return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), null)
            }
            val headers = request.requestHeaders.orEmpty()
            val referer = headers.valueOf("Referer")
            ledger.record(url, request.isForMainFrame, referer)
            val classification = MediaClassifier.classifyUrl(url) ?: return null
            val upstream =
                UpstreamHeaders(
                    referer = referer ?: pageUrl,
                    origin = headers.valueOf("Origin"),
                    userAgent = headers.valueOf("User-Agent") ?: userAgent,
                    cookie = cookies(url),
                )
            val capture = { capture(url, classification, upstream, fallbackHeaders = referer == null) }
            if (request.isForMainFrame) pendingMainFrameVideo = capture else capture()
            return null
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val block =
                redirectGuard.shouldBlockNavigation(
                    currentUrl = pageUrl,
                    targetUrl = request.url.toString(),
                    isMainFrame = request.isForMainFrame,
                    hasGesture = request.hasGesture(),
                    isRedirect = request.isRedirect,
                )
            if (block) events.onBlocked()
            return block
        }

        override fun onPageStarted(
            view: WebView,
            url: String,
            favicon: Bitmap?,
        ) {
            pageUrl = url
            pageTitle = ""
            // Called when the navigation commits: its redirects are over.
            redirectGuard.onNavigationCommitted()
            // A new document: requests made for the previous one no longer confirm anything.
            ledger.clear()
            ledger.record(url, isMainFrame = true, referer = null)
            events.onPageStarted(url)
            pendingMainFrameVideo?.invoke()
            pendingMainFrameVideo = null
            pageStartScript?.let { view.evaluateJavascript(it, null) }
            events.onNavState(view.canGoBack(), view.canGoForward())
        }

        override fun onPageFinished(
            view: WebView,
            url: String,
        ) {
            view.title?.let(::onReceivedTitle)
            redirectGuard.onNavigationEnded()
            events.onPageFinished()
            events.onNavState(view.canGoBack(), view.canGoForward())
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) redirectGuard.onNavigationEnded()
        }

        /**
         * An HTTP authentication challenge (e.g. 401 Basic): ask the user for credentials. Without this override the
         * WebView default cancels the challenge and the protected page never loads. [handler] is wrapped in plain
         * lambdas so the screen can prompt without touching platform types. Called on the main thread.
         */
        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String,
            realm: String,
        ) {
            events.onHttpAuthRequest(
                host = host,
                realm = realm,
                proceed = { user, password -> handler.proceed(user, password) },
                cancel = { handler.cancel() },
            )
        }

        /** A crashed or killed renderer must not take the app down: the owner replaces the dead WebView. */
        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            Log.w(TAG, "WebView renderer gone (crashed: ${detail.didCrash()})")
            onRendererGone()
            return true
        }

        override fun doUpdateVisitedHistory(
            view: WebView,
            url: String,
            isReload: Boolean,
        ) {
            pageUrl = url
            events.onUrlChanged(url)
            events.onNavState(view.canGoBack(), view.canGoForward())
        }

        /** From WebChromeClient.onReceivedTitle: names the captured videos and the screen title. */
        fun onReceivedTitle(title: String) {
            pageTitle = title
            events.onPageTitle(title)
        }

        /**
         * A raw message from `capture.js`, sent by a frame of [sourceOrigin] (null when the bridge cannot tell);
         * untrusted, so it is validated and only ever stored as data.
         */
        fun onCaptureMessage(
            message: String,
            sourceOrigin: String?,
        ) {
            val msg = CaptureMessage.parse(message, sourceOrigin) ?: return
            val referer = msg.frame ?: pageUrl
            val confirmed = ledger.confirms(msg.url, sourceOrigin)
            // Headers from a message only count as real (able to replace a guessed Referer) when tied to the request.
            val fallback = msg.frame == null || !ledger.mayUpgradeHeaders(msg.url, sourceOrigin, pageUrl)
            val headers =
                UpstreamHeaders(
                    referer = referer,
                    origin = (msg as? CaptureMessage.Hls)?.let { crossOrigin(it.url, referer) },
                    userAgent = userAgent,
                    cookie = cookies(msg.url),
                )
            when (msg) {
                is CaptureMessage.Hls -> {
                    store.addVideo(
                        url = msg.url,
                        kind = MediaKind.HLS,
                        headers = headers,
                        pageUrl = pageUrl.orEmpty(),
                        pageTitle = pageTitle,
                        isMaster = msg.master,
                        fallbackHeaders = fallback,
                        networkConfirmed = confirmed,
                    )
                }

                is CaptureMessage.Video -> {
                    val kind = (MediaClassifier.classifyUrl(msg.url) as? Classification.Video)?.kind ?: MediaKind.FILE
                    store.addVideo(
                        url = msg.url,
                        kind = kind,
                        headers = headers,
                        pageUrl = pageUrl.orEmpty(),
                        pageTitle = pageTitle,
                        fallbackHeaders = fallback,
                        networkConfirmed = confirmed,
                    )
                }

                is CaptureMessage.SubtitlePlaylist -> {
                    // Changes what others captured: only from a trusted reporter (see ReportTrust).
                    if (ReportTrust.isTrusted(confirmed, sourceOrigin, pageUrl)) {
                        store.addSubtitlePlaylist(msg.url, reporterOrigin = sourceOrigin ?: pageUrl)
                    }
                }

                is CaptureMessage.Track -> {
                    val format =
                        (MediaClassifier.classifyUrl(msg.url) as? Classification.Subtitle)?.format ?: SubtitleFormat.VTT
                    // Only a trusted report gets page priority (replacing an intercepted copy, outranking it in the cap).
                    val trusted = ReportTrust.isTrusted(confirmed, sourceOrigin, pageUrl)
                    store.addSubtitle(
                        subtitle(msg.url, format, headers, msg.label, msg.language).copy(reportedByPage = trusted),
                    )
                    store.setSubtitleShowing(id = msg.url, showing = msg.showing)
                }
            }
        }

        private fun capture(
            url: String,
            classification: Classification,
            headers: UpstreamHeaders,
            fallbackHeaders: Boolean,
        ) {
            when (classification) {
                is Classification.Video -> {
                    store.addVideo(
                        url = url,
                        kind = classification.kind,
                        headers = headers,
                        pageUrl = pageUrl.orEmpty(),
                        pageTitle = pageTitle,
                        fallbackHeaders = fallbackHeaders,
                        networkConfirmed = true,
                    )
                }

                is Classification.Subtitle -> {
                    store.addSubtitle(subtitle(url, classification.format, headers, label = "", language = null))
                }
            }
        }

        private fun subtitle(
            url: String,
            format: SubtitleFormat,
            headers: UpstreamHeaders,
            label: String,
            language: String?,
        ) = SubtitleSource(
            id = url,
            label = label.ifBlank { url.substringBefore('?').substringAfterLast('/') },
            language = language,
            format = format,
            url = url,
            headers = headers,
        )

        /** The blocklist, waiting for it once (bounded) while it is still loading. */
        private fun currentBlocklist(): HostBlocklist? {
            loadedBlocklist?.let { return it }
            if (waitedForBlocklist && !blocklist.isCompleted) return null
            waitedForBlocklist = true
            val loaded =
                try {
                    runBlocking { withTimeoutOrNull(BLOCKLIST_WAIT_MILLIS) { blocklist.await() } }
                } catch (e: Exception) {
                    Log.w(TAG, "Blocklist failed to load; nothing is blocked", e)
                    HostBlocklist(emptySet())
                }
            loadedBlocklist = loaded
            return loaded
        }

        private companion object {
            const val TAG = "BrowserWebViewClient"
            const val BLOCKLIST_WAIT_MILLIS = 2_000L
            const val MAX_FRAME_BRIDGES = 64

            fun Map<String, String>.valueOf(name: String): String? =
                entries
                    .firstOrNull {
                        it.key.equals(name, ignoreCase = true)
                    }?.value

            /** The Origin a fetch from [frameUrl] sends to [url]: present only when the two origins differ. */
            fun crossOrigin(
                url: String,
                frameUrl: String?,
            ): String? {
                val target = url.toHttpUrlOrNull() ?: return null
                val frame = frameUrl?.toHttpUrlOrNull() ?: return null
                return if (target.webOrigin() == frame.webOrigin()) null else frame.webOrigin()
            }
        }
    }
