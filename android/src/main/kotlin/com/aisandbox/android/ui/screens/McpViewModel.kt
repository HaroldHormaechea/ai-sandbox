package com.aisandbox.android.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.net.ApiResult
import com.aisandbox.android.net.McpAddRequest
import com.aisandbox.android.net.McpServerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UC-67 — backs the full-screen [McpScreen]. Fetches the session's MCP servers
 * from `GET /v1/sessions/{n}/mcp` on open and on manual refresh (AC3/AC4), and
 * drives the per-server controls via `POST …/{name}/{action}` (AC5/AC6),
 * re-fetching after each action so the listed state reflects the result.
 *
 * <p>Mirrors [ConversationViewModel]'s container access (the model-catalogue fetch
 * pattern): a null profile (not enrolled) or any transport / HTTP failure surfaces
 * as [McpUiState.Error] rather than spinning forever.
 */
class McpViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AiSandboxApplication).container

    private var sessionN: Int = -1

    private val _state = MutableStateFlow<McpUiState>(McpUiState.Loading)
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    /** UC-67 — the server name currently running a control action, for per-row disabling. */
    private val _busyServer = MutableStateFlow<String?>(null)
    val busyServer: StateFlow<String?> = _busyServer.asStateFlow()

    /**
     * UC-67 — a transient, human-readable note from the last control action
     * (notably the login "complete it in the live session" message). The screen
     * shows it as a snackbar and clears it via [consumeActionMessage].
     */
    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    /**
     * UC-82 — one-shot signal that an [add] succeeded, so the screen can close the Add
     * dialog. On a failed add it stays false, so the dialog stays open with the user's
     * input intact (notably a 409 duplicate-name). The screen consumes it via
     * [consumeAddSucceeded].
     */
    private val _addSucceeded = MutableStateFlow(false)
    val addSucceeded: StateFlow<Boolean> = _addSucceeded.asStateFlow()

    /** Bind to session [n] and run the initial fetch. Idempotent across recompositions. */
    fun attach(n: Int) {
        if (sessionN == n && _state.value !is McpUiState.Error) return
        sessionN = n
        refresh()
    }

    /** (Re)fetch the MCP inventory (AC4 — sourced from the server, not a placeholder). */
    fun refresh() {
        if (sessionN < 0) return
        _state.value = McpUiState.Loading
        viewModelScope.launch {
            val profile = container.profileStore.current()
            if (profile == null) {
                _state.value = McpUiState.Error("Not enrolled")
                return@launch
            }
            val result = try {
                container.mcpApi(container.httpClient(profile)).list(sessionN)
            } catch (t: Throwable) {
                _state.value = McpUiState.Error(t.message ?: t.javaClass.simpleName)
                return@launch
            }
            _state.value = when (result) {
                is ApiResult.Success ->
                    if (result.value.isEmpty()) McpUiState.Empty
                    else McpUiState.Loaded(result.value)
                is ApiResult.HttpFailure ->
                    McpUiState.Error(result.detail.ifBlank { result.code })
            }
        }
    }

    /**
     * UC-67 — run a control action (`login` / `reconnect` / `refresh`) against
     * [name], then re-fetch so the row reflects the new state (AC6). The action's
     * message (e.g. the login "finish in the live session" note) is surfaced via
     * [actionMessage].
     */
    fun operate(name: String, action: String) {
        if (sessionN < 0 || _busyServer.value != null) return
        _busyServer.value = name
        viewModelScope.launch {
            val profile = container.profileStore.current()
            if (profile == null) {
                _busyServer.value = null
                _actionMessage.value = "Not enrolled"
                return@launch
            }
            val result = try {
                container.mcpApi(container.httpClient(profile)).operate(sessionN, name, action)
            } catch (t: Throwable) {
                _busyServer.value = null
                _actionMessage.value = t.message ?: t.javaClass.simpleName
                return@launch
            }
            _busyServer.value = null
            _actionMessage.value = when (result) {
                is ApiResult.Success -> result.value.message.ifBlank { "Done." }
                is ApiResult.HttpFailure -> result.detail.ifBlank { result.code }
            }
            // Reflect the resulting state regardless of the message (AC6).
            refresh()
        }
    }

    /**
     * UC-82 — register a new MCP server (AC1), then re-fetch so it appears live (AC3).
     * Reuses [busyServer] (keyed by the new name) for the in-flight gate; the outcome
     * (the server's honest message, or the validation / duplicate / failure detail)
     * surfaces via [actionMessage]. On success only, [addSucceeded] is raised so the
     * screen closes the dialog.
     */
    fun add(body: McpAddRequest) {
        if (sessionN < 0 || _busyServer.value != null) return
        _busyServer.value = body.name
        viewModelScope.launch {
            val profile = container.profileStore.current()
            if (profile == null) {
                _busyServer.value = null
                _actionMessage.value = "Not enrolled"
                return@launch
            }
            val result = try {
                container.mcpApi(container.httpClient(profile)).add(sessionN, body)
            } catch (t: Throwable) {
                _busyServer.value = null
                _actionMessage.value = t.message ?: t.javaClass.simpleName
                return@launch
            }
            _busyServer.value = null
            when (result) {
                is ApiResult.Success -> {
                    _actionMessage.value = result.value.message.ifBlank { "Added." }
                    _addSucceeded.value = true
                    refresh() // AC3 — the new server appears without reopening the screen.
                }
                is ApiResult.HttpFailure -> _actionMessage.value = result.detail.ifBlank { result.code }
            }
        }
    }

    /**
     * UC-82 — deregister an MCP server (AC2), then re-fetch so it disappears live (AC3).
     * The server's response message is honest that this only deregisters (an
     * already-running child keeps running until the next MCP reload); it is shown via
     * [actionMessage]. Reuses [busyServer] for the in-flight gate.
     */
    fun remove(name: String) {
        if (sessionN < 0 || _busyServer.value != null) return
        _busyServer.value = name
        viewModelScope.launch {
            val profile = container.profileStore.current()
            if (profile == null) {
                _busyServer.value = null
                _actionMessage.value = "Not enrolled"
                return@launch
            }
            val result = try {
                container.mcpApi(container.httpClient(profile)).remove(sessionN, name)
            } catch (t: Throwable) {
                _busyServer.value = null
                _actionMessage.value = t.message ?: t.javaClass.simpleName
                return@launch
            }
            _busyServer.value = null
            _actionMessage.value = when (result) {
                is ApiResult.Success -> result.value.message.ifBlank { "Removed." }
                is ApiResult.HttpFailure -> result.detail.ifBlank { result.code }
            }
            if (result is ApiResult.Success) refresh() // AC3 — the row disappears live.
        }
    }

    /** Clear the transient action message after the snackbar has shown it. */
    fun consumeActionMessage() {
        _actionMessage.value = null
    }

    /** Clear the one-shot [addSucceeded] signal after the screen has closed the dialog. */
    fun consumeAddSucceeded() {
        _addSucceeded.value = false
    }
}

