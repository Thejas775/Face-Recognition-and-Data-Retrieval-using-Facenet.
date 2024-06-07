package com.thejas.facerecognitionanddataretrieval

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.thejas.facerecognitionanddataretrieval.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import android.util.Log
import android.view.View
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.PermissionChecker
import com.bumptech.glide.Glide
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.pow

typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null

    private val useGpu = true
    private val useXNNPack = true
    private val modelInfo = Models.FACENET
    private lateinit var faceNetModel : FaceNetModel
    private var embeddings: FloatArray? = null





    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        faceNetModel = FaceNetModel( this@MainActivity , modelInfo , useGpu , useXNNPack )





        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        // Set up the listeners for take photo and video capture buttons
       viewBinding.imageCaptureButton.setOnClickListener {takePhoto()
       }

        viewBinding.save.setOnClickListener {
            saveEmbeddings()
            backtomain()
        }
        viewBinding.back.setOnClickListener {


            backtomain()
        }


        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun saveEmbeddings() {
        val name = viewBinding.name.text.toString()
        if (name.isBlank()) {
            Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }
        if (embeddings == null) {
            Toast.makeText(this, "No embeddings to save", Toast.LENGTH_SHORT).show()
            return
        }

        val embeddingData = JSONObject().apply {
            put("name", name)
            put("embeddings", JSONArray(embeddings))
        }

        val file = File(filesDir, "embeddings.json")

        // Create the file if it does not exist
        if (!file.exists()) {
            file.createNewFile()
        }

        val jsonArray = if (file.exists() && file.length() > 0) {
            JSONArray(file.readText())
        } else {
            JSONArray()
        }
        jsonArray.put(embeddingData)

        FileWriter(file).use {
            it.write(jsonArray.toString())
        }

        Toast.makeText(this, "Embeddings saved successfully", Toast.LENGTH_SHORT).show()
    }




    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Log.d(TAG, msg)

                 //   Glide.with(this@MainActivity)
                   //     .load(output.savedUri)
                     //   .into(viewBinding.imgview)
                    val highspeed = FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .build()

                    val image: InputImage =
                        output.savedUri?.let { InputImage.fromFilePath(this@MainActivity, it) }!!
                    val detector = FaceDetection.getClient(highspeed)

                    val result = detector.process(image)
                        .addOnSuccessListener { faces ->
                            val immutableBitmap = MediaStore.Images.Media.getBitmap(contentResolver, output.savedUri)

                            val bitmap = immutableBitmap.copy(immutableBitmap.config, true)
                            val canvas = Canvas(bitmap)

                            val paint = Paint().apply {
                                color = Color.RED
                                style = Paint.Style.STROKE
                                strokeWidth = 4f
                            }
                            if (faces.isEmpty()) {
                                Toast.makeText(this@MainActivity, "No face detected", Toast.LENGTH_SHORT).show()
                                backtomain()
                                return@addOnSuccessListener
                            }
                            for (face in faces) {
                                if (faces.size == 1) {
                                    val bounds = face.boundingBox
                                    canvas.drawRect(bounds, paint)

                                    // Crop the face from the original image
                                    val faceBitmap = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
                                    embeddings = faceNetModel.getFaceEmbedding(faceBitmap)

                                    if (embeddings != null) {
                                        val bestMatch = compareFacesL2Norm(embeddings!!)
                                        if (bestMatch == "Unknown") {
                                            Toast.makeText(this@MainActivity, "No match found.Please Save name", Toast.LENGTH_SHORT).show()
                                            viewBinding.name.visibility = View.VISIBLE
                                            viewBinding.save.visibility = View.VISIBLE


                                        } else {
                                            Toast.makeText(this@MainActivity, "Best Match: $bestMatch", Toast.LENGTH_SHORT).show()
                                        }

                                    }
                                    viewBinding.imgview.setImageBitmap(faceBitmap)
                                }
                                else{
                                    Toast.makeText(this@MainActivity, "Multiple faces detected", Toast.LENGTH_SHORT).show()
                                    backtomain()
                                }



                        }}
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Face detection failed", e)
                            Toast.makeText(this@MainActivity, "Face detection failed. No face detected", Toast.LENGTH_SHORT).show()
                        }


                    viewBinding.imgview.visibility = View.VISIBLE
                    viewBinding.back.visibility = View.VISIBLE
                    viewBinding.imageCaptureButton.visibility = View.GONE
                    viewBinding.viewFinder.visibility = View.GONE




                    //For deleting the saved image use this
                    //output.savedUri?.let { uri ->
                    //  contentResolver.delete(uri, null, null)
                    //}


                    val cameraProviderFuture = ProcessCameraProvider.getInstance(this@MainActivity)
                    cameraProviderFuture.addListener({
                        val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                        cameraProvider.unbindAll()
                    }, ContextCompat.getMainExecutor(this@MainActivity))
                }
            }
        )
    }

    private fun backtomain(){
        viewBinding.imgview.setImageDrawable(null)
        //Glide ka disable kiya hai

        // Glide.with(this@MainActivity).clear(viewBinding.imgview)

        viewBinding.imgview.visibility = View.GONE
        viewBinding.back.visibility = View.GONE
        viewBinding.name.visibility = View.GONE
        viewBinding.save.visibility = View.GONE


        startCamera()
        viewBinding.imageCaptureButton.visibility = View.VISIBLE
        viewBinding.viewFinder.visibility = View.VISIBLE
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()

            val imageAnalyzer = ImageAnalysis.Builder().build().also {
                it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                    Log.d(TAG, "Average luminosity: $luma")
                })
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()
            val data = ByteArray(remaining())
            get(data)
            return data
        }

        override fun analyze(image: ImageProxy) {
            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            listener(luma)
            image.close()
        }
    }


    private fun loadEmbeddings(): List<Pair<String, FloatArray>> {
        val file = File(filesDir, "embeddings.json")
        val faceList = mutableListOf<Pair<String, FloatArray>>()

        if (file.exists()) {
            val jsonArray = JSONArray(file.readText())
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val name = jsonObject.getString("name")
                val embeddingsArray = jsonObject.getJSONArray("embeddings")
                val embeddings = FloatArray(embeddingsArray.length())
                for (j in 0 until embeddingsArray.length()) {
                    embeddings[j] = embeddingsArray.getDouble(j).toFloat()
                }
                faceList.add(Pair(name, embeddings))
            }
        }
        return faceList
    }

    private fun compareFacesL2Norm(newEmbedding: FloatArray): String {
        val savedEmbeddings = loadEmbeddings()
        var bestMatch = "Unknown"
        var bestDistance = Float.MAX_VALUE

        for ((name, savedEmbedding) in savedEmbeddings) {
            val distance = L2Norm(newEmbedding, savedEmbedding)
            if (distance < bestDistance) {
                bestDistance = distance
                bestMatch = name
            }
        }

        val threshold = Models.FACENET.l2Threshold  // Use the threshold from ModelInfo
        return if (bestDistance < threshold) bestMatch else "Unknown"
    }

    private fun L2Norm(x1: FloatArray, x2: FloatArray): Float {
        var sum = 0.0f
        for (i in x1.indices) {
            sum += (x1[i] - x2[i]).pow(2)
        }
        return kotlin.math.sqrt(sum)
    }


    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)

    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}