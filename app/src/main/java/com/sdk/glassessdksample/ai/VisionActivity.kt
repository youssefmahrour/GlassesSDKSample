package com.sdk.glassessdksample.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.oudmon.ble.base.communication.LargeDataHandler
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.ui.AlbumDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VisionActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "VisionActivity"
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var btnToggle: Button
    private lateinit var btnStream: Button
    private lateinit var btnCapture: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvDetection: TextView
    private lateinit var overlay: FrameLayout

    private var isAIActive = false
    private var isStreamActive = false
    private var detector: YOLODetector? = null
    private var detectorAvailable = false
    
    private var streamJob: Job? = null
    private var isProcessing = false

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    private var lastRenderedBitmap: Bitmap? = null
    private var lastDetections: List<Detection> = emptyList()

    private val surfaceHolderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.d(TAG, "Surface created")
            // Re-render the last frame if we have one
            lastRenderedBitmap?.let { drawOnSurface(it, lastDetections) }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.d(TAG, "Surface changed: $width x $height")
            lastRenderedBitmap?.let { drawOnSurface(it, lastDetections) }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.d(TAG, "Surface destroyed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vision)

        initViews()
        initDetector()
        
        updateStatus("Ready. Tap 'Capture' to take a photo from glasses, or 'Start Stream' to simulate live video.")
    }

    private fun initViews() {
        surfaceView = findViewById(R.id.surfaceView)
        btnToggle = findViewById(R.id.btnToggle)
        btnStream = findViewById(R.id.btnStream)
        btnCapture = findViewById(R.id.btnCapture)
        tvStatus = findViewById(R.id.tvStatus)
        tvDetection = findViewById(R.id.tvDetection)
        overlay = findViewById(R.id.overlay)

        surfaceView.holder.addCallback(surfaceHolderCallback)
        enableAiControls(false)

        btnToggle.setOnClickListener {
            toggleAI()
        }

        btnStream.setOnClickListener {
            toggleStream()
        }

        btnCapture.setOnClickListener {
            captureFrameFromGlasses()
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            tvStatus.text = message
            Log.d(TAG, message)
        }
    }

    private fun initDetector() {
        try {
            detector = YOLODetector(this)
            detectorAvailable = detector != null
            if (detectorAvailable) {
                updateStatus("AI detector ready.")
                enableAiControls(true)
            } else {
                throw IllegalStateException("Detector initialization returned null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize detector: ${e.message}", e)
            detectorAvailable = false
            enableAiControls(false)
            updateStatus("AI unavailable: model assets are missing.")
        }
    }

    private fun enableAiControls(enabled: Boolean) {
        btnToggle.isEnabled = enabled
        btnCapture.isEnabled = true
        btnToggle.text = if (enabled) "Start AI" else "AI offline"
        btnCapture.text = "Capture"
    }

    private fun toggleStream() {
        isStreamActive = !isStreamActive
        btnStream.text = if (isStreamActive) "Stop Stream" else "Start Stream"

        if (isStreamActive) {
            updateStatus("Starting simulated live video (chain capture)...")
            streamJob = scope.launch {
                while (isStreamActive) {
                    if (!isProcessing) {
                        isProcessing = true
                        try {
                            // 1. Trigger photo on glasses
                            LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x01)) { _, _ -> }
                            
                            // 2. Give the glasses a moment to save the file
                            delay(1500)
                            
                            // 3. Download the latest photo
                            val bitmap = fetchLatestPhoto()
                            
                            // 4. Process and render
                            if (bitmap != null) {
                                val detections = if (isAIActive) processFrame(bitmap) else emptyList()
                                updateUI(bitmap, detections)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Chain capture error", e)
                        } finally {
                            isProcessing = false
                        }
                    }
                    // Prevent tight loop spin
                    delay(500)
                }
            }
        } else {
            streamJob?.cancel()
            updateStatus("Stream stopped")
        }
    }

    private fun toggleAI() {
        if (!detectorAvailable) {
            updateStatus("AI is unavailable: the detector assets are missing")
            return
        }

        isAIActive = !isAIActive
        btnToggle.text = if (isAIActive) "Stop AI" else "Start AI"
        btnToggle.setBackgroundTintList(ContextCompat.getColorStateList(
            this,
            if (isAIActive) R.color.purple_500 else R.color.teal_700
        ))
        updateStatus(if (isAIActive) "AI Vision Active" else "Ready")

        if (!isAIActive) {
            tvDetection.text = "AI deactivated"
        }
    }

    private fun captureFrameFromGlasses() {
        if (isProcessing) return
        isProcessing = true
        updateStatus("Sending capture command to glasses...")
        btnCapture.isEnabled = false
        
        scope.launch {
            try {
                // 1. Send BLE command to capture photo
                LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x01)) { _, _ -> }
                
                // 2. Wait for the glasses to save the photo
                delay(2000) 
                
                // 3. Download the photo
                val bitmap = fetchLatestPhoto()
                if (bitmap != null) {
                    val detections = if (isAIActive) processFrame(bitmap) else emptyList()
                    updateUI(bitmap, detections)
                    updateStatus("Capture successful")
                } else {
                    updateStatus("Failed to download photo from glasses")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed", e)
                updateStatus("Capture error: ${e.message}")
            } finally {
                isProcessing = false
                withContext(Dispatchers.Main) {
                    btnCapture.isEnabled = true
                }
            }
        }
    }
    
    private suspend fun fetchLatestPhoto(): Bitmap? = withContext(Dispatchers.IO) {
        val ip = getSharedPreferences("app_state", MODE_PRIVATE).getString("device_ip", null)?.trim()
        if (ip.isNullOrBlank()) {
            updateStatus("Glasses IP not available. Ensure Wi-Fi is connected to the glasses.")
            return@withContext null
        }
        
        try {
            val downloader = AlbumDownloader(this@VisionActivity)
            val config = downloader.fetchConfig(ip)
            
            // Find the most recent JPG file (assumes filenames are sortable, like timestamps)
            val latestJpg = config.filter { it.type == 1 }.maxByOrNull { it.fileName }
            
            if (latestJpg == null) {
                updateStatus("No photos found on the glasses")
                return@withContext null
            }
            
            updateStatus("Downloading ${latestJpg.fileName}...")
            val file = downloader.fetchOne(ip, latestJpg.fileName)
            
            if (file != null && file.exists()) {
                val options = BitmapFactory.Options().apply {
                    // Downsample to avoid OOM, adjust as needed
                    inSampleSize = 2 
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch latest photo", e)
            null
        }
    }

    private fun processFrame(bitmap: Bitmap): List<Detection> {
        if (!detectorAvailable) return emptyList()
        return detector?.detect(bitmap) ?: emptyList()
    }

    private fun updateUI(bitmap: Bitmap, detections: List<Detection>) {
        runOnUiThread {
            lastRenderedBitmap = bitmap
            lastDetections = detections
            
            if (detections.isEmpty()) {
                tvDetection.text = "No objects detected"
            } else {
                val top = detections.firstOrNull()
                tvDetection.text = top?.let {
                    "${it.label} (${(it.confidence * 100).toInt()}%) - ${it.zone}"
                } ?: "No objects detected"
            }
            
            drawOnSurface(bitmap, detections)
        }
    }

    private fun drawOnSurface(bitmap: Bitmap, detections: List<Detection>) {
        val holder = surfaceView.holder
        val canvas = try {
            holder.lockCanvas()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            // Draw background
            canvas.drawColor(Color.BLACK)
            
            // Calculate scaling to fit the bitmap inside the surface view maintaining aspect ratio
            val canvasWidth = canvas.width.toFloat()
            val canvasHeight = canvas.height.toFloat()
            val imgWidth = bitmap.width.toFloat()
            val imgHeight = bitmap.height.toFloat()
            
            val scale = Math.min(canvasWidth / imgWidth, canvasHeight / imgHeight)
            val dx = (canvasWidth - imgWidth * scale) / 2f
            val dy = (canvasHeight - imgHeight * scale) / 2f
            
            val destRect = RectF(dx, dy, dx + imgWidth * scale, dy + imgHeight * scale)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            
            // Draw the downloaded photo
            canvas.drawBitmap(bitmap, null, destRect, paint)
            
            // Draw AI bounding boxes
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f
            
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 40f
                style = Paint.Style.FILL
                setShadowLayer(5f, 0f, 0f, Color.BLACK)
            }

            for (det in detections) {
                // Determine if coords are normalized [0,1] or absolute pixel values. 
                // YOLODetector seems to output normalized coords, but let's be safe.
                val isNormalized = det.box.right <= 1.0f && det.box.bottom <= 1.0f
                
                val boxLeft: Float
                val boxTop: Float
                val boxRight: Float
                val boxBottom: Float
                
                if (isNormalized) {
                    boxLeft = destRect.left + (det.box.left * destRect.width())
                    boxTop = destRect.top + (det.box.top * destRect.height())
                    boxRight = destRect.left + (det.box.right * destRect.width())
                    boxBottom = destRect.top + (det.box.bottom * destRect.height())
                } else {
                    // Assuming coordinates are relative to the original image dimensions
                    boxLeft = destRect.left + (det.box.left / imgWidth * destRect.width())
                    boxTop = destRect.top + (det.box.top / imgHeight * destRect.height())
                    boxRight = destRect.left + (det.box.right / imgWidth * destRect.width())
                    boxBottom = destRect.top + (det.box.bottom / imgHeight * destRect.height())
                }
                
                // Assign color based on confidence
                paint.color = when {
                    det.confidence > 0.7f -> Color.GREEN
                    det.confidence > 0.5f -> Color.YELLOW
                    else -> Color.RED
                }
                
                canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, paint)
                
                // Draw label
                val labelText = "${det.label} ${(det.confidence * 100).toInt()}%"
                canvas.drawText(labelText, boxLeft, boxTop - 10f, textPaint)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isStreamActive = false
        streamJob?.cancel()
        job.cancel()
    }
}