package com.bignerdranch.android.myapplication.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "prediction_ack")
data class PredictionAckEntity(
    @PrimaryKey val itemId: Long,
    val ackAt: Long
)