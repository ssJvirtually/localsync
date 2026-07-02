package com.example.localsync.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.localsync.data.DataRepository
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalGetImage::class)
@Composable
fun PairingScreen(
    repository: DataRepository,
    onBackClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    var isPairing by remember { mutableStateOf(false) }
    var qrDetected by remember { mutableStateOf(false) }
    var showManualPairDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        if (onBackClick != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Go back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
        if (!hasCameraPermission) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    text = "Camera Permission Required",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "We need camera access to scan the pairing QR code from your PC app.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Permission")
                }
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = { showManualPairDialog = true }) {
                    Text("Pair Manually (Tailscale / Custom IP)")
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = "Pair with LocalSync Server",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "Scan the QR code displayed on the PC application.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 32.dp)
                )

                // Camera Scanner Box
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (isPairing) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    } else {
                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build()
                                    
                                    val selector = CameraSelector.Builder()
                                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                                        .build()

                                    preview.setSurfaceProvider(previewView.surfaceProvider)

                                    val options = BarcodeScannerOptions.Builder()
                                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                        .build()
                                    val scanner = BarcodeScanning.getClient(options)

                                    val imageAnalysis = ImageAnalysis.Builder()
                                        .setTargetResolution(Size(1280, 720))
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()

                                    imageAnalysis.setAnalyzer(
                                        ContextCompat.getMainExecutor(ctx)
                                    ) { imageProxy ->
                                        val mediaImage = imageProxy.image
                                        if (mediaImage != null && !qrDetected) {
                                            val image = InputImage.fromMediaImage(
                                                mediaImage,
                                                imageProxy.imageInfo.rotationDegrees
                                            )
                                            scanner.process(image)
                                                .addOnSuccessListener { barcodes ->
                                                    for (barcode in barcodes) {
                                                        val rawValue = barcode.rawValue
                                                        if (rawValue != null && !qrDetected) {
                                                            qrDetected = true
                                                            isPairing = true
                                                            Log.d("PairingScreen", "Detected QR payload: $rawValue")
                                                            
                                                            scope.launch {
                                                                val result = repository.pairWithServer(rawValue)
                                                                isPairing = false
                                                                if (result.isSuccess) {
                                                                    Toast.makeText(
                                                                        ctx,
                                                                        "Paired successfully with ${result.getOrThrow()}!",
                                                                        Toast.LENGTH_LONG
                                                                    ).show()
                                                                } else {
                                                                    qrDetected = false
                                                                    Toast.makeText(
                                                                        ctx,
                                                                        "Pairing failed: ${result.exceptionOrNull()?.message}",
                                                                        Toast.LENGTH_LONG
                                                                    ).show()
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                .addOnFailureListener {
                                                    Log.e("PairingScreen", "Barcode scan failure", it)
                                                }
                                                .addOnCompleteListener {
                                                    imageProxy.close()
                                                }
                                        } else {
                                            imageProxy.close()
                                        }
                                    }

                                    try {
                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            selector,
                                            preview,
                                            imageAnalysis
                                        )
                                    } catch (e: Exception) {
                                        Log.e("PairingScreen", "Camera binding failed: ${e.message}", e)
                                    }
                                }, ContextCompat.getMainExecutor(ctx))

                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                TextButton(onClick = { showManualPairDialog = true }) {
                    Text("Pair Manually (Tailscale / Custom IP)")
                }
            }
        }
    }

    if (showManualPairDialog) {
        var ipInput by remember { mutableStateOf("") }
        var portInput by remember { mutableStateOf("8080") }
        var tokenInput by remember { mutableStateOf("") }
        var nameInput by remember { mutableStateOf("") }
        var manualPairing by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!manualPairing) showManualPairDialog = false },
            title = { Text("Pair Manually") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text("PC IP Address (e.g. 100.x.y.z)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it },
                        label = { Text("Server Port") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("Pairing Token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("PC Name (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = ipInput.isNotBlank() && tokenInput.isNotBlank() && !manualPairing,
                    onClick = {
                        val port = portInput.toIntOrNull() ?: 8080
                        manualPairing = true
                        scope.launch {
                            val result = repository.pairManually(
                                ip = ipInput.trim(),
                                port = port,
                                token = tokenInput.trim(),
                                pcName = nameInput.trim()
                            )
                            manualPairing = false
                            if (result.isSuccess) {
                                showManualPairDialog = false
                                Toast.makeText(
                                    context,
                                    "Paired successfully with ${result.getOrThrow()}!",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    "Manual pairing failed: ${result.exceptionOrNull()?.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                ) {
                    if (manualPairing) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text("Pair")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !manualPairing,
                    onClick = { showManualPairDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
