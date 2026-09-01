package com.quantum.player.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.quantum.player.core.PlaybackEngine
import com.quantum.player.library.LastPlayedStore
import com.quantum.player.library.MediaLibraryRepository
import com.quantum.player.library.PlaybackHistoryStore
import com.quantum.player.library.ThumbnailLoader
import com.quantum.player.library.VideoEntry
import com.quantum.player.model.MediaItem
import com.quantum.player.pip.QuantumPiPHandler
import com.quantum.player.service.QuantumBackgroundService
import com.quantum.player.ui.library.LibraryScreen
import com.quantum.player.ui.library.rememberLibraryPresenter
import com.quantum.player.ui.screen.PlayerScreen
import com.quantum.player.ui.theme.QuantumTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Main activity: folder-grouped media library plus fullscreen playback.
 *
 * Architecture:
 *  - The browser is the [LibraryScreen] (MediaStore folder hierarchy, resume
 *    FAB). There is no Settings screen - all player configuration lives in the
 *    player HUD / bottom sheets.
 *  - Playback lives in the application-scoped [PlaybackEngine]; this activity
 *    only attaches a video output while it is foregrounded.
 *  - Last played URI + timestamp are persisted in [LastPlayedStore]
 *    (DataStore) and updated from engine position ticks while playing.
 */
class QuantumPlayerActivity : ComponentActivity() {

    private val engine: PlaybackEngine
        get() = (application as QuantumApplication).playbackEngine

    private var pipHandler: QuantumPiPHandler? = null

