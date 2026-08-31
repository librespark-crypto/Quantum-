package com.quantum.player.browser

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.quantum.player.R
import com.quantum.player.core.MediaSourceDetector
import com.quantum.player.model.MediaItem
import java.io.File
import java.util.Locale

/** Entry in the browser - either file or directory. */
data class BrowserEntry(
    val path: String,
    val name: String,
    val isFile: Boolean,
    val isDirectory: Boolean,
    val size: Long = 0,
    val mimeType: String = "",
    val modificationTime: Long = 0,
    val durationMs: Long = 0
) {
    /** The URI playback should use: a content URI for MediaStore rows, a path otherwise. */
    val playbackUri: String get() = path
}

/**
 * Modern media browser for Quantum player.
 * Supports Android Storage Access Framework, local folders, recent files, favorites, and playlists.
 * Uses MediaStore where appropriate and avoids broad filesystem permissions.
 *
 * The previous implementation mixed Compose with a non-existent
 * `androidx.compose.ui.file.*` API, used `File.rootPath` / `File.parentPath`
 * (not Kotlin/Java members), and called `Card(elevation = CardElevation.Level1)`
 * and `items(entries) -> entry ->`, none of which exist.
 *
 * MediaStore is the primary source: on API 30+ a raw `File` walk of shared
 * storage returns nothing without MANAGE_EXTERNAL_STORAGE, which this app does
 * not require for normal use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaBrowser(
    initialPath: String = "",
    onSelect: (MediaItem) -> Unit,
    onNavigate: (String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentPath by remember { mutableStateOf(initialPath) }
    var searchQuery by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var hasPermission by remember { mutableStateOf(hasMediaPermission(context)) }
    var entries by remember { mutableStateOf<List<BrowserEntry>>(emptyList()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(hasPermission, currentPath, searchQuery) {
        entries = if (!hasPermission) {
            emptyList()
        } else if (currentPath.isBlank()) {
            queryVideos(context, searchQuery)
        } else {
            loadEntries(currentPath, searchQuery)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name)) },
            navigationIcon = {
                if (currentPath.isNotBlank()) {
                    IconButton(onClick = {
                        currentPath = File(currentPath).parent.orEmpty()
                        onNavigate(currentPath)
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.previous)
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface
            )
        )

        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            label = { Text(stringResource(R.string.enter_url)) },
            singleLine = true,
            trailingIcon = {
                TextButton(
                    onClick = { if (urlInput.isNotBlank()) onOpenUrl(urlInput.trim()) }
                ) { Text(stringResource(R.string.open)) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text(stringResource(R.string.videos)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )

        when {
            !hasPermission -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.permission_needed),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { permissionLauncher.launch(mediaReadPermission()) }) {
                    Text(stringResource(R.string.open))
                }
            }

            entries.isEmpty() -> Text(
                text = stringResource(R.string.no_media_selected),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )

            else -> DirectoryGrid(
                entries = entries,
                onSelect = { entry ->
                    if (entry.isDirectory) {
                        currentPath = entry.path
                        onNavigate(entry.path)
                    } else {
                        onSelect(entry.toMediaItem())
                    }
                }
            )
        }

        if (currentPath.isNotBlank()) {
            OutlinedButton(
                onClick = {
                    currentPath = ""
                    onNavigate("")
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.browse_device))
            }
        }
    }
}

/** Load browser entries for a given path and search query. */
fun loadEntries(path: String, query: String): List<BrowserEntry> {
    val result = mutableListOf<BrowserEntry>()
    val directory = File(path)
    if (!directory.exists() || !directory.isDirectory) return result

    val parent = directory.parent
    if (parent != null && parent.isNotEmpty()) {
        result.add(
            BrowserEntry(
                path = parent,
                name = "..",
                isFile = false,
                isDirectory = true
            )
        )
    }

    val children = directory.listFiles()?.sortedWith(
        compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) }
    ) ?: emptyList()

    children.forEach { child ->
        val isDirectory = child.isDirectory
        val isSupportedFile = child.isFile &&
            !child.name.startsWith(".") &&
            MediaSourceDetector.isSupportedMedia(child.name)
        if (!isDirectory && !isSupportedFile) return@forEach

        val matchesQuery = query.isBlank() ||
            child.name.contains(query, ignoreCase = true) ||
            child.path.contains(query, ignoreCase = true)
        if (!matchesQuery) return@forEach

        result.add(
            BrowserEntry(
                path = child.absolutePath,
                name = child.name,
                isFile = isSupportedFile,
                isDirectory = isDirectory,
                size = child.length(),
                mimeType = MediaSourceDetector.containerOf(child.name).orEmpty(),
                modificationTime = child.lastModified()
            )
        )
    }
    return result
}

