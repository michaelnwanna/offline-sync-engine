package dev.offlinesync.android

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

@Entity(tableName = "sync_metadata")
internal data class SyncMetadata(@PrimaryKey val name: String, val value: String)

@Dao
internal interface MetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(metadata: SyncMetadata)

    @Query("SELECT value FROM sync_metadata WHERE name = :name")
    suspend fun get(name: String): String?
}

@Database(entities = [SyncMetadata::class, StoredDocument::class, OutboxEntry::class], version = 2, exportSchema = true)
abstract class SyncDatabase : RoomDatabase() {
    private var passphrase: ByteArray? = null
    internal abstract fun metadata(): MetadataDao
    internal abstract fun documents(): DocumentDao
    internal abstract fun outbox(): OutboxDao

    override fun close() {
        try {
            super.close()
        } finally {
            passphrase?.fill(0)
            passphrase = null
        }
    }

    companion object {
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN nextAttemptMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN leaseToken TEXT")
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN leaseUntilMs INTEGER")
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN errorCode TEXT")
            }
        }

        @Synchronized
        fun open(context: Context, accountId: String): SyncDatabase {
            require(accountId.isNotBlank())
            val scope = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val directory = File(context.noBackupFilesDir, "offline-sync/$scope")
            val file = File(directory, "sync.db")
            check(directory.mkdirs() || directory.isDirectory)
            FileOutputStream(File(directory, "open.lock"), true).channel.use { channel ->
                channel.lock().use {
                    val passphrase = DatabaseKeyStore(directory, "offline-sync.$scope").loadOrCreate(file.exists())
                    var database: SyncDatabase? = null
                    try {
                        System.loadLibrary("sqlcipher")
                        database = Room.databaseBuilder(context.applicationContext, SyncDatabase::class.java, file.absolutePath)
                            .addMigrations(MIGRATION_1_2)
                            .openHelperFactory(SupportOpenHelperFactory(passphrase, null, true))
                            .build()
                        database.passphrase = passphrase
                        database.openHelper.writableDatabase
                        return database
                    } catch (failure: Throwable) {
                        database?.close()
                        passphrase.fill(0)
                        throw failure
                    }
                }
            }
        }
    }
}