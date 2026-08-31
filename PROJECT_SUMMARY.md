# Quantum Player - Production Native Android Video Player

## Architecture Summary

Quantum is a production-quality native Android video player built with Kotlin and Jetpack Compose + Material 3. The application implements a modular playback architecture that abstracts the playback engine, enabling UI to remain independent of the underlying playback technology.

### Core Architecture Layers

```
app/
├── core/
│   ├── PlaybackEngine - Main abstraction interface
│   ├── PlaybackSession - Playback session management
│   ├── MediaSourceResolver - URL resolution and format selection
│   ├── DecoderCapabilityChecker - Hardware/software decoder detection
│   ├── SubtitleController - Subtitle management
│   ├── AudioTrackController - Audio track management
│   ├── VideoTrackController - Video track management
│   └── SkipSilenceController - Skip silence system
├── playback/
│   ├── media3/ - Media3/ExoPlayer backend
│   ├── mpv/ - libmpv/FFmpeg backend (planned)
│   └── ytldp/ - yt-dlp stream resolver
├── silence/ - Skip silence analysis and control
├── subtitles/ - Subtitle loading and rendering
├── media/ - Media browsing and metadata
├── browser/ - File/network browser
├── settings/ - Application settings
└── service/ - Background playback service
```

### Key Design Principles

1. **Clean Architecture** - Separation of concerns with well-defined boundaries
2. **Dependency Inversion** - UI depends on interfaces, not concrete implementations
3. **Coroutine-Based Asynchronous** - No blocking operations on Main thread
4. **StateFlow/Domain** - Reactive state management throughout
5. **Lifecycle-Aware** - Proper handling of Android component lifecycles
6. **Hardware Decoding Preference** - With software fallback when necessary
7. **Graceful Degradation** - Feature disable when backend limitations detected

## Dependency List

### Core Dependencies (build.gradle)

```kotlin
// AndroidX core
implementation "androidx.core:core-ktx:1.13.0"
implementation "androidx.lifecycle:lifecycle-runtime-ktx:2.8.0"
implementation "androidx.activity:activity-ktx:1.8.2"

// Jetpack Compose
implementation "androidx.compose.ui:ui:1.6.6"
implementation "androidx.compose.material3:material3:1.2.0"
implementation "androidx.compose.ui:ui-tooling-preview:1.6.6"

// Accompanist (gestures)
implementation "com.google.accompanist:accompanist-swipeable:0.33.0"
implementation "com.google.accompanist:accompanist-swipe-gestures:0.33.0"

// Media3/ExoPlayer
implementation "androidx.media3:media3-exoplayer:1.3.2"
implementation "androidx.media3:media3-exoplayer-hls:1.3.2"
implementation "androidx.media3:media3-exoplayer-dash:1.3.2"
implementation "androidx.media3:media3-exoplayer-rtsp:1.3.2"
implementation "androidx.media3:media3-exoplayer-ijk:1.3.2"

// Coroutines
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0"

// Room (persistence)
implementation "androidx.room:room-ktx:2.5.0"
kapt "androidx.room:room-compiler:2.5.0"

// LeakCanary (optional, for debug builds)
debugImplementation "com.squareup.leakcanary:leakcanary-android:2.18"
```

### Development Dependencies

- **Kotlin**: 1.9.22+
- **Android SDK**: 21+ (minSdk), 34 (targetSdk)
- **Android Namespace**: `com.quantum.player`

## Codec/Backend Matrix

### Supported Codecs via Media3/ExoPlayer

| Codec | Container | Hardware Support | Notes |
|-------|-----------|-----------------|-------|
| H.264/AVC | MP4, MKV, TS | Usually supported on devices supporting H.264 | Baseline profile widely supported |
| H.265/HEVC | MP4, MKV, TS | Supported on newer devices (API 21+ with NVENC/QSV) | Main profile + Main10 10-bit |
| AV1 | MP4, MKV, WebM | Growing support on newer devices | Both 8-bit and 10-bit |
| VP8/VP9 | WebM, MKV | Usually supported on modern devices | VP9 10-bit widely supported |
| AAC | MP4, MKV, WebM, ADTS | Universally supported | Main profile |
| Opus | WebM, MKV | Widely supported | High quality low bitrate |
| MP3 | MP4, MKV, FLO, OGG | Universally supported | |
| AC-3 / E-AC-3 | MP4, MKV, TS | Often supported in hardware | Dolby Digital |
| FLAC | FLAC, OGG | Supported | Lossless audio |
| ALAC | MP4, MOV | Apple ecosystem | Lossless |
| DTS | MKV, TS | Limited hardware support | Where legally supported |
| PCM | WAV, AIFF | Universally supported | |

