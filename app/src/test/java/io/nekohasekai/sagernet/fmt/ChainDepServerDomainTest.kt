package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Bundle chains (ShadowTLS = shadowsocks + shadowtls front) import their dependency
// outbounds raw. The front dials the server by domain, so it needs a resolver.
class ChainDepServerDomainTest {
    @Test
    fun domainFrontWithoutResolverIsReported() {
        val stls = mapOf("type" to "shadowtls", "tag" to "stls", "server" to "vpn.example.net", "server_port" to 443)
        assertEquals("vpn.example.net", chainDepServerDomain(stls))
    }

    @Test
    fun ipFrontNeedsNothing() {
        assertNull(chainDepServerDomain(mapOf("type" to "masque", "server" to "162.159.192.1")))
        assertNull(chainDepServerDomain(mapOf("type" to "masque", "server" to "2606:4700::1")))
    }

    @Test
    fun existingResolverOrNoServerIsLeftAlone() {
        assertNull(chainDepServerDomain(mapOf("server" to "a.example", "domain_resolver" to mapOf("server" to "dns-direct"))))
        assertNull(chainDepServerDomain(mapOf("type" to "masque")))
        assertNull(chainDepServerDomain(mapOf("server" to "")))
    }
}
