package com.example.ytsaverbyjnde

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.example.ytsaverbyjnde.ui.theme.BgDeep
import com.example.ytsaverbyjnde.ui.theme.BgSurface
import com.example.ytsaverbyjnde.ui.theme.CardBase
import com.example.ytsaverbyjnde.ui.theme.CardBorder
import com.example.ytsaverbyjnde.ui.theme.FBBlue
import com.example.ytsaverbyjnde.ui.theme.FBBlueDeep
import com.example.ytsaverbyjnde.ui.theme.FBBlueGlow
import com.example.ytsaverbyjnde.ui.theme.Gold
import com.example.ytsaverbyjnde.ui.theme.INGlow
import com.example.ytsaverbyjnde.ui.theme.INOrange
import com.example.ytsaverbyjnde.ui.theme.INPink
import com.example.ytsaverbyjnde.ui.theme.TextPri
import com.example.ytsaverbyjnde.ui.theme.TextSec
import com.example.ytsaverbyjnde.ui.theme.YTRed
import com.example.ytsaverbyjnde.ui.theme.YTRedDeep
import com.example.ytsaverbyjnde.ui.theme.YTRedGlow
import com.example.ytsaverbyjnde.ui.theme.YTSaverByJndeTheme

// ─────────────────────────────────────────────
// Download state
// ─────────────────────────────────────────────

