package com.example.data.repository

import com.example.data.local.SniProfileDao
import com.example.data.local.SshProfileDao
import com.example.data.model.SniProfileEntity
import com.example.data.model.SshProfileEntity
import kotlinx.coroutines.flow.Flow

class GvpnRepository(
  private val sniProfileDao: SniProfileDao,
  private val sshProfileDao: SshProfileDao,
) {
  val sniProfiles: Flow<List<SniProfileEntity>> = sniProfileDao.observeAll()
  val sshProfiles: Flow<List<SshProfileEntity>> = sshProfileDao.observeAll()

  suspend fun getSniProfilesOnce(): List<SniProfileEntity> = sniProfileDao.getAllOnce()

  suspend fun getSniProfileById(id: Long): SniProfileEntity? = sniProfileDao.getById(id)

  suspend fun saveSniProfile(profile: SniProfileEntity): Long =
    if (profile.id == 0L) sniProfileDao.insert(profile)
    else {
      sniProfileDao.update(profile)
      profile.id
    }

  suspend fun deleteSniProfile(profile: SniProfileEntity) = sniProfileDao.delete(profile)

  suspend fun getSshProfilesOnce(): List<SshProfileEntity> = sshProfileDao.getAllOnce()

  suspend fun getSshProfileById(id: Long): SshProfileEntity? = sshProfileDao.getById(id)

  suspend fun saveSshProfile(profile: SshProfileEntity): Long =
    if (profile.id == 0L) sshProfileDao.insert(profile)
    else {
      sshProfileDao.update(profile)
      profile.id
    }

  suspend fun deleteSshProfile(profile: SshProfileEntity) = sshProfileDao.delete(profile)
}
