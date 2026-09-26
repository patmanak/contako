package com.patmanak.contako.data.proton

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.proton.core.account.data.db.AccountConverters
import me.proton.core.account.data.db.AccountDao
import me.proton.core.account.data.db.AccountDatabase
import me.proton.core.account.data.db.AccountMetadataDao
import me.proton.core.account.data.db.SessionDao
import me.proton.core.account.data.db.SessionDetailsDao
import me.proton.core.account.data.entity.AccountEntity
import me.proton.core.account.data.entity.AccountMetadataEntity
import me.proton.core.account.data.entity.SessionDetailsEntity
import me.proton.core.account.data.entity.SessionEntity
import me.proton.core.challenge.data.db.ChallengeConverters
import me.proton.core.challenge.data.db.ChallengeDatabase
import me.proton.core.challenge.data.db.ChallengeFramesDao
import me.proton.core.challenge.data.entity.ChallengeFrameEntity
import me.proton.core.crypto.common.keystore.EncryptedByteArray
import me.proton.core.data.room.db.BaseDatabase
import me.proton.core.data.room.db.CommonConverters
import me.proton.core.key.data.db.KeySaltDao
import me.proton.core.key.data.db.KeySaltDatabase
import me.proton.core.key.data.entity.KeySaltEntity
import me.proton.core.user.data.db.AddressDatabase
import me.proton.core.user.data.db.UserConverters
import me.proton.core.user.data.db.UserDatabase
import me.proton.core.user.data.db.dao.AddressDao
import me.proton.core.user.data.db.dao.AddressKeyDao
import me.proton.core.user.data.db.dao.AddressWithKeysDao
import me.proton.core.user.data.db.dao.UserDao
import me.proton.core.user.data.db.dao.UserKeyDao
import me.proton.core.user.data.db.dao.UserWithKeysDao
import me.proton.core.user.data.entity.AddressEntity
import me.proton.core.user.data.entity.AddressKeyEntity
import me.proton.core.user.data.entity.UserEntity
import me.proton.core.user.data.entity.UserKeyEntity

/** Proton Core's encrypted account/session and protected user-key support store only. */
@Database(
    entities = [
        AccountEntity::class,
        AccountMetadataEntity::class,
        SessionEntity::class,
        SessionDetailsEntity::class,
        UserEntity::class,
        UserKeyEntity::class,
        AddressEntity::class,
        AddressKeyEntity::class,
        KeySaltEntity::class,
        ChallengeFrameEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(
    CommonConverters::class,
    AccountConverters::class,
    UserConverters::class,
    ChallengeConverters::class,
    GateCCoreConverters::class,
)
internal abstract class ProtonGateCDatabase :
    BaseDatabase(),
    AccountDatabase,
    UserDatabase,
    AddressDatabase,
    KeySaltDatabase,
    ChallengeDatabase {
    abstract override fun accountDao(): AccountDao
    abstract override fun sessionDao(): SessionDao
    abstract override fun accountMetadataDao(): AccountMetadataDao
    abstract override fun sessionDetailsDao(): SessionDetailsDao
    abstract override fun userDao(): UserDao
    abstract override fun userKeyDao(): UserKeyDao
    abstract override fun userWithKeysDao(): UserWithKeysDao
    abstract override fun addressDao(): AddressDao
    abstract override fun addressKeyDao(): AddressKeyDao
    abstract override fun addressWithKeysDao(): AddressWithKeysDao
    abstract override fun keySaltDao(): KeySaltDao
    abstract override fun challengeFramesDao(): ChallengeFramesDao

    companion object {
        private const val DATABASE_NAME = "proton-core-gate-c.db"

        fun build(
            context: Context,
            @Suppress("UNUSED_PARAMETER") protectedStorage: GateCProtectedStorageGuard.Proof,
            databaseName: String = DATABASE_NAME,
        ): ProtonGateCDatabase =
            BaseDatabase.databaseBuilder<ProtonGateCDatabase>(
                context = context.applicationContext,
                dbName = databaseName,
            ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}

internal class GateCCoreConverters {
    @TypeConverter
    fun fromEncryptedByteArray(value: EncryptedByteArray?): ByteArray? = value?.array

    @TypeConverter
    fun toEncryptedByteArray(value: ByteArray?): EncryptedByteArray? = value?.let(::EncryptedByteArray)
}
