package io.github.yunuserdemsihhatli.homecast.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.yunuserdemsihhatli.homecast.HomeCastApp
import io.github.yunuserdemsihhatli.homecast.capture.CandidateStore
import io.github.yunuserdemsihhatli.homecast.capture.VideoCandidate
import io.github.yunuserdemsihhatli.homecast.playback.PlaybackController
import io.github.yunuserdemsihhatli.homecast.playback.PlaybackError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLEncoder

data class BrowserUiState(
    val url: String = "",
    val title: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val loading: Boolean = false,
    val candidates: List<VideoCandidate> = emptyList(),
    val blockedCount: Int = 0,
)

/** An HTTP authentication challenge the screen should show a credential dialog for ([host]/[realm] are display only). */
data class HttpAuthPrompt(
    val host: String,
    val realm: String,
)

/** State of the browser screen; receives page events from [BrowserWebViewClient] and hands videos to the TV. */
class BrowserViewModel(
    private val store: CandidateStore,
    private val playback: PlaybackController,
) : ViewModel(),
    BrowserEvents {
    private val page = MutableStateFlow(BrowserUiState())

    val state: StateFlow<BrowserUiState> =
        combine(page, store.candidates) { p, candidates -> p.copy(candidates = candidates) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, BrowserUiState(candidates = store.candidates.value))

    /** Failures of "Play on TV", to show to the user. */
    val errors: Flow<PlaybackError> = playback.errors

    private val _authPrompt = MutableStateFlow<HttpAuthPrompt?>(null)

    /** The HTTP authentication challenge the screen should prompt for, or null when there is none. */
    val authPrompt: StateFlow<HttpAuthPrompt?> = _authPrompt.asStateFlow()

    /** Callbacks of the pending challenge; main thread only, matched to [_authPrompt]. */
    private var pendingAuth: PendingAuth? = null

    private class PendingAuth(
        val proceed: (String, String) -> Unit,
        val cancel: () -> Unit,
    )

    /** Returns the URL to load: http(s) as-is, something host-like ("foo.com/x") over https, anything else a search. */
    fun onAddressSubmitted(input: String): String {
        val text = input.trim()
        return when {
            text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) -> text
            text.isNotEmpty() && '.' in text && text.none { it.isWhitespace() } -> "https://$text"
            else -> SEARCH_URL + URLEncoder.encode(text, Charsets.UTF_8.name())
        }
    }

    /** A new page clears what was captured on the previous one; a reload of the same URL keeps it. */
    override fun onPageStarted(url: String) {
        val isNewPage = !samePage(url, page.value.url)
        if (isNewPage) store.onPageChanged(url)
        page.update {
            if (isNewPage) {
                it.copy(url = url, title = "", loading = true, blockedCount = 0)
            } else {
                it.copy(url = url, loading = true)
            }
        }
    }

    /**
     * The address changed inside the page (history.pushState/replaceState, fragment) or a load committed. Only the
     * shown address follows: the same document may keep playing the same video (canonical slug, `?t=123`), so what
     * was captured stays. After an in-page route change a new episode's media is captured later and ranks first.
     */
    override fun onUrlChanged(url: String) {
        page.update { it.copy(url = url) }
    }

    override fun onPageFinished() {
        page.update { it.copy(loading = false) }
    }

    override fun onPageTitle(title: String) {
        page.update { it.copy(title = title) }
    }

    override fun onNavState(
        canBack: Boolean,
        canForward: Boolean,
    ) {
        page.update { it.copy(canGoBack = canBack, canGoForward = canForward) }
    }

    /** Called from WebView network threads for every blocked request or navigation. */
    override fun onBlocked() {
        page.update { it.copy(blockedCount = it.blockedCount + 1) }
    }

    /** An HTTP auth challenge: cancel any one still pending, then prompt for this one. Main thread. */
    override fun onHttpAuthRequest(
        host: String,
        realm: String,
        proceed: (user: String, password: String) -> Unit,
        cancel: () -> Unit,
    ) {
        pendingAuth?.cancel?.invoke()
        pendingAuth = PendingAuth(proceed, cancel)
        _authPrompt.value = HttpAuthPrompt(host, realm)
    }

    /** The user entered credentials in the auth dialog: authenticate the challenge and dismiss the dialog. */
    fun submitHttpAuth(
        user: String,
        password: String,
    ) {
        val auth = pendingAuth ?: return
        pendingAuth = null
        _authPrompt.value = null
        auth.proceed(user, password)
    }

    /** The user dismissed the auth dialog: cancel the challenge (the page shows its unauthorised response). */
    fun cancelHttpAuth() {
        val auth = pendingAuth ?: return
        pendingAuth = null
        _authPrompt.value = null
        auth.cancel()
    }

    /** Sends [candidate] to the TV with every captured subtitle, switching on the one the page is showing. */
    fun playOnTv(candidate: VideoCandidate) {
        val subtitles = store.subtitles.value
        val showing = subtitles.indexOfFirst { it.showing }.takeIf { it >= 0 }
        viewModelScope.launch { playback.playOnTv(candidate, subtitles, activeSubtitleIndex = showing) }
    }

    companion object {
        private const val SEARCH_URL = "https://duckduckgo.com/?q="

        private fun samePage(
            a: String,
            b: String,
        ) = a.substringBefore('#') == b.substringBefore('#')

        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val graph = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as HomeCastApp).graph
                    BrowserViewModel(graph.candidateStore, graph.playback)
                }
            }
    }
}
