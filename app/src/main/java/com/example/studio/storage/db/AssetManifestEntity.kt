package com.example.studio.storage.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "asset_manifest",
    indices = [
        Index(value = ["sha256Hex"]),
        Index(value = ["sceneId"])
    ]
)
data class AssetManifestEntity(
    @PrimaryKey
    val assetId: String,

    val sceneId: String,
    val versionNumber: Int,

    val relativePath: String,
    val contentUri: String,

    val sha256Hex: String,
    val byteSize: Long,
    val mimeType: String,

    val width: Int,
    val height: Int,
    val durationMs: Long,
    val nominalFps: Float,

    val isVfr: Boolean,
    val ptsManifestPath: String?,

    val createdAtEpochMs: Long
)
