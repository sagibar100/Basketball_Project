package com.example.myapplication4

import android.graphics.Bitmap
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val INPUT_SIZE = 640  // בגודל המתאים למודל שלך

object YoloProcessor {

    private const val CONFIDENCE_THRESHOLD = 0.7f

    fun runInference(bitmap: Bitmap): List<Rect> {
        val interpreter = TFLiteModel.getInterpreter() ?: return emptyList()

        // שינוי גודל התמונה והמרה ל-ByteBuffer
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val inputBuffer = convertBitmapToByteBuffer(resized)

        // יצירת output buffer בהתאם למודל YOLOv5
        val outputShape = arrayOf(1, 25200, 6) // התאמה בהתאם למודל שלך
        val outputBuffer = Array(outputShape[0]) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }

        // הרצת המודל
        interpreter.run(inputBuffer, outputBuffer)

        // החזרת תוצאות לאחר עיבוד
        return postprocess(outputBuffer[0], INPUT_SIZE, INPUT_SIZE)
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            val r = (pixel shr 16 and 0xFF) / 255.0f
            val g = (pixel shr 8 and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            byteBuffer.putFloat(r)
            byteBuffer.putFloat(g)
            byteBuffer.putFloat(b)
        }
        return byteBuffer
    }

    private fun postprocess(
        outputs: Array<FloatArray>,
        imageWidth: Int,
        imageHeight: Int
    ): List<Rect> {
        val boxes = mutableListOf<Rect>()

        for (row in outputs) {
            val confidence = row[4]
            val classProb = row[5]  // אם יש רק מחלקה אחת
            val score = confidence * classProb

            if (score >= CONFIDENCE_THRESHOLD) {
                val x = row[0]      // מרכז הקופסה (0..1)
                val y = row[1]
                val w = row[2]
                val h = row[3]

                val left = ((x - w / 2f) * imageWidth).toInt().coerceIn(0, imageWidth)
                val top = ((y - h / 2f) * imageHeight).toInt().coerceIn(0, imageHeight)
                val right = ((x + w / 2f) * imageWidth).toInt().coerceIn(0, imageWidth)
                val bottom = ((y + h / 2f) * imageHeight).toInt().coerceIn(0, imageHeight)

                if (right > left && bottom > top) {
                    boxes.add(Rect(left, top, right, bottom))
                }
            }
        }

        return boxes
    }
}
