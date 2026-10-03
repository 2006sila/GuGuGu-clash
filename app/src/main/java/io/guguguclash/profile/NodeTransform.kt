package io.guguguclash.profile

/**
 * 节点过滤与重命名。
 *
 * 两种模式要分别处理，风险完全不同：
 *
 *   重建模式 —— 节点在 provider 文件里，我们的 PROXY 组用 `use: [provider]` 引用，
 *               改名字不影响任何引用，安全。
 *
 *   采纳模式 —— 订阅原文里既有 proxies 块，又有 proxy-groups 里逐个列出的节点名。
 *               只改 proxies 而不改组的引用，内核会因为「组引用了不存在的节点」
 *               直接拒绝启动。所以必须同步改写组列表。
 */
object NodeTransform {

    data class Rules(
        val include: List<String> = emptyList(),
        val exclude: List<String> = emptyList(),
        val renames: List<Pair<String, String>> = emptyList()
    ) {
        val isEmpty: Boolean get() = include.isEmpty() && exclude.isEmpty() && renames.isEmpty()
    }

    data class Result(
        val text: String,
        val kept: Int,
        val dropped: Int,
        val renamed: Int,
        val error: String? = null
    )

    /** 解析用户输入：include/exclude 每行一个关键字；重命名每行 `正则=>新写法` */
    fun parseRules(include: String, exclude: String, rename: String): Rules {
        val inc = include.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val exc = exclude.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val ren = mutableListOf<Pair<String, String>>()
        for (line in rename.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val i = t.indexOf("=>")
            if (i <= 0) continue
            val from = t.substring(0, i).trim()
            val to = t.substring(i + 2).trim()
            if (from.isNotEmpty()) ren += from to to
        }
        return Rules(inc, exc, ren)
    }

    /** 关键字匹配：包含即算命中（大小写不敏感） */
    private fun hit(name: String, keys: List<String>): Boolean =
        keys.any { name.contains(it, ignoreCase = true) }

    private fun newName(name: String, rules: Rules): String {
        var n = name
        for ((from, to) in rules.renames) {
            n = runCatching { Regex(from).replace(n, to) }.getOrDefault(n)
        }
        return n.trim()
    }

    /** 从一行 `- { name: 'X', ... }` 或多行块的 `- name: X` 里取节点名 */
    private fun nameOf(entry: String): String? {
        val m = Regex("name:\\s*'([^']*)'").find(entry)
            ?: Regex("name:\\s*\"([^\"]*)\"").find(entry)
            ?: Regex("name:\\s*([^,\\}\\n]+)").find(entry)
        return m?.groupValues?.get(1)?.trim()
    }

    /**
     * 只替换 name 字段的值。
     *
     * **绝不能用 `text.replace(old, new)`** —— 那是整条条目的全文替换，
     * 会把 server / password / uuid 里恰好同名的子串一起改掉。
     * 典型翻车场景：节点名就是 server 域名（不少机场这么命名），改名后 server
     * 也跟着变成节点名，该节点从此永远连不上 —— 而且不报错、mihomo -t 自检也能过，
     * 只表现为「这个节点一直超时」，极难排查。
     *
     * 三种写法都覆盖（与 [nameOf] 保持一致），并保留原有引号风格。
     * 定位不到 name 字段时原样返回：宁可名字没改成，也不能改坏别的字段。
     */
    private fun renameInEntry(text: String, old: String, new: String): String {
        val e = Regex.escape(old)
        val variants = listOf(
            Regex("name:(\\s*)'(" + e + ")'"),
            Regex("name:(\\s*)\"(" + e + ")\""),
            Regex("name:(\\s*)(" + e + ")")
        )
        for (re in variants) {
            val m = re.find(text) ?: continue
            val spaces = m.groupValues[1]
            val quote = when {
                m.value.contains("'") -> "'"
                m.value.contains("\"") -> "\""
                else -> ""
            }
            return text.substring(0, m.range.first) + "name:" + spaces + quote + new + quote +
                text.substring(m.range.last + 1)
        }
        return text
    }

