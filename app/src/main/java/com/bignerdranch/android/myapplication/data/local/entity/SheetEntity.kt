package com.bignerdranch.android.myapplication.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sheets")
data class SheetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String = "",
    val hidden: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)