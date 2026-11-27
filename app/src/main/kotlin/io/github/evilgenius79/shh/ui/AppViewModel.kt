package io.github.evilgenius79.shh.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** The places the app can show. */
sealed interface Screen {
    data object Hosts : Screen

    /** Editing [hostId], or a new host when null. [template] pre-fills a new host (duplicate). */
    data class HostEdit(val hostId: String?, val templateId: String? = null) : Screen

    data object Terminal : Screen

    data class Files(val sessionId: String) : Screen

    data object Keys : Screen

    data object KnownHosts : Screen

    data object Snippets : Screen

    data object Settings : Screen

    data object Help : Screen

    data object About : Screen
}

/** Navigation state that has to survive rotation. */
class AppViewModel : ViewModel() {
    private val stack = mutableStateListOf<Screen>(Screen.Hosts)

    val current: Screen get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    /** The session shown on the terminal screen. */
    var activeSessionId: String? by mutableStateOf(null)

    /** Set once the user got past the app lock for this run of the app. */
    var unlocked: Boolean by mutableStateOf(false)

    fun open(screen: Screen) {
        // Going to a screen that is already on the stack returns to it instead of stacking a copy.
        val existing = stack.indexOf(screen)
        if (existing >= 0) {
            while (stack.size > existing + 1) stack.removeAt(stack.size - 1)
        } else {
            stack.add(screen)
        }
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.size - 1)
    }

    /** Shows the terminal for [sessionId]. */
    fun showSession(sessionId: String) {
        activeSessionId = sessionId
        open(Screen.Terminal)
    }
}
