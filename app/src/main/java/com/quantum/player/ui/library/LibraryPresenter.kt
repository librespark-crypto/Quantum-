package com.quantum.player.ui.library

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.quantum.player.library.LastPlayed
import com.quantum.player.library.LastPlayedStore
import com.quantum.player.library.MediaLibraryRepository
import com.quantum.player.library.ThumbnailLoader
import com.quantum.player.library.VideoEntry
import com.quantum.player.library.VideoFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Immutable snapshot the [LibraryScreen] renders.
 *
 * [rootVolumes] are the per-volume folder cards shown at the top level;
 * [currentFolder] is the folder the user drilled into (null = root view).
 * The [onOpenFolder] / [onNavigateBack] lambdas default to no-ops and are
 * replaced by the presenter with bound copies.
 */
data class LibraryUiState(
    val loading: Boolean = true,
    val hasPermission: Boolean = false,
    val root: VideoFolder = VideoFolder.ROOT,
    val currentFolder: VideoFolder? = null,
    val lastPlayed: LastPlayed = LastPlayed(),
    val onOpenFolder: (VideoFolder) -> Unit = {},
    val onNavigateBack: () -> Unit = {}
) {
    /** Per-volume roots (Internal storage + removable volumes) for the top grid. */
    val rootVolumes: List<VideoFolder> get() = root.folders

    /** Every video in a folder subtree in playback order (prev/next queue). */
    fun flatten(folder: VideoFolder): List<VideoEntry> = buildList {
        addAll(folder.videos)
        folder.folders.sortedBy { it.name }.forEach { addAll(flatten(it)) }
    }
}

/**
 * State holder + side-effect driver for the file explorer.
 *
 * Owns the [MediaLibraryRepository] (MediaStore scan), [LastPlayedStore]
 * (DataStore resume record) and [ThumbnailLoader] (keyframe cache), and exposes
 * plain callbacks the screen invokes. This replaces a Settings screen: nothing
 * here is a static configuration menu, it is the live library + resume state.
 */
class LibraryPresenter(
    private val context: Context,
    private val repository: MediaLibraryRepository,
    val lastPlayedStore: LastPlayedStore,
    val thumbnailLoader: ThumbnailLoader,
    private val scope: CoroutineScope
) {
    // Backing value; always exposed through [state] so callbacks stay bound.
    private var rawState by mutableStateOf(LibraryUiState(loading = true))

    /** Observed snapshot with navigation callbacks attached. */
    val state: LibraryUiState
        get() = rawState.copy(
            onOpenFolder = ::openFolder,
            onNavigateBack = ::navigateBack
        )

    private var navigationStack = ArrayDeque<VideoFolder>()

    /** Re-scan MediaStore and refresh the resume record. */
    fun refresh(hasPermission: Boolean) {
        rawState = rawState.copy(loading = hasPermission, hasPermission = hasPermission)
        if (!hasPermission) return
        scope.launch {
            val root = withContext(Dispatchers.IO) { repository.loadFolders() }
            val last = withContext(Dispatchers.IO) { lastPlayedStore.lastPlayed.first() }
            rawState = rawState.copy(
                loading = false,
                hasPermission = true,
                root = root,
                lastPlayed = last
            )
        }
    }

    /** Refresh only the last-played FAB record (after returning from playback). */
    fun refreshLastPlayed() {
        scope.launch {
            val last = withContext(Dispatchers.IO) { lastPlayedStore.lastPlayed.first() }
            rawState = rawState.copy(lastPlayed = last)
        }
    }

    private fun openFolder(folder: VideoFolder) {
        rawState.currentFolder?.let { navigationStack.addLast(it) }
        rawState = rawState.copy(currentFolder = folder)
    }

    private fun navigateBack() {
        val previous = navigationStack.removeLastOrNull()
        rawState = rawState.copy(currentFolder = previous)
    }
}

/**
 * Remember a [LibraryPresenter], reloading whenever [reloadTick] changes
 * (returning from the player, a rename/delete, or a permission grant).
 */
@Composable
fun rememberLibraryPresenter(
    context: Context,
    repository: MediaLibraryRepository,
    lastPlayedStore: LastPlayedStore,
    thumbnailLoader: ThumbnailLoader,
    hasPermission: Boolean,
    reloadTick: Long
): LibraryPresenter {
    val presenter = remember {
        LibraryPresenter(
            context = context,
            repository = repository,
            lastPlayedStore = lastPlayedStore,
            thumbnailLoader = thumbnailLoader,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        )
    }

    // Re-scan on first composition and whenever reloadTick changes.
    LaunchedEffect(hasPermission, reloadTick) {
        presenter.refresh(hasPermission)
    }

    // Also refresh when the screen is resumed (returning from the player).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                presenter.refresh(hasPermission)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return presenter
}
