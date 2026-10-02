package io.vpnshare

import io.vpnshare.profile.RuleAction
import io.vpnshare.profile.RuleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuleCatalogTest {

    @Test
    fun entryCountsMatchMetaRulesDat() {
        assertEquals(53, RuleCatalog.byKey("bilibili")!!.entries)
        assertEquals(910, RuleCatalog.byKey("ads")!!.entries)
        assertEquals(130, RuleCatalog.byKey("private")!!.entries)
        assertEquals(5069, RuleCatalog.byKey("cn")!!.entries)
    }

    @Test
    fun defaultActionsAreSane() {
        assertEquals(RuleAction.DIRECT, RuleCatalog.byKey("bilibili")!!.defaultAction)
        assertEquals(RuleAction.DIRECT, RuleCatalog.byKey("cn")!!.defaultAction)
        assertEquals(RuleAction.REJECT, RuleCatalog.byKey("ads")!!.defaultAction)
        assertEquals(RuleAction.DIRECT, RuleCatalog.byKey("private")!!.defaultAction)
    }

    @Test
    fun localRulesetFilesDeclaredInCatalogExistOnceVendored() {
        for (cat in RuleCatalog.ALL) {
            val f = cat.localRuleset ?: continue
            val file = File("src/main/assets/ruleset/" + f)
            if (!file.exists()) continue
            val count = file.readLines().count { it.trimStart().startsWith("-") }
            assertEquals("assets/ruleset/" + f + " 条目数与目录元数据不一致", cat.entries, count)
        }
    }

    @Test
    fun builtinRulesetPlaceholderDocumented() {
        // 规则集由 scripts/fetch-rulesets.ps1 拉取；未拉取时规则会退回 GEOSITE，功能不受影响
        assertTrue(RuleCatalog.ALL.count { it.localRuleset != null } == 3)
    }
}
