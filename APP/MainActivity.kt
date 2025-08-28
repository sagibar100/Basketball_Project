package com.example.myapplication4

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var cameraExecutor: ExecutorService
    private val recordedFrames = mutableListOf<Bitmap>()
    private var isRecording = false
    private var totalFramesAnalyzed = 0
    private var framesWithDetections = 0
    private lateinit var fpsText: TextView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            Log.d("DEBUG", "Permissions result: $permissions")
            val cameraGranted = permissions[Manifest.permission.CAMERA] == true
            if (cameraGranted) {
                Log.d("DEBUG", "CAMERA granted → startCamera()")
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("DEBUG", "onCreate started")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fpsText = findViewById(R.id.fpsText)
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        val startButton: Button = findViewById(R.id.startRecordingButton)
        val stopButton: Button = findViewById(R.id.stopRecordingButton)

        cameraExecutor = Executors.newSingleThreadExecutor()
        TFLiteModel.LoadModel(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("DEBUG", "Requesting CAMERA permission")
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            )
        } else {
            Log.d("DEBUG", "Permission already granted → startCamera()")
            startCamera()
        }

        startButton.setOnClickListener {
            isRecording = true
            recordedFrames.clear()
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
        }

        stopButton.setOnClickListener {
            isRecording = false

            val firstFrame = recordedFrames.firstOrNull()
            if (firstFrame != null) {
                val width = firstFrame.width
                val height = firstFrame.height

                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                if (!moviesDir.exists()) moviesDir.mkdirs()

                val timestamp = System.currentTimeMillis()
                val outputFile = File(moviesDir, "video_$timestamp.mp4")

                VideoSaver.saveFramesAsMp4(
                    frames = recordedFrames,
                    width = width,
                    height = height,
                    outputFile = outputFile
                ) {
                    runOnUiThread {
                        Toast.makeText(this, "Saved to: ${outputFile.absolutePath}", Toast.LENGTH_LONG).show()
                        MediaScannerConnection.scanFile(
                            this,
                            arrayOf(outputFile.absolutePath),
                            arrayOf("video/mp4"),
                            null
                        )
                    }
                }
            } else {
                Toast.makeText(this, "No frames recorded!", Toast.LENGTH_SHORT).show()
            }
        }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(network)

        if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            Log.d("DEBUG", "✅ Connected to Wi-Fi")
        } else {
            Log.e("DEBUG", "❌ Not connected to Wi-Fi")
        }
    }

    private fun startCamera() {
        Log.d("DEBUG", "startCamera() called")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        overlayView.post {
            val w = overlayView.width
            val h = overlayView.height
            Log.d("DEBUG", "OverlayView.post → width=$w height=$h")
            if (w > 0 && h > 0) {
                sendUdpMessage("SIZE:$w,$h")
                Log.d("UDP", "Sent screen size")
            } else {
                Log.e("DEBUG", "OverlayView has invalid size")
            }
        }
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            var frameCount = 0  // הוסף משתנה מחוץ לאנלייזר

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                //frameCount++

                // עבד רק כל 3 פריימים לדוגמה
                //if (frameCount % 3 != 0) {
                  //  imageProxy.close()
                    //return@setAnalyzer
                //}

                Log.d("DEBUG", "Analyzer triggered")
                val bitmap = imageProxy.toBitmapSafely()
                bitmap?.let { bmp ->
                    totalFramesAnalyzed++

                    val detections = YoloProcessor.runInference(bmp)

                    if (detections.isNotEmpty()) {
                        framesWithDetections++

                        val firstBox = detections.first()
                        val centerX = (firstBox.left + firstBox.right) / 2
                        val centerY = (firstBox.top + firstBox.bottom) / 2

                        val scaledX = (centerX / 640f) * overlayView.width
                        val scaledY = (centerY / 640f) * overlayView.height

                        val message = "${scaledX.toInt()},${scaledY.toInt()}"
                        Log.d("UDP", "Calling sendUdpMessage with: $message")
                        sendUdpMessage(message)
                    }

                    runOnUiThread {
                        overlayView.setRects(
                            rects = detections,
                            imageWidth = 640,
                            imageHeight = 640,
                            viewWidth = overlayView.width,
                            viewHeight = overlayView.height
                        )
                        fpsText.text = "Tracking FPS: ${"%.2f".format(framesWithDetections.toFloat() / totalFramesAnalyzed)}"
                    }

                    if (isRecording) {
                        recordedFrames.add(bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false))
                    }
                }
                imageProxy.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (exc: Exception) {
                Log.e("MainActivity", "Camera binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun ImageProxy.toBitmapSafely(): Bitmap? {
        return try {
            toBitmap()
        } catch (e: Exception) {
            Log.e("MainActivity", "Image to Bitmap failed: ${e.message}")
            null
        }
    }

    private fun sendUdpMessage(message: String, espIp: String = "192.168.150.67", port: Int = 4210) {
        Log.d("UDP", "Preparing to send: $message")
        CoroutineScope(Dispatchers.IO).launch {
            delay(500) // תן ל־ESP זמן להתארגן
            try {
                DatagramSocket().use { socket ->
                    val address = InetAddress.getByName("192.168.150.67")
                    val data = message.toByteArray()
                    val packet = DatagramPacket(data, data.size, address, port)
                    socket.send(packet)
                    Log.d("UDP", "Message sent: $message to $espIp:$port")
                }
            } catch (e: Exception) {
                Log.e("UDP", "Failed to send: ${e.message}", e)
            }
        }
    }
}