/** Query the device's video library through MediaStore. */
fun queryVideos(context: Context, query: String): List<BrowserEntry> {
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.MIME_TYPE
    )
    val selection = if (query.isBlank()) null else "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
    val args = if (query.isBlank()) null else arrayOf("%$query%")

    val entries = mutableListOf<BrowserEntry>()
    val cursor: Cursor? = runCatching {
        context.contentResolver.query(
            collection,
            projection,
            selection,
            args,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        )
    }.getOrNull()

    cursor?.use { c ->
        val idColumn = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameColumn = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val durationColumn = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val sizeColumn = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val dateColumn = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
        val mimeColumn = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
        while (c.moveToNext()) {
            val id = c.getLong(idColumn)
            val name = c.getString(nameColumn) ?: continue
            entries.add(
                BrowserEntry(
                    path = Uri.withAppendedPath(collection, id.toString()).toString(),
                    name = name,
                    isFile = true,
                    isDirectory = false,
                    size = c.getLong(sizeColumn),
                    mimeType = c.getString(mimeColumn).orEmpty(),
                    modificationTime = c.getLong(dateColumn) * 1000L,
                    durationMs = c.getLong(durationColumn)
                )
            )
        }
    }
    return entries
}

private fun BrowserEntry.toMediaItem(): MediaItem = MediaItem(
    id = path.hashCode().toString(),
    uri = playbackUri,
    title = name,
    durationMs = durationMs,
    container = mimeType.takeIf { it.isNotBlank() }
        ?: MediaSourceDetector.containerOf(name),
    sizeBytes = size,
    metadata = mapOf(
        "file_size" to size,
        "modification_time" to modificationTime
    )
)

/** The permission that gates media reading on this API level. */
fun mediaReadPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

fun hasMediaPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, mediaReadPermission()) ==
        PackageManager.PERMISSION_GRANTED

/** Format file size to human-readable form. */
fun formatSize(size: Long): String {
    val kb = size / 1024.0
    return when {
        kb >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f GB", kb / (1024.0 * 1024.0))
        kb >= 1024.0 -> String.format(Locale.US, "%.1f MB", kb / 1024.0)
        else -> String.format(Locale.US, "%.0f KB", kb)
    }
}

/** Format a duration in milliseconds as h:mm:ss or m:ss. */
fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "--:--"
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** Directory grid view showing files and folders. */
@Composable
fun DirectoryGrid(
    entries: List<BrowserEntry>,
    onSelect: (BrowserEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(entries, key = { it.path }) { entry ->
            BrowserItemCard(entry = entry, onClick = { onSelect(entry) })
        }
    }
}

/** Individual browser item card. */
@Composable
fun BrowserItemCard(
    entry: BrowserEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        if (entry.isDirectory) {
                            append("folder")
                        } else {
                            append(formatSize(entry.size))
                            if (entry.durationMs > 0) {
                                append(" · ").append(formatDuration(entry.durationMs))
                            }
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (entry.isFile) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.play),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** File detail view shown when a file is selected. */
@Composable
fun FileDetailView(
    file: File,
    onBack: () -> Unit,
    onOpen: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = file.name, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(text = file.absolutePath, style = MaterialTheme.typography.bodySmall)
        if (file.length() > 0) {
            Text(text = formatSize(file.length()), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.previous)) }
            Button(onClick = onOpen) { Text(stringResource(R.string.open_video)) }
        }
    }
}
