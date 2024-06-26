package com.example.currencyconvert

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.currencyconvert.camera.CameraManagerHelper
import com.googlecode.tesseract.android.TessBaseAPI
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class OcrActivity : AppCompatActivity() {
    private lateinit var textureView: PreviewView
    private lateinit var ocrResult: TextView
    private lateinit var spinnerCurrency: Spinner
    private lateinit var tessBaseAPI: TessBaseAPI
    private var selectedCurrency: String = "USD"

    private lateinit var cameraManagerHelper: CameraManagerHelper
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        System.loadLibrary("opencv_java4");
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ocr)

        textureView = findViewById(R.id.textureView)
        ocrResult = findViewById(R.id.ocrResult)
        spinnerCurrency = findViewById(R.id.spinnerCurrency)

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

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        copyTessDataFiles()

        tessBaseAPI = TessBaseAPI()
        tessBaseAPI.init(filesDir.absolutePath, "eng")

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        } else {
            startCameraX()
        }
    }

    private fun startCameraX() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(textureView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, { imageProxy ->
                        val bitmap = imageProxy.toBitmap()
                        val preprocessedBitmap = preprocessBitmap(bitmap)
                        val text = getTextFromBitmap(preprocessedBitmap)
                        runOnUiThread {
                            ocrResult.text = text
                        }
                        imageProxy.close()
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (exc: Exception) {
                Log.e("OcrActivity", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getTextFromBitmap(bitmap: Bitmap): String {
        tessBaseAPI.setImage(bitmap)
        val text = tessBaseAPI.utF8Text
        val numberRegex = Regex("\\d+")
        val numbers = numberRegex.findAll(text).map { it.value }.joinToString("")
        return numbers
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val buffer: ByteBuffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null)
    }

    private fun preprocessBitmap(bitmap: Bitmap): Bitmap {
        // Convert Bitmap to Mat
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Convert to grayscale
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGB2GRAY)

        // Apply Gaussian blur to reduce noise
        Imgproc.GaussianBlur(mat, mat, Size(5.0, 5.0), 0.0)

        // Apply adaptive thresholding to convert to black and white
        Imgproc.adaptiveThreshold(mat, mat, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 11, 2.0)

        // Convert back to Bitmap
        val preprocessedBitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, preprocessedBitmap)

        return preprocessedBitmap
    }

    private fun copyTessDataFiles() {
        val assetManager = assets
        val tessDir = File(filesDir, "tessdata")
        if (!tessDir.exists()) {
            tessDir.mkdir()
        }

        val fileList = assetManager.list("tessdata")
        fileList?.forEach { filename ->
            val outFile = File(tessDir, filename)
            if (!outFile.exists()) {
                val inputStream = assetManager.open("tessdata/$filename")
                val outputStream = FileOutputStream(outFile)

                val buffer = ByteArray(1024)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }

                inputStream.close()
                outputStream.flush()
                outputStream.close()
                Log.d("OcrActivity", "Copied $filename to ${outFile.absolutePath}")
            } else {
                Log.d("OcrActivity", "$filename already exists at ${outFile.absolutePath}")
            }
        }
        Log.d("OcrActivity", "Tessdata path: ${filesDir.absolutePath}/tessdata")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraX()
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
