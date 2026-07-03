package com.sdk.glassessdksample.ai

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*

class AIVisionService : Service() {
    companion object {
        private const val TAG = "AIVisionService"
        private const val SKIP_FRAMES = 2
        private const val ANNOUNCE_INTERVAL = 3500L
    }
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var detector: YOLODetector
    private lateinit var voiceEngine: VoiceEngine
    
    private var frameCounter = 0
    private var lastResult: List<Detection> = emptyList()
    private var lastAnnounceTime = 0L
    private var isAIActive = false
    private var isProcessing = false
    
    inner class LocalBinder : Binder() {
        fun getService(): AIVisionService = this@AIVisionService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        detector = YOLODetector(this)
        voiceEngine = VoiceEngine(this)
        Log.d(TAG, "AI Vision Service created")
    }
    
    fun startAI() {
        if (!isAIActive) {
            isAIActive = true
            voiceEngine.speak("AI vision activated", priority = 3, force = true)
            Log.d(TAG, "AI vision activated")
        }
    }
    
    fun stopAI() {
        if (isAIActive) {
            isAIActive = false
            voiceEngine.speak("AI vision deactivated", priority = 3, force = true)
            Log.d(TAG, "AI vision deactivated")
        }
    }
    
    fun processFrame(frameData: ByteArray, width: Int, height: Int) {
        if (!isAIActive) return
        if (isProcessing) return
        
        isProcessing = true
        
        serviceScope.launch {
            try {
                frameCounter++
                
                if (frameCounter % SKIP_FRAMES == 0 || lastResult.isEmpty()) {
                    val detections = withContext(Dispatchers.Default) {
                        val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size)
                        if (bitmap != null) {
                            val result = detector.detect(bitmap)
                            bitmap.recycle()
                            result
                        } else {
                            emptyList()
                        }
                    }
                    
                    lastResult = detections
                    announceResults(detections)
                } else {
                    announceResults(lastResult)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error", e)
            } finally {
                isProcessing = false
            }
        }
    }
    
    private fun announceResults(detections: List<Detection>) {
        val now = System.currentTimeMillis()
        
        if (now - lastAnnounceTime < ANNOUNCE_INTERVAL) return
        lastAnnounceTime = now
        
        if (detections.isEmpty()) {
            voiceEngine.speak("Area clear", priority = 8)
            return
        }
        
        val topDetections = detections.take(3)
        for (detection in topDetections) {
            val phrase = buildVoicePhrase(detection)
            voiceEngine.speak(phrase, priority = detection.priority)
        }
    }
    
    private fun buildVoicePhrase(detection: Detection): String {
        val position = when (detection.zone) {
            Zone.CENTER -> "ahead"
            Zone.LEFT -> "on your left"
            Zone.RIGHT -> "on your right"
        }
        
        return if (detection.distanceHint in listOf("very close", "close")) {
            "${detection.label} ${detection.distanceHint}, $position"
        } else {
            "${detection.label} $position"
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isAIActive = false
        voiceEngine.stop()
        serviceScope.cancel()
        Log.d(TAG, "AI Vision Service destroyed")
    }
}
