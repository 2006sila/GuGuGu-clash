package io.vpnshare

import io.vpnshare.profile.DelayHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 延迟历史。DelayHistory 只依赖 java.io.File 而非 Context，
 * 所以这里不需要任何 Android 桩件。
 */
class DelayHistoryTest {

    private fun dir(): File = Files.createTempDirectory("dh").toFile()

    @Test
    fun recordsAndReturnsInOrder() {
        val d = dir()
        listOf(100, 120, 110).forEach { DelayHistory.record(d, "HK", it) }
        assertEquals(listOf(100, 120, 110), DelayHistory.recent(d, "HK"))
    }

    @Test
    fun ignoresNonPositive() {
        val d = dir()
        DelayHistory.record(d, "HK", 0)
        DelayHistory.record(d, "HK", -1)
        DelayHistory.record(d, "HK", 150)
        assertEquals(listOf(150), DelayHistory.recent(d, "HK"))
    }

    @Test
    fun trimsToMax() {
        val d = dir()
        repeat(20) { DelayHistory.record(d, "HK", 100 + it) }
        val r = DelayHistory.recent(d, "HK", 100)
        assertEquals(10, r.size)
        assertEquals(110, r.first())   // 保留的是最后 10 次
        assertEquals(119, r.last())
    }

    @Test
    fun trendDetectsImprovementAndDegradation() {
        val d = dir()
        listOf(300, 290, 200, 190).forEach { DelayHistory.record(d, "A", it) }
        assertEquals(-1, DelayHistory.trend(d, "A"))
        listOf(100, 110, 300, 320).forEach { DelayHistory.record(d, "B", it) }
        assertEquals(1, DelayHistory.trend(d, "B"))
        listOf(150, 152, 149, 151).forEach { DelayHistory.record(d, "C", it) }
        assertEquals(0, DelayHistory.trend(d, "C"))
        DelayHistory.record(d, "D", 100)
        assertNull(DelayHistory.trend(d, "D"))
    }

    @Test
    fun survivesCorruptFile() {
        val d = dir()
        File(d, "delay.history").writeText("这不是合法内容|||\nA|x,y,z\nB|120,130\n")
        // 触发一次 load，损坏行应被跳过、有效行应保留
        assertEquals(listOf(120, 130), DelayHistory.recent(d, "B"))
        assertEquals(emptyList<Int>(), DelayHistory.recent(d, "A"))
    }

    @Test
    fun roundTripsThroughFile() {
        val d1 = dir()
        listOf(100, 200, 300).forEach { DelayHistory.record(d1, "X", it) }
        DelayHistory.save(d1)
        // 换一个目录对象读同一份文件，模拟重启
        val d2 = File(d1.absolutePath)
        assertEquals(listOf(100, 200, 300), DelayHistory.recent(d2, "X"))
    }
}
