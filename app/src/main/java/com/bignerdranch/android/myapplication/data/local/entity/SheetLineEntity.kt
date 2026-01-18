package com.bignerdranch.android.myapplication.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sheet_lines",
    foreignKeys = [ForeignKey(
        entity = SheetEntity::class,
        parentColumns = ["id"],
        childColumns = ["sheetId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sheetId"), Index("item"), Index("country")]
)
data class SheetLineEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sheetId: Long = 0,
    val item: String = "",
    val country: String = "",
    val needed: Int = 0,
    val have: Int = 0,
    val weight: Float = 0f,
    val price: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val hidden: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null
)