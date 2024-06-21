package com.example.currencyconvert

import android.Manifest
import android.content.ContentValues.TAG
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.googlecode.tesseract.android.TessBaseAPI
import okhttp3.OkHttpClient
import org.jetbrains.annotations.NotNull
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class OcrActivity : AppCompatActivity() {
    private lateinit var textureView: TextureView
    private lateinit var ocrResult: TextView
    private lateinit var spinnerCurrency: Spinner
    private var cameraDevice: CameraDevice? = null // Change to nullable
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var imageReader: ImageReader
    private lateinit var tessBaseAPI: TessBaseAPI
    private var selectedCurrency: String = "USD" // ค่าเงินที่ถูกเลือก

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ocr)

        textureView = findViewById(R.id.textureView)
        ocrResult = findViewById(R.id.ocrResult)
        spinnerCurrency = findViewById(R.id.spinnerCurrency)

        // ตั้งค่า Spinner สำหรับเลือกค่าเงิน
        val currencies = arrayOf("USD", "EUR", "JPY", "GBP")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, currencies)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCurrency.adapter = adapter

        spinnerCurrency.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                selectedCurrency = parent.getItemAtPosition(position).toString()
                ocrResult.text = "Selected Currency: $selectedCurrency"
                fetchExchangeRates(selectedCurrency)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // Do nothing
            }
        }

        // เรียกฟังก์ชันคัดลอกไฟล์ก่อนการใช้ init
        copyTessDataFiles()

        tessBaseAPI = TessBaseAPI()
        tessBaseAPI.init("${filesDir.absolutePath}/", "eng")

        startCamera()
    }

    private fun copyTessDataFiles() {
        val assetManager = assets
        val tessDir = File(filesDir, "tessdata")
        if (!tessDir.exists()) {
            tessDir.mkdir()
        }

        val fileList = assetManager.list("tessdata")
        fileList?.forEach { filename ->
            val inputStream = assetManager.open("tessdata/$filename")
            val outFile = File(tessDir, filename)
            val outputStream = FileOutputStream(outFile)

            val buffer = ByteArray(1024)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
            }

            inputStream.close()
            outputStream.flush()
            outputStream.close()

            // พิมพ์บันทึกเพื่อดูว่าคัดลอกไฟล์สำเร็จหรือไม่
            Log.d("OcrActivity", "Copied $filename to ${outFile.absolutePath}")
        }
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    return false
                }

                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openCamera()
                }
            }
        }
    }

    private fun openCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0]
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = map!!.getOutputSizes(SurfaceTexture::class.java)[0]

            imageReader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2)
            imageReader.setOnImageAvailableListener({ reader ->
                val image: Image = reader.acquireLatestImage()
                val planes: Array<Image.Plane> = image.planes
                val yBuffer = planes[0].buffer // Y
                val uBuffer = planes[1].buffer // U
                val vBuffer = planes[2].buffer // V

                val ySize = yBuffer.remaining()
                val uSize = uBuffer.remaining()
                val vSize = vBuffer.remaining()

                val nv21 = ByteArray(ySize + uSize + vSize)

                yBuffer[nv21, 0, ySize]
                vBuffer[nv21, ySize, vSize]
                uBuffer[nv21, ySize + vSize, uSize]

                val yuvImage = YuvImage(nv21, ImageFormat.NV21, previewSize.width, previewSize.height, null)
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, previewSize.width, previewSize.height), 50, out)
                val imageBytes = out.toByteArray()
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                val text = getTextFromBitmap(bitmap)
                runOnUiThread {
                    ocrResult.text = text
                }

                image.close()
            }, null)

            val texture = textureView.surfaceTexture!!
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(texture)

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                // Handle permissions if not already granted
                return
            }

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera

                    captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    captureRequestBuilder.addTarget(surface)
                    captureRequestBuilder.addTarget(imageReader.surface)

                    camera.createCaptureSession(listOf(surface, imageReader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(@NotNull session: CameraCaptureSession) {
                            cameraCaptureSession = session
                            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            try {
                                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null)
                            } catch (e: CameraAccessException) {
                                e.printStackTrace()
                            }
                        }

                        override fun onConfigureFailed(@NotNull session: CameraCaptureSession) {
                            Log.e(TAG, "Configuration change")
                        }
                    }, null)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null // Now cameraDevice is nullable
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null // Now cameraDevice is nullable
                }
            }, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun getTextFromBitmap(bitmap: Bitmap): String {
        tessBaseAPI.setImage(bitmap)
        return tessBaseAPI.utF8Text
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            }
        }
    }

    private fun fetchExchangeRates(currency: String) {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.exchangerate-api.com/v4/latest/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(OkHttpClient.Builder().build())
            .build()

        val service = retrofit.create(ExchangeRateApi::class.java)
        val call = service.getRates(currency)
        call.enqueue(object : Callback<CurrencyResponse> {
            override fun onResponse(call: Call<CurrencyResponse>, response: Response<CurrencyResponse>) {
                if (response.isSuccessful) {
                    val rates = response.body()?.rates
                    rates?.let {
                        val rate = it[selectedCurrency]
                        ocrResult.text = "1 $currency = $rate $selectedCurrency"
                    }
                } else {
                    Log.e("OcrActivity", "Failed to fetch exchange rates")
                }
            }

            override fun onFailure(call: Call<CurrencyResponse>, t: Throwable) {
                Log.e("OcrActivity", "Error: ${t.message}")
            }
        })
    }
}
