package com.example.myapplication4

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

object TFLiteModel {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    fun LoadModel(context: Context, modelName: String = "yolov5s_basketball.tflite") {
        try {
            val fileDescriptor = context.assets.openFd(modelName)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val modelBuffer: MappedByteBuffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                fileDescriptor.startOffset,
                fileDescriptor.declaredLength
            )

            // ✨ שימוש ב-GPU Delegate הקלאסי
            gpuDelegate = GpuDelegate()
            val options = Interpreter.Options().apply {
                addDelegate(gpuDelegate)
                setNumThreads(4)
            }

            interpreter = Interpreter(modelBuffer, options)
            Log.d("TFLiteModel", "✅ Model loaded with GPU delegate")

        } catch (e: Exception) {
            Log.e("TFLiteModel", "❌ Failed to load model with GPU: ${e.message}")
        }
    }

    fun getInterpreter(): Interpreter? = interpreter
}
