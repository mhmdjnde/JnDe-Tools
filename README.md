# JnDe Tools

JnDe Tools is an Android app built with Kotlin and Jetpack Compose for downloading YouTube videos into the device's `Downloads` folder.

## Library Used

The app uses the following main library for YouTube downloading:

- `io.github.junkfood02.youtubedl-android:library`
- `io.github.junkfood02.youtubedl-android:ffmpeg`

These libraries provide an Android-ready integration of `yt-dlp` and FFmpeg, which lets the app fetch video information, list available formats, and download the selected video/audio stream directly on-device.

## How Video Downloading Works

When a YouTube link is pasted into the app:

1. The app normalizes the shared YouTube URL so links like `youtu.be`, `watch`, and `shorts` all resolve to a valid video URL.
2. It uses the `yt-dlp` Android library to request the video's metadata and available MP4 formats.
3. The app shows a popup with the available resolutions and estimated file sizes.
4. After the user selects a format, a foreground download service starts.
5. The video is downloaded in the background and saved into the device's `Downloads` folder.
6. Progress is shown in the app and through Android notifications, including the percentage downloaded.

## Current Features

- YouTube video link support
- Resolution picker before download
- File size display for each available option
- Background downloading with a foreground service
- Live download progress in notifications
- Saving directly to `Downloads`

## Coming Soon

- Facebook Saver
- Instagram Saver

## Tech Stack

- Kotlin
- Jetpack Compose
- Android Foreground Service
- `yt-dlp` Android library
- FFmpeg
