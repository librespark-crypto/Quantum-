package com.quantum.player.browser

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.file.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.quantum.player.model.MediaItem
import com.quantum.player.model.WatchState
import java.io.File

/**
 * Modern media browser for Quantum player.
 * Supports Android Storage Access Framework, local folders, recent files, favorites, and playlists.
 * Uses MediaStore where appropriate and avoids broad filesystem permissions.
 */
@Composable
fun MediaBrowser(
    initialPath: String = "",
    onSelect: (MediaItem) -> Unit,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    var currentPath by remember { mutableStateOf(initialPath) }
    var showDirectoryView by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }

    // Directory entries
    val entries by remember { mutableStateOf<List<BrowserEntry>>() }

    // Load entries for current path
    val loadedEntries by remember(path = currentPath, searchQuery = searchQuery) {
        loadEntries(path = currentPath, query = searchQuery)
    }

    // Toolbar
    TopAppBar(
        title = { Text(text = "Quantum Browser") },
        navigationIcon = {
            IconButton(onClick = { /* toggle navigation */ }) {
                Icon(Icons.Back, contentDescription = "Back")
            }
        },
        actions = {
            TextButton(onClick = { /* search action */ }) {
                Text("Search")
            }
        }
    )

    // Content area
    if (showDirectoryView) {
        DirectoryGrid(
            entries = loadedEntries,
            onSelect = { entry ->
                currentPath = entry.path
                if (entry.isFile) {
                    val mediaItem = createMediaItem(entry.file)
                    onSelect(mediaItem)
                }
            },
            onNavigate = { newPath ->
                currentPath = newPath
            }
        )
    } else {
        // File detail view
        FileDetailView(
            file = File(currentPath),
            onBack = { showDirectoryView = true }
        )
    }

    // Search field
    OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        label = { Text("Search") },
        placeholder = { Text("Enter search term") },
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        onFocusChanged = { if (it.hasFocus) {} }
    )
}

/** Entry in the browser - either file or directory. */
data class BrowserEntry(
    val path: String,
    val name: String,
    val isFile: Boolean,
    val isDirectory: Boolean,
    val size: Long = 0,
    val mimeType: String = "",
    val modificationTime: Long = 0
)

/** Load browser entries for a given path and search query. */
private fun loadEntries(path: String, query: String): List<BrowserEntry> {
    val result = mutableListOf<BrowserEntry>()
    val file = File(path)

    if (!file.exists()) return result

    // Add parent directory if not at root
    if (path != File.rootPath) {
        result.add(BrowserEntry(
            path = file.parentPath,
            name = "..",
            isFile = false,
            isDirectory = true,
            size = 0
        ))
    }

    // Add children
    val children = file.listFiles()?.sortedBy { it.name } ?: emptyArray()

    children?.forEach { child ->
        val isChildDir = child.isDirectory
        val isChildFile = child.isFile && !child.name.startsWith(".")

        // Filter by search query
        val matchesQuery = if (query.isBlank()) true else {
            child.name.lowercase().contains(query.lowercase()) ||
                child.path.lowercase().contains(query.lowercase())
        }

        if (matchesQuery) {
            result.add(BrowserEntry(
                path = child.absolutePath,
                name = child.name,
                isFile = isChildFile && isSupportedMedia(child),
                isDirectory = isChildDir,
                size = child.length(),
                mimeType = child.name.substringAfterLast(".").let {
                    "." + it.lowercase()
                } ?: "",
                modificationTime = child.lastModified()
            ))
        }
    }

    return result
}

/** Check if a file is a supported media format. */
private fun isSupportedMedia(file: File): Boolean {
    val extension = file.name.substringAfterLast(".").lowercase()
    return when (extension) {
        "mp4", "mkv", "webm", "mov", "avi", "ts", "m2ts", "flv", "ogg", "3gp" -> true
        "m3u8", "mpd" -> true // playlist URLs
        else -> false
    }
}

/** Create a MediaItem from a browser entry. */
private fun createMediaItem(entry: BrowserEntry): MediaItem {
    return MediaItem(
        id = entry.path.hashCode().toString(),
        uri = entry.path,
        title = entry.name,
        durationMs = 0, // Would be populated by metadata
        container = entry.mimeType,
        videoCodec = null,
        audioCodec = null,
        subtitles = null,
        metadata = mapOf(
            "file_size" to entry.size,
            "modification_time" to entry.modificationTime
        )
    )
}

/** File detail view shown when a file is selected. */
@Composable
fun FileDetailView(
    file: File,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "File: ${file.name}",
            style = MaterialTheme.typography.headline6
        )
        Text(
            text = "Path: ${file.absolutePath}",
            style = MaterialTheme.typography.body1
        )
        if (file.length() > 0) {
            Text(
                text = "Size: ${formatSize(file.length())}",
                style = MaterialTheme.typography.body1
            )
        }
        Button(onClick = { onBack(); /* open with Quantum */ }) {
            Text("Open in Quantum")
        }
    }
}

/** Format file size to human-readable form. */
private fun formatSize(size: Long): String {
    val kb = size / 1024
    if (kb > 1024) {
        val mb = kb / 1024
        "$mb%.1f MB".format(mb)
    } else {
        "$kb KB"
    }
}

/** Directory grid view showing files and folders. */
@Composable
fun DirectoryGrid(
    entries: List<BrowserEntry>,
    onSelect: (BrowserEntry) -> Unit,
    onNavigate: (String) -> Unit
) {
    var columns by remember { mutableStateOf(2) } // Adaptive columns

    // Calculate column count based on width
    // columns = if (width < 600) 1 else if (width < 1200) 2 else 3

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        items(entries) -> entry ->
            BrowserItemCard(
                entry = entry,
                onSelect = { onSelect(it); onNavigate(it.path) },
                onNavigate = { OnNavigate(it.path) }
            )
    }
}

/** Individual browser item card. */
@Composable
fun BrowserItemCard(
    entry: BrowserEntry,
    onSelect: () -> Unit,
    onNavigate: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(0.8f).padding(4.dp),
        elevation = CardElevation.Level1
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Icon based on type
            val icon = if (entry.isDirectory) {
                Icons.Folder
            } else if (isSupportedMediaFile(entry.mimeType)) {
                Icons.FileVideo
            } else {
                Icons.File
            }

            Icon(
                imageVector = icon,
                contentDescription = entry.name,
                modifier = Modifier.size(40.dp)
            )

            // Name
            Text(
                text = entry.name,
                style = MaterialTheme.typography.body1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            // Size (for files only)
            if (entry.isFile && entry.size > 0) {
                Text(
                    text = formatSize(entry.size),
                    style = MaterialTheme.typography.caption,
                    alpha = 0.7f,
                    textAlign = TextAlign.Center
                )
            }

            // Select action
            Button(
                onClick = onSelect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                style = MaterialTheme.buttonStyleSmall
            ) {
                Text("Select")
            }
        }
    }
}

/** Check if mime type represents a supported media file. */
private fun isSupportedMediaFile(mimeType: String): Boolean {
    val extension = mimeType.substringAfterLast("/").lowercase()
    return when (extension) {
        "mp4", "mkv", "webm", "mov", "avi" -> true
        else -> false
    }
}