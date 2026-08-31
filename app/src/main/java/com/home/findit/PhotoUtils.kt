package com.home.findit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** 图片工具：按需采样加载，避免大图撑爆内存 */
object PhotoUtils {

    /** 列表缩略图 */
    fun loadThumb(path: String, maxSize: Int = 256): Bitmap? {
        return load(path, maxSize * 2)
    }

    /** 全屏查看大图 */
    fun loadFull(path: String, maxDim: Int = 1920): Bitmap? {
        return load(path, maxDim)
    }

    private fun load(path: String, maxDim: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) {
            null
        }
    }
}

/** 照片文件名 → 实际文件（照片目录随设备迁移，文件名跨设备一致） */
object PhotoFiles {
    fun resolve(context: Context, name: String?): File? {
        if (name.isNullOrBlank()) return null
        return File(context.filesDir, "photos").resolve(name)
    }
}
