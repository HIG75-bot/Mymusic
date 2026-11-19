package com.example.myplayer

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

object PlayerHolder {
    private var p: ExoPlayer? = null
    fun get(ctx: Context): ExoPlayer = p ?: ExoPlayer.Builder(ctx.applicationContext)
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setHandleAudioBecomingNoisy(true).build().also { p = it }
}

class PlayerService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() { super.onCreate(); session = MediaSession.Builder(this, PlayerHolder.get(this)).build() }
    override fun onGetSession(info: MediaSession.ControllerInfo) = session
    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }
}
