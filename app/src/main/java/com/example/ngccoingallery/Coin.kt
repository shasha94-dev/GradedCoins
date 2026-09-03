package com.example.ngccoingallery

data class Coin(
    val id: String,
    val service: String = "NGC",
    val coinNumber: String = "",
    val certNumber: String,
    val grade: String = "",
    val url: String,
    val imagePath: String = "",
    val siteImagePaths: List<String> = emptyList(),
    val frontImagePath: String = "",
    val backImagePath: String = "",
    val manualImagePaths: List<String> = emptyList(),
    val isMine: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val rawBarcode: String = "",
    val description: String = "",
    val year: String = "",
    val country: String = "",
    val denomination: String = "",
    val variety: String = "",
    val siteVerified: Boolean = false
)
