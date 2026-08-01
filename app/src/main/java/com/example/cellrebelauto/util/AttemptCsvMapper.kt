package com.example.cellrebelauto.util

import com.example.cellrebelauto.model.plan.AttemptWithTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure mapping from attempt rows to the 15-column audit CSV (AC-C3).
 * No Android deps — JVM-unit-testable; CsvExporter consumes this.
 * # 尝试行 → 15 列审计 CSV 的纯映射。无 Android 依赖，可 JVM 单测
 *
 * Column semantics:
 * - plan_row: task's 1-based execution-order index within its plan (INV-1)
 * - success_ordinal / scores: non-empty ONLY on succeeded rows (INV-3/4)
 * - failure_reason: required on failed/interrupted rows (INV-10)
 * - running_observed_at: RUNNING-transition audit timestamp (AC-B2)
 * - timestamps: yyyy-MM-dd HH:mm:ss; nulls map to empty cells
 */
object AttemptCsvMapper {

    // # 审计导出表头（设计稿 v2.1 §1.3）
    val HEADER: List<String> = listOf(
        "plan_row", "csv_row", "priority", "longitude", "latitude",
        "success_ordinal", "attempt_ordinal", "status", "failure_reason",
        "started_at", "running_observed_at", "ended_at",
        "web_score", "video_score", "session_id"
    )

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Maps attempts to CSV rows in the same column order as [HEADER].
     * # 将尝试映射为与表头同列序的 CSV 行
     */
    fun toCsvRows(attempts: List<AttemptWithTask>): List<List<String>> =
        attempts.map { item ->
            val a = item.attempt
            listOf(
                item.planRow.toString(),
                item.csvRow.toString(),
                item.priority.toString(),
                a.longitude.toString(),
                a.latitude.toString(),
                a.successOrdinal?.toString() ?: "",
                a.attemptOrdinal.toString(),
                a.status,
                a.failureReason ?: "",
                formatTs(a.startedAt),
                a.runningObservedAt?.let { formatTs(it) } ?: "",
                a.endedAt?.let { formatTs(it) } ?: "",
                a.webBrowsingScore?.toString() ?: "",
                a.videoStreamingScore?.toString() ?: "",
                a.runSessionId.toString()
            )
        }

    private fun formatTs(epochMs: Long): String = timestampFormat.format(Date(epochMs))
}
