package com.richkobe.piikandroid

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import com.richkobe.piikandroid.capture.ProjectionHolder

/** Invisible activity that shows Android's "start recording / casting" dialog. */
class ProjectionActivity : Activity() {
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mpm = service<MediaProjectionManager>()
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST)
        }
    }

    @Deprecated("Platform API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return
        answered = true
        if (resultCode == RESULT_OK && data != null) {
            val host = PiikService.instance
            // Android 14+: the media-projection foreground service must be
            // running before getMediaProjection() is called.
            val projection = try {
                if (host != null && host.promoteForProjection()) {
                    service<MediaProjectionManager>().getMediaProjection(resultCode, data)
                } else null
            } catch (e: Exception) {
                Log.e("PiikProjection", "getMediaProjection failed", e)
                null
            }
            ProjectionHolder.onConsent(projection)
        } else {
            ProjectionHolder.onConsent(null)
        }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!answered && isFinishing) ProjectionHolder.onConsent(null)
        super.onDestroy()
    }

    companion object {
        private const val REQUEST = 7
    }
}
