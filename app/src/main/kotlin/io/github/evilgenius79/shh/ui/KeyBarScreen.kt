@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.evilgenius79.shh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.maxLengthTrim
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.evilgenius79.shh.R

/**
 * Where the user arranges the key bar: pick a place on the bar, then the key that belongs there.
 * [layout] is what the settings hold (empty for the standard keys) and [onChange] stores a new one.
 */
@Composable
fun KeyBarScreen(layout: List<String>, onChange: (List<String>) -> Unit, onBack: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val keys = KeyBarLayout.of(layout)
    val own = rememberTextFieldState()

    // The keys below are not drawn again when the arrangement changes, so what a tap does must
    // not be tied to the arrangement as it was when they were drawn.
    val current by rememberUpdatedState(layout)
    val change by rememberUpdatedState(onChange)
    val put: (String) -> Unit = remember {
        { name ->
            change(KeyBarLayout.with(current, selected, name))
            // On to the next place, so that a row is filled by tapping its keys one after the other.
            selected = (selected + 1) % KeyBarLayout.SLOTS
        }
    }

    Scaffold(
        topBar = {
            BackTopBar(stringResource(R.string.keybar_title), onBack) {
                TextButton(enabled = layout.isNotEmpty(), onClick = { onChange(emptyList()) }) { Text(stringResource(R.string.keybar_reset)) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.keybar_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            KeyBarPreview(layout, selected, onSelect = { selected = it })
            Note(stringResource(if (KeyBarLayout.MORE in keys) R.string.keybar_note_wide else R.string.keybar_note_no_panel_key))

            SectionHeader(stringResource(R.string.keybar_section_keys))
            Palette(KeyBarLayout.NAMED, put)
            SectionHeader(stringResource(R.string.keybar_section_characters))
            Palette(KeyBarLayout.CHARACTERS, put)

            SectionHeader(stringResource(R.string.keybar_section_own))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    state = own,
                    label = { Text(stringResource(R.string.keybar_own_label)) },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation.maxLengthTrim(KeyBarLayout.MAX_TEXT),
                    modifier = Modifier.weight(1f),
                )
                val text = own.text.toString().trim()
                OutlinedButton(
                    enabled = text.isNotEmpty(),
                    onClick = {
                        put(text)
                        own.clearText()
                    },
                ) { Text(stringResource(R.string.keybar_own_use)) }
            }
            Note(pluralStringResource(R.plurals.keybar_own_note, KeyBarLayout.MAX_TEXT, KeyBarLayout.MAX_TEXT))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The keys that can be put on the bar, each drawn as it will look there. */
@Composable
private fun Palette(names: List<String>, onPick: (String) -> Unit) {
    val putOnBar = stringResource(R.string.keybar_put_on_bar)
    // The arrows keep pointing where they point, also in right-to-left languages.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (name in names) {
                KeyLegend(
                    name,
                    Modifier
                        .testTag("key:$name")
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClickLabel = putOnBar) { onPick(name) }
                        .defaultMinSize(minWidth = 44.dp)
                        .height(40.dp),
                )
            }
        }
    }
}
