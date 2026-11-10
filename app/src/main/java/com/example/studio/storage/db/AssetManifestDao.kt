package com.example.studio.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetManifestDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertManifestIfAbsent(manifest: AssetManifestEntity): Long

    @Query("SELECT * FROM asset_manifest WHERE assetId = :assetId")
    suspend fun getByAssetId(assetId: String): AssetManifestEntity?

    @Query("SELECT * FROM asset_manifest WHERE sceneId = :sceneId ORDER BY versionNumber ASC")
    suspend fun getBySceneId(sceneId: String): List<AssetManifestEntity>

    @Query("SELECT * FROM asset_manifest WHERE sceneId = :sceneId ORDER BY versionNumber ASC")
    fun observeBySceneId(sceneId: String): Flow<List<AssetManifestEntity>>

    @Query("SELECT * FROM asset_manifest WHERE sha256Hex = :sha256Hex LIMIT 1")
    suspend fun getBySha256(sha256Hex: String): AssetManifestEntity?

    @Query("SELECT * FROM asset_manifest WHERE sha256Hex = :sha256Hex AND sceneId = :sceneId LIMIT 1")
    suspend fun getBySceneAndSha256(sceneId: String, sha256Hex: String): AssetManifestEntity?

    @Query("SELECT COALESCE(MAX(versionNumber), 0) FROM asset_manifest WHERE sceneId = :sceneId")
    suspend fun getMaxVersionForScene(sceneId: String): Int

    @Query("SELECT * FROM asset_manifest")
    suspend fun getAllManifests(): List<AssetManifestEntity>
}
