package com.example.myapplication4

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import java.io.File

object VideoSaver {
    fun saveFramesAsMp4(
        frames: List<Bitmap>,
        width: Int,
        height: Int,
        fps: Int = 30,
        outputFile: File,
        onSaved: (File) -> Unit
    ) {
        Thread {
            try {
                val mimeType = "video/avc"
                val bitRate = 2_000_000

                val format = MediaFormat.createVideoFormat(mimeType, width, height)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                val encoder = MediaCodec.createEncoderByType(mimeType)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()

                val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var trackIndex = -1
                var muxerStarted = false

                val bufferInfo = MediaCodec.BufferInfo()
                val presentationTimeIncrement = 1_000_000L / fps
                var pts: Long = 0

                for (bitmap in frames) {
                    val yuv = bitmapToNV21(bitmap, width, height)
                    val inputBufferIndex = encoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputBufferIndex) ?: continue
                        inputBuffer.clear()
                        inputBuffer.put(yuv)

                        encoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            yuv.size,
                            pts,
                            0
                        )
                        pts += presentationTimeIncrement
                    }

                    var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                    while (outputIndex >= 0) {
                        val encodedData = encoder.getOutputBuffer(outputIndex) ?: continue

                        if (!muxerStarted) {
                            val formatOut = encoder.outputFormat
                            trackIndex = muxer.addTrack(formatOut)
                            muxer.start()
                            muxerStarted = true
                        }

                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        encoder.releaseOutputBuffer(outputIndex, false)
                        outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                    }

                    // שחרור bitmap מהזיכרון (לא חובה אבל נקי)
                    bitmap.recycle()
                }

                // ❌ אל תקרא ל־signalEndOfInputStream כשלא משתמשים ב־Surface
                // encoder.signalEndOfInputStream() ← נמחק

                encoder.stop()
                encoder.release()

                if (muxerStarted) {
                    muxer.stop()
                    muxer.release()
                }

                onSaved(outputFile)
            } catch (e: Exception) {
                Log.e("VideoSaver", "❌ Error saving video: ${e.message}", e)
            }
        }.start()
    }

    private fun bitmapToNV21(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val yuv = ByteArray(width * height * 3 / 2)
        var yIndex = 0
        var uvIndex = width * height
        for (j in 0 until height) {
            for (i in 0 until width) {
                val rgb = argb[j * width + i]
                val r = (rgb shr 16) and 0xff
                val g = (rgb shr 8) and 0xff
                val b = rgb and 0xff

                val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                val u = (-0.169 * r - 0.331 * g + 0.5 * b + 128).toInt()
                val v = (0.5 * r - 0.419 * g - 0.081 * b + 128).toInt()

                yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()

                }
            }
        }
        return yuv
    }
}
