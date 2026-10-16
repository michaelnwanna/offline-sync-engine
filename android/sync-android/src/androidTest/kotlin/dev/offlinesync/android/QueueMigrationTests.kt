package dev.offlinesync.android

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinesync.core.DocumentDelta
import dev.offlinesync.core.Mutation
import dev.offlinesync.core.WireJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QueueMigrationTests {
    @Test
    fun versionOneEncryptedDatabaseMigratesWithoutChangingPendingPayload() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = UUID.randomUUID().toString()
        val empty = SyncDatabase.open(context, account)
        val file = File(requireNotNull(empty.openHelper.writableDatabase.path))
        empty.close()
        check(file.delete())
        val directory = requireNotNull(file.parentFile)
        val passphrase = DatabaseKeyStore(directory, "offline-sync.${directory.name}").loadOrCreate(false)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("dev.offlinesync.android.SyncDatabase/1.json").bufferedReader().use { JSONObject(it.readText()) }
            .getJSONObject("database")
        val mutation = Mutation(1, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1,
            UUID.randomUUID().toString(), DocumentDelta(deleted = true))
        val payload = WireJson.format.encodeToString(mutation)
        val helper = SupportOpenHelperFactory(passphrase).create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (index in 0 until entities.length()) {
                        val entity = entities.getJSONObject(index)
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                        val indices = entity.optJSONArray("indices")
                        for (position in 0 until (indices?.length() ?: 0)) {
                            requireNotNull(indices)
                            db.execSQL(indices.getJSONObject(position).getString("createSql")
                                .replace("\${TABLE_NAME}", entity.getString("tableName")))
                        }
                    }
                    val queries = schema.getJSONArray("setupQueries")
                    for (index in 0 until queries.length()) db.execSQL(queries.getString(index))
                    db.execSQL("INSERT INTO sync_outbox (mutationId, entityId, localSequence, payload, state) VALUES (?, ?, ?, ?, ?)",
                        arrayOf<Any>(mutation.mutationId, mutation.entityId, 1L, payload, "PENDING"))
                    db.execSQL("INSERT INTO sync_metadata (name, value) VALUES ('replicaId', ?)", arrayOf(mutation.replicaId))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }).build())
        helper.writableDatabase
        helper.close()
        passphrase.fill(0)
        val upgraded = SyncDatabase.open(context, account)
        try {
            assertEquals(2, upgraded.openHelper.writableDatabase.version)
            val entry = MutationQueue(upgraded, { 100 }).entries.first().single()
            assertEquals(mutation, entry.mutation)
            assertEquals(0L, entry.attempts)
            assertEquals(payload, upgraded.outbox().pending(100).single().payload)
            assertEquals(mutation, MutationQueue(upgraded, { 100 }).claim().single().mutation)
        } finally {
            upgraded.close()
        }
    }
}