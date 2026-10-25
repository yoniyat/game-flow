package com.example.coverflow

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.media.*
import java.io.File

class Audio(private val ctx: Context) {
    var sound = true
    var musicOn = true
    private var tone: ToneGenerator? = try { ToneGenerator(AudioManager.STREAM_MUSIC, 40) } catch (e: Exception) { null }
    private var mp: MediaPlayer? = null
    private var music: MediaPlayer? = null
    private var gameMp: MediaPlayer? = null
    private var scrollId = 0
    private val sp = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()

    fun dir() = File(ctx.filesDir, "sounds").apply { mkdirs() }
    fun musicFile() = File(dir(), "lobby.snd")
    fun gameFile(pkg: String) = File(dir(), "game_$pkg.snd")

    private fun fade(m: MediaPlayer, from: Float, to: Float, ms: Long, end: () -> Unit = {}) {
        ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            addUpdateListener { try { val v = it.animatedValue as Float; m.setVolume(v, v) } catch (e: Exception) { } }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { end() } })
            start()
        }
    }

    fun loadScroll() {
        val f = File(dir(), "scroll.snd")
        scrollId = if (f.exists()) sp.load(f.path, 1) else 0
    }

    fun tick() {
        if (!sound) return
        if (scrollId != 0) sp.play(scrollId, 1f, 1f, 1, 0, 1f) else tone?.startTone(ToneGenerator.TONE_DTMF_S, 20)
    }

    fun launch() {
        if (!sound) return
        val f = File(dir(), "launch.snd")
        if (f.exists()) {
            try {
                mp?.release()
                mp = MediaPlayer().apply {
                    setDataSource(f.path)
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener { it.release(); if (mp === it) mp = null }
                    prepareAsync()
                }
                return
            } catch (e: Exception) { }
        }
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
    }

    fun startMusic() {
        stopMusic()
        if (!musicOn || !musicFile().exists()) return
        try {
            val v = if (gameMp != null) 0f else .5f
            music = MediaPlayer().apply {
                setDataSource(musicFile().path); isLooping = true; setVolume(v, v)
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (e: Exception) { music = null }
    }

    fun stopMusic() { music?.release(); music = null }

    // Lobby music gets quieter while a game's own music plays
    private fun duck(on: Boolean) {
        music?.let { m -> if (on) fade(m, .5f, 0f, 700) else fade(m, 0f, .5f, 900) }
    }

    fun playGame(pkg: String): Boolean {
        val f = gameFile(pkg)
        if (!f.exists()) return false
        stopGame(false)
        return try {
            gameMp = MediaPlayer().apply {
                setDataSource(f.path); isLooping = true; setVolume(0f, 0f)
                setOnPreparedListener { it.start(); fade(it, 0f, .7f, 800) }
                prepareAsync()
            }
            duck(true)
            true
        } catch (e: Exception) { gameMp = null; false }
    }

    fun stopGame(smooth: Boolean = true) {
        val m = gameMp ?: return
        gameMp = null
        if (smooth) {
            fade(m, .7f, 0f, 400) { try { m.release() } catch (e: Exception) { } }
            duck(false)
        } else {
            try { m.release() } catch (e: Exception) { }
            music?.let { try { it.setVolume(.5f, .5f) } catch (e: Exception) { } }
        }
    }

    fun release() { tone?.release(); mp?.release(); stopGame(false); stopMusic(); sp.release() }
}

