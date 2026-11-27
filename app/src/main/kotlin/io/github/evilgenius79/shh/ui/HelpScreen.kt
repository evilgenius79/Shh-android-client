package io.github.evilgenius79.shh.ui

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.evilgenius79.shh.R

/**
 * What stands in a key cap in front of an explanation: a key by its legend, which is the same in
 * every language, or a word that is translated, such as the name of a gesture or of a menu entry.
 */
internal sealed interface HelpCap {
    data class Key(val legend: String) : HelpCap

    data class Word(@StringRes val text: Int) : HelpCap
}

/**
 * One thing the how-to explains. [caps] are drawn as small key caps in front of [text]: keys of
 * the key bar, keys of the keyboard, or the name of a gesture. Without any, [text] is a note.
 */
internal data class HelpItem(val caps: List<HelpCap>, @StringRes val text: Int) {
    /** The legends of the keys among [caps]: what can be held against the keys the bar has. */
    val keys: List<String> get() = caps.filterIsInstance<HelpCap.Key>().map { it.legend }
}

internal data class HelpSection(@StringRes val title: Int, val items: List<HelpItem>)

private fun item(vararg keys: String, @StringRes text: Int) = HelpItem(keys.map { HelpCap.Key(it) }, text)

private fun item(vararg caps: HelpCap, @StringRes text: Int) = HelpItem(caps.toList(), text)

private fun note(@StringRes text: Int) = HelpItem(emptyList(), text)

/**
 * Everything the how-to says. Kept as data so that tests can hold it against the real key bar.
 * The texts are in strings_keybar_help.xml, in this order.
 */
internal val HelpSections: List<HelpSection> = listOf(
    HelpSection(
        R.string.help_bar_title,
        listOf(
            note(R.string.help_bar_intro),
            item("CTRL", "ALT", text = R.string.help_bar_modifier_once),
            item("CTRL", "CTRL", text = R.string.help_bar_modifier_lock),
            item("←", "→", text = R.string.help_bar_repeat),
            item("⋯", text = R.string.help_bar_more),
            item("ESC", text = R.string.help_bar_esc),
            item("TAB", text = R.string.help_bar_tab),
            note(R.string.help_bar_arrange),
            note(R.string.help_bar_hide),
        ),
    ),
    HelpSection(
        R.string.help_combos_title,
        listOf(
            item("CTRL", "C", text = R.string.help_combos_ctrl_c),
            item("CTRL", "D", text = R.string.help_combos_ctrl_d),
            item("CTRL", "Z", text = R.string.help_combos_ctrl_z),
            item("CTRL", "L", text = R.string.help_combos_ctrl_l),
            item("CTRL", "R", text = R.string.help_combos_ctrl_r),
            item("CTRL", "A", text = R.string.help_combos_ctrl_a),
            item("CTRL", "W", text = R.string.help_combos_ctrl_w),
            item("↑", text = R.string.help_combos_up),
        ),
    ),
    HelpSection(
        R.string.help_touch_title,
        listOf(
            item(HelpCap.Word(R.string.help_cap_tap), text = R.string.help_touch_tap),
            item(HelpCap.Word(R.string.help_cap_swipe), text = R.string.help_touch_swipe),
            item(HelpCap.Word(R.string.help_cap_pinch), text = R.string.help_touch_pinch),
            item(HelpCap.Word(R.string.help_cap_hold), text = R.string.help_touch_hold),
            note(R.string.help_touch_paste),
            note(R.string.help_touch_full_keyboard),
        ),
    ),
    HelpSection(
        R.string.help_keyboard_title,
        listOf(
            // What a keyboard prints on these two keys differs from country to country, unlike the legends of the bar.
            item(HelpCap.Word(R.string.help_cap_ctrl), HelpCap.Word(R.string.help_cap_shift), HelpCap.Key("C"), text = R.string.help_keyboard_copy),
            item(HelpCap.Word(R.string.help_cap_ctrl), HelpCap.Word(R.string.help_cap_shift), HelpCap.Key("V"), text = R.string.help_keyboard_paste),
            note(R.string.help_keyboard_other_keys),
        ),
    ),
    HelpSection(
        R.string.help_sessions_title,
        listOf(
            note(R.string.help_sessions_background),
            note(R.string.help_sessions_new),
            note(R.string.help_sessions_reconnect),
            note(R.string.help_sessions_ended),
            note(R.string.help_sessions_exit),
            note(R.string.help_sessions_bell),
            note(R.string.help_sessions_shortcuts),
            note(R.string.help_sessions_quick_connect),
            note(R.string.help_sessions_import_config),
        ),
    ),
    HelpSection(
        R.string.help_login_title,
        listOf(
            note(R.string.help_login_create),
            note(R.string.help_login_install),
            note(R.string.help_login_choose),
            note(R.string.help_login_certificate),
            note(R.string.help_login_security_key),
            note(R.string.help_login_agent),
        ),
    ),
    HelpSection(
        R.string.help_menu_title,
        listOf(
            item(HelpCap.Word(R.string.help_cap_files), text = R.string.help_menu_files),
            item(HelpCap.Word(R.string.help_cap_port_forwards), text = R.string.help_menu_port_forwards),
            item(HelpCap.Word(R.string.help_cap_snippets), text = R.string.help_menu_snippets),
            item(HelpCap.Word(R.string.help_cap_colours), text = R.string.help_menu_colours),
            note(R.string.help_menu_font),
        ),
    ),
)

/** The how-to: what the keys of the bar do, the gestures, and where things are. */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar(stringResource(R.string.help_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            for (section in HelpSections) {
                SectionHeader(stringResource(section.title))
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
        if (entry.caps.isNotEmpty()) {
            // A fixed column, so that the explanations line up whatever stands in front of them.
            FlowRow(
                Modifier.width(KeysColumn),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // The arrows keep pointing where they point, also in right-to-left languages.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    for (cap in entry.caps) {
                        when (cap) {
                            is HelpCap.Key -> KeyLegend(cap.legend)
                            is HelpCap.Word -> KeyLegend(stringResource(cap.text), word = true)
                        }
                    }
                }
            }
        }
        Text(stringResource(entry.text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private val KeysColumn = 96.dp
