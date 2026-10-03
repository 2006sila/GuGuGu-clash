package io.guguguclash

import io.guguguclash.profile.RuleAction
import io.guguguclash.profile.RuleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuleCatalogTest {

    @Test
    fun entryCountsMatchMetaRulesDat() {
        assertEquals(53, RuleCatalog.ALL.first { it.key == "bilibili" }.entries)
        assertEquals(910, RuleCatalog.ALL.first { it.key == "ads" }.entries)
        assertEquals(130, RuleCatalog.ALL.first { it.key == "private" }.entries)
        assertEquals(5069, RuleCatalog.ALL.first { it.key == "cn" }.entries)
    }

    @Test
    fun defaultActionsAreSane() {
        assertEquals(RuleAction.DIRECT, RuleCatalog.ALL.first { it.key == "bilibili" }.defaultAction)
        assertEquals(RuleAction.DIRECT, RuleCatalog.ALL.first { it.key == "cn" }.defaultAction)
        assertEquals(RuleAction.REJECT, RuleCatalog.ALL.first { it.key == "ads" }.defaultAction)
        assertEquals(RuleAction.DIRECT, RuleCatalog.ALL.first { it.key == "private" }.defaultAction)
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
