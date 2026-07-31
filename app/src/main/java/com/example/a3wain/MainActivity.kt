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
import androidx.camera.view.PreviewView
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
            binding.resultOrder.visibility = View.GONE
            binding.resultString.visibility = View.GONE
            binding.buttonNewScan.visibility = View.GONE
            binding.cameraPreviewView.visibility = View.VISIBLE
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

            // Initialize the analyzer. Context is no longer needed here.
            barcodeAnalyzer = BarcodeAnalyzer(
                scanner,
                binding.resultTextView,
                binding.resultMLFB,
                binding.resultZ,
                binding.resultID,
                binding.resultOrder,
                binding.resultString,
                binding.buttonNewScan,
                binding.cameraPreviewView
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
    private val resultOrder: TextView,
    private val resultString: TextView,
    private val buttonNewScan: Button,
    private val cameraPreviewView: PreviewView
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
        resultTextView.text = "3WA scanned"
        //For internal purposes only+0419285003+3WA3232-5AF02-2KU5-Z+SOE#/240119600019+D80+F40+P61+S40+U40
        //For internal purposes only+0423111124+3WA3232-5AM00-0AA0##+SOE#/260715600165+
        val parts = rawValue?.split("+")
        var mlfbStr = ""
        var zStartIndex = rawValue?.indexOf('/')
        var zText = ""
        if (zStartIndex != null && zStartIndex > 0) {
            zStartIndex += 14
            if (rawValue != null) {
                if (zStartIndex < rawValue.length)  zText = rawValue?.substring(zStartIndex, rawValue.length) ?: ""
            }
        }
        if (parts != null && parts.size > 1) {
            resultOrder.text = "Customer order: " + parts[1]
            resultOrder.visibility = View.VISIBLE
        } else {
            resultOrder.text = ""
            resultOrder.visibility = View.GONE
        }
        if (parts != null && parts.size > 2) {
            mlfbStr = parts[2]
            resultMLFB.text = "MLFB: " + mlfbStr
            resultMLFB.visibility = View.VISIBLE
            resultString.visibility = View.VISIBLE
            buttonNewScan.visibility = View.VISIBLE
            cameraPreviewView.visibility = View.GONE
        } else {
            resultMLFB.text = ""
            resultMLFB.visibility = View.GONE
            resultString.visibility = View.GONE
            buttonNewScan.visibility = View.GONE
            resultTextView.text = "Scan 3WA DataMatrixCode"
        }
        if (parts != null && parts.size > 3) {
            resultID.text = "ID: " +  parts[3]
            resultID.visibility = View.VISIBLE
        } else {
            resultID.text = ""
            resultID.visibility = View.GONE
        }
        if (zText != "") {
            resultZ.text = "Add-ons: " + zText
            resultZ.visibility = View.VISIBLE
        } else {
            resultZ.text = ""
            resultZ.visibility = View.GONE
        }
        if (mlfbStr.length >= 18) {
            lookupDescriptionByMlbf( mlfbStr,zText)
        }
    }

    private fun lookupDescriptionByMlbf(mlfb: String, z: String = "") {
        var rStr = ""
        // 3WA3232-5AF02-2KU5-Z
        // 000000000011111111
        // 012345678901234567

        // 0
        // 3 MARKET
        when(mlfb.substring(3,4)) {
            "1" ->  rStr += "IEC 60947-2, "
            "2" ->  rStr += "UL489, "
            "3" ->  rStr += "UL1066/IEC 60947-2, "
        }
        // 0
        // 4 FRAME SIZE
        when(mlfb.substring(4,5)) {
            "1" ->  rStr += "FS1, "
            "2" ->  rStr += "FS2, "
            "3" ->  rStr += "FS3, "
        }
        // 00
        // 56 In
        when(mlfb.substring(5,7)) {
            "06" ->  rStr += "In=630A, "
            "08" ->  rStr += "In=800A, "
            "10" ->  rStr += "In=1000A, "
            "12" ->  when(mlfb.substring(3,4)) {
                        "1" ->  rStr += "In=1250A, "
                        "2" ->  rStr += "In=1200A, "
                        "3" ->  rStr += "In=1200A, "
                     }
            "16" ->  rStr += "In=1600A, "
            "20" ->  rStr += "In=2000A, "
            "25" ->  rStr += "In=2500A, "
            "30" ->  rStr += "In=3000A, "
            "32" ->  rStr += "In=3200A, "
            "36" ->  rStr += "In=3600A, "
            "40" ->  rStr += "In=4000A, "
            "50" ->  rStr += "In=5000A, "
            "60" ->  rStr += "In=6000A, "
            "63" ->  rStr += "In=6300A, "
            "71" ->  rStr += "In=7100A, "
        }
        rStr += "\n"
        // 1
        // 0 ETU TYPE
        when(mlfb.substring(10,11)) {
            "A" ->  rStr += "disconnetor AC (no ETU), "
            "U" ->  rStr += "disconnetor DC (no ETU), "

            "B" ->  rStr += "ETU300, LSI, "
            "C" ->  rStr += "ETU300, LSIG, GF extended, "
            "D" ->  rStr += "ETU300, LSIG, GF standard, "

            "E" ->  rStr += "ETU600, LSI, "
            "F" ->  rStr += "ETU600, LSIG, GF extended, "
            "G" ->  rStr += "ETU600, LSIG Hi-Z, GF extended, "

            "K" ->  rStr += "ETU600, LSIG, GF standard, "
            "L" ->  rStr += "ETU600, LSIG Hi-Z, GF standard,"

            "H" ->  rStr += "ETU600, LSIRc, "

            "S" ->  rStr += "ETU600, for customizing shop, "
            "T" ->  rStr += "ETU300, for customizing shop, "
            "M" ->  rStr += "ModCenter (no ETU), "
        }
        // 0
        // 9 functions
        when(mlfb.substring(9,10)) {
            "A" -> rStr += "basic functions, "
            "C" -> rStr += "ready4COM BSS200, "
            "L" -> rStr += "ready4COM BSS200, PMF-I, V-TAP 680 @ TOP, "
            "E" -> rStr += "ready4COM BSS200, PMF-I, V-TAP 680 @ BOTTOM, "
            "U" -> rStr += "ready4COM BSS200, PMF-I, V-TAP 640 @ TOP, "
            "Q" -> rStr += "ready4COM BSS200, PMF-I, V-TAP 640 @ BOTTOM, "

            "M" -> rStr += "ready4COM BSS200, PMF-II, V-TAP 680 @ TOP, "
            "F" -> rStr += "ready4COM BSS200, PMF-II, V-TAP 680 @ BOTTOM, "
            "V" -> rStr += "ready4COM BSS200, PMF-II, V-TAP 640 @ TOP, "
            "R" -> rStr += "ready4COM BSS200, PMF-II, V-TAP 640 @ BOTTOM, "

            "N" -> rStr += "ready4COM BSS200, PMF-III, V-TAP 680 @ TOP, "
            "G" -> rStr += "ready4COM BSS200, PMF-III, V-TAP 680 @ BOTTOM, "
            "W" -> rStr += "ready4COM BSS200, PMF-III, V-TAP 640 @ TOP, "
            "S" -> rStr += "ready4COM BSS200, PMF-III, V-TAP 640 @ BOTTOM, "
        }
        rStr += "\n"
        // 1
        // 1 POLES + mounting
        when(mlfb.substring(11,12)) {
            "0" ->  rStr += "fixed-mounted, 3-pole, "
            "1" ->  rStr += "fixed-mounted, 4-pole, N pole left, "
            "2" ->  rStr += "fixed-mounted, 4-pole, N pole right, "

            "3" ->  rStr += "withdrawable, 3-pole, "
            "4" ->  rStr += "withdrawable, 4-pole, N pole left, "
            "5" ->  rStr += "withdrawable, 4-pole, N pole right, "

            "6" ->  rStr += "withdrawable with PSS, 3-pole, "
            "7" ->  rStr += "withdrawable with PSS, 4-pole, N pole left, "
            "8" ->  rStr += "withdrawable with PSS, 4-pole, N pole right, "
        }
        // 1
        // 4 MO + AUX Switches
        when(mlfb.substring(14,15)) {
            "0" ->  rStr += "AUX switches S1+S2, \nno MO, "
            "1" ->  rStr += "AUX switches S1+S2+S3+S4, \nno MO, "

            "2" ->  rStr += "AUX switches S1+S2, \nMO 24VDC, "
            "3" ->  rStr += "AUX switches S1+S2, \nMO 110VAC/DC, "
            "4" ->  rStr += "AUX switches S1+S2, \nMO 230VAC/DC, "

            "5" ->  rStr += "AUX switches S1+S2+S3+S4, \nMO 24VDC, "
            "6" ->  rStr += "AUX switches S1+S2+S3+S4, \nMO 48VDC, "
            "7" ->  rStr += "AUX switches S1+S2+S3+S4, \nMO 110VAC/DC, "
            "8" ->  rStr += "AUX switches S1+S2+S3+S4, \nMO 230VAC/DC, "
        }
        // 1
        // 5 CC + RR
        when(mlfb.substring(15,16)) {
            "A" ->  rStr += "no RR, no CC, "

            "B" ->  rStr += "no RR, CC 24VDC 100%, "
            "C" ->  rStr += "no RR, CC 48VDC 100%, "
            "D" ->  rStr += "no RR, CC 110VAC/DC 100%, "
            "E" ->  rStr += "no RR, CC 230VAC/DC 100%, "

            "F" ->  rStr += "RR, CC 24VDC 100%, "
            "G" ->  rStr += "RR, CC 48VDC 100%, "
            "H" ->  rStr += "RR, CC 110VAC/DC 100%, "
            "J" ->  rStr += "RR, CC 230VAC/DC 100%, "

            "K" ->  rStr += "no RR, CC 24VDC 5%, "
            "L" ->  rStr += "no RR, CC 48VDC 5%, "
            "M" ->  rStr += "no RR, CC 110VAC/DC 5%, "
            "N" ->  rStr += "no RR, CC 230VAC/DC 5%, "
            "P" ->  rStr += "RR, CC 24VDC 5%, "
            "Q" ->  rStr += "RR, CC 48VDC 5%, "
            "R" ->  rStr += "RR, CC 110VAC/DC 5%, "
            "S" ->  rStr += "RR, CC 230VAC/DC 5%, "
        }
        rStr += "\n"
        // 1
        // 7 ST1
        when(mlfb.substring(17,18)) {
            "0" ->  rStr += "no ST1, "

            "1" ->  rStr += "ST1 24VDC 100%, "
            "2" ->  rStr += "ST1 48VDC 100%, "
            "3" ->  rStr += "ST1 110VAC/DC 100%, "
            "4" ->  rStr += "ST1 230VAC/DC 100%, "

            "5" ->  rStr += "ST1 24VDC 5%, "
            "6" ->  rStr += "ST1 48VDC 5%, "
            "7" ->  rStr += "ST1 110VAC/DC 5%, "
            "8" ->  rStr += "ST1 230VAC/DC 5%, "
        }
        // 1
        // 6 ST2 / UVR
        when(mlfb.substring(16,17)) {
            "A" ->  rStr += "no ST2, no UVR, "

            "B" ->  rStr += "ST2 24VDC 100%, no UVR, "
            "C" ->  rStr += "ST2 48VDC 100%, no UVR, "
            "D" ->  rStr += "ST2 110VAC/DC 100%, no UVR, "
            "E" ->  rStr += "ST2 230VAC/DC 100%, no UVR, "

            "F" ->  rStr += "ST2 24VDC 5%, no UVR, "
            "G" ->  rStr += "ST2 48VDC 5%, no UVR, "
            "H" ->  rStr += "ST2 110VAC/DC 5%, no UVR, "
            "J" ->  rStr += "ST2 230VAC/DC 5%, no UVR, "

            "L" ->  rStr += "no ST2, UVR 24VDC, "
            "N" ->  rStr += "no ST2, UVR 48VDC, "
            "P" ->  rStr += "no ST2, UVR 110VAC/DC, "
            "Q" ->  rStr += "no ST2, UVR 230VAC/DC, "
            "R" ->  rStr += "no ST2, UVR 400VAC, "

            "S" ->  rStr += "no ST2, UVR-t 48VDC, "
            "T" ->  rStr += "no ST2, UVR-t 60VDC, "
            "U" ->  rStr += "no ST2, UVR-t 110VAC/DC, "
            "V" ->  rStr += "no ST2, UVR-t 230VAC/DC, "
            "W" ->  rStr += "no ST2, UVR-t 400VAC, "
        }

        // Z ADDONS
        if (z.length >= 2) {
            rStr += "\n" + "\n" + "Z add-ons:" + "\n"
            if (z.contains("B02")) rStr += "(B02) In=250A \n"
            if (z.contains("B03")) rStr += "(B03) In=315A \n"
            if (z.contains("B04")) rStr += "(B04) In=400A \n"
            if (z.contains("B05")) rStr += "(B05) In=500A \n"
            if (z.contains("B06")) rStr += "(B06) In=630A \n"
            if (z.contains("B07")) rStr += "(B07) In=700A \n"
            if (z.contains("B08")) rStr += "(B08) In=800A \n"
            if (z.contains("B10")) rStr += "(B10) In=1000A \n"
            if (z.contains("B11")) rStr += "(B11) In=1200A \n"
            if (z.contains("B12")) rStr += "(B12) In=1250A \n"
            if (z.contains("B16")) rStr += "(B16) In=1600A \n"
            if (z.contains("B20")) rStr += "(B20) In=2000A \n"
            if (z.contains("B25")) rStr += "(B25) In=2500A \n"
            if (z.contains("B30")) rStr += "(B30) In=3000A \n"
            if (z.contains("B32")) rStr += "(B32) In=3200A \n"
            if (z.contains("B36")) rStr += "(B36) In=3600A \n"
            if (z.contains("B40")) rStr += "(B40) In=4000A \n"
            if (z.contains("B50")) rStr += "(B50) In=5000A \n"
            if (z.contains("B60")) rStr += "(B60) In=6000A \n"
            if (z.contains("B63")) rStr += "(B63) In=6300A \n"
            if (z.contains("B71")) rStr += "(B71) In=225A \n"
            if (z.contains("B72")) rStr += "(B72) In=200A \n"
            if (z.contains("B73")) rStr += "(B73) In=300A \n"
            if (z.contains("B74")) rStr += "(B74) In=350A \n"
            if (z.contains("B75")) rStr += "(B75) In=450A \n"
            if (z.contains("B76")) rStr += "(B76) In=600A \n"

            if (z.contains("C01")) rStr += "(C01) operations counter \n"
            if (z.contains("C11")) rStr += "(C11) S10 local electric close button \n"
            if (z.contains("C12")) rStr += "(C12) S10 local electric close key-lock \n"
            if (z.contains("C24")) rStr += "(C24) S12 motor disconnect switch \n"
            if (z.contains("C25")) rStr += "(C25) emergency stop off button \n"

            if (z.contains("D03")) rStr += "(D03) 3WA3 with IEC main connections \n"
            if (z.contains("D05")) rStr += "(D05) without push-in aux.plugs \n"
            if (z.contains("D08")) rStr += "(D08) tin surface for main connections for guide frame \n"
            if (z.contains("D09")) rStr += "(D09) 4000A 3WA with connections that match 4000A WL \n"
            if (z.contains("D24")) rStr += "(D24) glued screws for breaker feet and lateral front covers \n"
            if (z.contains("D80")) rStr += "(D80) Bluetooth function disabled \n"
            if (z.contains("D85")) rStr += "(D85) ETU without battery \n"

            if (z.contains("E01")) rStr += "(E01) ETU protection function GF-alarm \n"
            if (z.contains("E05")) rStr += "(E05) ETU protection function dST+Reverse power \n"
            if (z.contains("E11")) rStr += "(E11) ETU protection functions under/over voltage+under/over frequency+current/voltage unbalance+forward/reverse power+current/voltage THD+phase rotation \n"
            if (z.contains("E12")) rStr += "(E12) ETU protection function current/voltage unbalance \n"
            if (z.contains("E13")) rStr += "(E13) ETU protection function under/over voltage \n"
            if (z.contains("E14")) rStr += "(E14) ETU protection function forward/reverse power \n"
            if (z.contains("E15")) rStr += "(E15) ETU protection function under/over frequency \n"
            if (z.contains("E21")) rStr += "(E21) ETU protection function 2nd paraset \n"

            if (z.contains("E24")) rStr += "(E24) ETU application function waveform buffer \n"
            if (z.contains("E25")) rStr += "(E25) ETU application function condition monitoring \n"
            if (z.contains("E52")) rStr += "(E52) ETU application function PMF-II \n"
            if (z.contains("E53")) rStr += "(E53) ETU application function PMF-III \n"

            if (z.contains("E31")) rStr += "(E31) ETU extended protection function NSP \n"
            if (z.contains("E32")) rStr += "(E32) ETU extended protection function DCP U-f \n"
            if (z.contains("E33")) rStr += "(E33) ETU extended protection function DCP Q-U \n"

            if (z.contains("F15")) rStr += "(F15) COM150 Modbus RTU module \n"
            if (z.contains("F17")) rStr += "(F17) COM170 Profibus-DP module \n"
            if (z.contains("F19")) rStr += "(F19) COM190 Profinet-IO / Modbus-TCP module \n"
            if (z.contains("F20")) rStr += "(F20) ZSI200 Zone Selective Interlocking module \n"
            if (z.contains("F23")) rStr += "(F23) IOM230 digital in/output module \n"
            if (z.contains("F40")) rStr += "(F40) ETU600 safety lock-out cover \n"
            if (z.contains("F41")) rStr += "(F41) EMC filter for ETU \n"

            if (z.contains("K01")) rStr += "(K01) automatic reset of the reclosing lockout  \n"
            if (z.contains("K02")) rStr += "(K02) non-automatic reset of the reclosing lockout \n"
            if (z.contains("K06")) rStr += "(K06) S25 2nd trip alarm switch \n"
            if (z.contains("K07")) rStr += "(K07) S25 2nd trip alarm switch \n"
            if (z.contains("K60")) rStr += "(K60) current sensors without energy core \n"
            if (z.contains("K62")) rStr += "(K62) current sensors without energy core, external ETU power \n"

            if (z.contains("M71")) rStr += "(M71) ST/CC without COM \n"

            if (z.contains("N03")) rStr += "(N03) secondary disconnect terminals SIGUT \n"
            if (z.contains("N05")) rStr += "(N05) secondary disconnect terminals ring lug \n"

            if (z.contains("P61")) rStr += "(P61) special packaging with moisture protection \n"
            if (z.contains("P81")) rStr += "(P81) with test certificate \n"

            if (z.contains("R10")) rStr += "(R10) arc chute cover \n"
            if (z.contains("R30")) rStr += "(R30) control cabinet lock-out for withdrawable \n"
            if (z.contains("R40")) rStr += "(R40) control cabinet prevent close lock-out \n"
            if (z.contains("R50")) rStr += "(R50) control cabinet prevent racking lock-out \n"
            if (z.contains("R55")) rStr += "(R55) mechanical interlocking for withdrawable \n"
            if (z.contains("R56")) rStr += "(R56) mechanical interlocking for frame \n"
            if (z.contains("R57")) rStr += "(R57) mechanical interlocking for withdrawable without frame \n"
            if (z.contains("R60")) rStr += "(R60) breaker frame close lock-out Profalux \n"
            if (z.contains("R61")) rStr += "(R61) breaker frame close lock-out CES \n"
            if (z.contains("R68")) rStr += "(R68) breaker frame close lock-out Ronis \n"
            if (z.contains("R71")) rStr += "(R71) withdrawable breaker prevent close lock-out CES \n"
            if (z.contains("R81")) rStr += "(R81) frame disconnected lock-out CES \n"
            if (z.contains("R85")) rStr += "(R85) frame disconnected lock-out Profalux \n"
            if (z.contains("R86")) rStr += "(R86) frame disconnected lock-out Ronis \n"

            if (z.contains("S01")) rStr += "(S01) close lock-out CES \n"
            if (z.contains("S02")) rStr += "(S02) close lock-out KIRK \n"
            if (z.contains("S03")) rStr += "(S03) close lock-out IKON \n"
            if (z.contains("S04")) rStr += "(S04) close lock-out Yale \n"
            if (z.contains("S05")) rStr += "(S05) close lock-out Fortress/Castell \n"
            if (z.contains("S06")) rStr += "(S06) close lock-out kit \n"
            if (z.contains("S07")) rStr += "(S07) close lock-out kit for padlocks \n"
            if (z.contains("S08")) rStr += "(S08) close lock-out Ronis \n"
            if (z.contains("S09")) rStr += "(S09) close lock-out Profalux \n"
            if (z.contains("S30")) rStr += "(S30) control cabinet lock-out for fixed-mounted \n"
            if (z.contains("S33")) rStr += "(S33) charging handle lock-out for padlocks \n"
            if (z.contains("S40")) rStr += "(S40) interlocking for mechanical close/open \n"
            if (z.contains("S55")) rStr += "(S55) mechanical interlocking for fixed-mounted \n"
            if (z.contains("S71")) rStr += "(S71) racking handle lock-out CES \n"
            if (z.contains("S72")) rStr += "(S72) racking handle lock-out kit \n"
            if (z.contains("S73")) rStr += "(S73) racking handle lock-out KIRK \n"
            if (z.contains("S75")) rStr += "(S75) racking handle lock-out Profalux \n"
            if (z.contains("S76")) rStr += "(S76) racking handle lock-out Ronis \n"

            if (z.contains("T40")) rStr += "(T40) door sealing frame \n"

            if (z.contains("U01")) rStr += "(U01) NEMA warning labels fo IEC breaker \n"
            if (z.contains("U40")) rStr += "(U40) customized NEMA labels \n"

            if (z.contains("V61")) rStr += "(V61) PMF-I with external V-TAP \n"
            if (z.contains("V62")) rStr += "(V62) PMF-II with external V-TAP \n"
            if (z.contains("V63")) rStr += "(V63) PMF-III with external V-TAP \n"
            if (z.contains("V68")) rStr += "(V68) V-TAP module VTM680 \n"
        }

        resultString.text = rStr
        resultString.visibility = View.VISIBLE
    }

    // Public method to re-enable scanning
    fun startScanning() {
        isScanning = true
    }
}