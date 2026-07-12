package com.atarayocast.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplaySizePolicyTest {
    @Test
    fun portraitDisplayIsNotPromotedToLandscape4k() {
        assertEquals(
            NegotiatedDisplaySize(1080, 2400, 60),
            DisplaySizePolicy.fromPhysicalDisplay(1080, 2400)
        )
    }

    @Test
    fun landscapeDisplayKeepsItsOrientation() {
        assertEquals(
            NegotiatedDisplaySize(2400, 1080, 60),
            DisplaySizePolicy.fromPhysicalDisplay(2400, 1080)
        )
    }

    @Test
    fun oversizedPortraitDisplayIsScaledProportionally() {
        assertEquals(
            NegotiatedDisplaySize(2024, 3840, 30),
            DisplaySizePolicy.fromPhysicalDisplay(2160, 4096)
        )
    }

    @Test
    fun ultrawideDisplayIsScaledWithoutChangingAspectDirection() {
        assertEquals(
            NegotiatedDisplaySize(3840, 1080, 30),
            DisplaySizePolicy.fromPhysicalDisplay(5120, 1440)
        )
    }

    @Test
    fun oddDimensionsAreRoundedDownToCodecSafeEvenValues() {
        assertEquals(
            NegotiatedDisplaySize(1079 and -2, 1919 and -2, 60),
            DisplaySizePolicy.fromPhysicalDisplay(1079, 1919)
        )
    }

    @Test
    fun manualSizeIsReducedToDecoderEnvelopeWithoutChangingAspectRatio() {
        assertEquals(
            NegotiatedDisplaySize(1920, 1200, 60),
            DisplaySizePolicy.fitWithin(
                requested = NegotiatedDisplaySize(2560, 1600, 60),
                maxWidth = 1920,
                maxHeight = 1200
            )
        )
    }

    @Test
    fun legacyDecoderEnvelopeProtectsOld16By10Hardware() {
        assertEquals(
            NegotiatedDisplaySize(1280, 800, 30),
            DisplaySizePolicy.legacyDecoderSafe(NegotiatedDisplaySize(1920, 1200, 60))
        )
    }
}
