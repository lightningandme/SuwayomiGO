package com.suwayomi.go

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject

// 1. 对应整个 API 响应
data class ChapterData(
    val status: String,
    val mangaId: Int,
    val chapter: Int,
    val page: Int,
    val imgWidth: Int,
    val imgHeight: Int,
    val items: List<MangaLine>
)

// 2. 对应每个气泡 (Item)
data class MangaLine(
    val id: String,
    val box: Rect,
    val text: String,
    val translation: String,
    val words: List<JapaneseWord>
)

// 辅助解析器
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
                        wordsList.add(JapaneseWord(
                            wObj.optString("s"), // surface
                            wObj.optString("b"), // baseForm
                            wObj.optString("p"), // pos
                            wObj.optString("r"), // reading
                            wObj.optString("d")  // definition
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