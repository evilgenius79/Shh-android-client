package io.github.evilgenius79.shh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * One thing the how-to explains. [keys] are drawn as small key caps in front of [text]: keys of
 * the key bar, keys of the keyboard, or the name of a gesture. Without any, [text] is a note.
 */
internal data class HelpItem(val keys: List<String>, val text: String)

internal data class HelpSection(val title: String, val items: List<HelpItem>)

private fun item(vararg keys: String, text: String) = HelpItem(keys.toList(), text)

private fun note(text: String) = HelpItem(emptyList(), text)

/** Everything the how-to says. Kept as data so that tests can hold it against the real key bar. */
internal val HelpSections: List<HelpSection> = listOf(
    HelpSection(
        "The key bar",
        listOf(
            note("The two rows above the keyboard have the keys a phone keyboard lacks."),
            item(
                "CTRL", "ALT",
                text = "Tap once. The key lights up, applies to the next key you press and then switches itself off. " +
                    "For Ctrl+C, tap CTRL and then C on the keyboard.",
            ),
            item(
                "CTRL", "CTRL",
                text = "Tap twice in a row to lock it. A locked key shows a dot and stays on for every key until you tap it again. " +
                    "ALT works the same way.",
            ),
            item("←", "→", text = "Hold an arrow, PGUP, PGDN or DEL and it repeats, as on a keyboard."),
            item(
                "⋯",
                text = "Opens a panel with F1 to F12, DEL and the characters that are hard to reach on a phone keyboard, " +
                    "such as ~ \\ ` { } [ ]. Tap it again to close the panel.",
            ),
            item("ESC", text = "Leaves insert mode in vi and closes menus in many programs."),
            item("TAB", text = "Completes the command or file name you have started typing."),
            note(
                "Which keys the bar shows, and where, is yours to choose: Settings, Keys of the bar. " +
                    "A key can also type a few characters of your own.",
            ),
            note("The bar can be switched off in Settings, under Extra keys, or set to hide while a hardware keyboard is connected."),
        ),
    ),
    HelpSection(
        "Combinations worth knowing",
        listOf(
            item("CTRL", "C", text = "Stop the program that is running."),
            item("CTRL", "D", text = "End of input. At an empty prompt it logs you out."),
            item("CTRL", "Z", text = "Pause the running program. Type fg to bring it back."),
            item("CTRL", "L", text = "Clear the screen."),
            item("CTRL", "R", text = "Search through the commands you typed before."),
            item("CTRL", "A", text = "Jump to the start of the line. CTRL and then E jumps to the end."),
            item("CTRL", "W", text = "Delete the word before the cursor."),
            item("↑", text = "Bring back the previous command."),
        ),
    ),
    HelpSection(
        "Touch",
        listOf(
            item("Tap", text = "Opens the keyboard. In programs that understand the mouse, such as htop or mc, a tap clicks instead."),
            item(
                "Swipe",
                text = "Scrolls back through earlier output. In full-screen programs such as less, man or vim it scrolls the program.",
            ),
            item("Pinch", text = "Makes the text smaller or larger."),
            item(
                "Hold",
                text = "Selects the word under your finger. Keep the finger down and drag to select more, then choose Copy. " +
                    "When what you selected is a web address, Open link is offered too.",
            ),
            note("The clipboard button at the top of the terminal pastes."),
            note(
                "If voice typing, swipe typing or a keyboard for another script does not work in the terminal, " +
                    "switch on Full keyboard features in Settings.",
            ),
        ),
    ),
    HelpSection(
        "With a hardware keyboard",
        listOf(
            item("Ctrl", "Shift", "C", text = "Copy the selected text."),
            item("Ctrl", "Shift", "V", text = "Paste."),
            note("Every other key goes to the server exactly as typed."),
        ),
    ),
    HelpSection(
        "Sessions",
        listOf(
            note("Sessions stay connected when you leave the app. A notification shows that they are open and can disconnect them all."),
            note("Open another one with New session in the ⋮ menu. With more than one open, tabs appear to switch between them."),
            note(
                "When a connection drops, for example because the phone changed networks, the app reconnects by itself and " +
                    "runs the host's startup command again. With a command such as tmux new -A -s main you are back where you were.",
            ),
            note("When a session ends for another reason, the bar under the terminal says why and offers to reconnect."),
            note("Leaving the shell with exit or with CTRL and then D closes its tab."),
            note(
                "A session you are not looking at that rings the bell sends a notification. " +
                    "End a long command with ; printf '\\a' to be told when it is done.",
            ),
            note("Hold the app's icon on the home screen for shortcuts to the hosts you used last."),
            note("On the host list, typing user@host or user@host:port into the search field connects without saving a host."),
            note(
                "If you have an SSH config file from a computer, Import SSH config in the host list's ⋮ menu adds its hosts, " +
                    "with their jump hosts and port forwards.",
            ),
        ),
    ),
    HelpSection(
        "Logging in with a key",
        listOf(
            note("Create or import a key under Keys, the key button on the host list."),
            note("While you are connected with a password, Install a public key in the ⋮ menu puts the key on the server."),
            note("Then choose the key in the host's settings. From then on no password is asked for."),
            note(
                "If your servers trust a certificate authority, add the certificate it issued for the key: " +
                    "tap the key under Keys and choose Add a certificate.",
            ),
            note(
                "With a FIDO2 security key, the + button under Keys can make a key on it. Logging in then takes the security key: " +
                    "hold it against the back of the phone or plug it in, and touch it when it blinks. " +
                    "This is new and has not been tried with real hardware yet.",
            ),
            note(
                "To use your keys from a server, for ssh or git there, switch on Forward the SSH agent in that host's settings. " +
                    "The keys stay on the phone, and you are asked before one is used.",
            ),
        ),
    ),
    HelpSection(
        "Also in the ⋮ menu of a terminal",
        listOf(
            item("Files", text = "Browse the server's files; upload, download, rename and edit them."),
            item("Port forwards", text = "Reach a service on the server's network through the connection."),
            item("Snippets", text = "Send a command you saved earlier."),
            item("Colours", text = "Change the colour scheme. Settings holds the default, and a host's settings can give that host its own."),
            note("Settings also lets you draw the terminal with a font of your own: any monospaced .ttf or .otf file on the phone."),
        ),
    ),
)

/** The how-to: what the keys of the bar do, the gestures, and where things are. */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar("How to use Shh", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            for (section in HelpSections) {
                SectionHeader(section.title)
                for (entry in section.items) HelpRow(entry)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HelpRow(entry: HelpItem) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (entry.keys.isNotEmpty()) {
            // A fixed column, so that the explanations line up whatever stands in front of them.
            FlowRow(
                Modifier.width(KeysColumn),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // The arrows keep pointing where they point, also in right-to-left languages.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    for (key in entry.keys) KeyLegend(key)
                }
            }
        }
        Text(entry.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private val KeysColumn = 96.dp
