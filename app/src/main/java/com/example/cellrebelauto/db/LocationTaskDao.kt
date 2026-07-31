package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Query
import com.example.cellrebelauto.model.plan.LocationTask

/**
 * DAO for location_tasks.
 * # 位置任务表的数据访问对象
 */
@Dao
interface LocationTaskDao {

    // # 执行顺序：priority ASC, csvRow ASC（INV-1）
    @Query("SELECT * FROM location_tasks WHERE planId = :planId ORDER BY priority ASC, csvRow ASC")
    suspend fun getTasksForPlan(planId: Long): List<LocationTask>

    @Query("SELECT * FROM location_tasks WHERE planId = :planId AND status = 'active' LIMIT 1")
    suspend fun getActiveTaskForPlan(planId: Long): LocationTask?

    @Query("SELECT * FROM location_tasks WHERE id = :taskId")
    suspend fun getTaskById(taskId: Long): LocationTask?

    @Query("UPDATE location_tasks SET status = :status WHERE id = :taskId")
    suspend fun updateTaskStatus(taskId: Long, status: String)

    /**
     * Guarded increment for INV-3 idempotency: only increments when the current
     * completedSuccesses still equals the expected value. Returns affected rows.
     * # 守卫式自增（INV-3 幂等）：仅当前值仍等于期望值时才 +1，返回受影响行数
     */
    @Query(
        "UPDATE location_tasks SET completedSuccesses = completedSuccesses + 1 " +
            "WHERE id = :taskId AND completedSuccesses = :expectedCompletedSuccesses"
    )
    suspend fun incrementSuccessIfCurrent(taskId: Long, expectedCompletedSuccesses: Int): Int

    @Query("UPDATE location_tasks SET status = 'completed' WHERE id = :taskId")
    suspend fun markTaskCompleted(taskId: Long)
}
