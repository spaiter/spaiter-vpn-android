package io.nekohasekai.sagernet.fmt.v2ray

import org.junit.Assert.assertEquals
import org.junit.Test

class V2RayFmtTest {

    @Test
    fun vlessFingerprintVariantsImportAsUIValue() {
        for (fingerprint in listOf("chrome_72", "Chrome_72", "HelloChrome_72")) {
            val bean = parseV2Ray(
                "vless://00000000-0000-0000-0000-000000000000@example.com:443" +
                        "?type=tcp&encryption=none&fp=$fingerprint",
            )

            assertEquals("chrome_72", bean.utlsFingerprint)
        }
    }
}
