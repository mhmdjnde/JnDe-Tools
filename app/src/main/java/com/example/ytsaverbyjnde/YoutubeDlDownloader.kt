package com.example.ytsaverbyjnde

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.Serializable
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import kotlin.math.max

const val ACTION_DOWNLOAD_PROGRESS = "com.example.ytsaverbyjnde.DOWNLOAD_PROGRESS"
const val ACTION_DOWNLOAD_COMPLETE = "com.example.ytsaverbyjnde.DOWNLOAD_COMPLETE"
const val ACTION_DOWNLOAD_FAILED = "com.example.ytsaverbyjnde.DOWNLOAD_FAILED"
const val EXTRA_DOWNLOAD_TITLE = "download_title"
const val EXTRA_DOWNLOAD_STATUS = "download_status"
const val EXTRA_DOWNLOAD_PERCENT = "download_percent"
const val EXTRA_DOWNLOAD_ERROR = "download_error"
const val EXTRA_DOWNLOAD_PLATFORM = "download_platform"
const val PLATFORM_YOUTUBE = "youtube"
const val PLATFORM_FACEBOOK = "facebook"
const val PLATFORM_INSTAGRAM = "instagram"

data class DownloadProgress(
    val percent: Int?,
    val status: String,
)

data class CompletedDownload(
    val title: String,
    val uri: Uri,
    val fileName: String,
)

data class VideoDownloadOption(
    val title: String,
    val normalizedUrl: String,
    val formatSelector: String,
    val resolutionLabel: String,
    val formatNote: String,
    val estimatedSizeBytes: Long?,
) : Serializable {
    val subtitle: String
        get() = buildString {
            append(resolutionLabel)
            estimatedSizeBytes?.let {
                append(" • ")
                append(formatBytes(it))
            }
            if (formatNote.isNotBlank()) {
                append(" • ")
                append(formatNote)
            }
        }
}

data class VideoDownloadChoices(
    val title: String,
    val options: List<VideoDownloadOption>,
)

data class FacebookDownloadOption(
    val title: String,
    val normalizedUrl: String,
    val formatSelector: String,
    val resolutionLabel: String,
    val formatNote: String,
    val estimatedSizeBytes: Long?,
    val extension: String?,
    val directDownloadUrl: String? = null,
    val requestHeaders: HashMap<String, String>? = null,
) : Serializable {
    val subtitle: String
        get() = buildString {
            append(resolutionLabel)
            estimatedSizeBytes?.let {
                append(" • ")
                append(formatBytes(it))
            }
            if (formatNote.isNotBlank()) {
                append(" • ")
                append(formatNote)
            }
        }
}

enum class InstagramMediaType : Serializable {
    PHOTO,
    VIDEO,
}

data class InstagramDownloadOption(
    val postTitle: String,
    val normalizedUrl: String,
    val playlistIndex: Int?,
    val mediaId: String?,
    val cardTitle: String,
    val notificationTitle: String,
    val mediaType: InstagramMediaType,
    val extension: String?,
    val estimatedSizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val directDownloadUrl: String? = null,
    val previewUrl: String? = null,
) : Serializable {
    val subtitle: String
        get() = buildString {
            append(if (mediaType == InstagramMediaType.VIDEO) "Video" else "Photo")
            extension?.takeIf { it.isNotBlank() }?.let {
                append(" • ")
                append(it.uppercase())
            }
            estimatedSizeBytes?.let {
                append(" • ")
                append(formatBytes(it))
            }
            val dimensionText = listOfNotNull(width?.takeIf { it > 0 }, height?.takeIf { it > 0 })
                .takeIf { it.size == 2 }
                ?.joinToString("x")
            if (!dimensionText.isNullOrBlank()) {
                append(" • ")
                append(dimensionText)
            }
        }

    val actionLabel: String
        get() = if (mediaType == InstagramMediaType.VIDEO) "Download video" else "Download photo"
}

data class InstagramDownloadChoices(
    val title: String,
    val items: List<InstagramDownloadOption>,
)

data class InstagramBatchDownloadRequest(
    val postTitle: String,
    val items: ArrayList<InstagramDownloadOption>,
) : Serializable

class StoragePermissionRequiredException : Exception(
    "Storage permission is required on Android 9 and below to save into Downloads."
)

internal object YoutubeDlRuntime {
    private const val PREFS_NAME = "yt_dlp_runtime"
    private const val LAST_UPDATE_KEY = "last_update_ms"
    private const val UPDATE_INTERVAL_MS = 3L * 24L * 60L * 60L * 1000L

    private val initMutex = Mutex()
    private var initialized = false

    suspend fun ensureReady(context: Context) {
        val appContext = context.applicationContext
        initMutex.withLock {
            if (!initialized) {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(appContext)
                    FFmpeg.getInstance().init(appContext)
                }
                initialized = true
            }

            withContext(Dispatchers.IO) {
                maybeUpdate(appContext)
            }
        }
    }

    private fun maybeUpdate(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUpdate = prefs.getLong(LAST_UPDATE_KEY, 0L)
        val now = System.currentTimeMillis()
        if (now - lastUpdate < UPDATE_INTERVAL_MS) return

        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
        }.onSuccess {
            prefs.edit().putLong(LAST_UPDATE_KEY, now).apply()
        }
    }
}

internal object DownloadNotificationHelper {
    private const val CHANNEL_ID = "yt_saver_downloads"

    fun buildProgressNotification(
        context: Context,
        title: String,
        message: String,
        progress: Int?,
    ) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText(cleanNotificationLine(message))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setProgress(100, progress ?: 0, progress == null)
        .build()

    fun showProgress(
        context: Context,
        notificationId: Int,
        title: String,
        message: String,
        progress: Int?,
    ) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)

        NotificationManagerCompat.from(context).notify(
            notificationId,
            buildProgressNotification(context, title, message, progress),
        )
    }

    fun showComplete(context: Context, notificationId: Int, title: String, fileName: String) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("$fileName saved to Downloads")
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun showFailure(context: Context, notificationId: Int, title: String, message: String) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    private fun cleanNotificationLine(line: String): String =
        line.removePrefix("[download]").removePrefix("[Merger]").trim().ifBlank { "Downloading..." }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "JnDe Tools Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows media download progress"
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }
}

fun startYouTubeDownloadService(context: Context, option: VideoDownloadOption) {
    val intent = Intent(context, YouTubeDownloadService::class.java).apply {
        putExtra(YouTubeDownloadService.EXTRA_OPTION, option)
    }
    ContextCompat.startForegroundService(context, intent)
}

fun startFacebookDownloadService(context: Context, option: FacebookDownloadOption) {
    val intent = Intent(context, FacebookDownloadService::class.java).apply {
        putExtra(FacebookDownloadService.EXTRA_OPTION, option)
    }
    ContextCompat.startForegroundService(context, intent)
}

