package com.example.musiccam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.PermissionChecker
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.widget.ImageView
import androidx.camera.core.ImageCapture.OnImageCapturedCallback

import org.tensorflow.lite.task.vision.detector.ObjectDetector
import com.example.musiccam.databinding.ActivityMainBinding
//import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import java.io.IOException


class MainActivity : AppCompatActivity() {
    private lateinit var classifier: TFLiteClassifier
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var inputImageView: ImageView
    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    private val trebleClefMapping = mapOf(
    //Individualnotesforeachlineandspace
                "Line1" to "F",
        "Line2" to "D",
        "Line3" to "B",
        "Line4" to "G",
        "Line5" to "E",
        "Space1" to "E",
        "Space2" to "C",
        "Space3" to "A",
        "Space4" to "F",

        //Chordsforcombinationsoflinesandspaces
        listOf("Line1","Line2","Line3") to "GMajor",//F,D,B
        listOf("Line2","Line3","Line4") to "CMajor",//D,B,G
        listOf("Line3","Line4","Line5") to "EMajor",//B,G,E
        listOf("Space1","Space2","Space3") to "AMajor",//E,C,A
        listOf("Line1","Space2","Line3") to "FMajor",//F,C,B
        listOf("Line2","Space2","Line4") to "DMajor",//D,C,G
        listOf("Space1","Line3","Space3") to "CMajor",//E,B,A

        //MinorChords
        listOf("Line1","Line3","Line5") to "AMinor",//F,B,E
        listOf("Line2","Line4","Line5") to "GMinor",//D,G,E
        listOf("Space1","Space3","Space4") to "EMinor",//E,A,F
        listOf("Space2","Space3","Line4") to "BMinor",//C,A,G
        listOf("Line2","Line3","Space3") to "DMinor",//D,B,A
        listOf("Space1","Line2","Line3") to "FMinor",//E,D,B

        //DiminishedChords
        listOf("Line2","Line3","Space1") to "CDiminished",//D,B,E
        listOf("Line3","Line4","Space3") to "BDiminished",//B,G,A
        listOf("Line1","Space2","Space3") to "FDiminished",//F,C,A
        listOf("Line2","Space2","Line5") to "DDiminished",//D,C,E
        listOf("Space1","Line3","Line5") to "EDiminished",//E,B,E

        //SeventhChords
        listOf("Line1","Line2","Space3","Line3") to "G7",//F,D,A,B
        listOf("Line2","Space1","Space3","Line5") to "C7",//D,E,A,E
        listOf("Line1","Space2","Line4","Line5") to "F7",//F,C,G,E
        listOf("Space1","Space2","Space3","Space4") to "A7",//E,C,A,F
        listOf("Line3","Space2","Line4","Space1") to "B7",//B,C,G,E
        listOf("Line2","Line3","Line4","Line5") to "E7",//D,B,G,E

        //MajorSeventhChords
        listOf("Line1","Space2","Line3","Space4") to "Fmaj7",//F,C,B,F
        listOf("Line2","Line3","Line4","Space3") to "Gmaj7",//D,B,G,A
        listOf("Space1","Line2","Space2","Line4") to "Amaj7",//E,D,C,G
        listOf("Space1","Line2","Space3","Line5") to "Cmaj7"//E,D,A,E
        )



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        inputImageView = findViewById(R.id.inputImageView)
        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }
        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        cameraExecutor = Executors.newSingleThreadExecutor()
        classifier = TFLiteClassifier(assets, "mobilenet_note_classifier.tflite")
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
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun
                        onImageSaved(output: ImageCapture.OutputFileResults){
                    val msg = "Photo capture succeeded: ${output.savedUri}"
//                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)

                    output.savedUri?.let { uri ->
                        val bitmap = uriToBitmap(uri)
                        runObjectDetection(bitmap)
                        classifyImage(bitmap)  // Call the classifyImage function with the Bitmap
                    }
                }


            },
        )



    }

    private fun uriToBitmap(uri: Uri): Bitmap {
        return contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        } ?: throw IOException("Failed to load Bitmap from URI: $uri")
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val planeProxy = image.planes[0]
        val buffer: ByteBuffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun classifyImage(bitmap: Bitmap) {
        val labels = arrayOf( "Line1", "Line2", "Line3", "Line4", "Line5", "Space1", "Space2", "Space3", "Space4", "Space5")
        val result = classifier.classifyNotePosition(bitmap)
        // Display or use the classification result
        Toast.makeText(this, "Predicted Class: ${labels[result]}", Toast.LENGTH_SHORT).show()
        trebleClefMapping[labels[result]]?.let { Log.d("note mapping: ", it) }
    }

    /**
     * TFLite Object Detection Function
     */
    private fun runObjectDetection(bitmap: Bitmap) {
        //TODO: Add object detection code here
        val image = TensorImage.fromBitmap(bitmap)
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setMaxResults(5)
            .setScoreThreshold(0.5f)
            .build()
        try {
            val detector = ObjectDetector.createFromFileAndOptions(
                this, // the application context
                "finetuned_circle_with_metadata.tflite", // must be same as the filename in assets folder
                options)

                val results = detector.detect(image)
                debugPrint(results)
                drawBoundingBoxes(bitmap,results, inputImageView)
                // TODO take this and draw
//                val resultToDisplay = results.map {
//                    // Get the top-1 category and craft the display text
//                    val category = it.categories.first()
//                    val text = "${category.label}, ${category.score.times(100).toInt()}%"
//
//                    // Create a data object to display the detection result
//                    DetectionResult(it.boundingBox, text)
//                }
                // Draw the detection result on the bitmap and show it.
//                val imgWithResult = drawDetectionResult(bitmap, resultToDisplay)
//                runOnUiThread {
//                    inputImageView.setImageBitmap(imgWithResult)
//                }

        }catch (e: Exception) {
            val testval = null
        }




    }

    private fun debugPrint(results : List<Detection>) {
        for ((i, obj) in results.withIndex()) {
            val box = obj.boundingBox

            Log.d(TAG, "Detected object: ${i} ")
            Log.d(TAG, "  boundingBox: (${box.left}, ${box.top}) - (${box.right},${box.bottom})")

            for ((j, category) in obj.categories.withIndex()) {
                Log.d(TAG, "    Label $j: ${category.label}")
                val confidence: Int = category.score.times(100).toInt()
                Log.d(TAG, "    Confidence: ${confidence}%")
            }
        }


    }



    private fun captureVideo() {}

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()


            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))

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

    fun drawBoundingBoxes(
        imageBitmap: Bitmap,
        results: List<Detection>, // Replace with your result class
        imageView: ImageView
    ) {
        // Create a mutable copy of the bitmap
        val mutableBitmap = imageBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        // Paint for bounding boxes
        val boxPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        // Paint for labels
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 40f
            isAntiAlias = true
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        // Iterate through detected objects
        for ((i, obj) in results.withIndex()) {
            val box = obj.boundingBox // Assuming boundingBox is RectF
            canvas.drawRect(box, boxPaint) // Draw bounding box

            // Draw labels
            for ((j, category) in obj.categories.withIndex()) {
                val labelText = "${category.label} (${(category.score * 100).toInt()}%)"
                canvas.drawText(labelText, box.left, box.top - 10 - (j * 40), textPaint)
            }
        }

        // Set the modified bitmap to the ImageView
        imageView.setImageBitmap(mutableBitmap)
    }

    /**
     * drawDetectionResult(bitmap: Bitmap, detectionResults: List<DetectionResult>
     *      Draw a box around each objects and show the object's name.
     */
