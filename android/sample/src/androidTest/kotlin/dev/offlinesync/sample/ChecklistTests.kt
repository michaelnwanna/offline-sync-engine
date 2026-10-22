package dev.offlinesync.sample

import android.content.Context
import android.util.Base64
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinesync.android.LocalSyncStore
import dev.offlinesync.android.MutationQueue
import dev.offlinesync.android.SyncDatabase
import dev.offlinesync.core.DocumentDelta
import dev.offlinesync.core.PnCounter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.coroutines.resume

@RunWith(AndroidJUnit4::class)
class ChecklistTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun checklistMutationsPersistAndUseOwnedCrdtDeltas() = runTest {
        val account = UUID.randomUUID().toString()
        val database = SyncDatabase.open(context, account)
        var schedules = 0
        val store = LocalSyncStore(database)
        val repository = ChecklistRepository(store) { schedules++ }
        repository.create("  Oats  ")
        val entity = store.documents.first().single().entityId
        repository.complete(entity, true)
        repository.quantity(entity, 1)
        repository.quantity(entity, -1)
        repository.addTag(entity, "pantry")
        val tagMutation = store.pendingMutations().last()
        assertEquals(mapOf(tagMutation.mutationId to "pantry"), tagMutation.delta.sets.getValue("tags").adds)
        repository.removeTag(entity, "pantry")
        repository.rename(entity, "Rolled oats")
        assertEquals(7, schedules)
        database.close()
        val reopened = SyncDatabase.open(context, account)
        try {
            val recovered = LocalSyncStore(reopened)
            val state = recovered.documents.first().single().state
            assertEquals(JsonPrimitive("Rolled oats"), state.registers.getValue("title").value)
            assertEquals(JsonPrimitive(true), state.registers.getValue("completed").value)
            assertEquals(1L, state.counters.getValue("quantity").value)
            assertTrue(state.sets.getValue("tags").values.isEmpty())
            ChecklistRepository(recovered) {}.delete(entity)
            assertTrue(recovered.documents.first().single().state.deleted)
            assertEquals(8, MutationQueue(reopened).entries.first().size)
        } finally { reopened.close() }
    }

    @Test
    fun counterEditDoesNotResendOtherReplicaComponents() = runTest {
        val database = SyncDatabase.open(context, UUID.randomUUID().toString())
        try {
            val store = LocalSyncStore(database)
            val entity = UUID.randomUUID().toString()
            val other = UUID.randomUUID().toString()
            dev.offlinesync.android.SyncResponseStore(database).applyPull(null,
                dev.offlinesync.core.PullResponse(listOf(dev.offlinesync.core.SyncChange(1, entity,
                    DocumentDelta(counters = mapOf("quantity" to PnCounter(mapOf(other to 7)))))), "page", false))
            ChecklistRepository(store) {}.quantity(entity, 1)
            val mutation = store.pendingMutations().single()
            assertEquals(setOf(mutation.replicaId), mutation.delta.counters.getValue("quantity").positive.keys)
            assertEquals(8L, store.documents.first().single().state.counters.getValue("quantity").value)
        } finally { database.close() }
    }

    @Test
    fun vaultEncryptsCredentialsAndDisconnectedScopeCannotOpenWorker() = runTest {
        val app = ApplicationProvider.getApplicationContext<ChecklistApplication>()
        val replica = LocalSyncStore(app.database()).replicaId()
        val workspace = UUID.randomUUID().toString()
        val claims = JSONObject().put("replica", replica).put("workspace", workspace)
            .put("iss", "test-issuer").put("sub", "test-user")
        fun token() = "e30." + Base64.encodeToString(claims.toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".test-signature"
        try {
            app.disconnect()
            val vault = ConnectionVault(context, app.scopeId)
            val original = vault.read()
            val binding = original?.binding
            if (binding == null) {
                app.connect("https://example.invalid/", token())
                val stored = vault.read()!!
                assertEquals(token(), stored.token)
                val bytes = java.io.File(context.noBackupFilesDir, "sample-connection").readBytes()
                assertFalse(String(bytes, Charsets.ISO_8859_1).contains(stored.token))
                claims.put("workspace", UUID.randomUUID().toString())
                assertTrue(runCatching { app.connect("https://example.invalid/", token()) }.isFailure)
            }
            app.disconnect()
            assertTrue(app.connection()?.token.isNullOrEmpty())
            assertEquals(null, app.openSyncSession(app.scopeId))
        } finally { app.disconnect() }
    }

    @Test
    fun nativeChecklistCreatesCompletesAndSurvivesActivityRecreation() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ChecklistApplication>()
        app.disconnect()
        val title = "Test oats ${UUID.randomUUID().toString().take(6)}"
        val store = LocalSyncStore(app.database())
        ActivityScenario.launch(ChecklistActivity::class.java).use { scenario ->
            withTimeout(15_000) { store.documents.first() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                fun find(view: android.view.View): EditText? {
                    if (view is EditText && view.hint == "New item") return view
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                        find(view.getChildAt(index))?.let { return it }
                    }
                    return null
                }
                find(activity.window.decorView)!!.setText(title)
            }
            onView(withContentDescription("Add item")).perform(click())
            val document = withTimeout(15_000) { store.documents.first { rows ->
                rows.any { it.state.registers["title"]?.value == JsonPrimitive(title) }
            } }.first { it.state.registers["title"]?.value == JsonPrimitive(title) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onView(withText(title)).check(matches(isDisplayed())).perform(click())
            withTimeout(15_000) { store.documents.first { rows -> rows.any {
                it.entityId == document.entityId && it.state.registers["completed"]?.value == JsonPrimitive(true)
            } } }
            onView(withContentDescription("Increase quantity for $title")).perform(click())
            withTimeout(15_000) { store.documents.first { rows -> rows.any {
                it.entityId == document.entityId && it.state.counters["quantity"]?.value == 2L
            } } }
            scenario.recreate()
            withTimeout(15_000) {
                suspendCancellableCoroutine { continuation ->
                    scenario.onActivity { activity ->
                        val root = activity.window.decorView
                        fun containsTitle(view: android.view.View): Boolean {
                            if (view is android.widget.CheckBox && view.text.toString() == title) return true
                            return view is android.view.ViewGroup && (0 until view.childCount).any {
                                containsTitle(view.getChildAt(it))
                            }
                        }
                        lateinit var listener: android.view.ViewTreeObserver.OnGlobalLayoutListener
                        listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                            if (containsTitle(root)) {
                                root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                        }
                        if (containsTitle(root)) continuation.resume(Unit)
                        else root.viewTreeObserver.addOnGlobalLayoutListener(listener)
                    }
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onView(withText(title)).check(matches(isDisplayed()))
            ChecklistRepository(store) {}.delete(document.entityId)
        }
    }
}