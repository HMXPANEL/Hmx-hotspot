package hmx.ui.screens.user

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import hmx.security.PairingCode
import hmx.ui.components.PrimaryButton
import hmx.ui.components.SecondaryButton
import java.util.concurrent.Executors

@Composable
fun ScannerScreen(onCodeScanned: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }

    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("Scan pairing QR", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))

        if (!hasPermission) {
            Text(
                "Camera access is needed to read the pairing QR code.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Grant camera access", { permissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth())
        } else {
            QrCameraView { payload ->
                val code = payload.removePrefix("hmx://p/").trim()
                if (PairingCode.isValid(PairingCode.normalize(code))) onCodeScanned(code)
            }
        }
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Back", onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun QrCameraView(onDecoded: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
    }
    var delivered by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                    if (!delivered) {
                        val result = decode(proxy, reader)
                        if (result != null && !delivered) {
                            delivered = true
                            ContextCompat.getMainExecutor(ctx).execute { onDecoded(result.text) }
                        }
                    }
                    proxy.close()
                }
                provider.unbindAll()
                runCatching {
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxWidth().height(380.dp),
    )
}

private fun decode(proxy: ImageProxy, reader: MultiFormatReader): com.google.zxing.Result? {
    val plane = proxy.planes[0]
    val bytes = plane.buffer.let { b ->
        val arr = ByteArray(b.remaining())
        b.get(arr)
        arr
    }
    val source = PlanarYUVLuminanceSource(
        bytes, plane.rowStride, proxy.height,
        0, 0, proxy.width.coerceAtMost(plane.rowStride), proxy.height, false,
    )
    return runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))) }
        .recoverCatching { reader.reset(); reader.decode(BinaryBitmap(HybridBinarizer(source))) }
        .getOrNull()
        ?.also { reader.reset() }
}
