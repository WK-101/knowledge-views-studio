package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** K8: Chinese, Japanese and Korean names matched by romanised syllables. */
class SyllableMatcherTest {
    // 张三 = zhang san, 王小明 = wang xiao ming, 김민수 = gim min su.
    private fun enc(name: String, vararg syl: String?) = T9.Encoded(name, syllables = syl.toList())
    private val zhangSan = enc("张三", "zhang", "san")
    private val wangXiaoMing = enc("王小明", "wang", "xiao", "ming")

    private fun m(q: String, e: T9.Encoded) = T9.match(q, e, emptyList())

    @Test fun without_syllables_cjk_names_have_no_keys() {
        assertNull(m("9", T9.Encoded("张三")))
    }

    @Test fun initials() {
        assertEquals(listOf(0..0, 1..1), m("97", zhangSan)!!.nameRanges) // z s
        assertNotNull(m("996", wangXiaoMing)) // w x m
    }

    @Test fun full_syllables() {
        assertNotNull(m("94264726", zhangSan)) // zhangsan
        assertNotNull(m("926494266464", wangXiaoMing)) // wangxiaoming
    }

    @Test fun mixed_initials_and_syllables() {
        assertNotNull(m("942647", zhangSan)) // zhang + s
        assertNotNull(m("9726", zhangSan)) // z + san
        assertNotNull(m("94266464", wangXiaoMing)) // xiao + ming (starts at the second character)
    }

    @Test fun later_start_scores_lower() {
        val first = m("9", wangXiaoMing)!!.score
        val second = m("6464", wangXiaoMing)!!.score
        assertTrue(first > second)
    }

    @Test fun no_match() {
        assertNull(m("2", zhangSan))
        assertNull(m("9428", zhangSan)) // zha + t: no syllable starts with t
        assertNull(m("7269", zhangSan)) // san, then nothing after the last character
    }

    @Test fun prefix_closed() {
        val q = "926494266464"
        for (i in 1..q.length) assertNotNull("prefix ${q.take(i)}", m(q.take(i), wangXiaoMing))
    }

    @Test fun korean_and_mixed_names() {
        val kim = enc("김민수", "gim", "min", "su")
        assertNotNull(m("467", kim)) // g m s
        assertNotNull(m("44664678", kim)) // gim min su
        val mixed = enc("李 Lee", "li", null, null, null, null)
        assertNotNull(m("54533", mixed)) // li + lee
    }

    @Test fun phonetic_name_is_searched() {
        val e = T9.Encoded("山田太郎", phonetic = "Yamada Taro")
        val r = m("92623", e)!!
        assertTrue(r.nameRanges.isEmpty())
        assertNotNull(m("8276", e)) // taro
        assertNull(m("4", e))
    }

    @Test fun matcher_prefers_whole_syllables_for_highlight() {
        val units = listOf(SyllableMatcher.Unit("94264", IntArray(5) { 0 }), SyllableMatcher.Unit("726", IntArray(3) { 1 }))
        val r = SyllableMatcher.match("94264726", units)!!
        assertEquals(0, r.firstUnit)
        assertEquals(listOf(0..0, 1..1), r.ranges)
    }
}