fun startInstagramDownloadService(context: Context, option: InstagramDownloadOption) {
    val intent = Intent(context, InstagramDownloadService::class.java).apply {
        putExtra(InstagramDownloadService.EXTRA_OPTION, option)
    }
    ContextCompat.startForegroundService(context, intent)
}

fun startInstagramBatchDownloadService(context: Context, choices: InstagramDownloadChoices) {
    val intent = Intent(context, InstagramBatchDownloadService::class.java).apply {
        putExtra(
            InstagramBatchDownloadService.EXTRA_REQUEST,
            InstagramBatchDownloadRequest(
                postTitle = choices.title,
                items = ArrayList(choices.items),
            ),
        )
    }
    ContextCompat.startForegroundService(context, intent)
}

suspend fun fetchYouTubeDownloadChoices(
    context: Context,
    rawUrl: String,
): VideoDownloadChoices = withContext(Dispatchers.IO) {
    val url = rawUrl.trim()
    require(url.isNotBlank()) { "Paste a YouTube link first." }
    val normalizedUrl = normalizeYoutubeVideoUrl(url)

    YoutubeDlRuntime.ensureReady(context)
    val info = YoutubeDL.getInstance().getInfo(
        YoutubeDLRequest(normalizedUrl).apply { addOption("--no-playlist") }
    )

    val title = info.title?.takeIf { it.isNotBlank() } ?: "YouTube Video"
    val options = buildDownloadOptions(normalizedUrl, title, info)

    if (options.isEmpty()) {
        throw Exception("No downloadable MP4 resolutions were found for this video.")
    }

    VideoDownloadChoices(title = title, options = options)
}

suspend fun fetchFacebookDownloadOption(
    context: Context,
    rawUrl: String,
): FacebookDownloadOption = withContext(Dispatchers.IO) {
    val url = rawUrl.trim()
    require(url.isNotBlank()) { "Paste a Facebook video link first." }
    val normalizedUrl = normalizeFacebookVideoUrl(url)

    YoutubeDlRuntime.ensureReady(context)
    val info = YoutubeDL.getInstance().getInfo(
        YoutubeDLRequest(normalizedUrl).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
        }
    )

    val title = info.title?.takeIf { it.isNotBlank() } ?: "Facebook Video"
    val bestDirectFormat = info.formats
        ?.asSequence()
        ?.filter { format ->
            isDirectVideoExtension(format.ext) &&
                format.url?.isNotBlank() == true &&
                format.vcodec != "none" &&
                format.acodec != "none"
        }
        ?.maxWithOrNull(
            compareBy<VideoFormat> { estimatedBytes(it) <= 0L }
                .thenByDescending { it.height }
                .thenByDescending { estimatedBytes(it) }
                .thenByDescending { it.tbr }
                .thenByDescending { it.fps }
        )

    val bestPreview = info.formats
        ?.asSequence()
        ?.filter { format ->
            format.vcodec != "none" &&
                isDirectVideoExtension(format.ext)
        }
        ?.maxWithOrNull(
            compareBy<VideoFormat> { estimatedBytes(it) <= 0L }
                .thenByDescending { it.height }
                .thenByDescending { estimatedBytes(it) }
                .thenByDescending { it.tbr }
        )

    val directDownloadUrl = bestDirectFormat?.url
        ?.takeIf { it.isNotBlank() }
        ?: info.url
            ?.takeIf { it.isNotBlank() && isDirectVideoExtension(info.ext) }
    val directHeaders = bestDirectFormat?.httpHeaders?.let(::HashMap)
        ?: info.httpHeaders?.let(::HashMap)
    val formatSelector = if (directDownloadUrl != null) {
        "best direct mp4"
    } else {
        "best[ext=mp4][vcodec!=none][acodec!=none]/best[ext=m4v][vcodec!=none][acodec!=none]/best[ext=mov][vcodec!=none][acodec!=none]/bestvideo*+bestaudio/best"
    }

    FacebookDownloadOption(
        title = title,
        normalizedUrl = normalizedUrl,
        formatSelector = formatSelector,
        resolutionLabel = (bestDirectFormat ?: bestPreview)?.height?.takeIf { it > 0 }?.let { "${it}p" }
            ?: info.resolution?.takeIf { it.isNotBlank() }
            ?: "Best available",
        formatNote = if (directDownloadUrl != null) "direct file" else "best available",
        estimatedSizeBytes = (bestDirectFormat ?: bestPreview)?.let(::estimatedBytes)?.takeIf { it > 0L }
            ?: info.fileSize.takeIf { it > 0L }
            ?: info.fileSizeApproximate.takeIf { it > 0L },
        extension = bestDirectFormat?.ext?.takeIf { it.isNotBlank() } ?: info.ext?.takeIf { it.isNotBlank() },
        directDownloadUrl = directDownloadUrl,
        requestHeaders = directHeaders,
    )
}

suspend fun fetchInstagramDownloadChoices(
    context: Context,
    rawUrl: String,
): InstagramDownloadChoices = withContext(Dispatchers.IO) {
    val url = rawUrl.trim()
    require(url.isNotBlank()) { "Paste an Instagram link first." }
    val normalizedUrl = normalizeInstagramMediaUrl(url)

    if (isInstagramPostUrl(normalizedUrl)) {
        return@withContext fetchInstagramEmbedDownloadChoices(normalizedUrl)
    }

    YoutubeDlRuntime.ensureReady(context)
    val request = YoutubeDLRequest(normalizedUrl).apply {
        addOption("--dump-single-json")
        addOption("--no-warnings")
    }
    val response = YoutubeDL.getInstance().execute(request)
    val json = JSONObject(extractJsonPayload(response.out))
    buildInstagramDownloadChoices(normalizedUrl, json)
}

suspend fun downloadYouTubeVideo(
    context: Context,
    option: VideoDownloadOption,
    onProgress: (DownloadProgress) -> Unit,
): CompletedDownload = withContext(Dispatchers.IO) {
    val videoId = option.normalizedUrl.substringAfter("v=", missingDelimiterValue = "")
        .ifBlank { System.currentTimeMillis().toString() }
    val safeBaseName = sanitizeFileName("${option.title} [$videoId]")
        .ifBlank { "youtube_video_$videoId" }
    downloadWithYtDlp(
        context = context,
        sourceUrl = option.normalizedUrl,
        safeBaseName = safeBaseName,
        notificationTitle = option.title,
        startMessage = "Starting ${option.resolutionLabel} download...",
        onProgress = onProgress,
    ) {
        addOption("--no-playlist")
        addOption("-f", option.formatSelector)
        addOption("--merge-output-format", "mp4")
    }
}

