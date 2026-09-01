package com.quantum.player.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.quantum.player.library.FileActions
import com.quantum.player.library.LastPlayed
import com.quantum.player.library.ThumbnailLoader
import com.quantum.player.library.VideoEntry
import com.quantum.player.library.VideoFolder
import java.io.File

/**
 * Root of the mpvRx / MX Player style file explorer.
 *
 * Shows a folder hierarchy (MediaStore grouped by parent directory). Tapping a
 * folder drills in; folder cards carry recursive video count, cumulative
 * duration and a red "new videos" pill. Video rows carry keyframe thumbnails,
 * a duration badge, a NEW tag and an inline ⋮ menu (Info / Rename / Share /
 * Delete). A resume FAB tracks the last played URI + timestamp and launches the
 * player straight into the saved position.
 *
 * There is deliberately **no settings affordance here**: every player option
 * lives in the player HUD.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    thumbnailLoader: ThumbnailLoader,
    onPlayVideo: (VideoEntry, List<VideoEntry>) -> Unit,
    onResumeLastPlayed: () -> Unit,
    onOpenStream: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var streamUrl by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.currentFolder?.takeUnless { it.isRoot }?.name
                            ?: "Quantum Library",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (state.currentFolder != null && !state.currentFolder.isRoot) {
                        IconButton(onClick = { state.onNavigateBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ResumeFab(lastPlayed = state.lastPlayed, onClick = onResumeLastPlayed)
        }
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {

            OutlinedTextField(
                value = streamUrl,
                onValueChange = { streamUrl = it },
                label = { Text("Open network stream (URL)") },
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = {
                        if (streamUrl.isNotBlank()) {
                            onOpenStream(streamUrl.trim())
                            streamUrl = ""
                        }
                    }) { Text("Open") }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search in this folder") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )

            when {
                !state.hasPermission -> PermissionPrompt(onRequestPermission = onRequestPermission)

                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.6f))
                }

                else -> {
                    val folder = state.currentFolder
                    if (folder == null || folder.isRoot) {
                        // Top level: volume roots as folder cards.
                        val volumes = state.rootVolumes
                        if (volumes.isEmpty()) {
                            EmptyLibrary()
                        } else {
                            FolderGrid(
                                folders = volumes.filter { it.matches(searchQuery) },
                                onOpenFolder = state.onOpenFolder
                            )
                        }
                    } else {
                        FolderContentView(
                            folder = folder,
                            searchQuery = searchQuery,
                            allVideosInFolder = state.flatten(folder),
                            thumbnailLoader = thumbnailLoader,
                            onOpenFolder = state.onOpenFolder,
                            onPlayVideo = onPlayVideo,
                            onAction = onRefresh
                        )
                    }
                }
            }
        }
    }
}

/** Does a folder subtree match [query] (by folder or video name)? */
private fun VideoFolder.matches(query: String): Boolean {
    if (query.isBlank()) return true
    if (name.contains(query, ignoreCase = true)) return true
    return videos.any { it.title.contains(query, ignoreCase = true) } ||
        folders.any { it.matches(query) }
}

@Composable
private fun PermissionPrompt(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Storage permission is needed to browse videos",
            style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRequestPermission) { Text("Grant permission") }
    }
}

@Composable
private fun EmptyLibrary() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No videos found on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ----------------------------------------------------------------------
// Folder cards
// ----------------------------------------------------------------------

/**
 * Grid of folder cards: directory name, recursive video count, cumulative
 * duration, and the red unplayed badge.
 */
@Composable
private fun FolderGrid(
    folders: List<VideoFolder>,
    onOpenFolder: (VideoFolder) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(folders, key = { it.path }) { folder ->
            FolderCard(folder = folder, onClick = { onOpenFolder(folder) })
        }
    }
}

