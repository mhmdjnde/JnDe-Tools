# JnDe Tools

JnDe Tools is an Android app built with Kotlin and Jetpack Compose for saving media from YouTube, Instagram, and Facebook directly into the device's `Downloads` folder.

## Features

- YouTube saver with a resolution picker and size estimates
- Instagram saver for reels, single posts, and carousel posts
- Facebook saver for public video, reel, watch, and shared video links
- Background downloads through foreground services
- Live progress inside the app and in Android notifications
- Files saved directly to the system `Downloads` folder
- Split debug/release APK outputs per ABI for smaller installs

## Libraries and Platform APIs Used

- `io.github.junkfood02.youtubedl-android:library`
- `io.github.junkfood02.youtubedl-android:ffmpeg`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android`
- Jetpack Compose Material 3
- Android `MediaStore` / legacy external storage APIs for saving files
- Android foreground services and notifications for long-running downloads

## How Downloads Work

### YouTube

1. The app normalizes shared links such as `youtu.be`, `watch`, `shorts`, and raw video IDs into one canonical YouTube URL.
2. `yt-dlp` fetches the available MP4 formats and metadata.
3. The app shows a resolution picker with estimated sizes.
4. After a selection is made, a foreground service downloads the video in the background.
5. If audio/video streams are separate, FFmpeg merges them before the final file is saved to `Downloads`.

### Instagram

1. The app normalizes reel and post links copied from Instagram.
2. Reels are resolved with `yt-dlp`.
3. Posts and carousels are resolved from Instagram's public embed page data.
4. The app shows every media item it finds, including photos and videos.
5. Each item has its own download button, and multi-item posts also get a `Download All` action.
6. Direct media URLs are downloaded straight into `Downloads`, with progress shown in the UI and notifications.

### Facebook

1. The app normalizes public Facebook share links such as `watch`, `video.php`, `reel`, `fb.watch`, and some shared/shimmed links.
2. `yt-dlp` resolves the best public video format and metadata.
3. When a direct progressive video URL is available, the app downloads that file directly for a faster start.
4. If Facebook only exposes a more complex format, the app falls back to the `yt-dlp` download path.
5. The finished file is saved to `Downloads` and the notification is updated live during the transfer.

## Project Structure

- [MainActivity.kt](/home/jndeishere/AndroidStudioProjects/YTSaverByJnde/app/src/main/java/com/example/ytsaverbyjnde/MainActivity.kt): Compose UI for Home, YouTube, Instagram, and Facebook screens
- [YoutubeDlDownloader.kt](/home/jndeishere/AndroidStudioProjects/YTSaverByJnde/app/src/main/java/com/example/ytsaverbyjnde/YoutubeDlDownloader.kt): downloader runtime, metadata fetchers, direct download helpers, and foreground services
- [YouTubeUrlNormalizer.kt](/home/jndeishere/AndroidStudioProjects/YTSaverByJnde/app/src/main/java/com/example/ytsaverbyjnde/YouTubeUrlNormalizer.kt): YouTube share-link normalization
- [InstagramUrlNormalizer.kt](/home/jndeishere/AndroidStudioProjects/YTSaverByJnde/app/src/main/java/com/example/ytsaverbyjnde/InstagramUrlNormalizer.kt): Instagram link normalization
- [FacebookUrlNormalizer.kt](/home/jndeishere/AndroidStudioProjects/YTSaverByJnde/app/src/main/java/com/example/ytsaverbyjnde/FacebookUrlNormalizer.kt): Facebook link normalization
- [YTSaverApp.kt](/home/jndeishere/AndroidStudioProjects/YTSaverByJnde/app/src/main/java/com/example/ytsaverbyjnde/YTSaverApp.kt): app startup and downloader warmup

## Permissions

- `INTERNET`: fetch media details and download files
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`: keep downloads running in the background
- `POST_NOTIFICATIONS`: show live download progress on Android 13+
- `WRITE_EXTERNAL_STORAGE` on Android 9 and below only

## Build

Debug build:

```bash
./gradlew :app:assembleDebug
```

Unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

## APK Output

Example debug APK for most modern Android phones:

- [app-arm64-v8a-debug.apk](/home/jndeishere/AndroidStudioProjects/YTSaverByJnde/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk)

## Notes

- Public links are the main supported path for Facebook and Instagram.
- Private, login-required, or region-restricted posts/videos can still fail if the source platform blocks access upstream.
