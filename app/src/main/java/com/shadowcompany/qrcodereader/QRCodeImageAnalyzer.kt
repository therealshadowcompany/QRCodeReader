package com.shadowcompany.qrcodereader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ImageFormat.*
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.common.internal.ImageConvertUtils

class QRCodeImageAnalyzer(private val listener: QRCodeFoundListener) {

    @SuppressLint("UnsafeOptInUsageError")
    fun analyze(imageProxy: ImageProxy, cropRect: RectF) {
        if (imageProxy.image == null) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        // Convert the InputImage to a Bitmap
        val bitmap = ImageConvertUtils.getInstance().getUpRightBitmap(inputImage)

        val left = cropRect.left * bitmap.width * 10
        val top = cropRect.top * bitmap.height
        val width = (cropRect.width() * bitmap.width) - left
        val height = cropRect.height() * bitmap.height

        val croppedBitmap: Bitmap

        // crop the bitmap
        try {
            croppedBitmap = Bitmap.createBitmap(
                bitmap,
                left.toInt(),
                top.toInt(),
                width.toInt(),
                height.toInt()
            )
        } catch (exception: Exception) {
            imageProxy.close()
            // y + height must be <= bitmap.height()
            return
        }

//        saveImage(croppedBitmap, File("/sdcard/Pictures/asd.jpeg"))

        val croppedInputImage = InputImage.fromBitmap(croppedBitmap, 0)

        // Pass image to an ML Kit Vision API
        val scanner = BarcodeScanning.getClient()
        // Or, to specify the formats to recognize:
        // val scanner = BarcodeScanning.getClient(options)

        val result = scanner.process(croppedInputImage)
            .addOnSuccessListener { barcodes ->

                listener.onQRCodeFound(barcodes)
            }
            .addOnFailureListener {
                // Task failed with an exception
                // ...
                listener.qrCodeNotFound()
            }

        imageProxy.close()
    }
}