@Composable
private fun FolderCard(folder: VideoFolder, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
            )
            if (folder.newCount > 0) {
                NewPill(
                    count = folder.newCount,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${folder.totalVideoCount} videos · " +
                    FileActions.formatDuration(folder.totalDurationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Red pill badge with the unplayed count (used on folders and as NEW tag). */
@Composable
private fun NewPill(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFFE53935))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = if (count > 0) "$count NEW" else "NEW",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

// ----------------------------------------------------------------------
// Folder content: sub-folders + video rows
// ----------------------------------------------------------------------

@Composable
private fun FolderContentView(
    folder: VideoFolder,
    searchQuery: String,
    allVideosInFolder: List<VideoEntry>,
    thumbnailLoader: ThumbnailLoader,
    onOpenFolder: (VideoFolder) -> Unit,
    onPlayVideo: (VideoEntry, List<VideoEntry>) -> Unit,
    onAction: () -> Unit
) {
    val matchingFolders = folder.folders.filter { it.matches(searchQuery) }
    val matchingVideos = folder.videos.filter {
        searchQuery.isBlank() || it.title.contains(searchQuery, ignoreCase = true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (matchingFolders.isNotEmpty()) {
            item {
                Text(
                    "Folders",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            items(matchingFolders, key = { "f_" + it.path }) { sub ->
                FolderRow(folder = sub, onClick = { onOpenFolder(sub) })
            }
        }
        if (matchingVideos.isNotEmpty()) {
            item {
                Text(
                    "Videos (${matchingVideos.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            items(matchingVideos, key = { it.id }) { video ->
                VideoRow(
                    video = video,
                    thumbnailLoader = thumbnailLoader,
                    onClick = { onPlayVideo(video, allVideosInFolder) },
                    onChanged = onAction
                )
            }
        }
        if (matchingFolders.isEmpty() && matchingVideos.isEmpty()) {
            item { EmptyLibrary() }
        }
    }
}

/** Compact folder row used when a folder contains sub-folders. */
@Composable
private fun FolderRow(folder: VideoFolder, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    folder.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${folder.totalVideoCount} videos · " +
                        FileActions.formatDuration(folder.totalDurationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (folder.newCount > 0) {
                NewPill(count = folder.newCount)
            }
        }
    }
}

// ----------------------------------------------------------------------
// Video row: thumbnail, NEW tag, duration badge, inline ⋮ actions
// ----------------------------------------------------------------------

@Composable
private fun VideoRow(
    video: VideoEntry,
    thumbnailLoader: ThumbnailLoader,
    onClick: () -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var thumbFile by remember(video.id, video.dateModifiedMs) { mutableStateOf<File?>(null) }

    // Resolve the cached keyframe JPEG (extracted once on IO by ThumbnailLoader).
    LaunchedEffect(video.id, video.dateModifiedMs) {
        thumbFile = thumbnailLoader.load(video)
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ---- Thumbnail with NEW tag (top-left) + duration badge (bottom-right) ----
            Box(
                modifier = Modifier
                    .width(132.dp)
                    .height(76.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
            ) {
                if (thumbFile != null) {
                    AsyncImage(
                        model = thumbFile,
                        contentDescription = video.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                if (video.isNew) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(Color(0xFFE53935), RoundedCornerShape(topStart = 8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "NEW",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (video.durationMs > 0) {
                    Text(
                        text = FileActions.formatDuration(video.durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    video.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(FileActions.formatSize(video.sizeBytes))
                        if (video.resumePositionMs > 0) append(" · resume available")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---- Inline ⋮: local file actions only ----
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "File actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("File info") },
                        leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                        onClick = { menuOpen = false; showInfo = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                        onClick = { menuOpen = false; showRename = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            FileActions.share(context, video)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; showDeleteConfirm = true }
                    )
                }
            }
        }
    }

    if (showInfo) {
        InfoSheet(video = video, onDismiss = { showInfo = false })
    }
    if (showRename) {
        RenameDialog(
            video = video,
            onDismiss = { showRename = false },
            onRenamed = {
                showRename = false
                onChanged()
            }
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete video?") },
            text = { Text("\"${video.fileName}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    FileActions.delete(context, video)
                    thumbnailLoader.invalidate(video)
                    onChanged()
                }) { Text("Delete", color = Color(0xFFE53935)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

// ----------------------------------------------------------------------
// File info + rename dialogs
// ----------------------------------------------------------------------

/** Bottom-sheet style info card listing the local file's metadata. */
@Composable
private fun InfoSheet(video: VideoEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("File info", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                FileActions.describe(video).forEach { (label, value) ->
                    Row(modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(96.dp)
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun RenameDialog(
    video: VideoEntry,
    onDismiss: () -> Unit,
    onRenamed: () -> Unit
) {
    val context = LocalContext.current
    var name by remember {
        mutableStateOf(video.fileName.substringBeforeLast('.'))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("New name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                FileActions.rename(context, video, name)
                onRenamed()
            }) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ----------------------------------------------------------------------
// Resume FAB (last played URI + timestamp, persisted via DataStore)
// ----------------------------------------------------------------------

@Composable
private fun ResumeFab(lastPlayed: LastPlayed, onClick: () -> Unit) {
    if (!lastPlayed.hasMedia) return
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
        text = {
            Column {
                Text(
                    "Resume",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    lastPlayed.title.ifBlank { "Last video" },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (lastPlayed.durationMs > 0) {
                    LinearProgressIndicator(
                        progress = { lastPlayed.progress },
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .width(96.dp)
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    )
}
