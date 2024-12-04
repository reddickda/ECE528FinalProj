package com.example.musiccam

//import org.tensorflow.lite.task.vision.detector.ObjectDetector
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import com.example.musiccam.databinding.ActivityMainBinding
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {
    private lateinit var classifier: TFLiteClassifier
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var imageViewOriginal: ImageView
    private lateinit var imageViewEdited: ImageView

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
        listOf("Line1", "Line2", "Line3") to "GMajor",//F,D,B
        listOf("Line2", "Line3", "Line4") to "CMajor",//D,B,G
        listOf("Line3", "Line4", "Line5") to "EMajor",//B,G,E
        listOf("Space1", "Space2", "Space3") to "AMajor",//E,C,A
        listOf("Line1", "Space2", "Line3") to "FMajor",//F,C,B
        listOf("Line2", "Space2", "Line4") to "DMajor",//D,C,G
        listOf("Space1", "Line3", "Space3") to "CMajor",//E,B,A

        //MinorChords
        listOf("Line1", "Line3", "Line5") to "AMinor",//F,B,E
        listOf("Line2", "Line4", "Line5") to "GMinor",//D,G,E
        listOf("Space1", "Space3", "Space4") to "EMinor",//E,A,F
        listOf("Space2", "Space3", "Line4") to "BMinor",//C,A,G
        listOf("Line2", "Line3", "Space3") to "DMinor",//D,B,A
        listOf("Space1", "Line2", "Line3") to "FMinor",//E,D,B

        //DiminishedChords
        listOf("Line2", "Line3", "Space1") to "CDiminished",//D,B,E
        listOf("Line3", "Line4", "Space3") to "BDiminished",//B,G,A
        listOf("Line1", "Space2", "Space3") to "FDiminished",//F,C,A
        listOf("Line2", "Space2", "Line5") to "DDiminished",//D,C,E
        listOf("Space1", "Line3", "Line5") to "EDiminished",//E,B,E

        //SeventhChords
        listOf("Line1", "Line2", "Space3", "Line3") to "G7",//F,D,A,B
        listOf("Line2", "Space1", "Space3", "Line5") to "C7",//D,E,A,E
        listOf("Line1", "Space2", "Line4", "Line5") to "F7",//F,C,G,E
        listOf("Space1", "Space2", "Space3", "Space4") to "A7",//E,C,A,F
        listOf("Line3", "Space2", "Line4", "Space1") to "B7",//B,C,G,E
        listOf("Line2", "Line3", "Line4", "Line5") to "E7",//D,B,G,E

        //MajorSeventhChords
        listOf("Line1", "Space2", "Line3", "Space4") to "Fmaj7",//F,C,B,F
        listOf("Line2", "Line3", "Line4", "Space3") to "Gmaj7",//D,B,G,A
        listOf("Space1", "Line2", "Space2", "Line4") to "Amaj7",//E,D,C,G
        listOf("Space1", "Line2", "Space3", "Line5") to "Cmaj7"//E,D,A,E
    )


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        imageViewOriginal = findViewById(R.id.imageViewOriginal)
        imageViewEdited = findViewById(R.id.imageViewEdited)

        val hideButton: Button = findViewById(R.id.hideButton)
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

        hideButton.setOnClickListener {
            if (imageViewOriginal.visibility == View.VISIBLE) {
                imageViewOriginal.visibility = View.GONE
                imageViewEdited.visibility = View.GONE

                hideButton.text = "Show"
            } else {
                imageViewOriginal.visibility = View.VISIBLE
                imageViewEdited.visibility = View.VISIBLE

                hideButton.text = "Hide"
            }
        }
    }

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        )
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(
                    baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT
                ).show()
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
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
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
                        onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${output.savedUri}"