/**
 * UC-67 — UI state of the MCP management screen.
 *
 * - [Loading] — the inventory fetch is in flight.
 * - [Loaded] — one or more MCP servers (with their states).
 * - [Empty] — the session has no MCP servers (AC7).
 * - [Error] — not-enrolled / transport / HTTP failure, with a display [message].
 */
sealed interface McpUiState {
    data object Loading : McpUiState
    data class Loaded(val servers: List<McpServerInfo>) : McpUiState
    data object Empty : McpUiState
    data class Error(val message: String) : McpUiState
}

/**
 * UC-82 — pure builder that turns the Add-dialog's raw text fields into a transport-
 * correct [McpAddRequest]. Kept top-level (not inside the composable) so it is
 * unit-testable without instrumentation. It trims every field and folds blank optionals
 * to null (so they are omitted from the wire body), then:
 *
 * - **stdio** → keeps [command] + whitespace-separated [argsText] as `args` + `K=V` lines
 *   from [envText] as `env`; ignores url/headers.
 * - **http / sse** → keeps [url] + `Header: value` lines from [headersText] as `headers`;
 *   ignores command/args/env.
 *
 * No shell escaping or quoting happens here: every value travels to the server as plain
 * data and lands as a discrete argv element server-side, so a value like `; rm -rf` or
 * `$(touch x)` is inert (AC4). The server enforces the authoritative validation (AC6).
 */
internal fun buildMcpAddRequest(
    name: String,
    transport: String,
    command: String,
    argsText: String,
    url: String,
    envText: String,
    headersText: String,
): McpAddRequest {
    val t = transport.trim().lowercase()
    return if (t == "stdio") {
        McpAddRequest(
            name = name.trim(),
            transport = "stdio",
            command = command.trim().ifBlank { null },
            args = argsText.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null },
            env = parseEnvLines(envText),
        )
    } else {
        McpAddRequest(
            name = name.trim(),
            transport = t,
            url = url.trim().ifBlank { null },
            headers = headersText.lines().map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null },
        )
    }
}

/** Parse `K=V` lines (one per line) into an env map; blank / `=`-less lines are dropped. */
private fun parseEnvLines(envText: String): Map<String, String>? {
    val map = envText.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.contains('=') }
        .associate { line ->
            val idx = line.indexOf('=')
            line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
    return map.ifEmpty { null }
}
