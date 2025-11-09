package com.bignerdranch.android.myapplication.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "quantity_log")
data class QuantityLogEntity (
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val itemId: Long = 0L,
    val countryId: Long? = 0L,
    val fromHave: Int = 0,
    val toHave: Int = 0,
    val delta: Int = 0,
    val timestamp: Long = 0L, // System.currentTimeMillis()
    val batchId: Long? = null,
    val archived: Int = 0
)