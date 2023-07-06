package com.shadowcompany.qrcodereader

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.shadowcompany.qrcodereader.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class ProgramState {
    BASE,
    QR_FOUND,
    QR_INPUT_ACTIVE,
    QR_IMAGE_GENERATED
}

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private val overlayRect: RectF = RectF(100f, 100f, 100f, 100f) // Define the filter region here

    private lateinit var viewBinding: ActivityMainBinding
    private var programState: ProgramState = ProgramState.BASE
    private var generatedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
//        setContentView(R.layout.activity_main)

        // prevent screenshots
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        viewBinding.qrCodeFoundResultText.movementMethod = ScrollingMovementMethod()

        // Set up the listeners for take photo and video capture buttons
        viewBinding.leftButton.setOnClickListener { /*takePhoto()*/ leftButtonClick() }
        viewBinding.rightButton.setOnClickListener { rightButtonClick() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun createBitmap(matrix: BitMatrix): Bitmap? {
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun closeKeyboard() {
        // Get the current focus of the view
        // val view = currentFocus
        val view = findViewById<View>(android.R.id.content)

        // If the view is not null, hide the keyboard
        if (view != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun configureUI(programState: ProgramState) {
        // buttons
        // - viewBinding.leftButton
        // - viewBinding.rightButton
        // input fields and image view
        // - viewBinding.qrCodeGenEditText
        // - viewBinding.qrCodeFoundResultText
        // - viewBinding.qrCodeGeneratedImage

        this.programState = programState
        when (this.programState) {
            // Reset to starting state
            ProgramState.BASE -> {
//                viewBinding.leftButton.isEnabled = false
                viewBinding.qrCodeFoundResultText.visibility = View.INVISIBLE
                viewBinding.qrCodeFoundResultText.text = ""

                viewBinding.rightButton.isEnabled = true
                viewBinding.qrCodeGeneratedImage.visibility = View.INVISIBLE
                viewBinding.qrCodeGeneratedImage.setImageResource(0)

                viewBinding.qrCodeGenEditText.visibility = View.INVISIBLE
                viewBinding.qrCodeGenEditText.text.clear()

                viewBinding.rightButton.text = getString(R.string.create_qr_code)
                viewBinding.leftButton.text = getString(R.string.upload_qr_code)

                this.closeKeyboard()
            }
            ProgramState.QR_FOUND -> {
                viewBinding.leftButton.isEnabled = true

                viewBinding.leftButton.text = getString(R.string.clear_screen)
                viewBinding.rightButton.text = getString(R.string.copy_decoded_text)
                viewBinding.qrCodeFoundResultText.visibility = View.VISIBLE
            }
            ProgramState.QR_INPUT_ACTIVE -> {
                viewBinding.leftButton.isEnabled = true
                viewBinding.qrCodeGenEditText.visibility = View.VISIBLE
                viewBinding.qrCodeGeneratedImage.visibility = View.INVISIBLE
                viewBinding.rightButton.text = getString(R.string.gen_qr_code)
                viewBinding.leftButton.text = getString(R.string.clear_screen)
            }
            ProgramState.QR_IMAGE_GENERATED -> {
                viewBinding.qrCodeGenEditText.visibility = View.INVISIBLE
                viewBinding.rightButton.text = getString(R.string.download_qr_code)
                viewBinding.qrCodeGeneratedImage.visibility = View.VISIBLE

                viewBinding.leftButton.text = getString(R.string.clear_screen)
                this.closeKeyboard()
            }
        }
    }

    private fun leftButtonClick() {
        if (this.programState == ProgramState.BASE) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "image/*"
            startActivityForResult(intent, REQUEST_SELECT_IMAGE_IN_ALBUM)
        } else {
            this.configureUI(ProgramState.BASE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (
            requestCode == REQUEST_SELECT_IMAGE_IN_ALBUM &&
            resultCode == Activity.RESULT_OK &&
            data != null &&
            data.data != null) {

            val selectedImageUri: Uri = data.data!!

            val source = ImageDecoder.createSource(this.contentResolver, selectedImageUri)
            val bitmap = ImageDecoder.decodeBitmap(source)

            val scanner = BarcodeScanning.getClient()
            scanner.process(bitmap, 0) // image angle: 0, 90, 180, 270
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isEmpty()) {
                        Toast.makeText(applicationContext, "Can not find QRCode on the selected image!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(applicationContext, "QRCode found!", Toast.LENGTH_LONG).show()

                        val barcode = barcodes[0]

                        viewBinding.qrCodeFoundResultText.text = barcode.rawValue
                        this.configureUI(ProgramState.QR_FOUND)
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(applicationContext, "Can not find QRCode on the selected image!", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun rightButtonClick() {
        when (this.programState) {
            // Reset to starting state
            ProgramState.BASE -> {
                this.configureUI(ProgramState.QR_INPUT_ACTIVE)
            }
            ProgramState.QR_FOUND -> {
                this.configureUI(ProgramState.QR_FOUND)

                val data = viewBinding.qrCodeFoundResultText.text.toString()

                val clipboard: ClipboardManager =
                    getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(data, data)
                clipboard.setPrimaryClip(clip)

                Toast.makeText(applicationContext, "Text copied to clipboard!", Toast.LENGTH_LONG).show()
            }
            ProgramState.QR_INPUT_ACTIVE -> {
                val text = viewBinding.qrCodeGenEditText.text.toString()
                viewBinding.qrCodeGenEditText.text.clear()

                if (text.isEmpty()) {
                    Toast.makeText(applicationContext, "Text can not be empty...", Toast.LENGTH_LONG).show()
                    return
                }

                val hintMap: MutableMap<EncodeHintType, Any> = EnumMap(
                    EncodeHintType::class.java
                )

                hintMap[EncodeHintType.CHARACTER_SET] = "UTF-8"
                hintMap[EncodeHintType.MARGIN] = 0
                hintMap[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M

                val qrCodeWriter = QRCodeWriter()
                val bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, 1024, 1024, hintMap)
                this.generatedBitmap = createBitmap(bitMatrix)

                viewBinding.qrCodeGeneratedImage.setImageBitmap(this.generatedBitmap)
                this.configureUI(ProgramState.QR_IMAGE_GENERATED)
            }
            ProgramState.QR_IMAGE_GENERATED -> {
                if (this.generatedBitmap != null) {
                    this.saveImageToStorage(this.generatedBitmap!!)
                }
            }
        }
    }

    private fun saveImageToStorage(bitmap: Bitmap) {
        // Get the directory to save the image
        val directory = this.getExternalFilesDir(Environment.DIRECTORY_PICTURES)

        // Create a new file in the directory
        val fileName = SimpleDateFormat(FILENAME_FORMAT, Locale.ENGLISH)
            .format(System.currentTimeMillis())
        val file = File(directory, fileName)

        // Convert the bitmap to a JPEG and save it to the file
        try {
            MediaStore.Images.Media.insertImage(contentResolver, bitmap, fileName , "yourDescription")
        } catch (ex: Exception) {
            Toast.makeText(applicationContext, "Image save failed!", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(applicationContext, "Image saved successfully!", Toast.LENGTH_LONG).show()

        // Notify the gallery that a new file has been added
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
//                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
//                .setTargetResolution(Size(1280, 1280))
                .setOutputImageRotationEnabled(false)
//                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
//                    if (programState == ProgramState.BASE) {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val qrCodeImageAnalyzer = QRCodeImageAnalyzer(object : QRCodeFoundListener {
                            override fun onQRCodeFound(barcodes: List<Barcode>) {
                                qrCodeFound(barcodes)
                            }
                            override fun qrCodeNotFound() { }
                        })

//                        qrCodeImageAnalyzer.analyze(imageProxy, Rect(170, 240, 780,850))

                        val screenWidth = viewBinding.viewFinder.width
                        val screenHeight = viewBinding.viewFinder.height

                        val overlay = RectF(
                            overlayRect.left / screenWidth,
                            overlayRect.top / screenHeight,
                            overlayRect.right / screenWidth,
                            overlayRect.bottom / screenHeight)
                        qrCodeImageAnalyzer.analyze(imageProxy, overlay)
                    }
//                    }
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                val aspectRatio = Rational(viewBinding.viewFinder.width, viewBinding.viewFinder.height)
                val viewPort =  ViewPort.Builder(aspectRatio, preview.targetRotation).build()
                UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(imageAnalyzer)
//                    .addUseCase(imageCapture)
                    .setViewPort(viewPort)
                    .build()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer)

                // Set the overlay on the CameraView
                viewBinding.overlayView.post {
                    updateOverlayRect()
                }

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    fun qrCodeFound(barcodes: List<Barcode>) {
        if (this.programState == ProgramState.BASE && barcodes.isNotEmpty()) {
            val barcode = barcodes[0]
            viewBinding.qrCodeFoundResultText.text = barcode.rawValue
            this.configureUI(ProgramState.QR_FOUND)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateOverlayRect() {
        overlayRect.left = viewBinding.overlayView.left.toFloat()
        overlayRect.top = viewBinding.overlayView.top.toFloat()
        overlayRect.right = viewBinding.overlayView.right.toFloat()
        overlayRect.bottom = viewBinding.overlayView.bottom.toFloat()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUEST_SELECT_IMAGE_IN_ALBUM = 200
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
            ).apply {
            }.toTypedArray()
    }
}

interface QRCodeFoundListener {
    fun onQRCodeFound(barcodes: List<Barcode>)
    fun qrCodeNotFound()
}