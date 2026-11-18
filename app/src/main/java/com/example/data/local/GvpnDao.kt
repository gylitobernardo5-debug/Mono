package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.SniProfileEntity
import com.example.data.model.SshProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SniProfileDao {
  @Query("SELECT * FROM sni_profiles ORDER BY id ASC")
  fun observeAll(): Flow<List<SniProfileEntity>>

  @Query("SELECT * FROM sni_profiles ORDER BY id ASC")
  suspend fun getAllOnce(): List<SniProfileEntity>

  @Query("SELECT * FROM sni_profiles WHERE id = :id")
  suspend fun getById(id: Long): SniProfileEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(profile: SniProfileEntity): Long

  @Update
  suspend fun update(profile: SniProfileEntity)

  @Delete
  suspend fun delete(profile: SniProfileEntity)
}

@Dao
interface SshProfileDao {
  @Query("SELECT * FROM ssh_profiles ORDER BY id ASC")
  fun observeAll(): Flow<List<SshProfileEntity>>

  @Query("SELECT * FROM ssh_profiles ORDER BY id ASC")
  suspend fun getAllOnce(): List<SshProfileEntity>

  @Query("SELECT * FROM ssh_profiles WHERE id = :id")
  suspend fun getById(id: Long): SshProfileEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(profile: SshProfileEntity): Long

  @Update
  suspend fun update(profile: SshProfileEntity)

  @Delete
  suspend fun delete(profile: SshProfileEntity)
}
