package com.suwayomi.go

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject

// --- 1. 核心数据结构 ---

// 对应整个 API 响应
data class ChapterData(
    val status: String,
    val mangaId: Int,
    val chapter: Int,
    val page: Int,
    val imgWidth: Int,
    val imgHeight: Int,
    val items: List<MangaLine>
)

// 对应每个气泡 (Item)
data class MangaLine(
    val id: String,
    val box: Rect,
    val text: String,
    val translation: String,
    val words: List<JapaneseWord>
)

// 对应单个单词 (统一放在这里，供 Manager 和 UI 使用)
data class JapaneseWord(
    val surface: String,   // 原文 (s)
    val baseForm: String,  // 基本形 (b)
    val pos: String,       // 词性 (p)
    val reading: String,   // 读音 (r)
    val definition: String // 释义 (d)
)

// 对应 UI 显示用的结果包 (弹窗用)
data class OcrResponse(
    val text: String,
    val translation: String,
    val words: List<JapaneseWord>
) {
    companion object {
        fun fromJson(jsonString: String): OcrResponse {
            val json = JSONObject(jsonString)
            val wordsArray = json.getJSONArray("words")
            val wordList = mutableListOf<JapaneseWord>()
            // 修改 OcrResponse 中的解析逻辑
            for (i in 0 until wordsArray.length()) {
                val w = wordsArray.getJSONObject(i)
                wordList.add(JapaneseWord(
                    w.optString("s"),
                    w.optString("b"),
                    w.optString("p"),
                    w.optString("r"),
                    w.optString("d") // 读取释义
                ))
            }
            return OcrResponse(
                text = json.optString("text"),
                translation = json.optString("translation"),
                words = wordList
            )
        }
    }
}

// --- 2. 解析器 ---

object ChapterDataParser {
    fun parse(jsonStr: String): ChapterData? {
        try {
            val root = JSONObject(jsonStr)
            if (root.optString("status") != "success") return null

            val itemsList = ArrayList<MangaLine>()
            val itemsArray = root.optJSONArray("items") ?: JSONArray()

            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.getJSONObject(i)
                val boxArr = item.optJSONArray("box") ?: JSONArray("[0,0,0,0]")

                // Server [x, y, w, h] -> Android Rect [left, top, right, bottom]
                val x = boxArr.optInt(0)
                val y = boxArr.optInt(1)
                val w = boxArr.optInt(2)
                val h = boxArr.optInt(3)

                val wordsList = ArrayList<JapaneseWord>()
                val wordsArr = item.optJSONArray("words")
                if (wordsArr != null) {
                    for (j in 0 until wordsArr.length()) {
                        val wObj = wordsArr.getJSONObject(j)
                        // 注意：这里做 JSON 字段 (s, b...) 到 Kotlin 属性 (surface, baseForm...) 的映射
                        wordsList.add(JapaneseWord(
                            surface = wObj.optString("s"),
                            baseForm = wObj.optString("b"),
                            pos = wObj.optString("p"),
                            reading = wObj.optString("r"),
                            definition = wObj.optString("d")
                        ))
                    }
                }

                itemsList.add(MangaLine(
                    id = item.optString("id"),
                    box = Rect(x, y, x + w, y + h),
                    text = item.optString("text"),
                    translation = item.optString("translation"),
                    words = wordsList
                ))
            }

            return ChapterData(
                status = "success",
                mangaId = root.optInt("manga_id"),
                chapter = root.optInt("chapter"),
                page = root.optInt("page"),
                imgWidth = root.optInt("img_width"),
                imgHeight = root.optInt("img_height"),
                items = itemsList
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}