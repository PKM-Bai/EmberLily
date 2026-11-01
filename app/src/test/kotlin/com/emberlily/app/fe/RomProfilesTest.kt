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
}
