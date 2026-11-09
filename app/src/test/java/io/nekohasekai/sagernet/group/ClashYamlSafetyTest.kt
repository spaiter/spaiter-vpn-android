package io.nekohasekai.sagernet.group

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClashYamlSafetyTest {
    // A global tag must not instantiate a Java class (CVE-2022-1471): the parser
    // rejects the document and the raw parser falls through to "no servers".
    @Test
    fun globalTagsAreRejected() = runBlocking {
        val proxies = RawUpdater.parseRaw(
            """
            proxies:
              - !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL ["http://127.0.0.1:1/"]]]]
            """.trimIndent(),
        )
        assertNull(proxies)
    }

    // The local !<str> tag used by some providers keeps parsing as a string.
    @Test
    fun localStrTagStillParses() = runBlocking {
        val proxies = RawUpdater.parseRaw(
            """
            proxies:
              - name: !<str> ss-node
                type: ss
                server: example.com
                port: 8388
                cipher: aes-256-gcm
                password: !<str> 123456
            """.trimIndent(),
        )
        assertNotNull(proxies)
        assertEquals(1, proxies!!.size)
        assertEquals("ss-node", proxies[0].name)
    }
}
