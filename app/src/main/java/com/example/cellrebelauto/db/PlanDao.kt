package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask

/**
 * DAO for location_plans.
 * # 位置计划表的数据访问对象
 */
@Dao
interface PlanDao {

    @Insert
    suspend fun insertPlan(plan: LocationPlan): Long

    @Insert
    suspend fun insertTasks(tasks: List<LocationTask>): List<Long>

    /**
     * Atomic import: plan row + all task rows in one transaction.
     * # 原子导入：同一事务内写入计划行和全部任务行
     */
    @Transaction
    suspend fun insertPlanWithTasks(plan: LocationPlan, tasks: List<LocationTask>): Long {
        val planId = insertPlan(plan)
        insertTasks(tasks.map { it.copy(planId = planId) })
        return planId
    }

    // # 获取最近导入的计划
    @Query("SELECT * FROM location_plans ORDER BY importedAt DESC LIMIT 1")
    suspend fun getLatestPlan(): LocationPlan?

    @Query("SELECT * FROM location_plans WHERE id = :planId")
    suspend fun getPlanById(planId: Long): LocationPlan?
}
