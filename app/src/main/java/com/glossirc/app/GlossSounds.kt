package com.glossirc.app

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri

/** User-selected sounds are persisted as document URIs with read permissions. */
class GlossSounds(private val context: Context) {
    private val prefs = context.getSharedPreferences("glossirc_sounds", Context.MODE_PRIVATE)
    fun enabled(event: String) = prefs.getBoolean("enabled_$event", true)
    fun setEnabled(event: String, enabled: Boolean) { prefs.edit().putBoolean("enabled_$event", enabled).apply() }
    fun uri(event: String) = prefs.getString("uri_$event", "").orEmpty()
    fun setUri(event: String, value: String) { prefs.edit().putString("uri_$event", value).apply() }
    fun play(event: String) {
        if (!enabled(event)) return
        val chosen = uri(event)
        val sound = if (chosen.isNotBlank()) Uri.parse(chosen) else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (sound == null) return
        runCatching {
            val player = MediaPlayer.create(context, sound) ?: return
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { mp, _, _ -> mp.release(); true }
            player.start()
        }
    }
}
