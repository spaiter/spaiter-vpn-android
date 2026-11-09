package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WireGuardDirectDetourTest {
    private fun node(vararg pairs: Pair<String, Any?>): MutableMap<String, Any?> = mutableMapOf(*pairs)

    @Test
    fun wireGuardNodesWithoutDetourGoThroughDirect() {
        val wg = node("type" to "wireguard", "tag" to "wg")
        val awgOutbound = node("type" to "awg", "tag" to "awg")
        val awgEndpoint = node("type" to "awg", "tag" to "awg-ep")
        val config = mutableMapOf<String, Any?>(
            "outbounds" to listOf(wg, awgOutbound),
            "endpoints" to listOf(awgEndpoint),
        )

        applyWireGuardDirectDetour(config)

        assertEquals(TAG_DIRECT, wg["detour"])
        assertEquals(TAG_DIRECT, awgOutbound["detour"])
        assertEquals(TAG_DIRECT, awgEndpoint["detour"])
    }

    @Test
    fun existingDetourAndOtherTypesAreLeftAlone() {
        val chained = node("type" to "wireguard", "tag" to "wg", "detour" to "front")
        val vless = node("type" to "vless", "tag" to "v")
        val config = mutableMapOf<String, Any?>("outbounds" to listOf(chained, vless))

        applyWireGuardDirectDetour(config)

        assertEquals("front", chained["detour"])
        assertNull(vless["detour"])
    }

    @Test
    fun configWithoutListsIsUntouched() {
        val config = mutableMapOf<String, Any?>("route" to mapOf("final" to "proxy"))
        applyWireGuardDirectDetour(config)
        assertEquals(1, config.size)
    }
}
