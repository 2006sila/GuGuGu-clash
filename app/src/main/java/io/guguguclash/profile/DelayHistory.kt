package io.guguguclash.profile

import java.io.File

/**
 * 节点延迟历史。
 *
 * 为什么需要：单次测速会被抖动骗到。实测里出现过「延迟最低的节点丢包最严重」——
 * 只看一个数字选节点是不可靠的，要看它稳不稳。
 *
 * 存储：<工作目录>/delay.history，每行 `节点名|t1,t2,t3`，每节点最多 [MAX] 次。
 *
 * 刻意只依赖 java.io.File 而不是 android.content.Context —— Context 是个庞大的抽象类，
 * 单测里没法做桩；传 File 既够用又能纯 JVM 测试。
 * 读失败一律当空处理，绝不因为历史数据损坏影响测速主流程。
 */
object DelayHistory {

    private const val MAX = 10
    private const val FILE = "delay.history"

    private val map = LinkedHashMap<String, ArrayDeque<Int>>()
    private var loadedFrom: String? = null

    @Synchronized
    fun load(dir: File) {
        val key = dir.absolutePath
        if (loadedFrom == key) return
        // 换了目录就重读；同一个目录只读一次
        loadedFrom = key
        map.clear()
        runCatching {
            val f = File(dir, FILE)
            if (!f.exists()) return@runCatching
            for (line in f.readLines()) {
                val i = line.indexOf('|')
                if (i <= 0) continue
                val q = ArrayDeque<Int>()
                for (s in line.substring(i + 1).split(',')) {
                    s.trim().toIntOrNull()?.let { q.addLast(it) }
                }
                if (q.isNotEmpty()) map[line.substring(0, i)] = q
            }
        }
    }

    /** 记一次延迟。<=0（超时/失败）不记，避免历史被无效值污染。 */
    @Synchronized
    fun record(dir: File, name: String, ms: Int) {
        if (name.isBlank() || ms <= 0) return
        load(dir)
        val q = map.getOrPut(name) { ArrayDeque() }
        q.addLast(ms)
        while (q.size > MAX) q.removeFirst()
    }

    @Synchronized
    fun recordAll(dir: File, delays: Map<String, Int>) {
        load(dir)
        for ((k, v) in delays) record(dir, k, v)
    }

    /** 最近 n 次，旧到新 */
    @Synchronized
    fun recent(dir: File, name: String, n: Int = MAX): List<Int> {
        load(dir)
        return map[name]?.toList()?.takeLast(n) ?: emptyList()
    }

    /**
     * 趋势：把最近几次分成前后两半比均值。
     * 返回 -1 变快 / 0 稳定 / 1 变慢 / null 样本不足。
     * 10% 以内算稳定，避免把正常抖动标成趋势。
     */
    @Synchronized
    fun trend(dir: File, name: String): Int? {
        val s = recent(dir, name, 6)
        if (s.size < 4) return null
        val half = s.size / 2
        val a = s.take(half).average()
        val b = s.drop(half).average()
        if (a <= 0) return null
        val d = b - a
        return when {
            d < -a * 0.1 -> -1
            d > a * 0.1 -> 1
            else -> 0
        }
    }

    @Synchronized
    fun save(dir: File) {
        runCatching {
            val sb = StringBuilder()
            for ((k, v) in map) {
                if (v.isEmpty()) continue
                sb.append(k).append('|').append(v.joinToString(",")).append('\n')
            }
            File(dir, FILE).writeText(sb.toString())
        }
    }

    /** 节点改名后按新名重新累积，不做迁移 —— 迁移会产生「这个名字到底是谁的历史」的歧义 */
    @Synchronized
    fun clear(dir: File) {
        map.clear()
        runCatching { File(dir, FILE).delete() }
    }

    @Synchronized
    fun all(dir: File): Map<String, List<Int>> {
        load(dir)
        return map.mapValues { it.value.toList() }
    }
}