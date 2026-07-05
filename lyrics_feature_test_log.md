# Synced Lyrics Feature Log

## Implementation Details
1. **Network Layer**: Added `LRCLibHelper` to query `https://lrclib.net/api/get` using Retrofit and Moshi for fetching synced `.lrc` lyrics based on track and artist name.
2. **Parser**: Added `parseLrc` function to parse the LRC timestamp format `[mm:ss.xx]` into a list of `LyricLine(timeMs, text)` objects.
3. **UI Component**: Created `SyncedLyricsDialog.kt` containing a scrollable `LazyColumn`.
   - Used `LaunchedEffect` to auto-scroll the `LazyColumn` to keep the currently active line centered.
   - Clickable lines to seek to the specified timestamp via `viewModel.seekTo(ms)`.
4. **Integration**: Hooked up `SyncedLyricsDialog` inside `MainActivity.kt` where the old static lyrics dialog was, passing it the `currentPositionMs` from the `viewModel`.

## Test Status
- [x] Application compiles successfully
- [x] Fetches synced lyrics via LRCLib API
- [ ] Auto-scroll works smoothly based on current playback progress
- [ ] Seeking works smoothly upon clicking a lyric line

*Note: Awaiting live user test to confirm visual smoothness on the tablet device.*
