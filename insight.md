# Verse: Technical Insights & Architectural Review

This document serves as a comprehensive reference guide to the architecture, structural components, execution pathways, and system anomalies in the **Verse** codebase.

---

## 1. High-Level Architecture Overview

Verse is designed with a hybrid **Local-Remote Architecture** optimized for background audio streaming and real-time multiplayer jam sessions:

```
                      +-----------------------------+
                      |   Jetpack Compose UI        |
                      |  (Screens, Click Wheel)     |
                      +--------------+--------------+
                                     ^
                                     | (Reactive StateFlows)
                                     v
                      +--------------+--------------+
                      |   MusicPlayerViewModel      |
                      +-------+--------------+------+
                              |              |
           (Queue mutations)  v              v  (HTTP / API requests)
             +----------------+--+        +--+-----------------+
             |   QueueManager    |        | iTunes, LRCLib, YT |
             +-------------------+        +--------------------+
                              |              |
            (Local persistence) v              v (Remote Session Sync)
             +----------------+--+        +--+-----------------+
             | SQLite (Room DB)  |        | Firebase (RTDB &   |
             +-------------------+        |  Firestore)        |
                              |           +--------------------+
                              v
             +----------------+--------------+
             |   VerseMusicService           |
             |   (Foreground Service)        |
             |  ├── WebViewHolder (WebView)  |
             |  └── VerseWebViewPlayer (Media3)|
             +-------------------------------+
```

### Core Architecture Components:
* **Tactile User Interface**: A custom Jetpack Compose layout replicates a classic click wheel interface. Circular swipe gestures (`atan2`) trigger standard list selections or OS volume changes via custom haptic feedback hooks.
* **Service-Level Playback Engine**: To prevent Android's background Activity optimization from throttling playback, a singleton `WebView` (`WebViewHolder`) runs at the Service level using the YouTube IFrame API. A foreground `VerseMusicService` keeps this WebView active in the background.
* **Media3 Integration**: Extends Media3's `SimpleBasePlayer` (`VerseWebViewPlayer`) to bind standard web callbacks to the Android system media notification, bluetooth controls, and lock screen commands.
* **Synchronization Pipelines**:
  * **Local DB (Room)**: Caches Apple Music and YouTube charts, offline playlists, play history, and liked songs.
  * **Cloud Firestore**: Synchronizes long-term storage data (profiles, persistent playlists, chat logs).
  * **Firebase Realtime Database (RTDB)**: Powers low-latency jam sessions (current track, play/pause commands, play positions, typing status, and active participants).

---

## 2. Directory-by-Directory Explanation

* **Root (`/`)**
  * `insight.md`: Deep technical review of architecture and anomalies.
  * `changes.md`: Tracks edits made to codebase over time.
  * `LANDING_PAGE_DESIGN.md`: Detailed brand strategy, color palette, design brief, and interactive mockup specifications for the web landing page.
  * `build_and_install.ps1`: Script to compile and push debug APKs to connected devices.
  * `local.properties` & `gradle.properties`: Path variables and configuration settings.
