package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.data.model.SniProfileEntity
import com.example.data.model.SshAuthMethod
import com.example.data.model.SshProfileEntity
import com.example.data.model.TlsTransportType

class GvpnTypeConverters {
  @TypeConverter fun fromTransport(v: TlsTransportType): String = v.name

  @TypeConverter fun toTransport(v: String): TlsTransportType =
    runCatching { TlsTransportType.valueOf(v) }.getOrDefault(TlsTransportType.HTTP_CONNECT_TLS)

  @TypeConverter fun fromAuthMethod(v: SshAuthMethod): String = v.name

  @TypeConverter fun toAuthMethod(v: String): SshAuthMethod =
    runCatching { SshAuthMethod.valueOf(v) }.getOrDefault(SshAuthMethod.PASSWORD)
}

@Database(
  entities = [SniProfileEntity::class, SshProfileEntity::class],
  version = 1,
  exportSchema = false,
)
@TypeConverters(GvpnTypeConverters::class)
abstract class GvpnDatabase : RoomDatabase() {
  abstract fun sniProfileDao(): SniProfileDao

  abstract fun sshProfileDao(): SshProfileDao

  companion object {
    @Volatile private var instance: GvpnDatabase? = null

    fun getInstance(context: Context): GvpnDatabase {
      return instance
        ?: synchronized(this) {
          instance
            ?: Room.databaseBuilder(
                context.applicationContext,
                GvpnDatabase::class.java,
                "gvpn_extreme_v2.db",
              )
              .build()
              .also { instance = it }
        }
    }
  }
}
