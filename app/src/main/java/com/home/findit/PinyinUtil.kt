package com.home.findit

import net.sourceforge.pinyin4j.PinyinHelper

/** 拼音工具：用于拼音首字母/全拼模糊搜索 */
object PinyinUtil {

    private val toneRegex = Regex("[0-9]")

    /** 全拼：剪刀 -> jiandao */
    fun full(text: String): String = buildString {
        for (c in text) {
            val arr = PinyinHelper.toHanyuPinyinStringArray(c)
            if (arr != null && arr.isNotEmpty()) {
                append(toneRegex.replace(arr[0], ""))
            } else {
                append(c)
            }
        }
    }

    /** 首字母：剪刀 -> jd */
    fun initials(text: String): String = buildString {
        for (c in text) {
            val arr = PinyinHelper.toHanyuPinyinStringArray(c)
            if (arr != null && arr.isNotEmpty()) {
                val p = toneRegex.replace(arr[0], "")
                if (p.isNotEmpty()) append(p[0])
            } else {
                append(c)
            }
        }
    }
}