suspend fun downloadFacebookVideo(
    context: Context,
    option: FacebookDownloadOption,
    onProgress: (DownloadProgress) -> Unit,
): CompletedDownload = withContext(Dispatchers.IO) {
    val videoId = option.normalizedUrl.substringAfter("v=", missingDelimiterValue = "")
        .substringBefore('&')
        .ifBlank {
            option.normalizedUrl.substringAfterLast('/').substringBefore('?')
        }
        .ifBlank { System.currentTimeMillis().toString() }
    val safeBaseName = sanitizeFileName("${option.title} [$videoId]")
        .ifBlank { "facebook_video_$videoId" }

    val directUrl = option.directDownloadUrl
    if (!directUrl.isNullOrBlank()) {
        return@withContext downloadDirectMediaFile(
            context = context,
            directUrl = directUrl,
            safeBaseName = safeBaseName,
            notificationTitle = option.title,
            startMessage = "Starting ${option.resolutionLabel.lowercase()} download...",
            expectedExtension = option.extension,
            onProgress = onProgress,
            refererUrl = option.normalizedUrl,
            requestHeaders = option.requestHeaders.orEmpty(),
        )
    }

    downloadWithYtDlp(
        context = context,
        sourceUrl = option.normalizedUrl,
        safeBaseName = safeBaseName,
        notificationTitle = option.title,
        startMessage = "Starting ${option.resolutionLabel.lowercase()} download...",
        onProgress = onProgress,
    ) {
        addOption("--no-playlist")
        addOption("-f", option.formatSelector)
        addOption("--merge-output-format", "mp4")
    }
}

suspend fun downloadInstagramMedia(
    context: Context,
    option: InstagramDownloadOption,
    notificationIdOverride: Int? = null,
    notificationTitleOverride: String? = null,
    showCompletionNotification: Boolean = true,
    onProgress: (DownloadProgress) -> Unit,
): CompletedDownload = withContext(Dispatchers.IO) {
    val itemId = option.mediaId?.takeIf { it.isNotBlank() }
        ?: option.playlistIndex?.toString()
        ?: System.currentTimeMillis().toString()
    val safeBaseName = sanitizeFileName("${option.postTitle} [${option.cardTitle}] [$itemId]")
        .ifBlank { "instagram_media_$itemId" }

    val directUrl = option.directDownloadUrl
    if (!directUrl.isNullOrBlank()) {
        return@withContext downloadDirectMediaFile(
            context = context,
            directUrl = directUrl,
            safeBaseName = safeBaseName,
            notificationTitle = option.notificationTitle,
            startMessage = "Starting ${option.cardTitle.lowercase()} download...",
            expectedExtension = option.extension,
            onProgress = onProgress,
            notificationIdOverride = notificationIdOverride,
            notificationTitleOverride = notificationTitleOverride,
            showCompletionNotification = showCompletionNotification,
            refererUrl = option.normalizedUrl,
        )
    }

    downloadWithYtDlp(
        context = context,
        sourceUrl = option.normalizedUrl,
        safeBaseName = safeBaseName,
        notificationTitle = option.notificationTitle,
        startMessage = "Starting ${option.cardTitle.lowercase()} download...",
        onProgress = onProgress,
        notificationIdOverride = notificationIdOverride,
        notificationTitleOverride = notificationTitleOverride,
        showCompletionNotification = showCompletionNotification,
    ) {
        addOption("--no-warnings")
        option.playlistIndex?.let { addOption("--playlist-items", it.toString()) }
        if (option.mediaType == InstagramMediaType.VIDEO) {
            addOption("-f", "bestvideo*+bestaudio/best")
            addOption("--merge-output-format", "mp4")
        }
    }
}

private suspend fun downloadWithYtDlp(
    context: Context,
    sourceUrl: String,
    safeBaseName: String,
    notificationTitle: String,
    startMessage: String,
    onProgress: (DownloadProgress) -> Unit,
    notificationIdOverride: Int? = null,
    notificationTitleOverride: String? = null,
    showCompletionNotification: Boolean = true,
    configureRequest: YoutubeDLRequest.() -> Unit,
): CompletedDownload = withContext(Dispatchers.IO) {
    ensureStoragePermissionIfNeeded(context)
    YoutubeDlRuntime.ensureReady(context)

    val mainHandler = Handler(Looper.getMainLooper())
    fun postProgress(progress: DownloadProgress) {
        mainHandler.post { onProgress(progress) }
    }

    val tempDir = File(context.cacheDir, "yt-dlp/${safeBaseName.take(48)}-${UUID.randomUUID()}").apply {
        mkdirs()
    }
    val outputTemplate = File(tempDir, "$safeBaseName.%(ext)s").absolutePath
    val processId = "media-download-${System.currentTimeMillis()}-${safeBaseName.hashCode()}"
    val notificationId = notificationIdOverride ?: notificationIdFor("$sourceUrl|$safeBaseName")
    val effectiveNotificationTitle = notificationTitleOverride ?: notificationTitle

    postProgress(DownloadProgress(percent = null, status = startMessage))
    DownloadNotificationHelper.showProgress(
        context = context,
        notificationId = notificationId,
        title = effectiveNotificationTitle,
        message = startMessage,
        progress = null,
    )

    try {
        val callback: (Float, Long, String) -> Unit = { progress, _, line ->
            val rounded = progress.takeIf { !it.isNaN() }?.toInt()?.coerceIn(0, 100)
            val message = line.trim().ifBlank {
                rounded?.let { "Downloading... $it%" } ?: "Downloading..."
            }
            postProgress(DownloadProgress(percent = rounded, status = message))
            DownloadNotificationHelper.showProgress(
                context = context,
                notificationId = notificationId,
                title = effectiveNotificationTitle,
                message = message,
                progress = rounded,
            )
        }

        val request = YoutubeDLRequest(sourceUrl).apply {
            addOption("--newline")
            addOption("--no-mtime")
            addOption("-o", outputTemplate)
            configureRequest()
        }

        YoutubeDL.getInstance().execute(request, processId, callback)

        val downloadedFile = tempDir
            .listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.lastModified() }
            ?: throw IOException("The download finished but no file was created.")

        postProgress(DownloadProgress(percent = 100, status = "Saving to Downloads..."))
        DownloadNotificationHelper.showProgress(
            context = context,
            notificationId = notificationId,
            title = effectiveNotificationTitle,
            message = "Saving to Downloads...",
            progress = 100,
        )

        val savedUri = saveToDownloads(context, downloadedFile)
        if (showCompletionNotification) {
            DownloadNotificationHelper.showComplete(
                context = context,
                notificationId = notificationId,
                title = effectiveNotificationTitle,
                fileName = downloadedFile.name,
            )
        }

        CompletedDownload(
            title = effectiveNotificationTitle,
            uri = savedUri,
            fileName = downloadedFile.name,
        )
    } catch (e: YoutubeDLException) {
        DownloadNotificationHelper.showFailure(
            context = context,
            notificationId = notificationId,
            title = effectiveNotificationTitle,
            message = e.message ?: "Download failed",
        )
        throw Exception(e.message ?: "yt-dlp failed to download this media.", e)
    } catch (e: Exception) {
        DownloadNotificationHelper.showFailure(
            context = context,
            notificationId = notificationId,
            title = effectiveNotificationTitle,
            message = e.message ?: "Download failed",
        )
        throw e
    } finally {
        tempDir.deleteRecursively()
    }
}

