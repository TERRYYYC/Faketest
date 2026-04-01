package com.example.cellrebelauto.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.cellrebelauto.model.TestResult
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports test results to a CSV file in the Downloads folder.
 * # 将测试结果导出为 CSV 文件到 Downloads 目录
 */
class CsvExporter(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /**
     * Exports results to CSV. Returns the file name.
     * # 导出结果到 CSV，返回文件名
     */
    fun export(results: List<TestResult>): String {
        val fileName = "cellrebel_results_${fileNameFormat.format(Date())}.csv"
        val stream = createOutputStream(fileName)
            ?: throw IllegalStateException("Cannot create output file")

        stream.use { out ->
            val writer = out.bufferedWriter()
            // # CSV 表头
            writer.write("Index,Timestamp,Web Browsing Score,Video Streaming Score,Latitude,Longitude,Status")
            writer.newLine()

            for (r in results) {
                writer.write(buildString {
                    append(r.cycleIndex).append(',')
                    append(dateFormat.format(Date(r.timestamp))).append(',')
                    append(r.webBrowsingScore).append(',')
                    append(r.videoStreamingScore).append(',')
                    append(r.latitude).append(',')
                    append(r.longitude).append(',')
                    append(r.status)
                })
                writer.newLine()
            }
            writer.flush()
        }
        return fileName
    }

    // # 创建输出流，兼容 Android 10+ 和旧版本
    private fun createOutputStream(fileName: String): OutputStream? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            )
            uri?.let { context.contentResolver.openOutputStream(it) }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            java.io.File(dir, fileName).outputStream()
        }
    }
}