    /**
     * 变换一个 proxies 块。返回新块、旧名→新名映射、被丢弃的名字集合。
     * 块里每个条目以 `- ` 起头，后续缩进行属于同一条目。
     */
    fun transformProxiesBlock(block: String, rules: Rules): Triple<String, Map<String, String>, Set<String>> {
        if (rules.isEmpty) return Triple(block, emptyMap(), emptySet())

        val lines = block.lines()
        val entries = mutableListOf<MutableList<String>>()
        for (l in lines.drop(1)) {   // 第一行是 `proxies:`
            if (l.trimStart().startsWith("- ") || l.trim() == "-") {
                entries += mutableListOf(l)
            } else if (entries.isNotEmpty()) {
                entries.last() += l
            }
        }

        val kept = mutableListOf<List<String>>()
        val rename = LinkedHashMap<String, String>()
        val dropped = LinkedHashSet<String>()
        val used = HashSet<String>()

        for (e in entries) {
            val text = e.joinToString("\n")
            val name = nameOf(text)
            if (name == null) { kept += e; continue }   // 认不出名字就原样保留，不擅自丢
            val includeOk = rules.include.isEmpty() || hit(name, rules.include)
            val excludeHit = rules.exclude.isNotEmpty() && hit(name, rules.exclude)
            if (!includeOk || excludeHit) { dropped += name; continue }

            var nn = newName(name, rules)
            if (nn.isBlank()) nn = name
            // 重名时补序号，否则内核会因为节点重名报错
            if (used.contains(nn)) {
                var k = 2
                while (used.contains(nn + " #" + k)) k++
                nn = nn + " #" + k
            }
            used += nn
            if (nn != name) rename[name] = nn

            val newText = if (nn == name) text else renameInEntry(text, name, nn)
            kept += newText.lines()
        }

        val out = ArrayList<String>()
        out += "proxies:"
        for (e in kept) out += e
        return Triple(out.joinToString("\n"), rename, dropped)
    }

    /**
     * 同步改写 proxy-groups 里 `proxies: [A, B, C]` 的引用。
     * 改名要跟着改，被丢掉的要从列表里去掉 —— 否则内核会因为引用了不存在的节点而拒绝启动。
     */
    fun rewriteGroupRefs(yaml: String, rename: Map<String, String>, dropped: Set<String>): String {
        if (rename.isEmpty() && dropped.isEmpty()) return yaml
        val re = Regex("proxies:\\s*\\[([^\\]]*)\\]")
        return re.replace(yaml) { m ->
            val items = m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val kept = items.filterNot { unquote(it) in dropped }.map {
                val u = unquote(it)
                val n = rename[u] ?: u
                if (n != u) quoteLike(it, n) else it
            }
            "proxies: [" + kept.joinToString(", ") + "]"
        }
    }

    private fun unquote(s: String): String = s.trim().trim('\'', '"').trim()

    private fun quoteLike(original: String, value: String): String =
        if (original.trim().startsWith("'")) "'" + value + "'" else value

    /** 对一个只含 proxies 的文件（重建模式的 provider）做变换 */
    fun applyToProxiesBlock(block: String, rules: Rules): Result {
        val (text, _, dropped) = transformProxiesBlock(block, rules)
        val kept = countEntries(text)
        if (rules.isEmpty) return Result(block, kept, 0, 0)
        if (kept == 0) return Result(text, 0, dropped.size, 0, "过滤条件把节点全过滤掉了")
        return Result(text, kept, dropped.size, 0)
    }

    /** 对完整配置（采纳模式）做变换：proxies 块 + 组引用一起改 */
    fun applyToConfig(yaml: String, rules: Rules): Result {
        if (rules.isEmpty) return Result(yaml, 0, 0, 0)
        val block = ConfigBuilder.extractTopLevelBlock(yaml, "proxies")
            ?: return Result(yaml, 0, 0, 0, "配置里没有 proxies 块")
        val (newBlock, rename, dropped) = transformProxiesBlock(block, rules)
        val kept = countEntries(newBlock)
        if (kept == 0) return Result(yaml, 0, dropped.size, 0, "过滤条件把节点全过滤掉了")

        var out = yaml.replace(block, newBlock)
        out = rewriteGroupRefs(out, rename, dropped)
        return Result(out, kept, dropped.size, rename.size)
    }

    fun countEntries(block: String): Int =
        block.lines().drop(1).count { it.trimStart().startsWith("- ") }
}