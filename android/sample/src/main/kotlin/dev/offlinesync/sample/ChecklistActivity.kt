package dev.offlinesync.sample

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkManager
import dev.offlinesync.android.LocalDocument
import dev.offlinesync.android.LocalSyncStore
import dev.offlinesync.android.MutationQueue
import dev.offlinesync.android.QueueState
import dev.offlinesync.android.QueuedMutation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

class ChecklistActivity : ComponentActivity() {
    private val app get() = application as ChecklistApplication
    private var repository: ChecklistRepository? = null
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var input: EditText
    private val ink = Color.rgb(27, 42, 40)
    private val muted = Color.rgb(94, 111, 107)
    private val teal = Color.rgb(8, 127, 114)
    private val coral = Color.rgb(190, 66, 62)
    private var entries: List<QueuedMutation> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column().apply { setBackgroundColor(Color.rgb(243, 246, 245)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val heading = row().apply { setPadding(dp(20), dp(18), dp(12), dp(8)) }
        heading.addView(label("Fieldlist", 32).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) },
            LinearLayout.LayoutParams(0, dp(52), 1f))
        heading.addView(icon(android.R.drawable.ic_popup_sync, "Sync now") { sync() })
        heading.addView(icon(android.R.drawable.ic_menu_preferences, "Connection settings") { settings() })
        root.addView(heading)
        status = label("Opening local workspace", 14).apply { setPadding(dp(20), 0, dp(20), dp(16)) }
        root.addView(status)
        val composer = row().apply { setPadding(dp(16), dp(4), dp(16), dp(16)) }
        input = EditText(this).apply {
            hint = "New item"
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(500))
            setTextColor(ink)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> addItem(); true }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, dp(52), 1f))
        composer.addView(icon(android.R.drawable.ic_input_add, "Add item") { addItem() })
        root.addView(composer)
        val section = row().apply { setPadding(dp(20), dp(12), dp(20), dp(12)); setBackgroundColor(Color.WHITE) }
        section.addView(label("CHECKLIST", 12).apply { setTextColor(muted) }, LinearLayout.LayoutParams(0, dp(24), 1f))
        count = label("0 items", 12)
        section.addView(count)
        root.addView(section)
        list = column().apply { setBackgroundColor(Color.WHITE); setPadding(dp(16), 0, dp(16), dp(32)) }
        root.addView(ScrollView(this).apply { addView(list); isFillViewport = true },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        lifecycleScope.launch {
            try {
                val store = LocalSyncStore(app.database())
                repository = ChecklistRepository(store) { app.schedule() }
                app.schedule()
                WorkManager.getInstance(this@ChecklistActivity).getWorkInfosByTagLiveData("offline-sync:${app.scopeId}")
                    .observe(this@ChecklistActivity) { lifecycleScope.launch { refreshStatus() } }
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    store.documents.combine(MutationQueue(app.database()).entries) { documents, queue -> documents to queue }
                        .collect { (documents, queue) ->
                            entries = queue
                            render(documents.filterNot { it.state.deleted })
                            refreshStatus()
                        }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                status.text = "Local workspace needs recovery"
                status.setTextColor(coral)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { safely { app.schedule() } }
    }

    private fun render(documents: List<LocalDocument>) {
        list.removeAllViews()
        val ordered = documents.sortedWith(compareBy<LocalDocument> {
            it.state.registers["completed"]?.value?.jsonPrimitive?.booleanOrNull ?: false
        }.thenBy { it.state.registers["title"]?.value?.jsonPrimitive?.content ?: "" }.thenBy { it.entityId })
        count.text = "${documents.size} items"
        if (ordered.isEmpty()) list.addView(label("Nothing on the list", 20).apply {
            gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(64), 0, dp(64))
        })
        for (document in ordered) {
            val entity = document.entityId
            val title = document.state.registers["title"]?.value?.jsonPrimitive?.content ?: "Untitled"
            val completed = document.state.registers["completed"]?.value?.jsonPrimitive?.booleanOrNull ?: false
            val item = column().apply { setPadding(0, dp(12), 0, dp(12)) }
            item.addView(CheckBox(this).apply {
                text = title
                textSize = 18f
                setTextColor(if (completed) muted else ink)
                buttonTintList = ColorStateList.valueOf(teal)
                isChecked = completed
                minHeight = dp(48)
                setOnCheckedChangeListener { _, checked -> edit { complete(entity, checked) } }
            })
            val controls = row()
            controls.addView(stepper("-", "Decrease quantity for $title") { edit { quantity(entity, -1) } })
            controls.addView(label((document.state.counters["quantity"]?.value ?: 0).toString(), 16).apply {
                gravity = Gravity.CENTER; contentDescription = "Quantity for $title"
            }, LinearLayout.LayoutParams(dp(56), dp(48)))
            controls.addView(stepper("+", "Increase quantity for $title") { edit { quantity(entity, 1) } })
            controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            controls.addView(icon(android.R.drawable.ic_menu_edit, "Rename $title") {
                textDialog("Rename item", "Title", title, 500) { rename(entity, it) }
            })
            controls.addView(icon(android.R.drawable.ic_menu_delete, "Delete $title", coral) {
                AlertDialog.Builder(this).setTitle("Delete $title?").setMessage("Deletion cannot be undone.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> edit { delete(entity) } }.show()
            })
            item.addView(controls)
            val tags = document.state.sets["tags"]?.values?.sorted() ?: emptyList()
            val tagRow = row()
            tagRow.addView(label(if (tags.isEmpty()) "No tags" else tags.joinToString("  /  "), 13).apply {
                setTextColor(muted)
                minHeight = dp(48)
                if (tags.isNotEmpty()) {
                    contentDescription = "Remove tag from $title"
                    setOnClickListener {
                        AlertDialog.Builder(this@ChecklistActivity).setTitle("Remove tag")
                            .setItems(tags.toTypedArray()) { _, index -> edit { removeTag(entity, tags[index]) } }.show()
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tagRow.addView(icon(android.R.drawable.ic_input_add, "Add tag to $title") {
                textDialog("Add tag", "Tag", "", 100) { addTag(entity, it) }
            })
            item.addView(tagRow)
            val state = entries.firstOrNull { it.mutation.entityId == entity && it.state != QueueState.SYNCED }
            item.addView(label(when (state?.state) {
                QueueState.PENDING -> "Pending"
                QueueState.IN_FLIGHT -> "Sending"
                QueueState.CONFLICT -> "Conflict: ${state.errorCode ?: "ACTION_REQUIRED"}"
                QueueState.REJECTED -> "Rejected: ${state.errorCode ?: "ACTION_REQUIRED"}"
                else -> "Synced"
            }, 12).apply { setTextColor(if (state?.state in listOf(QueueState.REJECTED, QueueState.CONFLICT)) coral else muted) })
            list.addView(item)
            list.addView(View(this).apply { setBackgroundColor(Color.rgb(224, 233, 230)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
    }

    private suspend fun refreshStatus() {
        try {
            val queue = MutationQueue(app.database())
            val pending = entries.count { it.state == QueueState.PENDING || it.state == QueueState.IN_FLIGHT }
            val terminal = entries.count { it.state == QueueState.REJECTED || it.state == QueueState.CONFLICT }
            status.text = when {
                app.connection()?.token.isNullOrEmpty() -> "Local workspace  |  $pending pending"
                queue.isAuthenticationPaused() -> "Credentials required  |  $pending pending"
                queue.isBackgroundSyncPaused() -> "Sync needs attention  |  $pending pending"
                terminal > 0 -> "$terminal need attention  |  $pending pending"
                pending > 0 -> "$pending pending"
                else -> "Up to date locally"
            }
            status.setTextColor(if (terminal > 0 || queue.isBackgroundSyncPaused()) coral else muted)
        } catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { status.text = "Connection storage needs recovery"; status.setTextColor(coral) }
    }

    private fun addItem() {
        val title = input.text.toString().trim()
        if (title.isEmpty()) { input.error = "Enter an item title"; return }
        edit { create(title); input.text.clear() }
    }

    private fun edit(action: suspend ChecklistRepository.() -> Unit) {
        val repository = repository ?: return
        lifecycleScope.launch { safely { repository.action() } }
    }

    private suspend fun safely(action: suspend () -> Unit) {
        try { action() }
        catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { Toast.makeText(this, "Could not finish. Check the item or connection settings.", Toast.LENGTH_LONG).show() }
    }

    private fun sync() {
        lifecycleScope.launch {
            safely {
                val queue = MutationQueue(app.database())
                if (app.connection()?.token.isNullOrEmpty() || queue.isAuthenticationPaused()) settings()
                else if (queue.isBackgroundSyncPaused()) {
                    AlertDialog.Builder(this@ChecklistActivity).setTitle("Resume sync?")
                        .setMessage("Confirm the connection issue has been resolved.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Resume") { _, _ ->
                            lifecycleScope.launch { safely { queue.resumeBackgroundSync(); app.schedule(); refreshStatus() } }
                        }.show()
                } else app.schedule()
            }
        }
    }

    private fun textDialog(title: String, hint: String, current: String, maximum: Int,
        action: suspend ChecklistRepository.(String) -> Unit) {
        val field = EditText(this).apply {
            this.hint = hint; setText(current); filters = arrayOf(InputFilter.LengthFilter(maximum))
            setSelectAllOnFocus(true)
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(field)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (field.text.isBlank()) field.error = "Enter a value"
                else edit { action(field.text.toString()); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun settings() {
        lifecycleScope.launch {
            safely {
                val replica = LocalSyncStore(app.database()).replicaId()
                val previous = app.connection()
                val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
                fields.addView(label("DEVICE REPLICA", 12))
                val identity = row()
                identity.addView(label(replica, 13).apply { setTextIsSelectable(true) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                identity.addView(icon(android.R.drawable.ic_menu_set_as, "Copy replica ID") {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Replica ID", replica))
                    Toast.makeText(this@ChecklistActivity, "Replica ID copied", Toast.LENGTH_SHORT).show()
                })
                fields.addView(identity)
                fields.addView(label("Server origin", 14))
                val origin = EditText(this@ChecklistActivity).apply {
                    hint = "https://sync.example.com"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setText(previous?.origin ?: ""); setSingleLine(true)
                }
                fields.addView(origin)
                fields.addView(label("Access token", 14))
                val token = EditText(this@ChecklistActivity).apply {
                    hint = "JWT"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    isSaveEnabled = false
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    filters = arrayOf(InputFilter.LengthFilter(16_384))
                }
                fields.addView(token)
                val error = label("", 13).apply { setTextColor(coral) }
                fields.addView(error)
                val dialog = AlertDialog.Builder(this@ChecklistActivity).setTitle("Connection")
                    .setView(ScrollView(this@ChecklistActivity).apply { addView(fields) })
                    .setNegativeButton("Cancel", null).setNeutralButton("Disconnect") { _, _ ->
                        lifecycleScope.launch { safely { app.disconnect(); refreshStatus() } }
                    }.setPositiveButton("Connect", null).create()
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                dialog.setOnShowListener {
                    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        lifecycleScope.launch {
                            try {
                                app.connect(origin.text.toString(), token.text.toString().trim())
                                token.text.clear(); dialog.dismiss(); refreshStatus()
                            } catch (failure: CancellationException) { throw failure }
                            catch (_: Exception) {
                                error.text = "Connection failed. Check the origin, JWT claims, and device replica."
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            }
                        }
                    }
                }
                dialog.setOnDismissListener { token.text.clear() }
                dialog.show()
            }
        }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(value: String, size: Int) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(ink); gravity = Gravity.CENTER_VERTICAL
    }
    private fun icon(resource: Int, description: String, tint: Int = teal, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(resource); imageTintList = ColorStateList.valueOf(tint)
        contentDescription = description; tooltipText = description
        background = getDrawable(android.R.drawable.list_selector_background)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setPadding(dp(12), dp(12), dp(12), dp(12)); setOnClickListener { action() }
    }
    private fun stepper(value: String, description: String, action: () -> Unit) = label(value, 24).apply {
        gravity = Gravity.CENTER; setTextColor(teal); contentDescription = description; tooltipText = description
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        background = getDrawable(android.R.drawable.list_selector_background)
        isFocusable = true; setOnClickListener { action() }
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}