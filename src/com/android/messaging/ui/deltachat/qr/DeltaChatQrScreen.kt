package com.android.messaging.ui.deltachat.qr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavKey
import com.android.messaging.R
import com.android.messaging.deltachat.DeltaChatProtocol
import com.android.messaging.util.UiUtils
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Delta Chat's QR code screen: your invite code, or the camera to scan someone else's. With
 * [groupChatId], the invite code of that group instead (no scanning).
 */
@Serializable
internal data class DeltaChatQrNavKey(
    val groupChatId: String? = null,
) : NavKey

@HiltViewModel
internal class DeltaChatQrViewModel @Inject constructor(
    private val protocol: DeltaChatProtocol,
) : ViewModel() {
    /**
     * Your invite link (the text in your QR code), or [groupChatId]'s, or null if Delta Chat
     * isn't set up.
     */
    suspend fun inviteLink(groupChatId: String? = null): String? =
        protocol.inviteLink(groupChatId?.toIntOrNull())

    /** The chat for a scanned invite (contact or group) or address, or null if it isn't one. */
    suspend fun startChat(scanned: String): String? = protocol.startChat(scanned.trim())
}

/**
 * Delta Chat's QR codes, as in Delta Chat's own "QR code" screen: "My QR code" shows your
 * invite for someone to scan (in person, or over a video call), and "Scan" reads theirs with
 * the camera and opens the chat. Both work with Delta Chat and other Delta Chat apps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeltaChatQrScreen(
    viewModel: DeltaChatQrViewModel,
    onNavigateBack: () -> Unit,
    onOpenChat: (chatId: String) -> Unit,
    groupChatId: String? = null,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val isGroup = groupChatId != null
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            if (isGroup) {
                                R.string.deltachat_qr_group_title
                            } else {
                                R.string.deltachat_qr_title
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // A group's code: just the code (joining is by scanning it from another device).
            if (isGroup) {
                MyQrCode(viewModel = viewModel, groupChatId = groupChatId)
                return@Column
            }
            PrimaryTabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(text = stringResource(R.string.deltachat_qr_mine)) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(text = stringResource(R.string.deltachat_qr_scan)) },
                )
            }
            when (tab) {
                0 -> MyQrCode(viewModel = viewModel, groupChatId = null)
                else -> ScanQrCode(viewModel = viewModel, onOpenChat = onOpenChat)
            }
        }
    }
}

@Composable
private fun MyQrCode(viewModel: DeltaChatQrViewModel, groupChatId: String?) {
    val context = LocalContext.current
    val link by produceState<String?>(initialValue = null, groupChatId) {
        value = viewModel.inviteLink(groupChatId)
    }
    val matrix = remember(link) { link?.let(::encodeQr) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Dark modules on white, in light and dark themes alike, so every scanner reads it.
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (matrix == null) {
                CircularProgressIndicator()
            } else {
                QrCodeImage(matrix = matrix, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            text = stringResource(
                if (groupChatId !=
                    null
                ) {
                    R.string.deltachat_qr_group_hint
                } else {
                    R.string.deltachat_qr_mine_hint
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                enabled = link != null,
                onClick = { link?.let { shareText(context, it) } },
            ) {
                Icon(
                    Icons.Rounded.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = stringResource(R.string.deltachat_share_invite),
                )
            }
            FilledTonalButton(
                enabled = link != null,
                onClick = {
                    link?.let {
                        copyText(context, it)
                        UiUtils.showToastAtBottom(R.string.deltachat_copied)
                    }
                },
            ) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = stringResource(R.string.deltachat_qr_copy),
                )
            }
        }
    }
}

@Composable
private fun QrCodeImage(matrix: BitMatrix, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cells = matrix.width
        val cell = size.minDimension / cells
        for (y in 0 until cells) {
            for (x in 0 until cells) {
                if (matrix[x, y]) {
                    // Slightly oversized so neighbouring modules join without hairline gaps.
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}

private fun encodeQr(text: String): BitMatrix = QRCodeWriter().encode(
    text,
    BarcodeFormat.QR_CODE,
    0,
    0,
    mapOf(
        EncodeHintType.MARGIN to 0,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    ),
)

@Composable
private fun ScanQrCode(
    viewModel: DeltaChatQrViewModel,
    onOpenChat: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val askCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCamera = granted }
    LaunchedEffect(Unit) { if (!hasCamera) askCamera.launch(Manifest.permission.CAMERA) }

    val scope = rememberCoroutineScope()
    var isStarting by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !hasCamera -> Button(onClick = { askCamera.launch(Manifest.permission.CAMERA) }) {
                    Text(text = stringResource(R.string.deltachat_qr_allow_camera))
                }

                isStarting -> CircularProgressIndicator()

                else -> QrScanner(
                    modifier = Modifier.fillMaxSize(),
                    onScanned = { text ->
                        isStarting = true
                        scope.launch {
                            val chatId = viewModel.startChat(text)
                            if (chatId != null) {
                                onOpenChat(chatId)
                            } else {
                                UiUtils.showToastAtBottom(R.string.deltachat_qr_not_invite)
                                isStarting = false
                            }
                        }
                    },
                )
            }
        }
        Text(
            text = stringResource(R.string.deltachat_qr_scan_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The back camera's preview, reporting the first QR code it reads. */
@Composable
private fun QrScanner(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val found = AtomicBoolean(false)
        val reader = QRCodeReader()
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(executor) { image ->
                    val text = if (found.get()) null else decode(reader, image)
                    image.close()
                    if (text != null && found.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(context).execute { currentOnScanned(text) }
                    }
                }
            }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                runCatching {
                    val provider = providerFuture.get()
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            runCatching { providerFuture.get().unbind(preview, analysis) }
            executor.shutdown()
        }
    }

    Box(modifier = modifier) {
        surfaceRequest?.let { request ->
            CameraXViewfinder(surfaceRequest = request, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The QR code's text in [image] (its brightness plane), or null if there's none. */
private fun decode(reader: QRCodeReader, image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val width = plane.rowStride
    val height = image.height
    return try {
        val source = PlanarYUVLuminanceSource(
            data,
            width,
            height,
            0,
            0,
            image.width.coerceAtMost(width),
            height,
            false,
        )
        reader.decode(
            BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.TRY_HARDER to true),
        ).text
    } catch (exception: ReaderException) {
        null
    } catch (exception: IllegalArgumentException) {
        null
    } finally {
        reader.reset()
    }
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, null))
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(null, text))
}
