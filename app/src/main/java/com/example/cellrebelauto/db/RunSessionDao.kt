package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.cellrebelauto.model.RunSession
import kotlinx.coroutines.flow.Flow

/**
 * DAO for run_sessions table.
 * # 运行会话表的数据访问对象
 */
@Dao
interface RunSessionDao {

    @Insert
    suspend fun insert(session: RunSession): Long

    // # 结束会话：更新结束时间、状态和循环数
    @Query("UPDATE run_sessions SET endedAt = :endedAt, status = :status, totalCycles = :totalCycles WHERE id = :id")
    suspend fun finish(id: Long, endedAt: Long, status: String, totalCycles: Int)

    // # 获取最近一次会话
    @Query("SELECT * FROM run_sessions ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLatest(): RunSession?

    // # 获取所有会话列表（用于历史查看）
    @Query("SELECT * FROM run_sessions ORDER BY startedAt DESC")
    fun getAllSessions(): Flow<List<RunSession>>
}
