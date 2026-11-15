package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopSmbAddressTest {
    @Test fun parsesHostWithDefaultPort() {
        assertEquals(DesktopSmbAddress("nas.local", 445), DesktopSmbAddress.parse("nas.local"))
    }

    @Test fun parsesExplicitPortAndScheme() {
        assertEquals(DesktopSmbAddress("192.168.1.20", 1445), DesktopSmbAddress.parse("smb://192.168.1.20:1445/music"))
    }

    @Test fun parsesBracketedIpv6() {
        assertEquals(DesktopSmbAddress("fe80::1", 445), DesktopSmbAddress.parse("[fe80::1]"))
        assertEquals(DesktopSmbAddress("2001:db8::1", 1445), DesktopSmbAddress.parse("[2001:db8::1]:1445"))
    }

    @Test fun rejectsInvalidPortsAndUnbracketedIpv6WithPort() {
        assertFailsWith<IllegalArgumentException> { DesktopSmbAddress.parse("nas:0") }
        assertFailsWith<IllegalArgumentException> { DesktopSmbAddress.parse("nas:65536") }
        assertFailsWith<IllegalArgumentException> { DesktopSmbAddress.parse("2001:db8::1:445") }
    }

    @Test fun normalizesPathsAndRejectsTraversal() {
        assertEquals("Albums/Live/track.flac", DesktopSmbPath.display("/Albums\\Live\\track.flac/"))
        assertEquals("Albums\\Live\\track.flac", DesktopSmbPath.normalize("Albums/Live/track.flac"))
        assertFailsWith<IllegalArgumentException> { DesktopSmbPath.normalize("Albums/../../private") }
    }
}