private suspend fun downloadDirectMediaFile(
    context: Context,
    directUrl: String,
    safeBaseName: String,
    notificationTitle: String,
    startMessage: String,
    expectedExtension: String?,
    onProgress: (DownloadProgress) -> Unit,
    notificationIdOverride: Int? = null,
    notificationTitleOverride: String? = null,
    showCompletionNotification: Boolean = true,
    refererUrl: String? = null,
    requestHeaders: Map<String, String> = emptyMap(),
): CompletedDownload = withContext(Dispatchers.IO) {
    ensureStoragePermissionIfNeeded(context)

    val mainHandler = Handler(Looper.getMainLooper())
    fun postProgress(progress: DownloadProgress) {
        mainHandler.post { onProgress(progress) }
    }

    val notificationId = notificationIdOverride ?: notificationIdFor("$directUrl|$safeBaseName")
    val effectiveNotificationTitle = notificationTitleOverride ?: notificationTitle
    val extension = expectedExtension
        ?.ifBlank { null }
        ?: extensionFromUrl(directUrl)
        ?: if (directUrl.contains(".mp4", ignoreCase = true)) "mp4" else "jpg"
    val tempDir = File(context.cacheDir, "direct-media/${safeBaseName.take(48)}-${UUID.randomUUID()}").apply {
        mkdirs()
    }
    val tempFile = File(tempDir, "$safeBaseName.$extension")

    postProgress(DownloadProgress(percent = null, status = startMessage))
    DownloadNotificationHelper.showProgress(
        context = context,
        notificationId = notificationId,
        title = effectiveNotificationTitle,
        message = startMessage,
        progress = null,
    )

    try {
        val connection = (URL(directUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", DEFAULT_HTTP_USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Referer", refererUrl ?: "https://www.instagram.com/")
            requestHeaders.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) {
                    setRequestProperty(key, value)
                }
            }
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Instagram returned HTTP $responseCode while downloading media.")
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloadedBytes = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        val percent = totalBytes?.let {
                            ((downloadedBytes * 100) / it).toInt().coerceIn(0, 100)
                        }
                        if (percent != null && percent != lastPercent) {
                            lastPercent = percent
                            val message = "Downloading... $percent%"
                            postProgress(DownloadProgress(percent = percent, status = message))
                            DownloadNotificationHelper.showProgress(
                                context = context,
                                notificationId = notificationId,
                                title = effectiveNotificationTitle,
                                message = message,
                                progress = percent,
                            )
                        } else if (percent == null) {
                            postProgress(DownloadProgress(percent = null, status = "Downloading..."))
                            DownloadNotificationHelper.showProgress(
                                context = context,
                                notificationId = notificationId,
                                title = effectiveNotificationTitle,
                                message = "Downloading...",
                                progress = null,
                            )
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        if (!tempFile.exists() || tempFile.length() <= 0L) {
            throw IOException("The download finished but no file was created.")
        }

        postProgress(DownloadProgress(percent = 100, status = "Saving to Downloads..."))
        DownloadNotificationHelper.showProgress(
            context = context,
            notificationId = notificationId,
            title = effectiveNotificationTitle,
            message = "Saving to Downloads...",
            progress = 100,
        )

        val savedUri = saveToDownloads(context, tempFile)
        if (showCompletionNotification) {
            DownloadNotificationHelper.showComplete(
                context = context,
                notificationId = notificationId,
                title = effectiveNotificationTitle,
                fileName = tempFile.name,
            )
        }

        CompletedDownload(
            title = effectiveNotificationTitle,
            uri = savedUri,
            fileName = tempFile.name,
        )
    } catch (e: Exception) {
        DownloadNotificationHelper.showFailure(
            context = context,
            notificationId = notificationId,
            title = effectiveNotificationTitle,
            message = e.message ?: "Download failed",
        )
        throw e
    } finally {
        tempDir.deleteRecursively()
    }
}

private fun ensureStoragePermissionIfNeeded(context: Context) {
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        throw StoragePermissionRequiredException()
    }
}

class YouTubeDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val option = intent.getSerializableCompat(EXTRA_OPTION, VideoDownloadOption::class.java)
        if (option == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notificationId = notificationIdFor(option.normalizedUrl)
        DownloadNotificationHelper.ensureChannel(applicationContext)
        startForegroundCompat(
            notificationId,
            DownloadNotificationHelper.buildProgressNotification(
                applicationContext,
                option.title,
                "Starting ${option.resolutionLabel} download...",
                null,
            ),
        )

        serviceScope.launch {
            try {
                downloadYouTubeVideo(applicationContext, option) { progress ->
                    sendProgressBroadcast(
                        title = option.title,
                        status = progress.status,
                        percent = progress.percent,
                    )
                }
                sendCompleteBroadcast(option.title)
            } catch (e: Exception) {
                sendFailureBroadcast(option.title, e.message ?: "Download failed")
            } finally {
                stopForegroundCompat()
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun sendProgressBroadcast(title: String, status: String, percent: Int?) {
        sendBroadcast(
            Intent(ACTION_DOWNLOAD_PROGRESS).apply {
                setPackage(packageName)
                putExtra(EXTRA_DOWNLOAD_PLATFORM, PLATFORM_YOUTUBE)
                putExtra(EXTRA_DOWNLOAD_TITLE, title)
                putExtra(EXTRA_DOWNLOAD_STATUS, status)
                if (percent != null) {
                    putExtra(EXTRA_DOWNLOAD_PERCENT, percent)
                }
            }
        )
    }

    private fun sendCompleteBroadcast(title: String) {
        sendBroadcast(
            Intent(ACTION_DOWNLOAD_COMPLETE).apply {
                setPackage(packageName)
                putExtra(EXTRA_DOWNLOAD_PLATFORM, PLATFORM_YOUTUBE)
                putExtra(EXTRA_DOWNLOAD_TITLE, title)
            }
        )
    }

    private fun sendFailureBroadcast(title: String, error: String) {
        sendBroadcast(
            Intent(ACTION_DOWNLOAD_FAILED).apply {
                setPackage(packageName)
                putExtra(EXTRA_DOWNLOAD_PLATFORM, PLATFORM_YOUTUBE)
                putExtra(EXTRA_DOWNLOAD_TITLE, title)
                putExtra(EXTRA_DOWNLOAD_ERROR, error)
            }
        )
    }

    private fun startForegroundCompat(notificationId: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfoTypes.dataSync)
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    companion object {
        const val EXTRA_OPTION = "extra_download_option"
    }
}

class FacebookDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val option = intent.getSerializableCompat(EXTRA_OPTION, FacebookDownloadOption::class.java)
        if (option == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notificationId = notificationIdFor(option.normalizedUrl)
        DownloadNotificationHelper.ensureChannel(applicationContext)
        startForegroundCompat(
            notificationId,
            DownloadNotificationHelper.buildProgressNotification(
                applicationContext,
                option.title,
                "Starting ${option.resolutionLabel.lowercase()} download...",
                null,
            ),
        )

        serviceScope.launch {
            try {
                downloadFacebookVideo(applicationContext, option) { progress ->
                    sendProgressBroadcast(
                        title = option.title,
                        status = progress.status,
                        percent = progress.percent,
                    )
                }
                sendCompleteBroadcast(option.title)
            } catch (e: Exception) {
                sendFailureBroadcast(option.title, e.message ?: "Download failed")
            } finally {
                stopForegroundCompat()
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun sendProgressBroadcast(title: String, status: String, percent: Int?) {
        sendBroadcast(
            Intent(ACTION_DOWNLOAD_PROGRESS).apply {
                setPackage(packageName)
                putExtra(EXTRA_DOWNLOAD_PLATFORM, PLATFORM_FACEBOOK)
                putExtra(EXTRA_DOWNLOAD_TITLE, title)
                putExtra(EXTRA_DOWNLOAD_STATUS, status)
                if (percent != null) {
                    putExtra(EXTRA_DOWNLOAD_PERCENT, percent)
                }
            }
        )
    }

    private fun sendCompleteBroadcast(title: String) {
        sendBroadcast(
            Intent(ACTION_DOWNLOAD_COMPLETE).apply {
                setPackage(packageName)
                putExtra(EXTRA_DOWNLOAD_PLATFORM, PLATFORM_FACEBOOK)
                putExtra(EXTRA_DOWNLOAD_TITLE, title)
            }
        )
    }

    private fun sendFailureBroadcast(title: String, error: String) {
        sendBroadcast(
            Intent(ACTION_DOWNLOAD_FAILED).apply {
                setPackage(packageName)
                putExtra(EXTRA_DOWNLOAD_PLATFORM, PLATFORM_FACEBOOK)
                putExtra(EXTRA_DOWNLOAD_TITLE, title)
                putExtra(EXTRA_DOWNLOAD_ERROR, error)
            }
        )
    }

    private fun startForegroundCompat(notificationId: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfoTypes.dataSync)
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    companion object {
        const val EXTRA_OPTION = "extra_facebook_download_option"
    }
}

class InstagramDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val option = intent.getSerializableCompat(EXTRA_OPTION, InstagramDownloadOption::class.java)
        if (option == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notificationId = notificationIdFor("${option.normalizedUrl}|${option.cardTitle}")
        DownloadNotificationHelper.ensureChannel(applicationContext)
        startForegroundCompat(
            notificationId,
            DownloadNotificationHelper.buildProgressNotification(
                applicationContext,
                option.notificationTitle,
                "Starting ${option.cardTitle.lowercase()} download...",
                null,
            ),
        )

        serviceScope.launch {
            try {
                downloadInstagramMedia(applicationContext, option) { progress ->
                    sendProgressBroadcast(
                        title = option.notificationTitle,
                        status = progress.status,
                        percent = progress.percent,
                    )
                }
                sendCompleteBroadcast(option.notificationTitle)
            } catch (e: Exception) {
                sendFailureBroadcast(option.notificationTitle, e.message ?: "Download failed")
            } finally {
                stopForegroundCompat()
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun sendProgressBroadcast(title: String, status: String, percent: Int?) {
        sendBroadcast(
            Intent(ACTION_DOWNLOAD_PROGRESS).apply {
                setPackage(packageName)
                putExtra(EXTRA_DOWNLOAD_PLATFORM, PLATFORM_INSTAGRAM)
                putExtra(EXTRA_DOWNLOAD_TITLE, title)
                putExtra(EXTRA_DOWNLOAD_STATUS, status)
                if (percent != null) {
                    putExtra(EXTRA_DOWNLOAD_PERCENT, percent)
                }
            }
        )
    }

    private fun sendCompleteBroadcast(title: String) {
        sendBroadcast(
            Intent(ACTION_DOWNLOAD_COMPLETE).apply {
                setPackage(packageName)
                putExtra(EXTRA_DOWNLOAD_PLATFORM, PLATFORM_INSTAGRAM)
                putExtra(EXTRA_DOWNLOAD_TITLE, title)
            }
        )
    }

    private fun sendFailureBroadcast(title: String, error: String) {
        sendBroadcast(
            Intent(ACTION_DOWNLOAD_FAILED).apply {
                setPackage(packageName)
                putExtra(EXTRA_DOWNLOAD_PLATFORM, PLATFORM_INSTAGRAM)
                putExtra(EXTRA_DOWNLOAD_TITLE, title)
                putExtra(EXTRA_DOWNLOAD_ERROR, error)
            }
        )
    }

    private fun startForegroundCompat(notificationId: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfoTypes.dataSync)
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    companion object {
        const val EXTRA_OPTION = "extra_instagram_download_option"
    }
}

class InstagramBatchDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent.getSerializableCompat(EXTRA_REQUEST, InstagramBatchDownloadRequest::class.java)
        if (request == null || request.items.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val batchNotificationId = notificationIdFor("instagram-batch|${request.postTitle}|${request.items.size}")
        DownloadNotificationHelper.ensureChannel(applicationContext)
        startForegroundCompat(
            batchNotificationId,
            DownloadNotificationHelper.buildProgressNotification(
                applicationContext,
                request.postTitle,
                "Starting download all...",
                null,
            ),
        )

        serviceScope.launch {
            try {
                request.items.forEachIndexed { index, option ->
                    val currentLabel = "${index + 1}/${request.items.size}"
                    downloadInstagramMedia(
                        context = applicationContext,
                        option = option,
                        notificationIdOverride = batchNotificationId,
                        notificationTitleOverride = "${request.postTitle} ($currentLabel)",
                        showCompletionNotification = false,
                    ) { progress ->
                        sendProgressBroadcast(
                            title = request.postTitle,
                            status = "[$currentLabel] ${option.cardTitle}: ${progress.status}",
                            percent = progress.percent,
                        )
                    }
                }

                DownloadNotificationHelper.showComplete(
                    context = applicationContext,
                    notificationId = batchNotificationId,
                    title = request.postTitle,
                    fileName = "${request.items.size} items",
                )
                sendCompleteBroadcast("${request.postTitle} (${request.items.size} items)")
            } catch (e: Exception) {
                DownloadNotificationHelper.showFailure(
                    context = applicationContext,
                    notificationId = batchNotificationId,
                    title = request.postTitle,
                    message = e.message ?: "Download failed",
                )
                sendFailureBroadcast(request.postTitle, e.message ?: "Download failed")
            } finally {
                stopForegroundCompat()
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun sendProgressBroadcast(title: String, status: String, percent: Int?) {
        sendBroadcast(
            Intent(ACTION_DOWNLOAD_PROGRESS).apply {
                setPackage(packageName)
                putExtra(EXTRA_DOWNLOAD_PLATFORM, PLATFORM_INSTAGRAM)
                putExtra(EXTRA_DOWNLOAD_TITLE, title)
                putExtra(EXTRA_DOWNLOAD_STATUS, status)
                if (percent != null) {
                    putExtra(EXTRA_DOWNLOAD_PERCENT, percent)
                }
            }
        )
    }

    private fun sendCompleteBroadcast(title: String) {
        sendBroadcast(
            Intent(ACTION_DOWNLOAD_COMPLETE).apply {
                setPackage(packageName)
                putExtra(EXTRA_DOWNLOAD_PLATFORM, PLATFORM_INSTAGRAM)
                putExtra(EXTRA_DOWNLOAD_TITLE, title)
            }
        )
    }

    private fun sendFailureBroadcast(title: String, error: String) {
        sendBroadcast(
            Intent(ACTION_DOWNLOAD_FAILED).apply {
                setPackage(packageName)
                putExtra(EXTRA_DOWNLOAD_PLATFORM, PLATFORM_INSTAGRAM)
                putExtra(EXTRA_DOWNLOAD_TITLE, title)
                putExtra(EXTRA_DOWNLOAD_ERROR, error)
            }
        )
    }

    private fun startForegroundCompat(notificationId: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfoTypes.dataSync)
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    companion object {
        const val EXTRA_REQUEST = "extra_instagram_batch_download_request"
    }
}

private suspend fun fetchInstagramEmbedDownloadChoices(normalizedUrl: String): InstagramDownloadChoices {
    val embedUrl = normalizedUrl.trimEnd('/') + "/embed/captioned/"
    val userAgents = listOf(
        "Mozilla/5.0",
        INSTAGRAM_EMBED_DESKTOP_USER_AGENT,
        INSTAGRAM_WEB_USER_AGENT,
    )

    var lastError: Exception? = null
    for (userAgent in userAgents) {
        val html = try {
            fetchInstagramHtml(embedUrl, refererUrl = normalizedUrl, userAgent = userAgent)
        } catch (e: Exception) {
            lastError = e
            continue
        }

        try {
            val media = extractInstagramEmbedShortcodeMedia(html)
            return buildInstagramEmbedDownloadChoices(normalizedUrl, media)
        } catch (e: Exception) {
            lastError = e
        }
    }

    throw lastError ?: IOException("Couldn't read Instagram post details from the embed page.")
}

private fun buildInstagramEmbedDownloadChoices(
    normalizedUrl: String,
    media: JSONObject,
): InstagramDownloadChoices {
    val postTitle = media.optCaptionText()
        ?: media.optString("title").takeIf { it.isNotBlank() }
        ?: "Instagram Post"

    val edges = media.optJSONObject("edge_sidecar_to_children")
        ?.optJSONArray("edges")

    val items = if (edges != null && edges.length() > 0) {
        buildList {
            for (index in 0 until edges.length()) {
                val node = edges.optJSONObject(index)?.optJSONObject("node") ?: continue
                buildInstagramEmbedItem(
                    postTitle = postTitle,
                    normalizedUrl = normalizedUrl,
                    item = node,
                    itemIndex = index + 1,
                    totalItems = edges.length(),
                )?.let(::add)
            }
        }
    } else {
        listOfNotNull(
            buildInstagramEmbedItem(
                postTitle = postTitle,
                normalizedUrl = normalizedUrl,
                item = media,
                itemIndex = 1,
                totalItems = 1,
            )
        )
    }

    if (items.isEmpty()) {
        throw Exception("No downloadable media were found for this Instagram post.")
    }

    return InstagramDownloadChoices(title = postTitle, items = items)
}

private fun buildInstagramEmbedItem(
    postTitle: String,
    normalizedUrl: String,
    item: JSONObject,
    itemIndex: Int,
    totalItems: Int,
): InstagramDownloadOption? {
    val mediaType = when {
        item.optBoolean("is_video") -> InstagramMediaType.VIDEO
        item.optString("__typename").contains("Video", ignoreCase = true) -> InstagramMediaType.VIDEO
        else -> InstagramMediaType.PHOTO
    }

    val previewUrl = item.optString("display_url")
        .takeIf { it.isNotBlank() }
        ?: item.optBestDisplayResource()
        ?: item.optString("thumbnail_src").takeIf { it.isNotBlank() }
        ?: item.optString("thumbnail").takeIf { it.isNotBlank() }

    val directDownloadUrl = when (mediaType) {
        InstagramMediaType.VIDEO -> item.optString("video_url").takeIf { it.isNotBlank() } ?: previewUrl
        InstagramMediaType.PHOTO -> previewUrl
    } ?: return null

    val mediaId = item.optString("id").takeIf { it.isNotBlank() }
    val dimensions = item.optJSONObject("dimensions")
    val width = dimensions?.optPositiveInt("width") ?: item.optPositiveInt("width")
    val height = dimensions?.optPositiveInt("height") ?: item.optPositiveInt("height")
    val extension = extensionFromUrl(directDownloadUrl)
        ?: if (mediaType == InstagramMediaType.VIDEO) "mp4" else "jpg"

    val cardTitle = when {
        totalItems > 1 -> "${if (mediaType == InstagramMediaType.VIDEO) "Video" else "Photo"} $itemIndex"
        mediaType == InstagramMediaType.VIDEO -> "Reel Video"
        else -> "Post Photo"
    }
    val notificationTitle = if (totalItems > 1) "$postTitle - $cardTitle" else postTitle

    return InstagramDownloadOption(
        postTitle = postTitle,
        normalizedUrl = normalizedUrl,
        playlistIndex = itemIndex,
        mediaId = mediaId,
        cardTitle = cardTitle,
        notificationTitle = notificationTitle,
        mediaType = mediaType,
        extension = extension,
        estimatedSizeBytes = null,
        width = width,
        height = height,
        directDownloadUrl = directDownloadUrl,
        previewUrl = previewUrl,
    )
}

private fun buildInstagramDownloadChoices(
    normalizedUrl: String,
    root: JSONObject,
): InstagramDownloadChoices {
    val postTitle = root.optString("title")
        .ifBlank { root.optString("fulltitle") }
        .ifBlank { "Instagram Post" }

    val entries = root.optJSONArray("entries")
    val items = if (entries != null && entries.length() > 0) {
        buildList {
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                buildInstagramDownloadItem(
                    postTitle = postTitle,
                    normalizedUrl = normalizedUrl,
                    item = item,
                    playlistIndex = index + 1,
                    totalItems = entries.length(),
                )?.let(::add)
            }
        }
    } else {
        listOfNotNull(
            buildInstagramDownloadItem(
                postTitle = postTitle,
                normalizedUrl = normalizedUrl,
                item = root,
                playlistIndex = null,
                totalItems = 1,
            )
        )
    }

    if (items.isEmpty()) {
        throw Exception("No downloadable media were found for this Instagram link.")
    }

    return InstagramDownloadChoices(title = postTitle, items = items)
}

private fun buildInstagramDownloadItem(
    postTitle: String,
    normalizedUrl: String,
    item: JSONObject,
    playlistIndex: Int?,
    totalItems: Int,
): InstagramDownloadOption? {
    val mediaId = item.optString("id")
        .ifBlank { item.optString("display_id") }
        .ifBlank { null }

    val extension = item.optString("ext")
        .ifBlank {
            item.optString("url").substringAfterLast('.', missingDelimiterValue = "").substringBefore('?')
        }
        .ifBlank {
            item.optString("display_url").substringAfterLast('.', missingDelimiterValue = "").substringBefore('?')
        }
        .ifBlank {
            item.optString("thumbnail").substringAfterLast('.', missingDelimiterValue = "").substringBefore('?')
        }
        .ifBlank { null }

    val hasVideoFormats = item.optJSONArray("formats")?.length()?.let { it > 0 } == true
    val hasVideoCodec = item.optString("vcodec").let { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
    val hasVideoUrl = item.optString("video_url").isNotBlank()
    val hasImageSignal = item.optString("display_url").isNotBlank() ||
        item.optString("thumbnail").isNotBlank() ||
        item.optString("url").isNotBlank() ||
        item.optPositiveInt("width") != null ||
        item.optPositiveInt("height") != null

    val mediaType = when {
        hasVideoFormats -> InstagramMediaType.VIDEO
        hasVideoCodec -> InstagramMediaType.VIDEO
        hasVideoUrl -> InstagramMediaType.VIDEO
        isInstagramVideoExtension(extension) -> InstagramMediaType.VIDEO
        extension != null -> InstagramMediaType.PHOTO
        hasImageSignal -> InstagramMediaType.PHOTO
        else -> InstagramMediaType.PHOTO
    }

    val itemNumberLabel = playlistIndex ?: 1
    val cardTitle = when {
        totalItems > 1 -> "${if (mediaType == InstagramMediaType.VIDEO) "Video" else "Photo"} $itemNumberLabel"
        mediaType == InstagramMediaType.VIDEO -> "Reel Video"
        else -> "Post Photo"
    }
    val notificationTitle = if (totalItems > 1) {
        "$postTitle - $cardTitle"
    } else {
        postTitle
    }

    return InstagramDownloadOption(
        postTitle = postTitle,
        normalizedUrl = normalizedUrl,
        playlistIndex = playlistIndex,
        mediaId = mediaId,
        cardTitle = cardTitle,
        notificationTitle = notificationTitle,
        mediaType = mediaType,
        extension = extension,
        estimatedSizeBytes = item.optPositiveLong("filesize")
            ?: item.optPositiveLong("filesize_approx"),
        width = item.optPositiveInt("width"),
        height = item.optPositiveInt("height"),
    )
}

private fun extractJsonPayload(rawOutput: String): String {
    val trimmed = rawOutput.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed

    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    if (start >= 0 && end > start) {
        return trimmed.substring(start, end + 1)
    }

    throw IOException("Couldn't read media details from yt-dlp.")
}

internal fun extractInstagramEmbedShortcodeMedia(html: String): JSONObject {
    val decodedObject = JSONObject(extractInstagramEmbedRawObject(html))
    return decodedObject.optJSONObject("shortcode_media") ?: decodedObject
}

internal fun extractInstagramEmbedRawObject(html: String): String {
    val marker = listOf(
        "\\\"gql_data\\\":",
        "\"gql_data\":",
        "\\\"shortcode_media\\\":",
        "\"shortcode_media\":",
    ).firstOrNull(html::contains)
        ?: throw IOException("Couldn't read Instagram post details from the embed page.")

    val objectStart = html.indexOf('{', html.indexOf(marker) + marker.length)
        .takeIf { it >= 0 }
        ?: throw IOException("Couldn't locate Instagram media data.")
    val rawObject = extractBalancedBraces(html, objectStart)
    return runCatching {
        JSONObject(rawObject)
        rawObject
    }.recoverCatching {
        decodeEscapedJsonObject(rawObject).toString()
    }.getOrElse {
        throw IOException("Couldn't decode Instagram media data.", it)
    }
}

private fun extractBalancedBraces(text: String, startIndex: Int): String {
    var depth = 0
    var started = false
    for (index in startIndex until text.length) {
        when (text[index]) {
            '{' -> {
                depth++
                started = true
            }
            '}' -> {
                depth--
                if (started && depth == 0) {
                    return text.substring(startIndex, index + 1)
                }
            }
        }
    }
    throw IOException("Couldn't extract Instagram media object.")
}

private fun decodeEscapedJsonObject(rawObject: String): JSONObject {
    val wrapped = "{\"value\":\"${rawObject.replace("\n", "\\n").replace("\r", "\\r")}\"}"
    val decoded = JSONObject(wrapped).getString("value")
    return JSONObject(decoded)
}

private fun fetchInstagramHtml(url: String, refererUrl: String, userAgent: String): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 20_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", userAgent)
        setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        setRequestProperty("Referer", refererUrl)
    }

    return try {
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw IOException("Instagram returned HTTP $responseCode while loading the post.")
        }
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private fun JSONObject.optCaptionText(): String? {
    val captionEdges = optJSONObject("edge_media_to_caption")
        ?.optJSONArray("edges")
        ?: return null
    if (captionEdges.length() == 0) return null
    return captionEdges.optJSONObject(0)
        ?.optJSONObject("node")
        ?.optString("text")
        ?.takeIf { it.isNotBlank() }
}

private fun JSONObject.optBestDisplayResource(): String? {
    val resources = optJSONArray("display_resources") ?: return null
    var bestUrl: String? = null
    var bestArea = -1
    for (index in 0 until resources.length()) {
        val resource = resources.optJSONObject(index) ?: continue
        val width = resource.optPositiveInt("config_width") ?: 0
        val height = resource.optPositiveInt("config_height") ?: 0
        val area = width * height
        val url = resource.optString("src").takeIf { it.isNotBlank() } ?: continue
        if (area >= bestArea) {
            bestArea = area
            bestUrl = url
        }
    }
    return bestUrl
}

private fun JSONObject.optPositiveLong(key: String): Long? =
    optLong(key).takeIf { it > 0L }

private fun JSONObject.optPositiveInt(key: String): Int? =
    optInt(key).takeIf { it > 0 }

private fun isInstagramPostUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.path.orEmpty().split('/').filter { it.isNotBlank() }.firstOrNull() == "p"
}

private fun isInstagramVideoExtension(extension: String?): Boolean =
    extension?.lowercase() in setOf("mp4", "mov", "m4v", "webm")

private fun isDirectVideoExtension(extension: String?): Boolean =
    extension?.lowercase() in setOf("mp4", "mov", "m4v")

private fun buildDownloadOptions(
    normalizedUrl: String,
    title: String,
    info: VideoInfo,
): List<VideoDownloadOption> {
    val formats = info.formats ?: arrayListOf()
    val bestAudio = formats
        .asSequence()
        .filter { format ->
            format.vcodec == "none" &&
                format.acodec != "none" &&
                !format.formatId.isNullOrBlank() &&
                (format.ext == "m4a" || format.ext == "mp4")
        }
        .maxWithOrNull(
            compareBy<VideoFormat> { estimatedBytes(it) <= 0L }
                .thenByDescending { estimatedBytes(it) }
                .thenByDescending { it.abr }
                .thenByDescending { it.asr }
        )

    val groupedByHeight = formats
        .asSequence()
        .filter { format ->
            format.ext == "mp4" &&
                format.vcodec != "none" &&
                format.height > 0 &&
                !format.formatId.isNullOrBlank()
        }
        .groupBy { it.height }

    val options = groupedByHeight
        .mapNotNull { (_, candidates) ->
            val bestVideo = candidates.maxWithOrNull(
                compareBy<VideoFormat> { it.acodec == "none" }
                    .thenByDescending { it.vcodec?.startsWith("avc", ignoreCase = true) == true }
                    .thenByDescending { estimatedBytes(it) }
                    .thenByDescending { it.tbr }
                    .thenByDescending { it.fps }
            ) ?: return@mapNotNull null

            val needsSeparateAudio = bestVideo.acodec == "none"
            if (needsSeparateAudio && bestAudio == null) return@mapNotNull null

            val estimatedSize = estimatedBytes(bestVideo).takeIf { it > 0L }?.let { videoBytes ->
                if (needsSeparateAudio) videoBytes + (estimatedBytes(bestAudio!!) .takeIf { it > 0L } ?: 0L)
                else videoBytes
            }

            val qualityLabel = "${bestVideo.height}p"
            val note = buildString {
                if (needsSeparateAudio) append("video+audio")
                else append("single file")
                if (bestVideo.fps > 0) {
                    append(" • ")
                    append("${bestVideo.fps}fps")
                }
            }

            VideoDownloadOption(
                title = title,
                normalizedUrl = normalizedUrl,
                formatSelector = if (needsSeparateAudio) {
                    "${bestVideo.formatId!!}+${bestAudio!!.formatId!!}"
                } else {
                    bestVideo.formatId!!
                },
                resolutionLabel = qualityLabel,
                formatNote = note,
                estimatedSizeBytes = estimatedSize,
            )
        }
        .sortedByDescending { it.resolutionLabel.removeSuffix("p").toIntOrNull() ?: 0 }

    return if (options.isNotEmpty()) options else buildFallbackOption(normalizedUrl, title, info)
}

private fun buildFallbackOption(
    normalizedUrl: String,
    title: String,
    info: VideoInfo,
): List<VideoDownloadOption> {
    val formatId = info.formatId?.takeIf { it.isNotBlank() } ?: return emptyList()
    val label = info.resolution?.takeIf { it.isNotBlank() } ?: "Best available"
    val size = info.fileSize.takeIf { it > 0L } ?: info.fileSizeApproximate.takeIf { it > 0L }

    return listOf(
        VideoDownloadOption(
            title = title,
            normalizedUrl = normalizedUrl,
            formatSelector = formatId,
            resolutionLabel = label,
            formatNote = "best available",
            estimatedSizeBytes = size,
        )
    )
}

private fun estimatedBytes(format: VideoFormat): Long =
    format.fileSize.takeIf { it > 0L } ?: format.fileSizeApproximate.takeIf { it > 0L } ?: 0L

private fun saveToDownloads(context: Context, source: File): Uri {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveToMediaStore(context, source)
    } else {
        saveToLegacyDownloads(source)
    }
}

private fun saveToMediaStore(context: Context, source: File): Uri {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(source))
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Couldn't create a Downloads entry for the video.")

    return try {
        resolver.openOutputStream(itemUri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IOException("Couldn't open the Downloads file for writing.")

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)
        itemUri
    } catch (e: Exception) {
        resolver.delete(itemUri, null, null)
        throw e
    }
}

private fun saveToLegacyDownloads(source: File): Uri {
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
        throw IOException("Couldn't create the Downloads folder.")
    }

    val destination = File(downloadsDir, source.name)
    source.copyTo(destination, overwrite = true)
    return Uri.fromFile(destination)
}

private fun sanitizeFileName(value: String): String =
    value.replace(Regex("""[\\/:*?"<>|]"""), "_").take(140).trim()

private fun extensionFromUrl(url: String): String? =
    url.substringBefore('?')
        .substringAfterLast('/', missingDelimiterValue = "")
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .takeIf { it.isNotBlank() && it.length <= 5 }

private fun mimeTypeFor(file: File): String {
    val extension = file.extension.lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "Unknown size"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    val decimals = if (value >= 100 || unitIndex == 0) 0 else 1
    return "%.${decimals}f %s".format(value, units[unitIndex])
}

private fun notificationIdFor(normalizedUrl: String): Int =
    max(normalizedUrl.hashCode(), 1)

private const val INSTAGRAM_EMBED_DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"

private const val DEFAULT_HTTP_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; SM-A566B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

private const val INSTAGRAM_WEB_USER_AGENT =
    DEFAULT_HTTP_USER_AGENT

private object ServiceInfoTypes {
    val dataSync: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
}

private fun <T : Serializable> Intent?.getSerializableCompat(key: String, clazz: Class<T>): T? {
    if (this == null) return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getSerializableExtra(key, clazz)
    } else {
        @Suppress("DEPRECATION")
        getSerializableExtra(key) as? T
    }
}
