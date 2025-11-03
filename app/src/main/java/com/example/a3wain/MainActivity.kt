package com.example.a3wain

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.a3wain.databinding.ActivityMainBinding
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val requestCodePermissions = 10
    private val requiredPermissions = arrayOf(Manifest.permission.CAMERA)
    private lateinit var barcodeAnalyzer: BarcodeAnalyzer
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions(requiredPermissions, requestCodePermissions)
        }

        // Set up the listener for the "New Scan" button
        binding.buttonNewScan.setOnClickListener {
            // Re-enable scanning in the analyzer
            if (::barcodeAnalyzer.isInitialized) {
                barcodeAnalyzer.startScanning()
            }
            // Reset UI elements
            binding.resultTextView.text = "Scan 3WA DataMatrixCode"
            binding.resultTextView.visibility = View.VISIBLE
            binding.resultMLFB.visibility = View.GONE
            binding.resultID.visibility = View.GONE
            binding.resultZ.visibility = View.GONE
            binding.buttonInsight.visibility = View.GONE
            binding.buttonNewScan.visibility = View.GONE
        }
    }

    // Helper function to check if all required permissions are granted
    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Handle the permission request response
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestCodePermissions) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish() // Close the app if permissions are denied
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Set up the Preview use case
            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = binding.cameraPreviewView.surfaceProvider
                }

            // Configure the BarcodeScanner to only look for Data Matrix
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX) // Specify Data Matrix only
                .build()
            val scanner = BarcodeScanning.getClient(options)

            // Initialize the analyzer and pass the UI elements
            barcodeAnalyzer = BarcodeAnalyzer(
                scanner,
                binding.resultTextView,
                binding.resultMLFB,
                binding.resultZ,
                binding.resultID,
                binding.buttonInsight,
                binding.buttonNewScan
            )

            // Set up the ImageAnalysis use case
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, barcodeAnalyzer)
                }

            // Bind the camera to the lifecycle
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("MainActivity", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}


class BarcodeAnalyzer(
    private val scanner: BarcodeScanner,
    private val resultTextView: TextView,
    private val resultMLFB: TextView,
    private val resultZ: TextView,
    private val resultID: TextView,
    private val buttonInsight: Button,
    private val buttonNewScan: Button
) : ImageAnalysis.Analyzer {

    // State flag to control scanning
    @Volatile
    private var isScanning = true

    @OptIn(ExperimentalGetImage::class)
    @SuppressLint("UnsafeOptInUsageWithError")
    override fun analyze(imageProxy: ImageProxy) {
        // If not scanning, close the image and stop processing
        if (!isScanning) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        // A barcode was found, so stop scanning
                        isScanning = false

                        val barcode = barcodes.first() // Process only the first barcode
                        val rawValue = barcode.rawBytes?.let { String(it, StandardCharsets.UTF_8) } ?: barcode.rawValue

                        // Post UI updates to the main thread
                        resultTextView.post {
                            updateUI(rawValue)
                        }
                    }
                }
                .addOnFailureListener {
                    // Log failure but continue scanning
                    Log.e("BarcodeAnalyzer", "Barcode scanning failed", it)
                }
                .addOnCompleteListener {
                    // Always close the imageProxy to release the frame
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun updateUI(rawValue: String?) {
        resultTextView.text = "3WA scanned:"
        val parts = rawValue?.split('+')
        if (parts != null && parts.size > 2) {
            resultMLFB.text = parts[2].trimEnd('#')
            resultMLFB.visibility = View.VISIBLE
            buttonInsight.visibility = View.VISIBLE
            buttonNewScan.visibility = View.VISIBLE
        } else {
            resultMLFB.text = ""
            resultMLFB.visibility = View.GONE
            buttonInsight.visibility = View.GONE
            buttonNewScan.visibility = View.GONE
            resultTextView.text = "Scan 3WA DataMatrixCode"
        }
        if (parts != null && parts.size > 3) {
            resultID.text = parts[3]
            resultID.visibility = View.VISIBLE
        } else {
            resultID.text = ""
            resultID.visibility = View.GONE
        }
        if (parts != null && parts.size > 4) {
            resultZ.text = parts.subList(4, parts.size).joinToString(" ")
            resultZ.visibility = View.VISIBLE
            if (parts[4].length == 0) resultZ.visibility = View.GONE
        } else {
            resultZ.text = ""
            resultZ.visibility = View.GONE
        }
    }

    // Public method to re-enable scanning
    fun startScanning() {
        isScanning = true
    }
}
