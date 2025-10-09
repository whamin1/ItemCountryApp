package com.bignerdranch.android.myapplication.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "save_session")
data class SaveSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long,
    val title: String,
    val createdAt: Long
)