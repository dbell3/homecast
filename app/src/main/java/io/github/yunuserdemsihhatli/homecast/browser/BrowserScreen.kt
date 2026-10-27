package io.github.yunuserdemsihhatli.homecast.browser

import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yunuserdemsihhatli.homecast.R
import io.github.yunuserdemsihhatli.homecast.capture.MediaKind
import io.github.yunuserdemsihhatli.homecast.capture.VideoCandidate
import io.github.yunuserdemsihhatli.homecast.ui.message
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The in-app browser: address bar, page, and a "Play on TV" bar once a video was captured.
 *
 * The WebView belongs to [host], which outlives this screen: leaving and coming back keeps the page as it was.
 * [requestedUrl] (a link shared with the app) is loaded once and then reported through [onRequestedUrlLoaded].
 * "Play on TV" hands the chosen video to [onPlayOnTv], which opens the remote and starts it from there, so the remote
 * reports how it went.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    host: BrowserHost,
    requestedUrl: String?,
    onRequestedUrlLoaded: () -> Unit,
    onOpenRemote: () -> Unit,
    onPlayOnTv: (VideoCandidate) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val authPrompt by viewModel.authPrompt.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var showChooser by remember { mutableStateOf(false) }

    val webViewGeneration by host.generation.collectAsStateWithLifecycle()

    LaunchedEffect(requestedUrl) {
        if (requestedUrl != null) {
            host.load(requestedUrl)
            onRequestedUrlLoaded()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.errors.collect { snackbar.showSnackbar(it.message(resources)) }
    }
    BackHandler(enabled = state.canGoBack, onBack = host::goBack)

    Scaffold(
        topBar = {
            AddressBar(
                state = state,
                onSubmit = { host.load(viewModel.onAddressSubmitted(it)) },
                onBack = host::goBack,
                onForward = host::goForward,
                onReload = host::reload,
                onOpenRemote = onOpenRemote,
            )
        },
        bottomBar = {
            if (state.candidates.isNotEmpty()) {
                VideoFoundBar(
                    candidates = state.candidates,
                    onPlay = onPlayOnTv,
                    onChoose = { showChooser = true },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            key(webViewGeneration) {
                AndroidView(
                    factory = { context -> FrameLayout(context).also(host::attachTo) },
                    modifier = Modifier.fillMaxSize(),
                    // Detach only: the host keeps the WebView (and its page) alive while the screen is away.
                    onRelease = host::detachFrom,
                )
            }
        }
    }

    if (showChooser) {
        ModalBottomSheet(onDismissRequest = { showChooser = false }) {
            CandidateList(
                candidates = state.candidates,
                onPick = {
                    showChooser = false
                    onPlayOnTv(it)
                },
            )
        }
    }

    authPrompt?.let { prompt ->
        HttpAuthDialog(
            prompt = prompt,
            onConfirm = viewModel::submitHttpAuth,
            onDismiss = viewModel::cancelHttpAuth,
        )
    }
}

/** A username/password dialog for an HTTP authentication challenge (e.g. a site behind Basic auth). */
@Composable
private fun HttpAuthDialog(
    prompt: HttpAuthPrompt,
    onConfirm: (user: String, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_auth_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.browser_auth_message, prompt.host, prompt.realm))
                TextField(
                    value = user,
                    onValueChange = { user = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.browser_auth_username)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.browser_auth_password)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation =
                        if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(
                                stringResource(
                                    if (passwordVisible) {
                                        R.string.browser_auth_hide_password
                                    } else {
                                        R.string.browser_auth_show_password
                                    },
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(user, password) }) {
                Text(stringResource(R.string.browser_auth_sign_in))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.browser_auth_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressBar(
    state: BrowserUiState,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onOpenRemote: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var text by remember(state.url) { mutableStateOf(state.url) }
    TopAppBar(
        title = {
            TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = {
                    Text(stringResource(R.string.browser_address_hint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions =
                    KeyboardActions(onGo = {
                        if (text.isNotBlank()) onSubmit(text)
                        focusManager.clearFocus()
                    }),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        actions = {
            IconButton(onClick = onBack, enabled = state.canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.browser_back))
            }
            IconButton(onClick = onForward, enabled = state.canGoForward) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.browser_forward),
                )
            }
            IconButton(onClick = onReload, enabled = state.url.isNotEmpty()) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.browser_reload))
            }
            IconButton(onClick = onOpenRemote) {
                Icon(
                    painterResource(R.drawable.ic_notification_cast),
                    contentDescription = stringResource(R.string.browser_open_remote),
                )
            }
        },
    )
}

@Composable
private fun VideoFoundBar(
    candidates: List<VideoCandidate>,
    onPlay: (VideoCandidate) -> Unit,
    onChoose: () -> Unit,
) {
    val best = candidates.first()
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.browser_video_found, hostOf(best.url)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (candidates.size > 1) {
                TextButton(onClick = onChoose) { Text(stringResource(R.string.browser_choose)) }
            }
            if (best.kind == MediaKind.DASH) {
                Button(onClick = {}, enabled = false) { Text(stringResource(R.string.browser_not_supported)) }
            } else {
                Button(onClick = { onPlay(best) }) { Text(stringResource(R.string.browser_play_on_tv)) }
            }
        }
    }
}

@Composable
private fun CandidateList(
    candidates: List<VideoCandidate>,
    onPick: (VideoCandidate) -> Unit,
) {
    Text(
        text = stringResource(R.string.browser_choose_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    LazyColumn(modifier = Modifier.navigationBarsPadding()) {
        items(candidates, key = { it.url }) { candidate ->
            val supported = candidate.kind != MediaKind.DASH
            ListItem(
                headlineContent = { Text(stringResource(kindLabel(candidate.kind))) },
                supportingContent = {
                    Text(
                        text =
                            if (supported) {
                                hostOf(candidate.url)
                            } else {
                                hostOf(candidate.url) + " · " + stringResource(R.string.browser_not_supported)
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    if (candidate.isMaster) {
                        Text(
                            text = stringResource(R.string.browser_badge_master),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                modifier = if (supported) Modifier.clickable { onPick(candidate) } else Modifier,
            )
        }
    }
}

private fun kindLabel(kind: MediaKind) =
    when (kind) {
        MediaKind.HLS -> R.string.video_kind_hls
        MediaKind.FILE -> R.string.video_kind_file
        MediaKind.DASH -> R.string.video_kind_dash
    }

private fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: url
