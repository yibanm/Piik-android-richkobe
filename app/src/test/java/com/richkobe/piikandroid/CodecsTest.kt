package com.richkobe.piikandroid

import com.richkobe.piikandroid.capture.Codecs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecsTest {
    @Test
    fun legacyVendorEncodersRemainAvailable() {
        for (name in listOf("OMX.qcom.video.encoder.avc", "OMX.Exynos.AVC.Encoder", "c2.qti.avc.encoder", "arc.avc.encoder")) {
            assertTrue(name, Codecs.legacyHardwareEncoder(name))
        }
    }

    @Test
    fun legacySoftwareEncodersAreNotPreferredAsHardware() {
        for (name in listOf("OMX.google.h264.encoder", "OMX.ffmpeg.h264.encoder", "OMX.SEC.avc.sw.encoder", "c2.android.avc.encoder", "c2.google.avc.encoder", "unknown.encoder")) {
            assertFalse(name, Codecs.legacyHardwareEncoder(name))
        }
    }
}
