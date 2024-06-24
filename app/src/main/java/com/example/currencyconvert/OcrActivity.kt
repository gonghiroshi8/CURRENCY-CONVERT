package com.example.currencyconvert

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.currencyconvert.camera.CameraManagerHelper
import com.googlecode.tesseract.android.TessBaseAPI
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream

class OcrActivity : AppCompatActivity() {
    private lateinit var textureView: TextureView
    private lateinit var ocrResult: TextView
    private lateinit var spinnerCurrency: Spinner
    private lateinit var tessBaseAPI: TessBaseAPI
    private var selectedCurrency: String = "USD"

    private lateinit var cameraManagerHelper: CameraManagerHelper

    override fun onCreate(savedInstanceState: Bundle?) {
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

        cameraManagerHelper = CameraManagerHelper(this, textureView, tessBaseAPI, ::onImageAvailable)
        cameraManagerHelper.startCamera()
    }


    private fun onImageAvailable(ocrText: String) {
        runOnUiThread {
            ocrResult.text = ocrText
        }
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
                cameraManagerHelper.startCamera()
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