* **`app/`**
  * `build.gradle.kts`: Declares project packages, versions, and dependencies (Media3, Room, Firebase, WorkManager, OkHttp, Retrofit, Moshi).
  * `src/main/`
    * `AndroidManifest.xml`: Declares foreground permissions, launcher properties, and services.
    * `java/com/example/`
      * `MainActivity.kt`: Entry point. Sets immersive mode, edge-to-edge layout, checks battery optimization, and loads the Compose view hierarchy.
      * `VerseApplication.kt`: Enqueues periodic WorkManager cleanups and creates memory/disk caches.
      * `WebViewHolder.kt`: Singleton holding the background WebView. Houses the JavaScript-to-Kotlin callback bridge (`PlayerBridge`) for YouTube states.
      * `data/`
        * `local/SongDatabase.kt`: Handles local persistence tables for liked tracks, history, playlists, and cached charts.
        * `model/`: Structures for `Track` metadata and helper mappers.
        * `network/`: Houses helper utilities for iTunes (RSS charts), LRCLib (synchronized lyrics), YouTube (resolving query strings to video IDs), and version updates.
        * `queue/QueueManager.kt`: Drives the playback queue state engine. Separates tracks into Manual, Context, and Autoplay buckets.
        * `remote/`: Houses `FirestoreService` (user sync), `JammingService` (RTDB multiplayer loops), `ExploreRefreshWorker` (chart scraping), and `RoomCleanupWorker` (20m empty room deletion).
      * `ui/`
        * `components/`: Modular views like `AuthScreen`, `ClickWheel`, `SyncedLyricsDialog`, and `YouTubeWebViewPlayer`.
        * `screens/`: Screens for chatting (`ChatRoomFullScreenOverlay`), checking queue parameters (`QueueInspector`), and device listings.
        * `theme/`: Styling system (Glassmorphism, dark theme, and crimson highlight properties).
        * `viewmodel/`: `AuthViewModel` (user state) and `MusicPlayerViewModel` (core controller connecting UI gestures, databases, and play states).
      * `utils/`: `SilentAudioPlayer` (plays silent audio loop to protect CPU from sleep mode) and `CacheManager` (cleans local database sizes).

---

## 3. Application Execution Flow

1. **System Launch**:
   * `VerseApplication` runs, scheduling `ExploreRefreshWorker` (24h loop) and configuring Coil image loader caches.
   * `MainActivity` sets portrait fullscreen constraints, checks power manager whitelist, and triggers `MusicPlayerViewModel`.
   * `MusicPlayerViewModel` restores playback parameters (track, seek position, queue indexes) from SharedPreferences.
2. **Browsing Content**:
   * User navigates to Explore. `loadSection` queries local cache memory. If empty or expired, it fetches fresh charts from iTunes and YouTube search APIs, updates the UI, and persists it to the SQLite cache.
3. **Triggering Playback**:
   * User selects a song. `playFromIntent` initializes a `QueueManager` session.
   * `playResolvedTrack` resolves iTunes placeholders to YouTube video IDs.
   * Calls `WebViewHolder.loadVideo()`. JavaScript evaluates and starts playback.
   * `MusicPlayerViewModel` starts the `VerseMusicService` foreground service, binding Media3's `MediaSession`.
   * `VerseMusicService` monitors position updates and updates the lock screen. It activates a WakeLock and launches `SilentAudioPlayer` to preserve background CPU cycles.
4. **Active Jam Session**:
   * Users join rooms. ViewModels start listening to real-time events via `JammingService.listenToRoom()`.
   * When a host performs an action (play, pause, seek, track change), it writes the status to Firebase RTDB.
   * Guests receive the event. The system computes network latency (`expectedMs = state.positionMs + (trueTime - state.updatedAt)`) and seeks the Guest's WebView to match the host if the difference exceeds 3000ms.

---

## 4. Playback Queue System Audit: Identified Logic Bugs & Edge Cases

The following critical logic errors were uncovered during the system audit:

### Bug A: The Repeat-All Queue Annihilation Bug
* **Location**: `MusicPlayerViewModel.kt` (lines 1676-1679) & `QueueManager.kt` (`startSession` / `validate`)
* **Impact**: Skips all tracks and empties the queue.
* **Mechanism**: When `RepeatMode.ALL` is active and the queue is exhausted, the ViewModel calls `queueManager.startSession(history.first(), history.drop(1), ...)`. This sets the new `context` to the remaining history tracks, but copies the entire old history to the new session's `history` list. During `validate()`, the deduplication logic detects that the new context tracks already exist in `history` and deletes them from `context`. This empties the upcoming queue, causing it to loop indefinitely.

### Bug B: The Queue Reordering (Drag & Drop) No-Op Bug
* **Location**: `MusicPlayerViewModel.kt` (lines 1984-2024)
* **Impact**: User reordering is ignored.
* **Mechanism**: `reorderQueue()` mutates local compatibility list variables (`historyQueue`, `manualQueue`, etc.) but never updates the single source of truth (`queueManager`). It then calls `rebuildUnifiedQueue()`, which immediately overwrites these local lists with the unmodified data from `queueManager.state.value`.

