package com.ctfly.app.ui.staff.history

import androidx.compose.runtime.*
import com.ctfly.app.database.remote.obj
import com.ctfly.app.database.remote.rows
import com.ctfly.app.database.remote.text
import com.ctfly.app.state.AppViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Each query owns its cursor. Changing a filter cancels the previous request. */
class StaffRecordState {
    var rows by mutableStateOf<List<JSONObject>>(emptyList())
        internal set

    var loading by mutableStateOf(true)
        internal set

    var error by mutableStateOf("")
        internal set

    var hasMore by mutableStateOf(false)
        internal set

    internal var before: String? = null
    internal var beforeKey: String? = null
    internal var revision by mutableIntStateOf(0)
    internal var job: Job? = null
    internal var fetch: (Boolean) -> Unit = {}

    fun reload() {
        revision++
    }

    fun loadMore() {
        if (!loading && hasMore) fetch(true)
    }
}

@Composable
fun rememberStaffRecords(
    vm: AppViewModel,
    module: String,
    category: String = "All",
    changeKey: String = "",
    dayKey: String = "${module}HistoryDay",
    kind: String = "All",
): StaffRecordState {
    val day = vm.field(dayKey)
    val search = vm.field("${module}HistorySearch").trim()
    val owner = vm.userId
    val state = remember(owner, module, category, day, search, kind) { StaffRecordState() }
    val scope = rememberCoroutineScope()
    state.fetch = { append ->
        state.job?.cancel()
        state.job =
            scope.launch {
                state.loading = true
                state.error = ""
                try {
                    if (!append) delay(250)
                    val result =
                        vm.staffHistory(
                            obj(
                                    "p_module" to module,
                                    "p_day" to day.ifBlank { null },
                                    "p_category" to category,
                                    "p_search" to
                                        if (
                                            module == "Transactions" &&
                                                search.startsWith("CTFLY-", ignoreCase = true)
                                        )
                                            search.substringAfter('-')
                                        else search,
                                    "p_before" to if (append) state.before else null,
                                    "p_before_key" to if (append) state.beforeKey else null,
                                )
                                .apply { if (module == "Transactions") put("p_kind", kind) }
                        )
                    state.rows =
                        ((if (append) state.rows else emptyList()) + result.rows("items"))
                            .distinctBy { it.text("history_key") }
                    state.hasMore = result.optBoolean("has_more")
                    state.before = result.text("next_before").ifBlank { null }
                    state.beforeKey = result.text("next_key").ifBlank { null }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    state.error =
                        "History could not be loaded. Check your connection and try again."
                } finally {
                    state.loading = false
                }
            }
    }
    LaunchedEffect(state, state.revision, changeKey) { state.fetch(false) }
    DisposableEffect(state) { onDispose { state.job?.cancel() } }
    return state
}
