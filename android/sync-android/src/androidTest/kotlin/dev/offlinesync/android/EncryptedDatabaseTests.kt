package dev.offlinesync.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun encryptedRoomDatabaseReopensAndPlainSqliteCannotReadIt() = runTest {
        val account = UUID.randomUUID().toString()
        val database = SyncDatabase.open(context, account)
        database.metadata().put(SyncMetadata("probe", "retained"))
        val file = File(requireNotNull(database.openHelper.writableDatabase.path))
        database.close()
        assertTrue(file.toPath().startsWith(context.noBackupFilesDir.toPath()))
        assertThrows(Exception::class.java) {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { plain ->
                plain.rawQuery("SELECT * FROM sync_metadata", null).use { it.moveToFirst() }
            }
        }
        val wrongKey = Room.databaseBuilder(context, SyncDatabase::class.java, file.absolutePath)
            .openHelperFactory(SupportOpenHelperFactory(ByteArray(32) { 7 })).build()
        try {
            assertThrows(Exception::class.java) { wrongKey.openHelper.writableDatabase }
        } finally {
            wrongKey.close()
        }
        val reopened = SyncDatabase.open(context, account)
        assertEquals("retained", reopened.metadata().get("probe"))
        reopened.close()
    }

    @Test
    fun wrongOrMissingWrappingKeyFailsWithoutReplacingDatabase() = runTest {
        val account = UUID.randomUUID().toString()
        val database = SyncDatabase.open(context, account)
        database.metadata().put(SyncMetadata("probe", "retained"))
        val file = File(requireNotNull(database.openHelper.writableDatabase.path))
        database.close()
        val original = file.readBytes()
        val directory = requireNotNull(file.parentFile)
        val wrapped = File(directory, "wrapped-key")
        val originalWrapped = wrapped.readBytes()
        wrapped.writeBytes(originalWrapped.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() })
        assertThrows(KeyRecoveryRequiredException::class.java) { SyncDatabase.open(context, account) }
        assertTrue(original.contentEquals(file.readBytes()))
        wrapped.writeBytes(originalWrapped)
        val reopened = SyncDatabase.open(context, account)
        assertEquals("retained", reopened.metadata().get("probe"))
        reopened.close()
        wrapped.delete()
        assertThrows(KeyRecoveryRequiredException::class.java) { SyncDatabase.open(context, account) }
        assertTrue(file.exists())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry("offline-sync.${directory.name}")
    }
}