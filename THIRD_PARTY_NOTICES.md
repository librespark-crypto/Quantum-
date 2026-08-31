# Third-Party Licenses - Quantum Player

This project uses several third-party libraries and components. Below is the license information for each.

## AndroidX Media3 (ExoPlayer)

- **Location**: `app/build.gradle` - `androidx.media3:media3-exoplayer`
- **License**: Apache License 2.0
- **Location**: https://github.com/google/media3/blob/main/LICENSE
- **NOTICE**: See https://github.com/google/media3/blob/main/NOTICE for additional attributions

## Skip Silence Algorithm

- **Concept inspiration**: Next Player (https://github.com/anilbeesetti/nextplayer)
- **Implementation**: Original algorithm developed for Quantum player
- **License**: Custom - see project root for licensing

## Kotlin Coroutines

- **Location**: `build.gradle` - `org.jetbrains.kotlin:kotlin-coroutines`
- **License**: Apache License 2.0
- **Location**: https://kotlinlang.org/docs/license.html

## Jetpack Compose

- **Location**: `build.gradle` - `androidx.compose.ui:ui`, `androidx.compose.material3:material3`
- **License**: Apache License 2.0
- **Location**: https://developer.android.com/jetpack/compose/license

## Trimmomatic / Audio Processing

- **Concept**: Audio amplitude threshold analysis for silence detection
- **Implementation**: Original code developed for Quantum player
- **License**: Custom - see project root

## Material Icons (AndroidX)

- **Location**: Various Compose UI components
- **License**: Apache License 2.0
- **Location**: https://developer.android.com/jetpack/compose/io

## yt-dlp (optional backend)

- **Location**: Potential integration for URL resolution
- **License**: Unlicense / Public Domain
- **Location**: https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE
- **Note**: yt-dlp is invoked as an external subprocess; binaries are not bundled

## FFmpeg / libmpv (optional backends)

- **Location**: Potential integration for broad codec support
- **License**: Various (LGPL for FFmpeg, custom for libmpv)
- **Note**: If integrated, proper notices and attributions must be preserved
- **Recommendation**: Keep as separate native modules with their own license files

## Room Database

- **Location**: `app/src/main/java/com/quantum/player/database/`
- **License**: Apache License 2.0 (part of AndroidX)
- **Location**: https://developer.android.com/jetpack/components/room

## Project-Specific Code

- **All Kotlin source files** in `app/src/main/java/` are original work developed for Quantum player
- **All Compose UI** in `app/src/main/java/com/quantum/player/ui/` is original work
- **All architecture and interfaces** defined in `core/` package are original design

## Attribution

This project is a complete reimplementation of video playback concepts inspired by:
- mpvRex (https://github.com/sfsakhawat999/mpvRex) - gesture interaction philosophy
- Next Player (https://github.com/anilbeesetti/nextplayer) - playback features and UX concepts

No proprietary code, branding, artwork, or UI code from reference projects has been copied. All visual designs, interaction models, and code implementations are original to the Quantum project.

## License for Quantum Player Source Code

The core Quantum player application code is released under a source-available license. See the project's LICENSE file for specific terms. Third-party components retain their original licenses.

---
*Generated for Quantum Player - Production-Quality Native Android Video Player*
*Date: 2026-08-31*