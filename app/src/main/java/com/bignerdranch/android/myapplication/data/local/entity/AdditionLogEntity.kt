package com.bignerdranch.android.myapplication.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "addition_log")
data class AdditionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long = 0L,
    val countryId: Long = 0L,
    val needed: Int = 0,
    val have: Int = 0,
    val timestamp: Long = 0L
)