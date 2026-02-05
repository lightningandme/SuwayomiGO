package com.suwayomi.go

import android.util.Log

object MangaDataManager {
    // 缓存当前页的数据 (Key: "mangaId_chapter_page")
    private var currentChapterData: ChapterData? = null
    private var currentCacheKey: String = ""

    // 保存数据
    fun updateData(key: String, data: ChapterData) {
        currentCacheKey = key
        currentChapterData = data
        Log.d("MangaData", "数据已更新: $key, 包含 ${data.items.size} 个气泡")
    }

    // 获取数据
    fun getData(key: String): ChapterData? {
        return if (currentCacheKey == key) currentChapterData else null
    }

    // 命中测试 (Hit Test)
    fun hitTest(originalX: Int, originalY: Int): MangaLine? {
        val data = currentChapterData ?: return null
        // 遍历所有气泡，看点击点是否在 box 矩形内
        for (item in data.items) {
            if (item.box.contains(originalX, originalY)) {
                return item
            }
        }
        return null
    }
}