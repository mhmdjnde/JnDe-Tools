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
import java.io.File
import java.io.IOException
import java.io.Serializable
import java.util.UUID
import kotlin.math.max

const val ACTION_DOWNLOAD_PROGRESS = "com.example.ytsaverbyjnde.DOWNLOAD_PROGRESS"
const val ACTION_DOWNLOAD_COMPLETE = "com.example.ytsaverbyjnde.DOWNLOAD_COMPLETE"
const val ACTION_DOWNLOAD_FAILED = "com.example.ytsaverbyjnde.DOWNLOAD_FAILED"
const val EXTRA_DOWNLOAD_TITLE = "download_title"
const val EXTRA_DOWNLOAD_STATUS = "download_status"
const val EXTRA_DOWNLOAD_PERCENT = "download_percent"
const val EXTRA_DOWNLOAD_ERROR = "download_error"

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

class StoragePermissionRequiredException : Exception(
    "Storage permission is required on Android 9 and below to save into Downloads."
)

private object YoutubeDlRuntime {
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

private object DownloadNotificationHelper {
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
            "YT Saver Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows YouTube download progress"
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

suspend fun downloadYouTubeVideo(
    context: Context,
    option: VideoDownloadOption,
    onProgress: (DownloadProgress) -> Unit,
): CompletedDownload = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        throw StoragePermissionRequiredException()
    }

    YoutubeDlRuntime.ensureReady(context)

    val mainHandler = Handler(Looper.getMainLooper())
    fun postProgress(progress: DownloadProgress) {
        mainHandler.post { onProgress(progress) }
    }

    val videoId = option.normalizedUrl.substringAfter("v=", missingDelimiterValue = "")
        .ifBlank { System.currentTimeMillis().toString() }
    val safeBaseName = sanitizeFileName("${option.title} [$videoId]")
        .ifBlank { "youtube_video_$videoId" }
    val tempDir = File(context.cacheDir, "yt-dlp/$videoId-${UUID.randomUUID()}").apply { mkdirs() }
    val outputTemplate = File(tempDir, "$safeBaseName.%(ext)s").absolutePath
    val processId = "yt-download-$videoId-${System.currentTimeMillis()}"
    val notificationId = max(videoId.hashCode(), 1)

    postProgress(DownloadProgress(percent = null, status = "Starting ${option.resolutionLabel} download..."))
    DownloadNotificationHelper.showProgress(
        context = context,
        notificationId = notificationId,
        title = option.title,
        message = "Starting ${option.resolutionLabel} download...",
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
                title = option.title,
                message = message,
                progress = rounded,
            )
        }

        val request = YoutubeDLRequest(option.normalizedUrl).apply {
            addOption("--no-playlist")
            addOption("--newline")
            addOption("--no-mtime")
            addOption("-f", option.formatSelector)
            addOption("--merge-output-format", "mp4")
            addOption("-o", outputTemplate)
        }

        YoutubeDL.getInstance().execute(request, processId, callback)

        val downloadedFile = tempDir
            .listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.lastModified() }
            ?: throw IOException("The download finished but no video file was created.")

        postProgress(DownloadProgress(percent = 100, status = "Saving to Downloads..."))
        DownloadNotificationHelper.showProgress(
            context = context,
            notificationId = notificationId,
            title = option.title,
            message = "Saving to Downloads...",
            progress = 100,
        )

        val savedUri = saveToDownloads(context, downloadedFile)
        DownloadNotificationHelper.showComplete(
            context = context,
            notificationId = notificationId,
            title = option.title,
            fileName = downloadedFile.name,
        )

        CompletedDownload(
            title = option.title,
            uri = savedUri,
            fileName = downloadedFile.name,
        )
    } catch (e: YoutubeDLException) {
        DownloadNotificationHelper.showFailure(
            context = context,
            notificationId = notificationId,
            title = option.title,
            message = e.message ?: "Download failed",
        )
        throw Exception(e.message ?: "yt-dlp failed to download this video.", e)
    } catch (e: Exception) {
        DownloadNotificationHelper.showFailure(
            context = context,
            notificationId = notificationId,
            title = option.title,
            message = e.message ?: "Download failed",
        )
        throw e
    } finally {
        tempDir.deleteRecursively()
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
                putExtra(EXTRA_DOWNLOAD_TITLE, title)
            }
        )
    }

    private fun sendFailureBroadcast(title: String, error: String) {
        sendBroadcast(
            Intent(ACTION_DOWNLOAD_FAILED).apply {
                setPackage(packageName)
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

private fun mimeTypeFor(file: File): String {
    val extension = file.extension.lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "video/mp4"
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
