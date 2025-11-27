package com.bignerdranch.android.myapplication.data.local.entity

data class ItemSearchRow(
    val sheetId: Long,
    val sheetTitle: String,
    val country: String,
    val item: String,
    val price: Int,
    val weight: Float
)