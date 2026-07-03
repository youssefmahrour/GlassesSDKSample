package com.sdk.glassessdksample.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.max
import kotlin.math.min

data class Detection(
    val label: String,
    val confidence: Float,
    val box: RectF,
    val zone: Zone,
    val distanceHint: String,
    val priority: Int
)

enum class Zone {
    LEFT, CENTER, RIGHT
}

class YOLODetector(private val context: Context) {
    companion object {
        private const val TAG = "YOLODetector"
        private const val CONFIDENCE_THRESHOLD = 0.45f
        private const val IOU_THRESHOLD = 0.5f
        private const val MODEL_FILE = "yolov8n_float32.tflite"
    }

    private var interpreter: Interpreter? = null
    private var inputSize = 320
    private val labels = mutableListOf<String>()

    private val trackedClasses = setOf(
        "person", "car", "truck", "bus", "motorcycle",
        "bicycle", "stairs", "dog", "cat", "chair",
        "couch", "bed", "dining table", "door",
        "toilet", "sink", "refrigerator", "tv", "laptop"
    )

    init {
        loadLabels()
        loadModel()
    }

    private fun loadLabels() {
        try {
            context.assets.open("coco_labels.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.forEachLine { labels.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load labels", e)
            // Fallback labels
            labels.addAll(listOf(
                "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
                "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
                "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra"
            ))
        }
    }

    private fun loadModel() {
        try {
            val modelFile = FileUtil.loadMappedFile(context, MODEL_FILE)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                try { setUseNNAPI(true) } catch (e: Exception) { /* NNAPI not available */ }
            }

            interpreter = Interpreter(modelFile, options)
            val inputTensor = interpreter?.getInputTensor(0)
            inputSize = inputTensor?.shape()?.get(1) ?: 320

            Log.d(TAG, "Model loaded, input size: $inputSize")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}", e)
        }
    }

    fun detect(frame: Bitmap): List<Detection> {
        val interpreter = interpreter ?: return emptyList()

        try {
            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
                .build()

            val tensorImage = TensorImage.fromBitmap(frame)
            val processedImage = imageProcessor.process(tensorImage)

            val outputShape = interpreter.getOutputTensor(0).shape()
            val outputBuffer = TensorBuffer.createFixedSize(
                outputShape,
                DataType.FLOAT32
            )

            interpreter.run(processedImage.tensorBuffer.buffer.rewind(), outputBuffer.buffer.rewind())

            val detections = parseOutput(outputBuffer.floatArray, frame.width, frame.height)
            val filtered = detections.filter { 
                it.confidence >= CONFIDENCE_THRESHOLD && it.label in trackedClasses 
            }
            val result = applyNMS(filtered, IOU_THRESHOLD)

            if (result.isNotEmpty()) {
                Log.d(TAG, "Detected ${result.size} objects")
            }

            return result

        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}", e)
            return emptyList()
        }
    }

    private fun parseOutput(output: FloatArray, frameWidth: Int, frameHeight: Int): List<Detection> {
        val detections = mutableListOf<Detection>()
        val numBoxes = output.size / 6

        for (i in 0 until numBoxes) {
            val offset = i * 6
            if (offset + 5 >= output.size) break

            val x1 = output[offset]
            val y1 = output[offset + 1]
            val x2 = output[offset + 2]
            val y2 = output[offset + 3]
            val confidence = output[offset + 4]
            val classId = output[offset + 5].toInt()

            if (classId >= 0 && classId < labels.size && confidence > 0) {
                val label = labels[classId]
                val box = RectF(x1, y1, x2, y2)
                val zone = computeZone(box)
                val distanceHint = estimateDistance(box)

                detections.add(
                    Detection(
                        label = label,
                        confidence = confidence,
                        box = box,
                        zone = zone,
                        distanceHint = distanceHint,
                        priority = 5
                    )
                )
            }
        }

        return detections
    }

    private fun computeZone(box: RectF): Zone {
        val cx = (box.left + box.right) / 2
        val third = 1.0f / 3.0f

        return when {
            cx < third -> Zone.LEFT
            cx < 2 * third -> Zone.CENTER
            else -> Zone.RIGHT
        }
    }

    private fun estimateDistance(box: RectF): String {
        val ratio = (box.right - box.left) * (box.bottom - box.top)
        return when {
            ratio > 0.25 -> "very close"
            ratio > 0.10 -> "close"
            ratio > 0.04 -> "medium"
            else -> "far"
        }
    }

    private fun applyNMS(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }
        val result = mutableListOf<Detection>()

        for (detection in sorted) {
            var shouldAdd = true
            for (selected in result) {
                if (calculateIOU(detection.box, selected.box) > iouThreshold) {
                    shouldAdd = false
                    break
                }
            }
            if (shouldAdd) {
                result.add(detection)
            }
        }

        return result
    }

    private fun calculateIOU(box1: RectF, box2: RectF): Float {
        val x1 = max(box1.left, box2.left)
        val y1 = max(box1.top, box2.top)
        val x2 = min(box1.right, box2.right)
        val y2 = min(box1.bottom, box2.bottom)

        if (x2 < x1 || y2 < y1) return 0f

        val intersection = (x2 - x1) * (y2 - y1)
        val area1 = (box1.right - box1.left) * (box1.bottom - box1.top)
        val area2 = (box2.right - box2.left) * (box2.bottom - box2.top)
        val union = area1 + area2 - intersection

        return if (union > 0) intersection / union else 0f
    }
}