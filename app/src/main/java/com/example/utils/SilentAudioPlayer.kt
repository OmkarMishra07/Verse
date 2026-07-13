package com.example.utils

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

class SilentAudioPlayer {
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var thread: Thread? = null

    fun start() {
        if (isPlaying) return
        isPlaying = true
        
        try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            
            thread = Thread {
                // ShortArray initialized to 0 is perfect silence
                val silence = ShortArray(bufferSize) 
                while (isPlaying) {
                    try {
                        val written = audioTrack?.write(silence, 0, silence.size) ?: 0
                        if (written <= 0) {
                            Thread.sleep(100) // Prevent tight loop if write fails
                        }
                    } catch (e: Exception) {
                        Log.e("SilentAudioPlayer", "Error writing silence: ${e.message}")
                        break
                    }
                }
            }
            thread?.priority = Thread.MIN_PRIORITY // Keep CPU overhead as low as possible
            thread?.start()
            Log.d("SilentAudioPlayer", "Silent audio loop started to prevent CPU throttling.")
        } catch (e: Exception) {
            Log.e("SilentAudioPlayer", "Failed to start silent audio: ${e.message}")
            isPlaying = false
        }
    }

    fun stop() {
        if (!isPlaying) return
        isPlaying = false
        try {
            thread?.join(500)
        } catch (e: Exception) {}
        thread = null
        
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
        Log.d("SilentAudioPlayer", "Silent audio loop stopped.")
    }
}
