package com.example.musiccam
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TFLiteClassifier(assetManager: AssetManager, modelPath: String) {
    private var interpreter: Interpreter

    init {
        interpreter = Interpreter(loadModelFile(assetManager, modelPath))
    }

    @Throws(IOException::class)
    private fun loadModelFile(assetManager: AssetManager, modelPath: String): MappedByteBuffer {
        val fileDescriptor = assetManager.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun close() {
        interpreter.close()
    }

    private fun preprocessImage(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 128, 128, true)
        val input = Array(1) { Array(128) { Array(128) { FloatArray(3) } } }

        for (y in 0 until 128) {
            for (x in 0 until 128) {
                val pixel = resizedBitmap.getPixel(x, y)
                input[0][y][x][0] = Color.red(pixel) / 255.0f   // Normalize R
                input[0][y][x][1] = Color.green(pixel) / 255.0f // Normalize G
                input[0][y][x][2] = Color.blue(pixel) / 255.0f  // Normalize B
            }
        }
        return input
    }

    fun classifyNotePosition(bitmap: Bitmap): Int {
        val input = preprocessImage(bitmap)  // Preprocess the bitmap image
        val output = Array(1) { FloatArray(10) }  // Adjust size to the number of classes
        val labels = arrayOf( "Line1", "Line2", "Line3", "Line4", "Line5", "Space1", "Space2", "Space3", "Space4", "Space5")// { 'Line1', 'Line2', 'Line3', 'Line4', 'Line5', 'Space1', 'Space2', 'Space3', 'Space4', 'Space5'}
        interpreter.run(input, output)

        labels.forEachIndexed { index, labelName ->
            Log.d("ClassProbability", "Class $labelName: Probability = ${output[0][index]}")
        }

//        output[0].forEachIndexed { index, probability ->
//            Log.d("ClassProbability", "Class $index: Probability = $probability")
//        }
        // Find the index with the highest probability
        return output[0].indices.maxByOrNull { output[0][it] } ?: -1
    }
}
