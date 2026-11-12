package dev.rusteq.audio

import android.media.AudioDeviceInfo as D
import org.junit.Assert.*
import org.junit.Test

class AutoProfilesTest {
    @Test fun headsetsBeatTheBuiltInSpeaker() {
        assertEquals(OutputKind.SPEAKER, OutputClassifier.classify(listOf(D.TYPE_BUILTIN_SPEAKER)))
        assertEquals(OutputKind.WIRED, OutputClassifier.classify(listOf(D.TYPE_BUILTIN_SPEAKER, D.TYPE_WIRED_HEADPHONES)))
        assertEquals(OutputKind.BLUETOOTH, OutputClassifier.classify(listOf(D.TYPE_BUILTIN_SPEAKER, D.TYPE_WIRED_HEADSET, D.TYPE_BLUETOOTH_A2DP)))
        assertEquals(OutputKind.BLUETOOTH, OutputClassifier.classify(listOf(D.TYPE_BLE_HEADSET)))
        assertEquals(OutputKind.WIRED, OutputClassifier.classify(listOf(D.TYPE_USB_HEADSET)))
        assertEquals(OutputKind.OTHER, OutputClassifier.classify(listOf(D.TYPE_HDMI)))
        assertEquals(OutputKind.OTHER, OutputClassifier.classify(emptyList()))
    }

    @Test fun nightWindowWrapsPastMidnight() {
        for (h in listOf(22, 23, 0, 3, 5)) assertTrue("hour $h", ProfileResolver.isNight(h))
        for (h in listOf(6, 12, 21)) assertFalse("hour $h", ProfileResolver.isNight(h))
    }

    @Test fun trackBeatsNightBeatsDevice() {
        val t = ProfileResolver.trackKey("content://a/1")
        val all = setOf(t, ProfileResolver.NIGHT_KEY, ProfileResolver.deviceKey(OutputKind.BLUETOOTH))
        assertEquals(t, ProfileResolver.resolve(ProfileResolver.candidates("content://a/1", 23, OutputKind.BLUETOOTH), all))
        assertEquals(ProfileResolver.NIGHT_KEY, ProfileResolver.resolve(ProfileResolver.candidates("content://a/2", 23, OutputKind.BLUETOOTH), all))
        assertEquals("device:BLUETOOTH", ProfileResolver.resolve(ProfileResolver.candidates("content://a/2", 12, OutputKind.BLUETOOTH), all))
        assertNull(ProfileResolver.resolve(ProfileResolver.candidates(null, 12, OutputKind.SPEAKER), all))
    }

    @Test fun trackKeysAreStableAndDistinct() {
        assertEquals(ProfileResolver.trackKey("x"), ProfileResolver.trackKey("x"))
        assertNotEquals(ProfileResolver.trackKey("x"), ProfileResolver.trackKey("y"))
    }
}