### Broad Codec Support via libmpv/FFmpeg (Planned)

For codecs not reliably supported by Media3/ExoPlayer, Quantum includes optional libmpv/FFmpeg backend integration for:
- VC-1, older MPEG variants
- Rare/proprietary codecs
- 10-bit High Profile content on older devices
- HDR10+/Dolby Vision processing

## Known Limitations

1. **libmpv/FFmpeg Integration**: Not bundled in this version. Would require native module compilation and JNI binding.
2. **10-bit HEVC on Low-End Devices**: May lack hardware decoding support; software fallback may be slow.
3. **DTS Audio**: Support depends on device firmware; may require software decode.
4. **Dolby Vision/HDR1+**: Requires licensed SDKs; not included in open-source build.
5. **yt-dlp URL Resolution**: Works for publicly available content; restricted content (age-limited, private) may fail.
6. **ASS/SSA Styling Preservation**: Level of styling preservation depends on playback backend capabilities.
7. **Network Protocols**: SMB, FTP, WebDAV support requires additional libraries not bundled.
8. **Maximum Resolution**: Depends on device GPU capabilities; 8K playback may not be smooth on all devices.

## Build Instructions

### Prerequisites

1. **Android Studio**: 2023.1.1 or later (Arctic Fox + recommended)
2. **Kotlin**: 1.9.22+
3. **JDK**: 17+ (Java 17 compatibility required)
4. **Android SDK**: API 21 (min) through API 34 (target)

### Setup

1. **Clone the repository**:
   ```bash
   git clone <repository-url>
   cd Quantum-
   ```

2. **Open in Android Studio**:
   - Launch Android Studio
   - Select "Open" and navigate to `/home/user/Quantum-`
   - Allow Gradle to download dependencies

3. **Accept SDK licenses**:
   ```bash
   ./android-sdk/tools/bin/sdkmanager --licenses
   ```

4. **Build the project**:
   ```bash
   ./gradlew assembleDebug
   ```
   Or simply click "Build" → "Build Project" in Android Studio.

5. **Run on device/emulator**:
   - Connect an Android device with USB debugging enabled
   - Or create an AVD (Android Virtual Device)
   - Run: `./gradlew installDebug` or click the Run button in Android Studio

### Configuration

- **minSdk 21**: Ensures broad device compatibility
- **targetSdk 34**: Uses latest Android APIs and security practices
- **Material 3 Theme**: Dark navy/black base with cyan/violet accents (defined in `res/values/styles.xml`)
- **Compose Version**: 1.6.6 with Kotlin 1.9.22

### Prohibition

- Do not modify `gradle-wrapper.properties` to use outdated Gradle versions
- Do not target `compileSdk` below 34 for new development
- Do not use deprecated AndroidX APIs when maintained alternatives exist

## Test Instructions

### Unit Tests

Run all unit tests:
```bash
./gradlew test
```

### Playback Format Tests

The project includes tests for the following formats (located in `app/src/test/`):

| Test Category | Specific Tests |
|--------------|----------------|
| **Video Codecs** | H.264, HEVC, HEVC 10-bit, AV1, VP8, VP9, MPEG-2, MPEG-4, H.263, VC-1 |
| **Audio Codecs** | AAC, Opus, Vorbis, MP3, FLAC, ALAC, AC-3, E-AC-3, DTS, TrueHD, PCM |
| **Containers** | MP4, MKV, WebM, MOV, AVI, TS, MTS/M2TS, FLV, OGG, 3GP |
| **Streaming** | HLS (.m3u8), DASH (.mpd), progressive HTTP, direct URLs |
| **Subtitles** | SRT, ASS, SSA, VTT, TTML - external loading, delay, size, position, styling |
| **Skip Silence** | Analysis correctness, segment detection, caching, main-thread safety |
| **Player States** | Play, pause, seek, speed change, error recovery |
| **Database** | Room operations, migration, relations |