sealed class DownloadState {
    object Idle : DownloadState()
    data class Working(
        val message: String,
        val progress: Int? = null,
    ) : DownloadState()
    data class Success(val title: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/** Maps raw exceptions to user-friendly messages. */
fun friendlyError(e: Throwable): String {
    val msg = e.message?.trim().orEmpty()
    return when {
        e is IllegalArgumentException -> msg
        e is StoragePermissionRequiredException -> msg
        msg.isBlank() -> "Something went wrong while downloading the video. Please try again."
        msg.contains("Unsupported URL", ignoreCase = true) ->
            "That share link doesn't look like a valid YouTube video URL."
        msg.contains("Sign in to confirm", ignoreCase = true) ->
            "YouTube temporarily blocked this request. Try again in a moment on a stable connection."
        msg.contains("requested format is not available", ignoreCase = true) ->
            "This video's downloadable format isn't available right now. Please try again."
        msg.contains("unavailable", ignoreCase = true) -> msg
        msg.contains("private", ignoreCase = true) -> msg
        msg.contains("Unable to resolve host", ignoreCase = true) ||
        msg.contains("failed to connect", ignoreCase = true) ->
            "No internet connection. Check your Wi-Fi or data and try again."
        msg.contains("timed out", ignoreCase = true) ->
            "Request timed out. Check your connection and try again."
        else -> msg
    }
}

// ─────────────────────────────────────────────
// Navigation
// ─────────────────────────────────────────────

sealed class Screen {
    object Home    : Screen()
    object YTSaver : Screen()
}

// ─────────────────────────────────────────────
// Entry point
// ─────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YTSaverByJndeTheme {
                var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                val ctx = LocalContext.current

                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        val enter = slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) {
                            if (targetState is Screen.YTSaver) it else -it
                        } + fadeIn(tween(280))
                        val exit = slideOutHorizontally(tween(380, easing = FastOutSlowInEasing)) {
                            if (targetState is Screen.YTSaver) -it else it
                        } + fadeOut(tween(200))
                        enter togetherWith exit
                    },
                    label = "nav",
                ) { current ->
                    when (current) {
                        is Screen.Home -> HomeScreen(
                            onYTSaver = { screen = Screen.YTSaver },
                            onFBSaver = { Toast.makeText(ctx, "Facebook Saver — Coming Soon!", Toast.LENGTH_SHORT).show() },
                            onINSaver = { Toast.makeText(ctx, "Instagram Saver — Coming Soon!", Toast.LENGTH_SHORT).show() },
                        )
                        is Screen.YTSaver -> YTSaverScreen(
                            onBack = { screen = Screen.Home }
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// HOME SCREEN
// ─────────────────────────────────────────────

@Composable
fun HomeScreen(
    onYTSaver: () -> Unit,
    onFBSaver: () -> Unit,
    onINSaver: () -> Unit,
) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        initialValue = 0.25f,
        targetValue  = 0.55f,
        animationSpec = infiniteRepeatable(
            tween(2200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "glow",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep),
    ) {
        // Ambient orb — top-left red
        Box(
            modifier = Modifier
                .size(340.dp)
                .offset(x = (-100).dp, y = (-80).dp)
                .background(
                    Brush.radialGradient(listOf(YTRed.copy(alpha = glow * 0.18f), Color.Transparent)),
                    CircleShape,
                ),
        )
        // Ambient orb — bottom-right blue
        Box(
            modifier = Modifier
                .size(280.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 90.dp, y = 80.dp)
                .background(
                    Brush.radialGradient(listOf(FBBlue.copy(alpha = glow * 0.14f), Color.Transparent)),
                    CircleShape,
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(70.dp))

            // App icon
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(
                        Brush.radialGradient(listOf(YTRed, YTRedDeep)),
                        CircleShape,
                    )
                    .border(
                        2.dp,
                        Brush.linearGradient(listOf(Color.White.copy(.25f), Color.Transparent)),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("\u25B6", fontSize = 36.sp, color = Color.White)
            }

            Spacer(Modifier.height(22.dp))

            Text(
                text = "W E L C O M E",
                color = TextSec,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 4.sp,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "Media\nSaver",
                style = TextStyle(
                    brush = Brush.linearGradient(listOf(Color.White, YTRed, Gold)),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 60.sp,
                    shadow = Shadow(YTRed.copy(.4f), Offset(0f, 8f), 24f),
                ),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "Download anything. Anywhere. Anytime.",
                color = TextSec,
                fontSize = 13.sp,
                letterSpacing = .4.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(42.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f), color = CardBorder)
                Text(
                    "  CHOOSE PLATFORM  ",
                    color = TextSec,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                )
                HorizontalDivider(Modifier.weight(1f), color = CardBorder)
            }

            Spacer(Modifier.height(22.dp))

            PlatformCard(
                symbol   = "\u25B6",
                name     = "YT Saver",
                tagline  = "Download YouTube videos in HD",
                gradient = listOf(YTRed, YTRedDeep),
                glow     = YTRedGlow,
                onClick  = onYTSaver,
            )

            Spacer(Modifier.height(14.dp))

            PlatformCard(
                symbol     = "f",
                name       = "FB Saver",
                tagline    = "Save Facebook videos & reels",
                gradient   = listOf(FBBlue, FBBlueDeep),
                glow       = FBBlueGlow,
                onClick    = onFBSaver,
                comingSoon = true,
            )

            Spacer(Modifier.height(14.dp))

            PlatformCard(
                symbol     = "\u25C8",
                name       = "IN Saver",
                tagline    = "Download Instagram posts & stories",
                gradient   = listOf(INPink, INOrange),
                glow       = INGlow,
                onClick    = onINSaver,
                comingSoon = true,
            )

            Spacer(Modifier.height(44.dp))

            AppFooter()

            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
fun PlatformCard(
    symbol:     String,
    name:       String,
    tagline:    String,
    gradient:   List<Color>,
    glow:       Color,
    onClick:    () -> Unit,
    comingSoon: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(CardBase)
            .border(
                1.dp,
                Brush.linearGradient(listOf(gradient[0].copy(.5f), CardBorder, CardBorder)),
                RoundedCornerShape(22.dp),
            )
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        // Left ambient glow blob
        Box(
            modifier = Modifier
                .size(110.dp)
                .align(Alignment.CenterStart)
                .offset(x = (-36).dp)
                .background(
                    Brush.radialGradient(listOf(glow, Color.Transparent)),
                    CircleShape,
                ),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Icon tile
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        Brush.linearGradient(gradient),
                        RoundedCornerShape(15.dp),
                    )
                    .border(1.dp, Color.White.copy(.12f), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(symbol, fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = TextPri, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    if (comingSoon) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(gradient[0].copy(.18f), RoundedCornerShape(5.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                "SOON",
                                color = gradient[0],
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(tagline, color = TextSec, fontSize = 13.sp)
            }

            Spacer(Modifier.width(8.dp))

            // Chevron
            Text("\u203A", color = gradient[0], fontSize = 28.sp, fontWeight = FontWeight.Light)
        }
    }
}

// ─────────────────────────────────────────────
// YT SAVER SCREEN
// ─────────────────────────────────────────────

@Composable
fun YTSaverScreen(onBack: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var dlState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var qualityChoices by remember { mutableStateOf<VideoDownloadChoices?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val workingState = dlState as? DownloadState.Working
    val isWorking = workingState != null

    val startDownload: (VideoDownloadOption) -> Unit = { option ->
        qualityChoices = null
        dlState = DownloadState.Working(message = "Starting ${option.resolutionLabel} download...")
        startYouTubeDownloadService(context, option)
    }

    val beginChoiceFetch: () -> Unit = {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) {
            dlState = DownloadState.Error("Paste a YouTube link first.")
        } else {
            qualityChoices = null
            dlState = DownloadState.Working(message = "Loading available resolutions...")
            scope.launch {
                try {
                    val choices = fetchYouTubeDownloadChoices(context, trimmedUrl)
                    qualityChoices = choices
                    dlState = DownloadState.Idle
                } catch (e: Exception) {
                    dlState = DownloadState.Error(friendlyError(e))
                }
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        beginChoiceFetch()
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                beginChoiceFetch()
            }
        } else {
            dlState = DownloadState.Error(
                "Storage permission is required on Android 9 and below to save into Downloads."
            )
        }
    }

    val requestDownload: () -> Unit = {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            beginChoiceFetch()
        }
    }

    val pulse = rememberInfiniteTransition(label = "yt_pulse")
    val glowSize by pulse.animateFloat(
        initialValue = 0.9f,
        targetValue  = 1.1f,
        animationSpec = infiniteRepeatable(
            tween(1800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "yt_glow",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep),
    ) {
        DisposableEffect(context) {
            val appContext = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        ACTION_DOWNLOAD_PROGRESS -> {
                            dlState = DownloadState.Working(
                                message = intent.getStringExtra(EXTRA_DOWNLOAD_STATUS).orEmpty(),
                                progress = intent.getIntExtra(EXTRA_DOWNLOAD_PERCENT, -1)
                                    .takeIf { it >= 0 },
                            )
                        }
                        ACTION_DOWNLOAD_COMPLETE -> {
                            dlState = DownloadState.Success(
                                intent.getStringExtra(EXTRA_DOWNLOAD_TITLE).orEmpty()
                            )
                        }
                        ACTION_DOWNLOAD_FAILED -> {
                            dlState = DownloadState.Error(
                                intent.getStringExtra(EXTRA_DOWNLOAD_ERROR).orEmpty()
                            )
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(ACTION_DOWNLOAD_PROGRESS)
                addAction(ACTION_DOWNLOAD_COMPLETE)
                addAction(ACTION_DOWNLOAD_FAILED)
            }
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            onDispose {
                appContext.unregisterReceiver(receiver)
            }
        }

        // Breathing red glow at top
        Box(
            modifier = Modifier
                .size((380 * glowSize).dp)
                .align(Alignment.TopCenter)
                .offset(y = (-120).dp)
                .background(
                    Brush.radialGradient(listOf(YTRed.copy(.18f), Color.Transparent)),
                    CircleShape,
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header band
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(270.dp)
                    .background(
                        Brush.verticalGradient(listOf(YTRed.copy(.22f), Color.Transparent))
                    ),
            ) {
                // Back button
                Box(
                    modifier = Modifier
                        .padding(top = 52.dp, start = 18.dp)
                        .align(Alignment.TopStart)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(CardBase)
                        .border(1.dp, CardBorder, CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("\u2190", fontSize = 20.sp, color = TextPri)
                }

                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(82.dp)
                            .background(
                                Brush.linearGradient(listOf(YTRed, YTRedDeep)),
                                RoundedCornerShape(24.dp),
                            )
                            .border(
                                2.dp,
                                Brush.linearGradient(listOf(Color.White.copy(.28f), Color.Transparent)),
                                RoundedCornerShape(24.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("\u25B6", fontSize = 34.sp, color = Color.White)
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        "YT Saver",
                        style = TextStyle(
                            brush = Brush.linearGradient(listOf(Color.White, YTRed)),
                            fontSize = 38.sp,
                            fontWeight = FontWeight.ExtraBold,
                            shadow = Shadow(YTRed.copy(.5f), Offset(0f, 6f), 18f),
                        ),
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        "High-quality YouTube downloader",
                        color = TextSec,
                        fontSize = 13.sp,
                        letterSpacing = .5.sp,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Input card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(CardBase)
                    .border(
                        1.dp,
                        Brush.linearGradient(listOf(YTRed.copy(.38f), CardBorder, CardBorder)),
                        RoundedCornerShape(28.dp),
                    )
                    .padding(22.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(YTRed, CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "PASTE YOUR LINK",
                            color = TextSec,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 2.sp,
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "Enter the YouTube\nvideo URL below",
                        color = TextPri,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 28.sp,
                    )

                    Spacer(Modifier.height(18.dp))

                    OutlinedTextField(
                        value         = url,
                        onValueChange = {
                            url = it
                            qualityChoices = null
                            if (dlState !is DownloadState.Idle && dlState !is DownloadState.Working)
                                dlState = DownloadState.Idle
                        },
                        modifier      = Modifier.fillMaxWidth(),
                        placeholder   = {
                            Text(
                                "https://youtube.com/watch?v=...",
                                color = TextSec.copy(.45f),
                                fontSize = 13.sp,
                            )
                        },
                        prefix     = { Text("\uD83D\uDD17  ", fontSize = 14.sp) },
                        singleLine = true,
                        shape      = RoundedCornerShape(14.dp),
                        colors     = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor      = YTRed,
                            unfocusedBorderColor    = CardBorder,
                            focusedTextColor        = TextPri,
                            unfocusedTextColor      = TextPri,
                            cursorColor             = YTRed,
                            focusedContainerColor   = BgSurface,
                            unfocusedContainerColor = BgSurface,
                        ),
                    )

                    Spacer(Modifier.height(18.dp))

                    // Download button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(
                                when {
                                    isWorking -> Brush.horizontalGradient(listOf(YTRed.copy(.5f), Color(0xFFFF6B35).copy(.5f)))
                                    dlState is DownloadState.Success -> Brush.horizontalGradient(listOf(Color(0xFF1DB954), Color(0xFF17A348)))
                                    else -> Brush.horizontalGradient(listOf(YTRed, Color(0xFFFF6B35)))
                                }
                            )
                            .border(1.dp, Color.White.copy(.15f), RoundedCornerShape(15.dp))
                            .clickable(enabled = !isWorking && url.isNotBlank(), onClick = requestDownload),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            isWorking -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.5.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    workingState?.progress?.let { "Downloading $it%" } ?: "Preparing...",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            dlState is DownloadState.Success -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("\u2713", fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text("Saved to Downloads!", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("\u2B07", fontSize = 20.sp, color = Color.White)
                                Spacer(Modifier.width(10.dp))
                                Text("Download", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .6.sp)
                            }
                        }
                    }

                    // Error / success detail message
                    when (val s = dlState) {
                        is DownloadState.Working -> {
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(.05f))
                                    .border(1.dp, Color.White.copy(.12f), RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                            ) {
                                Text(
                                    s.progress?.let { "${s.message} ($it%)" } ?: s.message,
                                    color = TextSec,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                        is DownloadState.Error -> {
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(YTRed.copy(.12f))
                                    .border(1.dp, YTRed.copy(.35f), RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                            ) {
                                Text(
                                    "\u26A0  ${s.message}",
                                    color = YTRed,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                        is DownloadState.Success -> {
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF1DB954).copy(.1f))
                                    .border(1.dp, Color(0xFF1DB954).copy(.35f), RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                            ) {
                                Text(
                                    "\u2193  \"${s.title}\" was saved to Downloads.",
                                    color = Color(0xFF1DB954),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                        else -> {}
                    }
                }
            }

            qualityChoices?.let { choices ->
                AlertDialog(
                    onDismissRequest = {
                        qualityChoices = null
                        if (dlState is DownloadState.Working) {
                            dlState = DownloadState.Idle
                        }
                    },
                    title = {
                        Text(
                            "Choose resolution",
                            color = TextPri,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                choices.title,
                                color = TextSec,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                            )
                            Spacer(Modifier.height(14.dp))
                            choices.options.forEach { option ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(BgSurface)
                                        .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                                        .clickable { startDownload(option) }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                ) {
                                    Column {
                                        Text(
                                            option.resolutionLabel,
                                            color = TextPri,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            option.subtitle,
                                            color = TextSec,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(
                            onClick = {
                                qualityChoices = null
                                dlState = DownloadState.Idle
                            }
                        ) {
                            Text("Cancel")
                        }
                    },
                )
            }

            Spacer(Modifier.height(18.dp))

            // How to use
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(BgSurface)
                    .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                    .padding(18.dp),
            ) {
                Column {
                    Text(
                        "\uD83D\uDCA1  How to use",
                        color = Gold,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                    TipRow("Open YouTube and copy the video link")
                    TipRow("Paste it in the field above")
                    TipRow("Hit Download and enjoy!")
                }
            }

            Spacer(Modifier.height(44.dp))

            AppFooter()

            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
fun TipRow(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(YTRed, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = TextSec, fontSize = 13.sp)
    }
}

// ─────────────────────────────────────────────
// SHARED FOOTER
// ─────────────────────────────────────────────

@Composable
fun AppFooter() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = CardBorder.copy(.5f))
            Spacer(Modifier.width(10.dp))
            Text("\u2726", color = YTRed.copy(.6f), fontSize = 10.sp)
            Spacer(Modifier.width(10.dp))
            HorizontalDivider(Modifier.weight(1f), color = CardBorder.copy(.5f))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Made by mhmdjnde",
            style = TextStyle(
                brush = Brush.linearGradient(listOf(YTRed, Gold)),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            ),
        )
    }
}
