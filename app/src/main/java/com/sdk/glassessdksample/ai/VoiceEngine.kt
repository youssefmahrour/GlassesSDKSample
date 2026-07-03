package com.sdk.glassessdksample.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit

data class SpeechRequest(
    val text: String,
    val priority: Int = 5,
    val timestamp: Long = System.currentTimeMillis()
) : Comparable<SpeechRequest> {
    override fun compareTo(other: SpeechRequest): Int {
        return this.priority.compareTo(other.priority)
    }
}

class VoiceEngine(private val context: Context) {
    companion object {
        private const val TAG = "VoiceEngine"
        private const val COOLDOWN_SECONDS = 4L
    }
    
    private var tts: TextToSpeech? = null
    private val requestQueue = PriorityBlockingQueue<SpeechRequest>()
    private val cooldownMap = ConcurrentHashMap<String, Long>()
    private var isInitialized = false
    private var isSpeaking = false
    private var isRunning = true
    
    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                tts?.language = Locale.US
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                startWorker()
                Log.d(TAG, "TTS initialized")
            } else {
                Log.e(TAG, "TTS initialization failed")
            }
        }
    }
    
    fun speak(text: String, priority: Int = 5, force: Boolean = false) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, cannot speak: $text")
            return
        }
        
        val normalized = text.lowercase().trim()
        
        if (!force) {
            val lastSpoken = cooldownMap[normalized] ?: 0
            if (System.currentTimeMillis() - lastSpoken < COOLDOWN_SECONDS * 1000) {
                Log.d(TAG, "Cooldown active for: $text")
                return
            }
        }
        
        if (requestQueue.size >= 3) {
            requestQueue.poll()
        }
        
        requestQueue.offer(SpeechRequest(text, priority))
        Log.d(TAG, "Added to queue: $text (priority: $priority)")
    }
    
    fun speakUrgent(text: String) {
        speak(text, priority = 1, force = true)
    }
    
    private fun startWorker() {
        Thread {
            while (isRunning) {
                try {
                    val request = requestQueue.poll(100, TimeUnit.MILLISECONDS)
                    request?.let { speakInternal(it) }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Worker error", e)
                }
            }
        }.start()
    }
    
    private fun speakInternal(request: SpeechRequest) {
        if (!isInitialized) return
        
        val normalized = request.text.lowercase().trim()
        isSpeaking = true
        
        try {
            tts?.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, null)
            
            Thread.sleep(100)
            while (tts?.isSpeaking == true) {
                Thread.sleep(50)
            }
            
            cooldownMap[normalized] = System.currentTimeMillis()
            Log.d(TAG, "Spoke: ${request.text}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Speech error", e)
        } finally {
            isSpeaking = false
        }
    }
    
    fun isBusy(): Boolean = isSpeaking || requestQueue.isNotEmpty()
    
    fun stop() {
        isRunning = false
        tts?.stop()
        tts?.shutdown()
        requestQueue.clear()
        Log.d(TAG, "Voice engine stopped")
    }
}
