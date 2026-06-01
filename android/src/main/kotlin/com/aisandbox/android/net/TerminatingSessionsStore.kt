package com.aisandbox.android.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UC-28 process-scoped holder of the set of session numbers the operator has
 * optimistically marked as terminating (a delete was confirmed for them but
 * the server has not yet resolved the teardown).
 *
 * <p>Lives on [com.aisandbox.android.AppContainer] — process-scoped, NOT
 * tied to any [androidx.navigation.NavBackStackEntry]-scoped ViewModel — so
 * the optimistic flag survives back-navigation between the sessions list and
 * the terminal screen (and is observable from both). The set is the
 * client-side half of the "terminating" presentation; the server's
 * authoritative `terminating` status is the other half, and the union of the
 * two drives the pill (see [com.aisandbox.android.ui.screens.SessionsUiState]).
 *
 * <p>Backed by an immutable-snapshot [MutableStateFlow] so Compose collectors
 * recompose on every mutation and reads are always a consistent set.
 *
 * <p>[clearAll] is invoked on a server-profile switch (re-enrollment / wipe):
 * a stale optimistic `n` from one server must never leak onto the session
 * list of a different server.
 */
class TerminatingSessionsStore {

    private val _flow = MutableStateFlow<Set<Int>>(emptySet())

    /** Read-only view of the currently optimistically-terminating session numbers. */
    val flow: StateFlow<Set<Int>> = _flow.asStateFlow()

    /** Mark session [n] terminating (the operator just confirmed its delete). */
    fun mark(n: Int) {
        _flow.value = _flow.value + n
    }

    /** Clear the optimistic flag for a single session [n] (delete resolved). */
    fun clear(n: Int) {
        _flow.value = _flow.value - n
    }

    /** Drop every optimistic flag — called on a server-profile switch. */
    fun clearAll() {
        if (_flow.value.isNotEmpty()) {
            _flow.value = emptySet()
        }
    }
}
