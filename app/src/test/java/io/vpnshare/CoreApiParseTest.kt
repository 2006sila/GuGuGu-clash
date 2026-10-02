package io.vpnshare

import io.vpnshare.core.CoreApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * provider 解析单测。JSON 形状取自 mihomo 的 providerForApi
 * （adapter/provider/provider.go）与 SubscriptionInfo（adapter/provider/subscription_info.go，
 * 无 json tag 故序列化为大写 Upload/Download/Total/Expire）。
 */
class CoreApiParseTest {

    private val proxyProvidersJson = """
        {
          "providers": {
            "sub": {
              "name": "sub",
              "type": "Proxy",
              "vehicleType": "File",
              "proxies": [{"name": "n1"}, {"name": "n2"}, {"name": "n3"}],
              "testUrl": "https://www.gstatic.com/generate_204",
              "expectedStatus": "",
              "updatedAt": "2026-10-02T01:46:49.123456789Z",
              "subscriptionInfo": {
                "Upload": 1024,
                "Download": 536870912,
                "Total": 107374182400,
                "Expire": 1798761600
              }
            },
            "empty": {
              "name": "empty",
              "type": "Proxy",
              "vehicleType": "HTTP",
              "proxies": [],
              "updatedAt": "0001-01-01T00:00:00Z"
            }
          }
        }
    """.trimIndent()

    private val ruleProvidersJson = """
        {
          "providers": {
            "bilibili-local": {
              "name": "bilibili-local",
              "type": "Rule",
              "vehicleType": "File",
              "behavior": "domain",
              "ruleCount": 53,
              "updatedAt": "2026-10-02T01:46:49Z"
            },
            "ads-local": {
              "name": "ads-local",
              "type": "Rule",
              "vehicleType": "File",
              "behavior": "domain",
              "ruleCount": 910
            }
          }
        }
    """.trimIndent()

    @Test
    fun parsesProxyProviders() {
        val list = CoreApi.parseProviders("proxies", proxyProvidersJson)
        assertEquals(2, list.size)
        assertEquals(listOf("empty", "sub"), list.map { it.name })   // 按名字排序

        val sub = list.first { it.name == "sub" }
        assertEquals("proxies", sub.kind)
        assertEquals("Proxy", sub.type)
        assertEquals("File", sub.vehicleType)
        assertEquals(3, sub.itemCount)
        assertEquals("2026-10-02T01:46:49.123456789Z", sub.updatedAt)
        assertEquals(1024L, sub.upload)
        assertEquals(536870912L, sub.download)
        assertEquals(107374182400L, sub.total)
        assertEquals(1798761600L, sub.expire)
    }

    @Test
    fun handlesProviderWithoutProxiesOrSubscriptionInfo() {
        val empty = CoreApi.parseProviders("proxies", proxyProvidersJson).first { it.name == "empty" }
        assertEquals(0, empty.itemCount)
        assertEquals(0L, empty.total)
        assertEquals(0L, empty.upload)
    }

    @Test
    fun parsesRuleProvidersUsingRuleCount() {
        val list = CoreApi.parseProviders("rules", ruleProvidersJson)
        assertEquals(2, list.size)
        assertEquals(53, list.first { it.name == "bilibili-local" }.itemCount)
        assertEquals(910, list.first { it.name == "ads-local" }.itemCount)
        assertEquals("rules", list.first().kind)
    }

    @Test
    fun malformedJsonYieldsEmptyInsteadOfThrowing() {
        assertTrue(CoreApi.parseProviders("proxies", "not json at all").isEmpty())
        assertTrue(CoreApi.parseProviders("proxies", "{}").isEmpty())
        assertTrue(CoreApi.parseProviders("proxies", "{\"providers\": null}").isEmpty())
        assertTrue(CoreApi.parseProviders("proxies", "").isEmpty())
    }

    @Test
    fun providerWithNullEntryIsSkipped() {
        val raw = """
            {"providers": {"bad": null, "good": {"name": "good", "proxies": [{"name": "x"}]}}}
        """.trimIndent()
        val list = CoreApi.parseProviders("proxies", raw)
        assertEquals(1, list.size)
        assertEquals("good", list[0].name)
        assertEquals(1, list[0].itemCount)
    }
}
