package com.emberlily.app.fe

import org.junit.Assert.*
import org.junit.Test

class RomProfilesTest {
    @Test fun sameHeaderDoesNotShareIdentityOrImplyCapabilities() {
        val original = RomProfiles.identify(RomProfiles.FE8J_SHA256)!!
        val translated = RomProfiles.identify(RomProfiles.FE8J_VOICE_SHA256)!!
        assertEquals(original.identity.gameCode, translated.identity.gameCode)
        assertNotEquals(original.id, translated.id)
        assertNotEquals(original.identity.sha256, translated.identity.sha256)
        assertNotEquals(original.identity.size, translated.identity.size)
        assertSame(original, RomProfiles.identify(RomProfiles.FE8J_SHA256.uppercase()))
        assertNull(RomProfiles.identify("BE8J"))
        assertNull(RomProfiles.identify("00".repeat(32)))
    }

    @Test fun capabilitiesFollowEvidenceForEachExactBuildInsteadOfInheritingTheHeader() {
        assertEquals(setOf(FeCapability.ITEM_DATA, FeCapability.TEXT_NAMES),
            FeCapability.entries.filter(RomProfiles.fe8j::supports).toSet())
        assertEquals(setOf(FeCapability.CHAPTER_AND_CURSOR, FeCapability.LIVE_UNITS, FeCapability.ITEM_DATA, FeCapability.RESEARCH_EDIT),
            FeCapability.entries.filter(RomProfiles.fe8jVoice::supports).toSet())
        assertFalse(RomProfiles.fe8jVoice.supports(FeCapability.TEXT_NAMES))
        assertFalse(RomProfiles.fe8j.supports(FeCapability.RESEARCH_EDIT))
        assertTrue(RomProfiles.fe8jVoice.supports(FeCapability.RESEARCH_EDIT))
        for (profile in RomProfiles.all) {
            assertFalse(profile.supports(FeCapability.NORMAL_INVENTORY))
            assertTrue(profile.capabilities.values.all { it.state != SupportState.AVAILABLE || !it.evidence.isNullOrBlank() })
        }
    }

    @Test fun availabilityRequiresEvidenceAndAnExplicitEntryForEveryCapability() {
        assertThrows(IllegalArgumentException::class.java) { CapabilitySupport(SupportState.AVAILABLE, "test") }
        assertThrows(IllegalArgumentException::class.java) { RomProfile("incomplete", 1, RomProfiles.fe8j.identity, emptyMap()) }
    }

    @Test fun fe7ProfilesDoNotInheritFe8RuntimeEvidenceOrEachOthersTextEncoding() {
        val original = RomProfiles.fe7j
        val translated = RomProfiles.fe7jChinese
        assertSame(original, RomProfiles.identify(RomProfiles.FE7J_SHA256))
        assertSame(translated, RomProfiles.identify(RomProfiles.FE7J_CHINESE_SHA256))
        assertEquals(original.identity.gameCode, translated.identity.gameCode)
        assertEquals(original.identity.size, translated.identity.size)
        assertNotEquals(original.identity.sha256, translated.identity.sha256)
        assertEquals(FeNameEncoding.SHIFT_JIS, original.nameEncoding)
        assertEquals(FeNameEncoding.UNVERIFIED_CUSTOM, translated.nameEncoding)
        assertNotEquals(original.layout, RomProfiles.fe8j.layout)
        assertEquals(0x0202BD4CL, original.layout.unitAddress(1))
        assertEquals(0x0202CEBCL, original.layout.unitAddress(0x81))
        assertEquals(0x0202DCCCL, original.layout.unitAddress(0x41))
        assertNull(original.layout.unitAddress(0))
        assertNull(original.layout.unitAddress(0x3F))
        assertNull(original.layout.unitAddress(0x80))
        for (profile in listOf(original, translated)) {
            assertTrue(profile.supports(FeCapability.CHAPTER_AND_CURSOR))
            assertTrue(profile.supports(FeCapability.LIVE_UNITS))
            assertTrue(profile.supports(FeCapability.ITEM_DATA))
            assertTrue(profile.supports(FeCapability.RESEARCH_EDIT))
            assertFalse(profile.supports(FeCapability.NORMAL_INVENTORY))
            assertTrue(profile.capabilities.filterValues { it.state == SupportState.AVAILABLE }.values
                .all { it.evidence?.contains("docs/M2_VALIDATION.md") == true })
        }
        assertTrue(original.supports(FeCapability.TEXT_NAMES))
        assertFalse(translated.supports(FeCapability.TEXT_NAMES))
        // The target hack shares AE7J, but remains a separate future adaptation.
        assertNull(RomProfiles.identify("347bceec89755a1873ea6780d20d5c2d477225dad6502fdf35aca9b453186702"))
    }
}
