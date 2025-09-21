package com.bignerdranch.android.myapplication.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "countries",
    indices = [Index(value = ["name"], unique = true)]
)
data class CountryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = ""
)