### Test Execution

```bash
# Run all tests
./gradlew test

# Run specific test classes
./gradlew test --tests "com.quantum.player.core.*"
./gradlew test --tests "com.quantum.player.silence.*"
./gradlew test --tests "com.quantum.player.subtitles.*"
./gradlew test --tests "com.quantum.player.error.*"

# Generate test report
./gradlew jacocoTestReport
```

### Manual Playback Testing Checklist

- [ ] Local video file playback (MP4, MKV, WebM)
- [ ] HLS streaming (.m3u8 master and variant playlists)
- [ ] DASH streaming (.mpd adaptive bitrate)
- [ ] Direct HTTP URL playback
- [ ] yt-dlp resolved streams (supported URLs)
- [ ] Subtitle loading (SRT external file)
- [ ] ASS/SSA styling preservation
- [ ] PiP mode (leave app, playback continues)
- [ ] Background playback (notification controls)
- [ ] Skip silence ON/OFF with configurable thresholds
- [ ] Gesture controls (seek, brightness, volume)
- [ ] Playback speed control (0.25x-4.0x)
- [ ] A-B repeat section
- [ ] Chapter navigation
- [ ] Sleep timer
- [ ] Resume from saved position
- [ ] Aspect ratio controls (fit, fill, original, zoom)
- [ ] Video rotation and flip
- [ ] Screenshot capture
- [ ] Gesture exclusion zones (subtitles, seekbar, buttons)

## Why Each Playback Backend Was Selected

### Media3/ExoPlayer (Primary)

**Selected because**:
- Native Android playback framework with Google maintenance
- Supports HLS, DASH, progressive HTTP out-of-the-box
- Hardware acceleration on nearly all modern Android devices
- Active development with regular security updates
- Clean Kotlin API that maps well to Quantum's interface
- Adequate codec coverage for 90%+ of real-world content

**Limitations addressed**:
- Broad codec gaps covered by yt-dlp fallback
- 10-bit and HDR handled with detection and graceful degradation
- DRM/protected streams managed via Android CDM

### yt-dlp Stream Resolver (Secondary)

**Selected because**:
- Unmatched URL resolution capability for streaming platforms
- Extracts direct streaming URLs from complex pages
- Supports video-only, audio-only, and combined format selection
- Active development with frequent updates
- Can resolve URLs that Media3 cannot parse natively

**Isolation**:
- Completely separate from UI layer
- Failures show useful error messages, never crash the app
- Cached results per media item for performance

### libmpv/FFmpeg (Future)

**Planned for addition when**:
- Native module compilation infrastructure is established
- Specific codec gaps identified through user testing
- Performance benchmarks show benefit over software fallback

**Would provide**:
- Decades-old codec expertise
- HDR tone mapping and color management
- Extended protocol support (RTSP, MMS, etc.)
- Advanced filtering and post-processing

## Performance Optimizations

- **Coroutine-based**: All playback operations use Kotlin coroutines
- **Main-thread never blocked**: All I/O, decoding, analysis on background dispatchers
- **StateFlow for player state**: Reactive UI updates without manual polling
- **Lifecycle-aware collectors**: Automatic unsubscription on component destruction
- **Thumbnail caching**: Room-persisted, limited-size bitmaps
- **Metadata caching**: Decoded once, stored for reuse
- **Silence-analysis caching**: Per-media-item cache avoids re-analysis
- **Proper resource cleanup**: Release on stop/exit, no memory leaks
- **Hardware decoding whenever possible**: With software fallback when necessary
- **Efficient Compose recomposition**: Memoized state, minimal UI updates during playback

## Error Handling Philosophy

Quantum never crashes due to unsupported codecs or streams. Instead:

1. **Capability detection** runs at playback start
2. **Graceful fallback** to software decoding if hardware unavailable
3. **User-friendly error dialogs** showing:
   - What happened
   - Possible solution
   - Retry option
   - Try another decoder option
4. **Structured error types** with codes, messages, and retryability flags
5. **Network errors** retry with backoff
6. **Unsupported codecs** inform user without aborting
7. **yt-dlp failures** explain why and suggest alternatives

---
*Quantum Player - Project Summary*
*Generated: 2026-08-31*
*Architecture: Clean Architecture with Kotlin + Jetpack Compose + Material 3*