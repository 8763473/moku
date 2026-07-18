package com.yue.moku.network

data class ModelDetail(
    val id: String,
    val state: String? = null,
    val maxContextLength: Int? = null,
    val loadedContextLength: Int? = null,
    val arch: String? = null,
    val quant: String? = null,
)
