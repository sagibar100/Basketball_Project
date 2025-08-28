package com.example.myapplication4

import android.content.Context
import android.graphics.Bitmap
import android.media.*
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

object VideoEncoder {

    fun saveBitmapsAsMp4(
        context: Context,
        bitmaps: List<Bitmap>,
        outputFile: File,
        fps: Int = 30,
        bitrate: Int = 2_000_000
    ) {
        if (bitmaps.isEmpty()) {
            Log.e("VideoEncoder", "No bitmaps to encode")
            return
        }

        val width = bitmaps[0].width
        val height = bitmaps[0].height

        val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC // H.264
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(mimeType)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val inputSurface = encoder.createInputSurface()
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        encoder.start()

        val eglHelper = EglBitmapRenderer(width, height, inputSurface)

        var presentationTimeUs = 0L
        val frameDurationUs = TimeUnit.SECONDS.toMicros(1) / fps

        val bufferInfo = MediaCodec.BufferInfo()
        var trackIndex = -1
        var muxerStarted = false

        for (bitmap in bitmaps) {
            eglHelper.drawBitmap(bitmap)
            eglHelper.setPresentationTime(presentationTimeUs * 1000)
            eglHelper.swapBuffers()
            presentationTimeUs += frameDurationUs

            var outputAvailable = true
            while (outputAvailable) {
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    outIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outIndex) ?: continue
                        if (bufferInfo.size > 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)

                            if (!muxerStarted) {
                                trackIndex = encoder.outputFormat.let {
                                    muxer.addTrack(it)
                                }
                                muxer.start()
                                muxerStarted = true
                            }

                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                    }

                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        outputAvailable = false
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) throw RuntimeException("Format changed twice")
                        trackIndex = encoder.outputFormat.let {
                            muxer.addTrack(it)
                        }
                        muxer.start()
                        muxerStarted = true
                    }
                }
            }
        }

        // סיום
        encoder.signalEndOfInputStream()

        var endOfStream = false
        while (!endOfStream) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outIndex >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(outIndex) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        endOfStream = true
                    }
                    if (bufferInfo.size > 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                }

                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
            }
        }

        encoder.stop()
        encoder.release()
        muxer.stop()
        muxer.release()
        eglHelper.release()

        Log.d("VideoEncoder", "✅ MP4 saved to ${outputFile.absolutePath}")
    }
}
