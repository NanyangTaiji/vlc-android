/*
 * *************************************************************************
 *  StartActivity.java
 * **************************************************************************
 *  Copyright © 2015 VLC authors and VideoLAN
 *  Author: Geoffrey Métais
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.util.AndroidUtil
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.resources.ACTION_PLAY_FROM_SEARCH
import org.videolan.resources.ACTION_SEARCH_GMS
import org.videolan.resources.ACTION_VIEW_ARC
import org.videolan.resources.AndroidDevices
import org.videolan.resources.AppContextProvider
import org.videolan.resources.EXTRA_FIRST_RUN
import org.videolan.resources.EXTRA_PATH
import org.videolan.resources.EXTRA_SEARCH_BUNDLE
import org.videolan.resources.EXTRA_TARGET
import org.videolan.resources.EXTRA_UPGRADE
import org.videolan.resources.MOBILE_MAIN_ACTIVITY
import org.videolan.resources.MOBILE_SEARCH_ACTIVITY
import org.videolan.resources.PREF_FIRST_RUN
import org.videolan.resources.TV_MAIN_ACTIVITY
import org.videolan.resources.TV_ONBOARDING_ACTIVITY
import org.videolan.resources.TV_SEARCH_ACTIVITY
import org.videolan.resources.util.getFromMl
import org.videolan.resources.util.launchForeground
import org.videolan.resources.util.startMedialibrary
import org.videolan.tools.AppScope
import org.videolan.tools.BETA_WELCOME
import org.videolan.tools.KEY_CURRENT_SETTINGS_VERSION
import org.videolan.tools.KEY_FRAGMENT_ID
import org.videolan.tools.KEY_NAVIGATOR_SCREEN_UNSTABLE
import org.videolan.tools.KEY_TV_ONBOARDING_DONE
import org.videolan.tools.PREF_SHOW_VIDEO_SETTINGS_DISCLAIMER
import org.videolan.tools.PREF_TV_UI
import org.videolan.tools.Settings
import org.videolan.tools.awaitAppIsForegroung
import org.videolan.tools.getContextWithLocale
import org.videolan.tools.putSingle
import org.videolan.vlc.gui.BetaWelcomeActivity
import org.videolan.vlc.gui.helpers.hf.StoragePermissionsDelegate.Companion.getStoragePermission
import org.videolan.vlc.gui.onboarding.ONBOARDING_DONE_KEY
import org.videolan.vlc.gui.onboarding.startOnboarding
import org.videolan.vlc.gui.video.VideoPlayerActivity
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.util.FileUtils
import org.videolan.vlc.util.Permissions
import org.videolan.vlc.util.Util
import org.videolan.vlc.util.checkWatchNextId
import videolan.org.commontools.TV_CHANNEL_PATH_APP
import videolan.org.commontools.TV_CHANNEL_PATH_VIDEO
import videolan.org.commontools.TV_CHANNEL_QUERY_VIDEO_ID
import videolan.org.commontools.TV_CHANNEL_SCHEME

private const val SEND_CRASH_RESULT = 0
private const val PROPAGATE_RESULT = 1
private const val TAG = "VLC/StartActivity"
class StartActivity : FragmentActivity() {

    private val idFromShortcut: Int
        get() {
            if (!AndroidUtil.isNougatMR1OrLater) return 0
            val intent = intent
            val action = intent?.action
            if (!action.isNullOrEmpty()) {
                return when (action) {
                    "vlc.shortcut.video" -> R.id.nav_video
                    "vlc.shortcut.audio" -> R.id.nav_audio
                    "vlc.shortcut.browser" -> R.id.nav_directories
                    "vlc.shortcut.resume" -> R.id.ml_menu_last_playlist
                    else -> 0
                }
            }
            return 0
        }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.getContextWithLocale(AppContextProvider.locale))
    }

    override fun getApplicationContext(): Context {
        return super.getApplicationContext().getContextWithLocale(AppContextProvider.locale)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            if (!Settings.showTvUi && BuildConfig.BETA && !Settings.getInstance(this).getBoolean(BETA_WELCOME, false)) {
                val intent = Intent(this, BetaWelcomeActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                startActivityForResult(intent, SEND_CRASH_RESULT)
                Settings.getInstance(this).putSingle(BETA_WELCOME, true)
                return
            }
        } catch (ignored: Exception) {}
        resume()
    }

    private fun resume() {
        // if browse screen is unstable, revert back to the video screen

        val preferences = Settings.getInstance(this)
        if (preferences.getBoolean(KEY_NAVIGATOR_SCREEN_UNSTABLE, false)) {
            Log.w(TAG, "Crash found in the browser!!!")
            if (!BuildConfig.DEBUG) {
                Log.w(TAG, "Reverting to the default screen")
                preferences.edit(true) {
                    remove(KEY_FRAGMENT_ID)
                    remove(KEY_NAVIGATOR_SCREEN_UNSTABLE)
                }
            }
        }

        val intent = intent
        val action = intent?.action

        if ((Intent.ACTION_VIEW == action || ACTION_VIEW_ARC == action)
                && TV_CHANNEL_SCHEME != intent.data?.scheme) {
            startPlaybackFromApp(intent)
            return
        } else if (Intent.ACTION_SEND == action) {
            val cd = intent.clipData
            val item = if (cd != null && cd.itemCount > 0) cd.getItemAt(0) else null
            if (item != null) {
                var uri: Uri? = FileUtils.getUri(item.uri)
                if (uri == null && item.text != null) uri = item.text.toString().toUri()
                if (uri != null) {
                    lifecycleScope.launch {
                        var media = getFromMl { getMedia(uri) }
                        if (media == null)
                            media = MLServiceLocator.getAbstractMediaWrapper(uri)
                        MediaUtils.openMediaNoUi(this@StartActivity, media)
                        finish()
                    }
                    return
                }
            }
        }

        // Setting test mode with stubbed media library if required
        if (intent.hasExtra(MLServiceLocator.EXTRA_TEST_STUBS)
                && intent.getBooleanExtra(MLServiceLocator.EXTRA_TEST_STUBS, false)) {
            MLServiceLocator.setLocatorMode(MLServiceLocator.LocatorMode.TESTS)
            Log.i(TAG, "onCreate: Setting test mode`")
        }

        // Start application
        /* Get the current version from package */
        val settings = Settings.getInstance(this)
        val currentVersionNumber = BuildConfig.VLC_VERSION_CODE
        val savedVersionNumber = settings.getInt(PREF_FIRST_RUN, -1)
        /* Check if it's the first run */
        val firstRun = savedVersionNumber == -1
        Settings.firstRun = firstRun
        if (firstRun && AndroidDevices.hasToForceTV()) {
            settings.putSingle(PREF_TV_UI, true)
        }
        val upgrade = firstRun || savedVersionNumber != currentVersionNumber
        val tv = showTvUi()
        if (upgrade && (tv || !firstRun)) settings.putSingle(PREF_FIRST_RUN, currentVersionNumber)
        val removeOldDevices = savedVersionNumber in 3028201..3028399
        settings.putSingle(PREF_SHOW_VIDEO_SETTINGS_DISCLAIMER, savedVersionNumber < 3060330 && !firstRun)
        // Route search query
        if (Intent.ACTION_SEARCH == action || ACTION_SEARCH_GMS == action) {
            intent.setClassName(applicationContext, if (tv) TV_SEARCH_ACTIVITY else MOBILE_SEARCH_ACTIVITY)
            startActivity(intent)
            finish()
            return
        } else if (MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH == action) {
            val serviceInent = Intent(ACTION_PLAY_FROM_SEARCH, null, this, PlaybackService::class.java)
                    .putExtra(EXTRA_SEARCH_BUNDLE, intent.extras)
            launchForeground(serviceInent)
        } else if (Intent.ACTION_VIEW == action && intent.data != null) { //launch from TV Channel
            val data = intent.data
            val path = data!!.path
            if (path == "/$TV_CHANNEL_PATH_APP")
                startApplication(tv, firstRun, upgrade, 0, removeOldDevices)
            else if (path == "/$TV_CHANNEL_PATH_VIDEO") {
                var id = java.lang.Long.valueOf(data.getQueryParameter(TV_CHANNEL_QUERY_VIDEO_ID)!!)
                val ctx = this@StartActivity
                lifecycleScope.launch(Dispatchers.IO) {
                    id = checkWatchNextId(ctx, id)
                    withContext(Dispatchers.Main) {
                        MediaUtils.openMediaNoUi(ctx, id)
                    }
                }
            }
        } else {
            if (action != null && action.startsWith("vlc.mediashortcut:")) {
                val split = action.split(":")
                val type = split[split.count() - 2]
                val id = split.last()
                lifecycleScope.launch {
                    getFromMl {
                        val album = when(type) {
                         "album" ->   getAlbum(id.toLong())
                         "artist" ->   getArtist(id.toLong())
                         "genre" ->   getGenre(id.toLong())
                         "playlist" ->   getPlaylist(id.toLong(), false, false)
                         else ->   getMedia(id.toLong())
                        }
                        MediaUtils.playTracks(this@StartActivity, album, 0)
                    }
                }
            } else if(action != null && action== "vlc.remoteaccess.share") {
                startActivity(Intent().apply { component = ComponentName(this@StartActivity, "org.videolan.vlc.remoteaccessserver.gui.remoteaccess.RemoteAccessShareActivity") })
            } else {
                val target = idFromShortcut
                val service = PlaybackService.instance
                if (target == R.id.ml_menu_last_playlist) {
                    PlaybackService.loadLastAudio(this, true)
                    startApplication(tv, firstRun, upgrade, R.id.nav_audio, removeOldDevices)
                } else if (service != null && service.isInPiPMode.value == true) {
                    service.isInPiPMode.value = false
                    val startIntent = Intent(this, VideoPlayerActivity::class.java)
                    startIntent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    startActivity(startIntent)
                }
                else
                    startApplication(tv, firstRun, upgrade, target, removeOldDevices)
            }
        }
        FileUtils.copyLua(applicationContext, upgrade)
        FileUtils.copyHrtfs(applicationContext, upgrade)
        if (AndroidDevices.watchDevices) this.enableStorageMonitoring()
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SEND_CRASH_RESULT) {
            resume()
        }
        if (requestCode == PROPAGATE_RESULT) {
            setResult(resultCode, data)
            finish()
        }
    }

    private fun startApplication(tv: Boolean, firstRun: Boolean, upgrade: Boolean, target: Int, removeDevices:Boolean = false) {
        val settings = Settings.getInstance(this@StartActivity)
        val onboarding = !settings.getBoolean(if (tv) KEY_TV_ONBOARDING_DONE else ONBOARDING_DONE_KEY, false)
        // Start Medialibrary from background to workaround Dispatchers.Main causing ANR
        // cf https://github.com/Kotlin/kotlinx.coroutines/issues/878
        if (!onboarding || !firstRun) {
            Thread {
                AppScope.launch {
                    // workaround for a Android 9 bug
                    // https://issuetracker.google.com/issues/113122354
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P && !awaitAppIsForegroung()) {
                        return@launch
                    }
                    this@StartActivity.startMedialibrary(firstRun, upgrade, true, removeDevices)
                    if (onboarding) settings.putSingle(ONBOARDING_DONE_KEY, true)
                }
            }.start()
            val mainIntent = Intent(Intent.ACTION_VIEW)
                    .setClassName(applicationContext, if (tv) TV_MAIN_ACTIVITY else MOBILE_MAIN_ACTIVITY)
                    .putExtra(EXTRA_FIRST_RUN, firstRun)
                    .putExtra(EXTRA_UPGRADE, upgrade)
            if (tv && intent.hasExtra(EXTRA_PATH)) mainIntent.putExtra(EXTRA_PATH, intent.getStringExtra(EXTRA_PATH))
            if (target != 0) mainIntent.putExtra(EXTRA_TARGET, target)
            startActivity(mainIntent)
        } else {
            if (!tv) startOnboarding() else startActivity(Intent(Intent.ACTION_VIEW).apply { setClassName(applicationContext, TV_ONBOARDING_ACTIVITY) })
        }
    }

    private fun startPlaybackFromApp(intent: Intent) = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
        // workaround for a Android 9 bug
        // https://issuetracker.google.com/issues/113122354
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P && !awaitAppIsForegroung()) {
            finish()
            return@launch
        }
        // Remove FLAG_ACTIVITY_FORWARD_RESULT that is incompatible with startActivityForResult
        intent.flags = Intent.FLAG_ACTIVITY_FORWARD_RESULT.inv() and intent.flags

        // If data shared with content scheme then it should be from a provider and might
        // be read without permissions.
        // If not should check for permission, and ask them if we don't have them
        if ((intent.data != null && intent.data!!.scheme == "content" && FileUtils.getUri(intent.data) != null)
            || (Permissions.canReadStorage(applicationContext) || getStoragePermission())) when {
            intent.type?.startsWith("video") == true -> try {
                startActivityForResult(intent.setClass(this@StartActivity, VideoPlayerActivity::class.java).apply { putExtra(VideoPlayerActivity.FROM_EXTERNAL, true) }, PROPAGATE_RESULT, Util.getFullScreenBundle())
                return@launch
            } catch (ex: SecurityException) {
                intent.data?.let { MediaUtils.openMediaNoUi(this@StartActivity, it) }
            }
            intent.data?.authority == getString(R.string.tv_provider_authority) -> MediaUtils.openMediaNoUiFromTvContent(this@StartActivity, intent.data)
            intent.data?.authority == "skip_to" -> PlaybackService.instance?.playIndex(intent.getIntExtra("index", 0))
            else -> withContext(Dispatchers.IO) { FileUtils.getUri(intent.data)}?.let {
                // Some media might not be properly labeled
                // As the last option, it is safer to reset the player before playing any media
                if (PlaybackService.instance?.isPlaying == true)
                    PlaybackService.instance?.playlistManager?.player?.stop()
                MediaUtils.openMediaNoUi(this@StartActivity, it)
            }
        }
        finish()
    }

    private fun showTvUi(): Boolean {
        val settings = Settings.getInstance(this)
        //because the [VersionMigration] is done after the first call to this method, we have to keep the old implementation for people coming from an older version of the app
        if (settings.getInt(KEY_CURRENT_SETTINGS_VERSION, 0) < 5) return AndroidDevices.isAndroidTv || !AndroidDevices.isChromeBook && !AndroidDevices.hasTsp ||
                settings.getBoolean("tv_ui", false)
        return  settings.getBoolean("tv_ui", false)
    }
}