//    private fun drawDetectionResult(
//        bitmap: Bitmap,
//        detectionResults: List<DetectionResult>
//    ): Bitmap {
//        val outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
//        val canvas = Canvas(outputBitmap)
//        val pen = Paint()
//        pen.textAlign = Paint.Align.LEFT
//
//        detectionResults.forEach {
//            // draw bounding box
//            pen.color = Color.RED
//            pen.strokeWidth = 8F
//            pen.style = Paint.Style.STROKE
//            val box = it.boundingBox
//            canvas.drawRect(box, pen)
//
//
//            val tagSize = Rect(0, 0, 0, 0)
//
//            // calculate the right font size
//            pen.style = Paint.Style.FILL_AND_STROKE
//            pen.color = Color.YELLOW
//            pen.strokeWidth = 2F
//
//            pen.textSize = 5F
//            pen.getTextBounds(it.text, 0, it.text.length, tagSize)
//            val fontSize: Float = pen.textSize * box.width() / tagSize.width()
//
//            // adjust the font size so texts are inside the bounding box
//            if (fontSize < pen.textSize) pen.textSize = fontSize
//
//            var margin = (box.width() - tagSize.width()) / 2.0F
//            if (margin < 0F) margin = 0F
//            canvas.drawText(
//                it.text, box.left + margin,
//                box.top + tagSize.height().times(1F), pen
//            )
//        }
//        return outputBitmap
//    }
}

/**
 * DetectionResult
 *      A class to store the visualization info of a detected object.
 */
//}
//data class DetectionResult(val boundingBox: RectF, val text: String)