//                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)

                    output.savedUri?.let { uri ->
                        val bitmap = uriToBitmap(uri)
                        Toast.makeText(this@MainActivity, "Processing image...", Toast.LENGTH_SHORT).show()

                        val resizedPhoto = Bitmap.createScaledBitmap(bitmap, 320, 320, true);

                        runObjectDetection(bitmap, resizedPhoto)
                        //classifyImage(bitmap)  // Call the classifyImage function with the Bitmap
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
        val labels = arrayOf(
            "Line1",
            "Line2",
            "Line3",
            "Line4",
            "Line5",
            "Space1",
            "Space2",
            "Space3",
            "Space4",
            "Space5"
        )
        val result = classifier.classifyNotePosition(bitmap)
        // Display or use the classification result
        Toast.makeText(this, "Predicted Class: ${labels[result]}", Toast.LENGTH_SHORT).show()
        trebleClefMapping[labels[result]]?.let { Log.d("note mapping: ", it) }
    }

    /**
     * TFLite Object Detection Function
     */
    private fun runObjectDetection(origbitmap: Bitmap, resizedBitmap: Bitmap) {
        //TODO: Add object detection code here
//        val image = TensorImage.fromBitmap(bitmap)
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setMaxResults(10)
            .setScoreThreshold(0.1f)
            .build()
        try {
            val detector = ObjectDetector.createFromFileAndOptions(
                this, // the application context
                "finetuned_for_testing.tflite", // must be same as the filename in assets folder
                options
            )
//            val rotatedBitmap = rotateBitmap(origbitmap, 90f)
            // val image = TensorImage.fromBitmap(rotatedBitmap)
//            val greyScaleBitmap = preprocessToGrayscale(origbitmap)
//            val directory = File(applicationContext.filesDir, "saved_images")
//            val savedFile = saveImageToFile(greyScaleBitmap, directory, "grayscale_image")
//            if (savedFile != null) {
//                Toast.makeText(this, "Image saved to ${savedFile.absolutePath}", Toast.LENGTH_SHORT).show()
//                Log.d("ImageSave", "Image saved at: ${savedFile.absolutePath}")
//            } else {
//                Toast.makeText(this, "Failed to save image.", Toast.LENGTH_SHORT).show()
//            }
//            val bwBitmap = convertToBlackAndWhite(bitmap, threshold = 80)
            val bwBitmap = convertToVeryDarkBlackAndWhite(resizedBitmap, threshold = 80)

            val bwImage = TensorImage.fromBitmap(bwBitmap)

            val results = detector.detect(bwImage)
            debugPrint(results)

            //val imageView: ImageView = findViewById(R.id.inputImageView)
            imageViewEdited.setImageBitmap(bwBitmap)
            imageViewOriginal.setImageBitmap(origbitmap)
            Toast.makeText(this@MainActivity, "Drawing Bounding Boxes...", Toast.LENGTH_SHORT).show()

            drawBoundingBoxes(bwBitmap, results, imageViewEdited)

//            val (circleRect, lineRect) = getCircleAndLineRects(results)
//
//            if (circleRect != null && lineRect != null) {
//                println("Circle detected at: $circleRect")
//                println("Line detected at: $lineRect")
//                Toast.makeText(this@MainActivity, "circle and line detected", Toast.LENGTH_SHORT).show()
//
//            } else {
//                println("Circle or line not detected.")
//                Toast.makeText(this@MainActivity, "circle and line NOT detected", Toast.LENGTH_SHORT).show()
//            }

//            val (circleRect, lineRects) = getCircleAndFiveLinesRects(mockDetections)
//
//            if (circleRect != null && lineRects != null) {
//                println("Circle detected at: $circleRect")
//                println("Lines detected at:")
//                lineRects.forEach { println(it) }
//            } else {
//                println("Missing circle or fewer than 5 lines detected.")
//            }

            val (circleRect, sortedLines, overlappingLineIndex) = getCircleAndOverlappingLine(results)

            if (circleRect != null && sortedLines != null) {
                println("Circle detected at: $circleRect")
                println("Lines detected (sorted by Y-coordinate):")
                sortedLines.forEachIndexed { index, line -> println("Line ${index + 1}: $line") }
                Toast.makeText(this@MainActivity, "circle and line detected: ordering", Toast.LENGTH_SHORT).show()

                if (overlappingLineIndex != null) {
                    println("Circle overlaps with line $overlappingLineIndex")
                    Toast.makeText(this@MainActivity, "circle overlaps with line $overlappingLineIndex", Toast.LENGTH_SHORT).show()
                    val note = getNoteFromLineOverlap(overlappingLineIndex);
                    Toast.makeText(this@MainActivity, "Note is $note", Toast.LENGTH_SHORT).show()
                } else {
                    println("Circle does not overlap with any line.")
                    Toast.makeText(this@MainActivity, "circle DOES NOT overlap with line", Toast.LENGTH_SHORT).show()
                    // todo add space code
                    val spacePos = getCircleSpacePosition(circleRect, sortedLines)
                    Toast.makeText(this@MainActivity, "circle in space $spacePos", Toast.LENGTH_SHORT).show()
                    val noteSpace = getNoteFromSpaceOverlap(spacePos)
                    Toast.makeText(this@MainActivity, "note is $noteSpace", Toast.LENGTH_SHORT).show()

                }
            } else {
                println("Missing circle or fewer than 5 lines detected.")
                Toast.makeText(this@MainActivity, "MISSING circle or fewer than 5 lines detected", Toast.LENGTH_SHORT).show()

            }

            Toast.makeText(this@MainActivity, "Ready to view...", Toast.LENGTH_SHORT).show()


        } catch (e: Exception) {
            val testval = null
        }

    }

    private fun debugPrint(results: List<Detection>) {
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
                    this, cameraSelector, preview, imageCapture
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))

    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }


    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }


    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
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

        val scaleX = imageBitmap.width.toFloat() / 320
        val scaleY = imageBitmap.height.toFloat() / 320
        // Create a mutable copy of the bitmap
        val mutableBitmap = imageBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        // Paint for bounding boxes
        val boxPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        // Paint for labels
        val textPaint = Paint().apply {
            color = Color.BLUE
            textSize = 10f
            isAntiAlias = true
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        // Iterate through detected objects
        for ((i, obj) in results.withIndex()) {
            val box = obj.boundingBox // Assuming boundingBox is RectF

            val scaledBox = RectF(
                box.left * scaleX,
                box.top * scaleY,
                box.right * scaleX,
                box.bottom * scaleY
            )

            val filteredCategories = obj.categories.filter { it.score >= 0.3 }

            if (filteredCategories.isNotEmpty()) {
                canvas.drawRect(scaledBox, boxPaint) // Draw bounding box

                // Draw labels
                for ((j, category) in filteredCategories.withIndex()) {
                    val labelText = "${category.label} (${(category.score * 100).toInt()}%)"
                    canvas.drawText(labelText, scaledBox.left, scaledBox.top - 10 - (j * 40), textPaint)
                }
            }
        }

        // Set the modified bitmap to the ImageView
        imageView.setImageBitmap(mutableBitmap)
    }

    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun preprocessToGrayscale(bitmap: Bitmap): Bitmap {
        // Create a grayscale bitmap
        val grayscaleBitmap =
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscaleBitmap)
        val paint = Paint()

        // Apply a color matrix to convert the image to grayscale
        val colorMatrix = android.graphics.ColorMatrix()
        colorMatrix.setSaturation(0f) // Set saturation to 0 for grayscale
        val colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
        paint.colorFilter = colorFilter

        // Draw the original bitmap onto the grayscale canvas
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        return grayscaleBitmap
    }

    fun convertToBlackAndWhite(bitmap: Bitmap, threshold: Int = 128): Bitmap {
        // Create a new bitmap with the same dimensions
        val bwBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)

        // Iterate through each pixel
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                // Get the pixel color
                val pixel = bitmap.getPixel(x, y)

                // Extract the grayscale intensity (average of RGB channels)
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                val intensity = (red + green + blue) / 3

                // Apply threshold: black if below, white if above
                if (intensity < threshold) {
                    bwBitmap.setPixel(x, y, 0xFF000000.toInt()) // Black
                } else {
                    bwBitmap.setPixel(x, y, 0xFFFFFFFF.toInt()) // White
                }
            }
        }

        return bwBitmap
    }

    fun convertToVeryDarkBlackAndWhite(bitmap: Bitmap, threshold: Int = 50): Bitmap {
        // Create a mutable bitmap with the same dimensions
        val width = bitmap.width
        val height = bitmap.height
        val bwBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Get all pixels in an array
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Process the pixels in bulk
        for (i in pixels.indices) {
            val pixel = pixels[i]

            // Extract RGB values
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF

            // Compute intensity
            val intensity = (red + green + blue) / 3

            // Apply threshold
            pixels[i] = if (intensity < threshold) {
                0xFF000000.toInt() // Black
            } else {
                0xFFFFFFFF.toInt() // White
            }
        }

        // Set the modified pixels back to the bitmap
        bwBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bwBitmap
    }



    // Check if a circle overlaps with any lines
    fun checkCircleOverlapWithLines(
        circleBox: RectF,
        lineBoxes: List<RectF>
    ): String? {
        // Step 1: Sort the lines by their Y position
        val sortedLines = lineBoxes.sortedBy { it.top }

        // Step 2: Check overlap and determine which line overlaps
        for ((index, lineBox) in sortedLines.withIndex()) {
            if (RectF.intersects(circleBox, lineBox)) {
                // Return the overlapping line's position
                return when (index) {
                    0 -> "Top Line"
                    1 -> "Middle Line"
                    2 -> "Bottom Line"
                    else -> "Line $index"
                }
            }
        }

        // No overlap found
        return null
    }


    fun getCircleAndLineRects(detections: List<Detection>): Pair<RectF?, RectF?> {
        var circleRect: RectF? = null
        var lineRect: RectF? = null

        // Iterate through the detections
        for (detection in detections) {
            // Get the bounding box
            val box = detection.boundingBox

            // Get the label with the highest confidence
            val label = detection.categories.maxByOrNull { it.score }?.label

            when (label) {
                "circle" -> if (circleRect == null) circleRect = box
                "line" -> if (lineRect == null) lineRect = box
            }

            // Early exit if both are found
            if (circleRect != null && lineRect != null) break
        }

        // Return the detected circle and line RectF objects
        return Pair(circleRect, lineRect)
    }

    fun getCircleAndFiveLinesRects(detections: List<Detection>): Pair<RectF?, List<RectF>?> {
        var circleRect: RectF? = null
        val lineRects = mutableListOf<RectF>()

        // Iterate through the detections
        for (detection in detections) {
            // Get the bounding box
            val box = detection.boundingBox

            // Get the label with the highest confidence
            val label = detection.categories.maxByOrNull { it.score }?.label

            when (label) {
                "circle" -> if (circleRect == null) circleRect = box
                "line" -> if (lineRects.size < 5) lineRects.add(box)
            }

            // Early exit if both the circle and 5 lines are found
            if (circleRect != null && lineRects.size == 5) break
        }

        // Check if we have exactly 5 lines
        return if (lineRects.size == 5) {
            Pair(circleRect, lineRects)
        } else {
            Pair(circleRect, null) // Return null for lines if fewer than 5 are detected
        }
    }

    fun getCircleAndOverlappingLine(
        detections: List<Detection>
    ): Triple<RectF?, List<RectF>?, Int?> {
        var circleRect: RectF? = null
        val lineRects = mutableListOf<RectF>()

        // Iterate through the detections
        for (detection in detections) {
            // Get the bounding box
            val box = detection.boundingBox

            // Get the label with the highest confidence
            val label = detection.categories.maxByOrNull { it.score }?.label

            when (label) {
                "circle" -> if (circleRect == null) circleRect = box
                "line" -> if (lineRects.size < 5) lineRects.add(box)
            }

            // Early exit if both the circle and 5 lines are found
            if (circleRect != null && lineRects.size == 5) break
        }

        // Ensure we have exactly 5 lines
        if (lineRects.size != 5) return Triple(circleRect, null, null)

        // Step 1: Sort the lines by their top (y-coordinate)
        val sortedLines = lineRects.sortedBy { it.top }

        // Step 2: Check which line the circle overlaps
        val circle: RectF = circleRect!!;
        val overlappingLineIndex = sortedLines.indexOfFirst { line ->
            RectF.intersects(circle, line)
        }.takeIf { it != -1 }?.plus(1) // Convert 0-based index to 1-based

        // Return the circle, sorted lines, and overlapping line index
        return Triple(circleRect, sortedLines, overlappingLineIndex)
    }

    fun getNoteFromLineOverlap(overlappingLineIndex: Int?): String? {
        // Map line index to musical notes
        val lineToNoteMap = mapOf(
            1 to "F",
            2 to "D",
            3 to "B",
            4 to "G",
            5 to "E"
        )

        // Return the note for the given line index, or null if no overlap
        return overlappingLineIndex?.let { lineToNoteMap[it] }
    }

    fun getNoteFromSpaceOverlap(spacePosition: Int?): String? {
        // Map space positions to musical notes
        val spaceToNoteMap = mapOf(
            1 to "E",
            2 to "C",
            3 to "A",
            4 to "F",
            5 to "D"
        )

        // Return the note for the given space position, or null if not valid
        return spacePosition?.let { spaceToNoteMap[it] }
    }


    fun getCirclePosition(circleRect: RectF, sortedLines: List<RectF>): Pair<String, Pair<Int, Int>?> {
        // If the circle is above all lines, it's in Space 1
        if (circleRect.bottom < sortedLines.first().top) {
            return Pair("Space 1", null)
        }

        // If the circle is below all lines, it's in Space 6
        if (circleRect.top > sortedLines.last().bottom) {
            return Pair("Space 6", null)
        }

        // Otherwise, determine which two lines the circle is between
        for (i in 0 until sortedLines.size - 1) {
            val currentLine = sortedLines[i]
            val nextLine = sortedLines[i + 1]

            // Check if the circle's vertical center is between these two lines
            val circleCenterY = (circleRect.top + circleRect.bottom) / 2
            if (circleCenterY > currentLine.bottom && circleCenterY < nextLine.top) {
                return Pair("Between", Pair(i + 1, i + 2)) // Return 1-based indices
            }
        }

        // If no position is found, return null
        return Pair("Unknown", null)
    }

    fun getCircleSpacePosition(circleRect: RectF, sortedLines: List<RectF>): Int? {
        // If the circle is above all lines, it's in Space 1
        if (circleRect.bottom < sortedLines.first().top) {
            return 1
        }

        // If the circle is below all lines, it's in Space 6
        if (circleRect.top > sortedLines.last().bottom) {
            return 6
        }

        // Otherwise, determine which two lines the circle is between
        for (i in 0 until sortedLines.size - 1) {
            val currentLine = sortedLines[i]
            val nextLine = sortedLines[i + 1]

            // Check if the circle's vertical center is between these two lines
            val circleCenterY = (circleRect.top + circleRect.bottom) / 2
            if (circleCenterY > currentLine.bottom && circleCenterY < nextLine.top) {
                return i + 2 // Return the space between lines as 2-based index
            }
        }

        // If no position is found, return null
        return null
    }




    fun saveImageToFile(bitmap: Bitmap, directory: File, filename: String): File? {
        // Ensure the directory exists
        if (!directory.exists()) {
            directory.mkdirs()
        }

        val file = File(directory, "$filename.png") // Change to .jpg for JPG files
        var fileOutputStream: FileOutputStream? = null
        try {
            fileOutputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream) // Use JPEG for JPG files
            fileOutputStream.flush()
            return file
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            fileOutputStream?.close()
        }
        return null
    }

}