    private var currentItem by mutableStateOf<MediaItem?>(null)
    private var playlist by mutableStateOf<List<MediaItem>>(emptyList())
    private var startPositionMs by mutableLongStateOf(0L)
    private var reloadTick by mutableLongStateOf(0L)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pipHandler = QuantumPiPHandler(this, engine)
        pipHandler?.restorePiPState(savedInstanceState)?.let { currentItem = it }
        handleIncomingIntent(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            QuantumTheme(forceDark = currentItem != null) {
                val item = currentItem
                if (item == null) {
                    LibraryRoute(
                        onPlayVideo = { entry, queue -> playFromLibrary(entry, queue) },
                        onOpenStream = { url ->
                            currentItem = mediaItemFromUri(url)
                            startPositionMs = 0L
                        },
                        onResumeLast = { last -> resumeLastPlayed(last) },
                        onReloadTick = reloadTick
                    )
                } else {
                    PlayerRoute(
                        engine = engine,
                        item = item,
                        playlist = playlist,
                        startPositionMs = startPositionMs,
                        onClose = {
                            currentItem = null
                            playlist = emptyList()
                            startPositionMs = 0L
                            reloadTick++
                        },
                        onEnterPiP = { pipHandler?.enterPiP(item) },
                        onPlayItem = { newItem, resumeAt ->
                            currentItem = newItem
                            startPositionMs = resumeAt
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        @Suppress("DEPRECATION")
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    // ------------------------------------------------------------------
    // Library → player wiring
    // ------------------------------------------------------------------

    private fun playFromLibrary(entry: VideoEntry, queue: List<VideoEntry>) {
        playlist = queue.map { it.toMediaItem() }
        startPositionMs = entry.resumePositionMs
        currentItem = entry.toMediaItem()
    }

    private fun VideoEntry.toMediaItem(): MediaItem = MediaItem(
        id = uri.toString(),
        uri = uri.toString(),
        title = title.ifBlank { fileName },
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        container = mimeType.ifBlank { null },
        metadata = buildMap {
            put("file_path", path)
            put("file_size", sizeBytes)
        }
    )

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data?.toString() ?: intent.getStringExtra(
                    QuantumBackgroundService.EXTRA_URI
                )
                if (!uri.isNullOrBlank()) {
                    currentItem = mediaItemFromUri(
                        uri,
                        intent.getStringExtra(QuantumBackgroundService.EXTRA_TITLE)
                    )
                }
            }

            QuantumBackgroundService.ACTION_START -> {
                val uri = intent.getStringExtra(QuantumBackgroundService.EXTRA_URI)
                if (!uri.isNullOrBlank()) {
                    currentItem = mediaItemFromUri(
                        uri,
                        intent.getStringExtra(QuantumBackgroundService.EXTRA_TITLE)
                    )
                }
            }
        }
    }

    private fun mediaItemFromUri(uri: String, title: String? = null): MediaItem = MediaItem(
        id = uri,
        uri = uri,
        title = title ?: displayNameFor(uri)
    )

    /** FAB action: launch the player on the last played URI, resuming at its timestamp. */
    private fun resumeLastPlayed(last: com.quantum.player.library.LastPlayed) {
        if (!last.hasMedia) return
        playlist = emptyList()
        startPositionMs = last.positionMs
        currentItem = MediaItem(
            id = last.uri,
            uri = last.uri,
            title = last.title.ifBlank { displayNameFor(last.uri) },
            durationMs = last.durationMs,
            metadata = buildMap { put("file_path", last.path) }
        )
    }

    private fun displayNameFor(uri: String): String =
        runCatching { Uri.parse(uri).lastPathSegment?.substringAfterLast('/') }.getOrNull()
            ?: uri.substringAfterLast('/')

    // ---- Picture in picture ----

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        currentItem?.let { pipHandler?.enterPiP(it) }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        @Suppress("DEPRECATION")
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipHandler?.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pipHandler?.savePiPState(outState)
        outState.putString(STATE_CURRENT_URI, currentItem?.uri.orEmpty())
        outState.putString(STATE_CURRENT_TITLE, currentItem?.title.orEmpty())
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStop() {
        if (pipHandler?.isInPiPMode != true) {
            engine.setVideoTextureView(null)
        }
        super.onStop()
    }

    override fun onDestroy() {
        engine.setVideoTextureView(null)
        pipHandler = null
        super.onDestroy()
    }

    private companion object {
        const val STATE_CURRENT_URI = "state_current_uri"
        const val STATE_CURRENT_TITLE = "state_current_title"
    }
}

// ----------------------------------------------------------------------
// Compose routes
// ----------------------------------------------------------------------

/** Library route: permission gate, presenter wiring, resume FAB, sheet actions. */
@androidx.compose.runtime.Composable
private fun ComponentActivity.LibraryRoute(
    onPlayVideo: (VideoEntry, List<VideoEntry>) -> Unit,
    onOpenStream: (String) -> Unit,
    onResumeLast: (com.quantum.player.library.LastPlayed) -> Unit,
    onReloadTick: Long
) {
    val context = this
    val scope = rememberCoroutineScope()
    val app = application as QuantumApplication
    val repository = remember {
        MediaLibraryRepository(context, app.database)
    }
    val lastPlayedStore = remember { LastPlayedStore(context) }
    val thumbnailLoader = remember { ThumbnailLoader(context) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_VIDEO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    val presenter = rememberLibraryPresenter(
        context = context,
        repository = repository,
        lastPlayedStore = lastPlayedStore,
        thumbnailLoader = thumbnailLoader,
        hasPermission = hasPermission,
        reloadTick = onReloadTick
    )

    LibraryScreen(
        state = presenter.state,
        thumbnailLoader = thumbnailLoader,
        onPlayVideo = onPlayVideo,
        onResumeLastPlayed = {
            scope.launch {
                val last = withContext(Dispatchers.IO) {
                    kotlinx.coroutines.flow.first(lastPlayedStore.lastPlayed)
                }
                onResumeLast(last)
            }
        },
        onOpenStream = onOpenStream,
        onRequestPermission = {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_VIDEO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            permissionLauncher.launch(permission)
        },
        onRefresh = {
            presenter.refresh(hasPermission)
        }
    )
}

/** Player route: renders [PlayerScreen] and persists last-played progress. */
@androidx.compose.runtime.Composable
private fun ComponentActivity.PlayerRoute(
    engine: PlaybackEngine,
    item: MediaItem,
    playlist: List<MediaItem>,
    startPositionMs: Long,
    onClose: () -> Unit,
    onEnterPiP: () -> Unit,
    onPlayItem: (MediaItem, Long) -> Unit
) {
    val context = this
    val scope = rememberCoroutineScope()
    val app = application as QuantumApplication
    val lastPlayedStore = remember { LastPlayedStore(context) }
    val historyStore = remember { PlaybackHistoryStore(app.database) }
    var subtitleUri by remember { mutableStateOf<String?>(null) }

    val subtitlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Persistable read grant so the file survives restarts.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            subtitleUri = uri.toString()
            scope.launch {
                engine.addExternalSubtitle(
                    uri = uri.toString(),
                    mimeType = contentResolver.getType(uri)
                )
            }
        }
    }

    // Record last-played + Room history when playback starts.
    LaunchedEffect(item.id) {
        lastPlayedStore.onPlaybackStarted(
            uri = item.uri,
            title = item.displayName,
            path = (item.metadata["file_path"] as? String).orEmpty(),
            positionMs = startPositionMs
        )
        if (historyStore.isLocalUri(item.uri)) {
            historyStore.recordStarted(item.uri, item.displayName)
        }
    }

    // Persist progress every 5 seconds while playing (resume FAB + NEW badges).
    LaunchedEffect(item.id) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            val position = engine.currentPosition
            val duration = engine.duration.coerceAtLeast(0)
            if (engine.isPlaying) {
                lastPlayedStore.saveProgress(position, duration)
                if (historyStore.isLocalUri(item.uri)) {
                    historyStore.recordProgress(item.uri, item.displayName, position, duration)
                }
            }
        }
    }

    PlayerScreen(
        engine = engine,
        mediaItem = item,
        playlist = playlist,
        startPositionMs = startPositionMs,
        onClose = onClose,
        onEnterPiP = onEnterPiP,
        onPlayItem = onPlayItem,
        onToggleBackgroundAudioMode = {
            QuantumBackgroundService.start(
                context,
                item.uri,
                item.displayName
            )
        },
        onPickSubtitleFile = {
            subtitlePicker.launch(arrayOf("text/srt", "text/vtt", "application/x-subrip", "*/*"))
        },
        showControlsInitially = pipHandler?.isInPiPMode != true
    )
}
