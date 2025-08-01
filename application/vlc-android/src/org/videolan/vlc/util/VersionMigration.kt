/*
 * ************************************************************************
 *  VersionMigration.kt
 * *************************************************************************
 * Copyright © 2019 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.vlc.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.Playlist
import org.videolan.resources.AndroidDevices
import org.videolan.resources.AndroidDevices.canUseSystemNightMode
import org.videolan.resources.util.getFromMl
import org.videolan.tools.KEY_APP_THEME
import org.videolan.tools.KEY_CURRENT_EQUALIZER_ID
import org.videolan.tools.KEY_CURRENT_MAJOR_VERSION
import org.videolan.tools.KEY_CURRENT_SETTINGS_VERSION_AFTER_LIBVLC_INSTANTIATION
import org.videolan.tools.KEY_CURRENT_SETTINGS_VERSION
import org.videolan.tools.KEY_PLAYBACK_SPEED_AUDIO_GLOBAL
import org.videolan.tools.KEY_PLAYBACK_SPEED_AUDIO_GLOBAL_VALUE
import org.videolan.tools.KEY_PLAYBACK_SPEED_VIDEO_GLOBAL
import org.videolan.tools.KEY_PLAYBACK_SPEED_VIDEO_GLOBAL_VALUE
import org.videolan.tools.KEY_SUBTITLES_COLOR
import org.videolan.tools.KEY_VIDEO_CONFIRM_RESUME
import org.videolan.tools.PLAYLIST_MODE_AUDIO
import org.videolan.tools.PLAYLIST_MODE_VIDEO
import org.videolan.tools.Preferences
import org.videolan.tools.SCREENSHOT_MODE
import org.videolan.tools.Settings
import org.videolan.tools.VIDEO_HUD_TIMEOUT
import org.videolan.tools.coerceInOrDefault
import org.videolan.tools.putSingle
import org.videolan.tools.toInt
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.DefaultPlaybackAction
import org.videolan.vlc.gui.helpers.DefaultPlaybackActionMediaType
import org.videolan.vlc.gui.onboarding.ONBOARDING_DONE_KEY
import org.videolan.vlc.isVLC4
import org.videolan.vlc.mediadb.models.EqualizerBand
import org.videolan.vlc.mediadb.models.EqualizerEntry
import org.videolan.vlc.mediadb.models.EqualizerWithBands
import org.videolan.vlc.repository.EqualizerRepository
import java.io.File
import java.io.IOException

private const val CURRENT_VERSION = 16
private const val CURRENT_VERSION_LIBVLC = 1

object VersionMigration {

    val currentMajorVersion = if (isVLC4()) 4 else 3

    // Used for migration 13, old parameter constants
    private const val FORCE_PLAY_ALL_VIDEO = "force_play_all_video"
    private const val FORCE_PLAY_ALL_AUDIO = "force_play_all_audio"

    /**
     * Migrate version
     *
     * @param context the context to be used to retrieve the preferences
     * @param restoringPrefs the forced preferences to be used
     * @param forcedVersion the forced version to be used
     */
    suspend fun migrateVersion(context: Context, restoringPrefs: SharedPreferences? = null, forcedVersion:Int? = null) {
        val settings = restoringPrefs ?: Settings.getInstance(context)
        val lastVersion = forcedVersion ?: settings.getInt(KEY_CURRENT_SETTINGS_VERSION, 0)
        val lastMajorVersion = settings.getInt(KEY_CURRENT_MAJOR_VERSION, 3)

        migrateSettings(settings)

        if (lastVersion < 1) {
            migrateToVersion1(settings)
        }
        if (lastVersion < 2) {
            migrateToVersion2(context)
        }
        if (lastVersion < 3) {
            migrateToVersion3(context)
        }
        if (lastVersion < 4) {
            migrateToVersion4(settings)
        }
        if (lastVersion < 5) {
            migrateToVersion5(settings)
        }
        if (lastVersion < 6) {
            migrateToVersion6(settings)
        }
        if (lastVersion < 7) {
            migrateToVersion7(settings)
        }
        if (lastVersion < 8) {
            migrateToVersion8(settings)
        }

        if (lastVersion < 9) {
            migrateToVersion9(settings)
        }

        if (lastVersion < 10) {
            migrateToVersion10(settings)
        }

        if (lastVersion < 11) {
            migrateToVersion11(settings)
        }

        if (lastVersion < 12) {
            migrateToVersion12(settings)
        }

        if (lastVersion < 13) {
            migrateToVersion13(settings)
        }

        if (lastVersion < 14) {
            migrateToVersion14(settings)
        }

        if (lastVersion < 15) {
            migrateToVersion15(settings)
        }

        if (lastVersion < 16) {
            migrateToVersion16(settings)
        }

        //Major version upgrade
        if (lastMajorVersion == 3 && currentMajorVersion == 4) {
            migrateToVlc4(settings)
        }

        settings.putSingle(KEY_CURRENT_SETTINGS_VERSION, CURRENT_VERSION)
        settings.putSingle(KEY_CURRENT_MAJOR_VERSION, currentMajorVersion)
    }


    /**
     * Same migration as before but once the libvlc instance has been setup
     *
     * @param context The context used for the migration
     */
    fun migrateVersionAfterLibVLC(context: Context) {
        val settings = Settings.getInstance(context)
        val lastVersion = settings.getInt(KEY_CURRENT_SETTINGS_VERSION_AFTER_LIBVLC_INSTANTIATION, 0)
        if (lastVersion < 1) {
            migrateToVersionLibvlc1(context, settings)
        }
        settings.putSingle(KEY_CURRENT_SETTINGS_VERSION_AFTER_LIBVLC_INSTANTIATION, CURRENT_VERSION_LIBVLC)
    }

    /**
     * Migrate settings not depending on an app version upgrade
     * It's useful for migration depending on System version upgrade for example
     *
     * @param settings
     */
    private fun migrateSettings(settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Starting migrateSettings")
        //Force not using DayNight when Follow system is available
        if (canUseSystemNightMode() && settings.contains("app_theme") && settings.getString("app_theme", "-1") == "0") {
            settings.edit {
                putString("app_theme",  "-1")
            }
        }
    }

    private fun migrateToVersion1(settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Migrating preferences to Version 1")
        settings.edit {
            //Migrate video Resume confirmation
            val dialogConfirmResume = settings.getBoolean("dialog_confirm_resume", false)
            if (dialogConfirmResume) {
                putString(KEY_VIDEO_CONFIRM_RESUME, "2")
            }
            remove("dialog_confirm_resume")
            //Migrate apptheme
            if (!settings.contains(KEY_APP_THEME)) {
                val daynight = settings.getBoolean("daynight", false)
                val dark = settings.getBoolean("enable_black_theme", false)
                val mode = if (dark) AppCompatDelegate.MODE_NIGHT_YES else if (daynight) AppCompatDelegate.MODE_NIGHT_AUTO else AppCompatDelegate.MODE_NIGHT_NO
                putString(KEY_APP_THEME, mode.toString())
            }
            remove("daynight")
            remove("enable_black_theme")
        }
    }

    fun getCurrentVersion() = CURRENT_VERSION

    /**
     * Deletes all the video thumbnails as we change the way to name them.
     */
    private suspend fun migrateToVersion2(context: Context) {
        Log.i(this::class.java.simpleName, "Migrating version to Version 2: flush all the video thumbnails")
        withContext(Dispatchers.IO) {
            try {
                context.getExternalFilesDir(null)?. let {
                    val cacheDir = it.absolutePath + Medialibrary.MEDIALIB_FOLDER_NAME
                    val files = File(cacheDir).listFiles()
                    files?.forEach { file ->
                        if (file.isFile) FileUtils.deleteFile(file)
                    }
                }
            } catch (e: IOException) {
                Log.e(this::class.java.simpleName, e.message, e)
            }
        }
        val settings = Settings.getInstance(context)
        val onboarding = !settings.getBoolean(ONBOARDING_DONE_KEY, false)
        val tv = AndroidDevices.isAndroidTv || !AndroidDevices.isChromeBook && !AndroidDevices.hasTsp ||
                settings.getBoolean("tv_ui", false)
        if (!tv && !onboarding) context.getFromMl { flushUserProvidedThumbnails() }
    }

    /**
     * Deletes all the programs from the WatchNext channel on the TV Home.
     * After reindexing media ids can change, so programs now also have the uri of their media file.
     */
    private suspend fun migrateToVersion3(context: Context) {
        Log.i(this::class.java.simpleName, "Migrating to Version 3: remove all WatchNext programs")
        withContext(Dispatchers.IO) {
            deleteAllWatchNext(context)
        }
    }

    /**
     * Migrate the video hud timeout preference to a value in seconds
     */
    private fun migrateToVersion4(settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Migrating to Version 4: migrate from video_hud_timeout to video_hud_timeout_in_s")
        val hudTimeOut = settings.getString("video_hud_timeout", "2")?.toInt() ?: 2
        settings.edit {
            when  {
                hudTimeOut < 0 -> putInt(VIDEO_HUD_TIMEOUT, -1)
                hudTimeOut == 2 -> putInt(VIDEO_HUD_TIMEOUT, 4)
                hudTimeOut == 3 -> putInt(VIDEO_HUD_TIMEOUT, 8)
                else -> putInt(VIDEO_HUD_TIMEOUT, 2)
            }
            remove("video_hud_timeout")
        }
    }

    /**
     * Migrate the TV Ui to make sure the preference is setup
     */
    private fun migrateToVersion5(settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Migrating to Version 5: force the TV ui setting if device is TV")
        if (Settings.device.isTv && settings.getBoolean("tv_ui", false) != settings.getBoolean("tv_ui", true)) {
            settings.putSingle("tv_ui", true)
            Settings.tvUI = true
        }
    }

    /**
     * Migrate the Video hud timeout to the new range
     */
    private fun migrateToVersion6(settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Migrating to Version 6: Migrate the Video hud timeout to the new range")
        val hudTimeOut = settings.getInt(VIDEO_HUD_TIMEOUT, 4)
        if (hudTimeOut == 0) settings.edit { putInt(VIDEO_HUD_TIMEOUT, 16) }
        Settings.videoHudDelay = settings.getInt(VIDEO_HUD_TIMEOUT, 4).coerceInOrDefault(1,15,-1)
    }

    /**
     * Migrate the PLAYLIST_REPEAT_MODE_KEY from the PlaylistManager to split it in two
     * audio / video separate preferences, PLAYLIST_VIDEO_REPEAT_MODE_KEY and
     * PLAYLIST_AUDIO_REPEAT_MODE_KEY, but keep the value previously set by the user
     */
    private fun migrateToVersion7(settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Migrating to Version 7: migrate PlaylistManager " +
                "PLAYLIST_REPEASE_MODE_KEY to PLAYLIST_VIDEO_REPEAT_MODE_KEY " + 
                "and PLAYLIST_AUDIO_REPEAT_MODE_KEY")
        val repeat = settings.getInt("audio_repeat_mode", -1)
        if (repeat != -1) {
            settings.putSingle("video_repeat_mode", repeat)
        }
    }

    /**
     * Migrate from having one force_play_all that was labeled as Video Playlist Mode in the settings
     * but also affected some audio in the browser to two separate settings force_play_all,
     * historically will continue forcing to play all videos, and force_play_all_audio which will
     * do the same when playing audio files. Migration to keep the previous value in both settings
     */
    private fun migrateToVersion8(settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Migration to Version 8: split force_play_all " +
                "and add force_play_all_audio to separately handle video and audio")
        if (settings.contains("force_play_all"))
            settings.edit {
                val oldSetting = settings.getBoolean("force_play_all", false)
                putBoolean(PLAYLIST_MODE_VIDEO, oldSetting)
                putBoolean(PLAYLIST_MODE_AUDIO, oldSetting)
                remove("force_play_all")
            }
    }

    /**
     * Migrate the video screenshot control setting from a boolean
     * to a multiple entry value
     */
    private fun migrateToVersion9(settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Migration to Version 9: migrate the screenshot setting to a multiple entry value")
        if (settings.contains("enable_screenshot_gesture"))
            settings.edit {
                val oldSetting = settings.getBoolean("enable_screenshot_gesture", false)
                if (oldSetting) putString(SCREENSHOT_MODE, "2")
                remove("enable_screenshot_gesture")
            }
    }

    /**
     * Migrate the subtitle color setting
     */
    private fun migrateToVersion10(settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Migration to Version 10: Migrate the subtitle color setting")
        if (settings.contains("subtitles_color"))
            settings.edit(true) {
                settings.getString("subtitles_color", "16777215")?.let {oldSetting ->
                    try {
                        val oldColor = oldSetting.toInt()
                        val newColor = Color.argb(255, Color.red(oldColor), Color.green(oldColor), Color.blue(oldColor))
                        putInt(KEY_SUBTITLES_COLOR, newColor)
                    } catch (e: Exception) {
                        remove("subtitles_color")
                    }
                }
            }
    }

    /**
     * Migrate the  playlists' display in grid setting
     */
    private fun migrateToVersion11(settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Migration to Version 11: Migrate the  playlists' display in grid setting")
        if (settings.contains("display_mode_playlists"))
            settings.edit(true) {
                settings.getBoolean("display_mode_playlists", true).let {oldSetting ->
                    try {
                        val oldColor = oldSetting.toInt()
                        val newColor = Color.argb(255, Color.red(oldColor), Color.green(oldColor), Color.blue(oldColor))
                        putInt("subtitles_color", newColor)
                    } catch (e: Exception) {
                    }
                    putBoolean("display_mode_playlists_${Playlist.Type.Audio}", oldSetting)
                    putBoolean("display_mode_playlists_${Playlist.Type.Video}", oldSetting)
                    remove("display_mode_playlists")
                }
            }
    }

    /**
     * Migrate the show all files pref to show only multimedia files
     */
    private fun migrateToVersion12(settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Migration to Version 12: Migrate the show all files pref to show only multimedia files")
        if (settings.contains("browser_show_all_files"))
            settings.edit(true) {
                putBoolean("browser_show_only_multimedia", !settings.getBoolean("browser_show_all_files", true))
                remove("browser_show_all_files")
            }
    }

    /**
     * Migrate after refactor to move all FORCE_PLAY_ALL_VIDEO/AUDIO to PLAYLIST_MODE_VIDEO/AUDIO
     * This is to have the constant name and setting name more similar
     */
    private fun migrateToVersion13(settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Migration to Version 13: refactor to move all FORCE_PLAY_ALL_VIDEO/AUDIO to PLAYLIST_MODE_VIDEO/AUDIO")
        if (settings.contains(FORCE_PLAY_ALL_VIDEO)) {
            settings.edit(true) {
                putBoolean(PLAYLIST_MODE_VIDEO, settings.getBoolean(FORCE_PLAY_ALL_VIDEO, false))
                remove(FORCE_PLAY_ALL_VIDEO)
            }
        }
        if (settings.contains(FORCE_PLAY_ALL_AUDIO)) {
            settings.edit(true) {
                putBoolean(PLAYLIST_MODE_AUDIO, settings.getBoolean(FORCE_PLAY_ALL_AUDIO, false))
                remove(FORCE_PLAY_ALL_AUDIO)
            }
        }
    }

    /**
     * Migrate after refactor of the playback speed dialog to move all playback_speed/playback_speed_video to KEY_PLAYBACK_SPEED_AUDIO_GLOBAL/KEY_PLAYBACK_SPEED_VIDEO_GLOBAL
     *
     */
    private fun migrateToVersion14(settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Migration to Version 14: refactor to move all playback_speed/playback_speed_video to KEY_PLAYBACK_SPEED_AUDIO_GLOBAL/KEY_PLAYBACK_SPEED_VIDEO_GLOBAL")
        if (settings.contains("playback_speed")) {
            settings.edit(true) {
                putBoolean(KEY_PLAYBACK_SPEED_AUDIO_GLOBAL, settings.getBoolean("playback_speed", false))
                putFloat(KEY_PLAYBACK_SPEED_AUDIO_GLOBAL_VALUE, settings.getFloat("playback_rate", 1F))
                remove("playback_speed")
            }
        }
        if (settings.contains("playback_speed_video")) {
            settings.edit(true) {
                putBoolean(KEY_PLAYBACK_SPEED_VIDEO_GLOBAL, settings.getBoolean("playback_speed_video", false))
                putFloat(KEY_PLAYBACK_SPEED_VIDEO_GLOBAL_VALUE, settings.getFloat("playback_rate_video", 1F))
                remove("playback_speed_video")
            }
        }
    }

    /**
     * Migrate after implementation of the default playback actions
     *
     */
    private fun migrateToVersion15(settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Migrate after implementation of the default playback actions")
        if (settings.contains("playlist_mode_video") && settings.getBoolean("playlist_mode_video", false)) {
            settings.edit(true) {
                putString(DefaultPlaybackActionMediaType.VIDEO.defaultActionKey, DefaultPlaybackAction.PLAY_ALL.name)
            }

        }
        if (settings.contains("playlist_mode_audio") && settings.getBoolean("playlist_mode_audio", false)) {
            DefaultPlaybackActionMediaType.entries.filter { it.allowPlayAll }.forEach {
                settings.edit(true) {
                    putString(DefaultPlaybackActionMediaType.TRACK.defaultActionKey, DefaultPlaybackAction.PLAY_ALL.name)
                }
            }
        }
    }

    /**
     * Migrate the fast play speed setting
     *
     */
    private fun migrateToVersion16(settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Migrate to version 16: Migrate the fast play speed setting")
        if (settings.contains("fastplay_speed")) {
            settings.edit(true) {
                putInt("fastplay_speed", settings.getString("fastplay_speed", "2")
                    ?.toFloat()
                    ?.times(10)
                    ?.toInt()
                    ?.coerceInOrDefault(11, 80, 20)
                    ?: 20)
            }
        }
    }

    /**
     * Migrate the equalizer to room
     */
    private fun migrateToVersionLibvlc1(context: Context, settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Libvlc migration to Version 1: Migrate the equalizer entries to Room DB")
        val equalizerRepository = EqualizerRepository.getInstance(context)
        val count = MediaPlayer.Equalizer.getPresetCount()
        val bandCount = MediaPlayer.Equalizer.getBandCount()

        // First, add all VLC default presets
        for (i in 0 until count) {
            val equalizer = MediaPlayer.Equalizer.createFromPreset(i)
            val bands = buildList {
                for (j in 0 until bandCount) {
                    add(EqualizerBand(j,equalizer.getAmp(j)))
                }
            }
            val eqEntity = EqualizerWithBands(EqualizerEntry(MediaPlayer.Equalizer.getPresetName(i), equalizer.preAmp, i), bands)
            equalizerRepository.addOrUpdateEqualizerWithBands(context, eqEntity)
        }

        // Then, add all custom presets
        for ((key) in settings.all) {
            if (key.startsWith("custom_equalizer_")) {
                val bands = Preferences.getFloatArray(settings, key)
                var isCurrent = settings.getString("equalizer_values", "") == settings.getString(key, "")
                if (bands!!.size == bandCount + 1) {
                    val name = key.replace("custom_equalizer_", "").replace("_", " ")
                    val bandList = buildList {
                        for (j in 0 until bandCount) {
                            add(EqualizerBand(j,bands[j+1]))
                        }
                    }
                    val eqEntity = EqualizerWithBands(EqualizerEntry(name, bands[0]), bandList)
                    val id = equalizerRepository.addOrUpdateEqualizerWithBands(context, eqEntity)
                    if (isCurrent) settings.edit {
                        putLong(KEY_CURRENT_EQUALIZER_ID, id)
                        remove("equalizer_values")
                        remove("equalizer_set")
                    }
                }
                settings.edit { remove(key) }
            }
        }

        //check if previous unsaved equalizer is still set
        if (settings.contains("equalizer_values") && settings.contains("equalizer_set")) {
            val bands = Preferences.getFloatArray(settings, "equalizer_values")
            if (bands!!.size == bandCount + 1) {
                val oldName = settings.getString("equalizer_set", "")?.replace("custom_equalizer_", "")?.replace("_", " ") ?: context.getString(R.string.new_equalizer_copy_template)
                var name = oldName
                val fromScratch = settings.getString("equalizer_set", "")?.trim()?.isEmpty() != false
                val bandList = buildList {
                    for (j in 0 until bandCount) {
                        add(EqualizerBand(j, bands[j + 1]))
                    }
                }

                var i = 0
                while (!equalizerRepository.isNameAllowed(name)) {
                    ++i
                    name = if (fromScratch)
                        context.getString(R.string.new_equalizer_copy_template, " $i")
                    else
                        oldName + " " + context.getString(R.string.equalizer_copy_template, " $i")
                }

                val eqEntity = EqualizerWithBands(EqualizerEntry(name, bands[0]), bandList)
                val id = equalizerRepository.addOrUpdateEqualizerWithBands(context, eqEntity)
                settings.edit {
                    putLong(KEY_CURRENT_EQUALIZER_ID, id)
                }
            }
        }

        //finally, remove all the old shared preferences
        settings.edit {
            remove("equalizer_values")
            remove("equalizer_set")
            remove("equalizer_saved")
        }
    }

    /**
     * Migration to vlc 4
     * ⚠️⚠️⚠️ This should not be destructive! Any first install will run this.
     */
    private fun migrateToVlc4(settings: SharedPreferences) {
        Log.i(this::class.java.simpleName, "Migration to VLC 4")

        // Removing the aout preference to choose aaudio by default
        if (settings.contains("aout")) settings.edit(true) {
            remove("aout")
        }
    }
}