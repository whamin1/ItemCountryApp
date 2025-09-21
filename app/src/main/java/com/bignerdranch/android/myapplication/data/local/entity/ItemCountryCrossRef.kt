package com.bignerdranch.android.myapplication.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/** 아이템 ↔ 나라 다대다 연결 테이블 */
@Entity(
    tableName = "item_country",
    primaryKeys = ["itemId", "countryId"],
    indices = [Index(value = ["countryId"])]
)
data class ItemCountryCrossRef(
    val itemId: Long = 0L,
    val countryId: Long = 0L,
    val needed: Int = 0,
    val have: Int = 0
)