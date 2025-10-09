package com.bignerdranch.android.myapplication.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "save_session_line", indices = [Index("sessionId")])
data class SaveSessionLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val batchId: Long,
    val country: String,
    val item: String,
    val fromHave: Int,
    val toHave: Int,
    val delta: Int,
    val timestamp: Long
)