### Bug C: Tapping History Items in Queue Does Nothing
* **Location**: `MusicPlayerViewModel.kt` (line 1550) & `QueueManager.kt` (`selectExisting`)
* **Impact**: Inability to tap and play previously played songs.
* **Mechanism**: If a user taps a history item, `playFromIntent` sees the track ID in `visible` (which includes history) and calls `selectExisting()`. However, `selectExisting` only searches the `manual`, `context`, and `autoplay` lists, returning `null`. This causes the tap event to exit early and do nothing.

### Bug D: Queue Mutations Lost Under Shuffle
* **Location**: `QueueManager.kt` (`toggleShuffle`, `addPlayNext`, `addToEnd`, `remove`)
* **Impact**: Discarded additions/deletions when toggling shuffle.
* **Mechanism**: Enabling shuffle stores the queue state in `originalManual`, `originalContext`, and `originalAutoplay`. Any queue mutations (adding, removing, or playing tracks) while shuffle is active modify only the active lists, not the backups. Disabling shuffle overwrites the active lists with these backups, restoring deleted tracks and removing newly added ones.

### Bug E: Tapping Context Song Skips and Clears Manual Queue
* **Location**: `QueueManager.kt` (lines 74-86)
* **Impact**: Explicitly queued songs are deleted without playing.
* **Mechanism**: Tapping a track in the `CONTEXT` queue causes `selectExisting` to treat all previous tracks (including all items in `MANUAL`) as "passed" or played. It moves the entire manual queue to `history` and clears the active `MANUAL` list.

### Bug F: Requeued Previous Songs Trapped Behind Manual Queue
* **Location**: `QueueManager.kt` (lines 111-113)
* **Impact**: Pressing Previous followed by Next breaks standard track order.
* **Mechanism**: Pressing **Previous** inserts the old current track into the `CONTEXT` bucket, even if it originally came from the `MANUAL` queue. If the manual queue is not empty, tapping Next will play the next manual track instead of returning to the requeued track.

### Bug G: Unplayed Upcoming Songs Dumped into History
* **Location**: `QueueManager.kt` (line 58)
* **Impact**: History contains tracks the user never listened to.
* **Mechanism**: Starting a new session (e.g. playing a song from a new playlist) appends the entire previous session's context and autoplay lists to `history`. This treats unplayed upcoming tracks as played history.

---

## 5. Important Dependencies

* **androidx.media3 (`media3-session`, `media3-common`)**: Connects playback state to system controls (lock screen, bluetooth devices, notifications).
* **Firebase Realtime Database (RTDB)**: Syncs low-latency room player states and participant lists.
* **Firebase Firestore**: Syncs profiles, shared playlists, and persistent chat logs.
* **Room Database**: Caches network feeds and stores local user data.
* **WorkManager**: Runs background synchronization (`ExploreRefreshWorker`) and sweeps inactive rooms (`RoomCleanupWorker`).
* **Coil**: Image loading library.
* **Retrofit / OkHttp / Moshi**: Networking clients and JSON parsing.

---

## 6. Risks & Caution Areas

1. **Regional RTDB URL Hardcoding**: The URL in `JammingService.kt` is hardcoded. Changing Firebase projects requires manual updates to this string.
2. **Background WebView Restrictions**: Background execution is kept alive by `VerseMusicService` combined with `SilentAudioPlayer`'s looping audio. Altering this mechanism could cause the OS to freeze audio playback in the background.
3. **Embedded Video Blocks**: External playback of certain YouTube tracks may fail due to copyright/embedding blocks (handled via auto-skips on errors `100`, `101`, and `150`).
4. **Firebase Free Tier Limits**: Keep database heartbeats spaced (currently at 15s with interpolation) to prevent exceeding free tier quotas.
