package io.github.evilgenius79.shh.ui

import android.content.res.Configuration
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.evilgenius79.shh.R
import io.github.evilgenius79.shh.data.Settings
import io.github.evilgenius79.shh.terminal.ModifierState
import io.github.evilgenius79.shh.terminal.TerminalKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One key of the bar. */
private sealed interface BarKey {
    /** Sends a key that has no character of its own, such as Escape or an arrow. */
    data class Send(
        val key: TerminalKey,
        val label: String,
        /** What a screen reader says for a key that shows [icon]. The others are read by their [label]. */
        @StringRes val description: Int? = null,
        @DrawableRes val icon: Int? = null,
        /** Holding the key down sends it again and again, as on a keyboard. */
        val repeats: Boolean = false,
    ) : BarKey

    /** Types a character. */
    data class Type(val text: String) : BarKey

    data object Ctrl : BarKey

    data object Alt : BarKey

    /** Shows and hides the panel with the keys that are needed less often. */
    data object More : BarKey
}

private val Esc = BarKey.Send(TerminalKey.ESCAPE, "ESC")
private val Tab = BarKey.Send(TerminalKey.TAB, "TAB")
private val Home = BarKey.Send(TerminalKey.HOME, "HOME")
private val End = BarKey.Send(TerminalKey.END, "END")
private val PageUp = BarKey.Send(TerminalKey.PAGE_UP, "PGUP", repeats = true)
private val PageDown = BarKey.Send(TerminalKey.PAGE_DOWN, "PGDN", repeats = true)
private val Delete = BarKey.Send(TerminalKey.DELETE, "DEL", repeats = true)
private val Up = BarKey.Send(TerminalKey.UP, "↑", R.string.keybar_key_up, R.drawable.ic_arrow_upward, repeats = true)
private val Down = BarKey.Send(TerminalKey.DOWN, "↓", R.string.keybar_key_down, R.drawable.ic_arrow_downward, repeats = true)
private val Left = BarKey.Send(TerminalKey.LEFT, "←", R.string.keybar_key_left, R.drawable.ic_arrow_back, repeats = true)
private val Right = BarKey.Send(TerminalKey.RIGHT, "→", R.string.keybar_key_right, R.drawable.ic_arrow_forward, repeats = true)

private val Insert = BarKey.Send(TerminalKey.INSERT, "INS")
private val Enter = BarKey.Send(TerminalKey.ENTER, "ENTER")

private val FunctionKeys: List<BarKey> = listOf(
    TerminalKey.F1, TerminalKey.F2, TerminalKey.F3, TerminalKey.F4, TerminalKey.F5, TerminalKey.F6,
    TerminalKey.F7, TerminalKey.F8, TerminalKey.F9, TerminalKey.F10, TerminalKey.F11, TerminalKey.F12,
).mapIndexed { index, key -> BarKey.Send(key, "F${index + 1}") }

/**
 * Which keys the bar shows, as the user can arrange it: sixteen names, the upper row of eight
 * followed by the lower one. A name from [NAMED] is that key; anything else is a key that types
 * exactly that text.
 */
object KeyBarLayout {
    const val SLOTS = 16

    /** The longest text a key of its own can type. Longer things belong in snippets. */
    const val MAX_TEXT = 4

    /** The name of the key that opens the panel with the function keys. */
    const val MORE = "MORE"

    val DEFAULT: List<String> = listOf("ESC", "/", "|", "-", "HOME", "UP", "END", "PGUP", "TAB", "CTRL", "ALT", MORE, "LEFT", "DOWN", "RIGHT", "PGDN")

    /** The keys that do something other than type their name, in the order they are offered. */
    val NAMED: List<String> = listOf("ESC", "TAB", "CTRL", "ALT", MORE, "UP", "DOWN", "LEFT", "RIGHT", "HOME", "END", "PGUP", "PGDN", "ENTER", "DEL", "INS") +
        (1..12).map { "F$it" }

    /** Characters worth a key of their own: awkward on a phone keyboard, common in a shell. */
    val CHARACTERS: List<String> = listOf("/", "|", "-", "~", "\\", "_", ":", ";", "*", "=", "&", "$", "#", "<", ">", "[", "]", "{", "}", "`", "^", "'", "\"", "!", "?", "@", "%", "+")

    /**
     * [stored] as a layout that can be shown: exactly [SLOTS] keys, none of them empty or too
     * long. An empty list, which is what a fresh installation has, is the standard layout.
     */
    fun of(stored: List<String>): List<String> {
        if (stored.isEmpty()) return DEFAULT
        return List(SLOTS) { index ->
            val wanted = stored.getOrNull(index)?.trim().orEmpty()
            if (wanted.isEmpty()) DEFAULT[index] else if (wanted in NAMED) wanted else wanted.take(MAX_TEXT)
        }
    }

    /** [layout] with [name] at [index], as it is stored: nothing at all when that is the standard layout. */
    fun with(layout: List<String>, index: Int, name: String): List<String> {
        val changed = of(layout).toMutableList().also { it[index] = name }
        return if (of(changed) == DEFAULT) emptyList() else of(changed)
    }

    /** Whether a hardware keyboard is there to type on. */
    fun hasHardwareKeyboard(configuration: Configuration): Boolean =
        configuration.keyboard == Configuration.KEYBOARD_QWERTY && configuration.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
}

private val NamedKeys: Map<String, BarKey> = mapOf(
    "ESC" to Esc, "TAB" to Tab, "CTRL" to BarKey.Ctrl, "ALT" to BarKey.Alt, KeyBarLayout.MORE to BarKey.More,
    "UP" to Up, "DOWN" to Down, "LEFT" to Left, "RIGHT" to Right, "HOME" to Home, "END" to End,
    "PGUP" to PageUp, "PGDN" to PageDown, "ENTER" to Enter, "DEL" to Delete, "INS" to Insert,
) + FunctionKeys.associateBy { (it as BarKey.Send).label }

private fun barKey(name: String): BarKey = NamedKeys[name] ?: BarKey.Type(name)

/** What a key shows, and with an [icon] what a screen reader says for it. */
private class Face(val label: String, @StringRes val description: Int? = null, @DrawableRes val icon: Int? = null, val character: Boolean = false)

private fun BarKey.face(panelOpen: Boolean = false): Face = when (this) {
    is BarKey.Send -> Face(label, description, icon)
    is BarKey.Type -> Face(text, character = true)
    BarKey.Ctrl -> Face(CTRL_LABEL)
    BarKey.Alt -> Face(ALT_LABEL)
    BarKey.More -> Face("", R.string.keybar_key_more, if (panelOpen) R.drawable.ic_expand_more else R.drawable.ic_more_horiz)
}

private fun characters(vararg text: String): List<BarKey> = text.map { BarKey.Type(it) }

/*
 * The layout for a phone held upright: eight keys to a row, so that nothing is cut off and nothing
 * has to be scrolled to. The arrows form the inverted T of a keyboard, with Home and End beside
 * the up arrow and the page keys in a column of their own.
 */
private val NarrowBase: List<List<BarKey>> = listOf(
    listOf(Esc) + characters("/", "|", "-") + listOf(Home, Up, End, PageUp),
    listOf(Tab, BarKey.Ctrl, BarKey.Alt, BarKey.More, Left, Down, Right, PageDown),
)

/*
 * The panel above it: the function keys, and the characters that sit two layers deep on a phone
 * keyboard although a shell needs them all the time.
 */
private val NarrowMore: List<List<BarKey>> = listOf(
    FunctionKeys.subList(0, 8),
    FunctionKeys.subList(8, 12) + listOf(Delete) + characters("~", "`", "^"),
    characters("\\", "=", "<", ">", "[", "]", "{", "}"),
)

/* With room to spare (landscape, tablets) the same keys take one row, which leaves more terminal. */
private val WideBase: List<List<BarKey>> = listOf(
    listOf(Esc, Tab, BarKey.Ctrl, BarKey.Alt, BarKey.More) + characters("/", "|", "-") +
        listOf(Home, End, PageUp, PageDown, Left, Down, Up, Right),
)

private val WideMore: List<List<BarKey>> = listOf(
    FunctionKeys,
    listOf(Delete) + characters("~", "`", "^", "\\", "=", "<", ">", "[", "]", "{", "}"),
)

private const val CTRL_LABEL = "CTRL"
private const val ALT_LABEL = "ALT"

/** The labels among [keys] that are words rather than characters or icons. */
private fun wordLabels(keys: List<BarKey>): List<String> = keys.mapNotNull { key ->
    when (key) {
        is BarKey.Send -> key.label.takeIf { key.icon == null }
        BarKey.Ctrl -> CTRL_LABEL
        BarKey.Alt -> ALT_LABEL
        else -> null
    }
}

private val WideFrom = 640.dp
private val BarPadding = 6.dp
private val KeyHeight = 38.dp
private val KeyGap = 4.dp
private val KeyShape = RoundedCornerShape(8.dp)
private val LabelPadding = 2.dp

/* How far a key's colour is from the surface towards the text colour. This works in every theme. */
private const val BAR_TINT = 0.04f
private const val CONTROL_KEY_TINT = 0.10f
private const val CHARACTER_KEY_TINT = 0.17f

private const val REPEAT_AFTER_MS = 400L
private const val REPEAT_EVERY_MS = 60L

/** Two taps on Ctrl or Alt within this time lock it. */
private const val LOCK_TAP_MS = 400L

/** A key lighting up or going dark again. */
private val HighlightChange = tween<Color>(durationMillis = 120)

/** Characters are shown in the terminal's own typeface: what the key shows is what it types. */
private val CharacterFont = FontFamily(Font(R.font.jetbrains_mono_regular))
private val CharacterStyle = TextStyle(fontFamily = CharacterFont, fontSize = 16.sp)

/** Whether the bar is shown: it is switched on, and no hardware keyboard has made it unnecessary. */
@Composable
fun keyBarShown(settings: Settings): Boolean =
    settings.showExtraKeys && !(settings.hideKeysWithKeyboard && KeyBarLayout.hasHardwareKeyboard(LocalConfiguration.current))

/**
 * The keys a phone keyboard lacks, as a grid that fills the width of the screen.
 *
 * Ctrl and Alt apply to the next key; tapping one twice in a row locks it until it is tapped
 * again. The arrow, page and delete keys repeat while held.
 */
@Composable
fun ExtraKeys(
    ctrl: ModifierState,
    alt: ModifierState,
    onCtrl: (ModifierState) -> Unit,
    onAlt: (ModifierState) -> Unit,
    onKey: (TerminalKey) -> Unit,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The sixteen keys of the bar as stored in the settings, see [KeyBarLayout]. */
    layout: List<String> = emptyList(),
    uptimeMillis: () -> Long = SystemClock::uptimeMillis,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Which modifier was tapped last and when, to recognise the double tap that locks it.
    var lastTap by remember { mutableStateOf<Pair<BarKey, Long>?>(null) }
    fun tapped(key: BarKey, state: ModifierState): ModifierState {
        val now = uptimeMillis()
        val again = lastTap?.let { it.first == key && now - it.second <= LOCK_TAP_MS } == true
        lastTap = key to now
        return state.tapped(again)
    }

    KeyBarFrame(modifier) { maxWidth ->
        val wide = maxWidth >= WideFrom
        val base = remember(layout, wide) {
            val keys = KeyBarLayout.of(layout)
            when {
                !wide -> keys.chunked(KeyBarLayout.SLOTS / 2).map { row -> row.map(::barKey) }
                // The standard keys have an order of their own for a single row.
                keys == KeyBarLayout.DEFAULT -> WideBase
                else -> listOf(keys.map(::barKey))
            }
        }
        val more = if (wide) WideMore else NarrowMore
        // Without its key on the bar the panel could never be closed again.
        val panelOpen = expanded && base.any { BarKey.More in it }
        // Measured over the panel's keys as well, so that opening it changes nothing.
        val fit = rememberFit(remember(base, more) { (base + more).flatten() }, keyWidth(maxWidth, base[0].size))
        KeyRows((if (panelOpen) more else emptyList()) + base) { key, _ ->
            val cell = Modifier.weight(1f)
            when (key) {
                is BarKey.Send -> KeyCap(cell, key.face(), fit, repeats = key.repeats) { onKey(key.key) }
                is BarKey.Type -> KeyCap(cell, key.face(), fit) { onText(key.text) }
                BarKey.Ctrl -> KeyCap(cell, key.face(), fit, state = ctrl, stateDescription = describe(ctrl)) { onCtrl(tapped(key, ctrl)) }
                BarKey.Alt -> KeyCap(cell, key.face(), fit, state = alt, stateDescription = describe(alt)) { onAlt(tapped(key, alt)) }
                BarKey.More -> KeyCap(
                    cell,
                    key.face(panelOpen),
                    fit,
                    state = if (panelOpen) ModifierState.ONCE else ModifierState.OFF,
                    stateDescription = stringResource(if (panelOpen) R.string.keybar_panel_shown else R.string.keybar_panel_hidden),
                ) { expanded = !panelOpen }
            }
        }
    }
}

/**
 * The bar as the screen that arranges it shows it: always in two rows, and a tap on a key picks
 * its place, number [selected] being the one picked now, instead of pressing it.
 */
@Composable
internal fun KeyBarPreview(layout: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    KeyBarFrame(modifier) { maxWidth ->
        val columns = KeyBarLayout.SLOTS / 2
        val rows = remember(layout) { KeyBarLayout.of(layout).map(::barKey).chunked(columns) }
        // The same sizes as on the real bar, which allows for the keys of its panel.
        val fit = rememberFit(remember(rows) { (rows + NarrowMore).flatten() }, keyWidth(maxWidth, columns))
        KeyRows(rows) { key, index ->
            KeyCap(
                Modifier.weight(1f).testTag("place:$index"),
                key.face(),
                fit,
                state = if (index == selected) ModifierState.ONCE else ModifierState.OFF,
                stateDescription = stringResource(if (index == selected) R.string.keybar_place_selected else R.string.keybar_place, index + 1, KeyBarLayout.SLOTS),
                feedback = false,
            ) { onSelect(index) }
        }
    }
}

/** The strip the keys sit on, as wide as the screen. [content] is told how wide that is. */
@Composable
private fun KeyBarFrame(modifier: Modifier, content: @Composable (maxWidth: Dp) -> Unit) {
    val colors = MaterialTheme.colorScheme
    // The arrows and their places are physical. They do not swap sides in right-to-left languages.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxWidth().background(lerp(colors.surface, colors.onSurface, BAR_TINT))) {
            val width = maxWidth
            Column {
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                content(width)
            }
        }
    }
}

/** [rows] of equally wide keys. [key] draws one, and is told its number counted along the rows. */
@Composable
private fun KeyRows(rows: List<List<BarKey>>, key: @Composable RowScope.(BarKey, Int) -> Unit) {
    Column(Modifier.padding(BarPadding), verticalArrangement = Arrangement.spacedBy(KeyGap)) {
        var index = 0
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                for (entry in row) key(entry, index++)
            }
        }
    }
}

private fun keyWidth(barWidth: Dp, columns: Int): Dp = (barWidth - BarPadding * 2 - KeyGap * (columns - 1)) / columns

/** The sizes at which the labels fit their keys. */
private class Fit(
    /** For every word: the largest size at which the widest of them still fits. */
    val words: TextStyle,
    /** For a key that types more than one character, by its text. */
    val texts: Map<String, TextUnit>,
)

/**
 * How to set the labels of [keys] on keys [keyWidth] wide. Every word gets the same size, since
 * shrinking each on its own would look uneven; a key that types several characters gets the
 * largest size at which they fit.
 */
@Composable
private fun rememberFit(keys: List<BarKey>, keyWidth: Dp): Fit {
    val wordStyle = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(keys, keyWidth, density, wordStyle) {
        val room = with(density) { (keyWidth - LabelPadding * 2 - 2.dp).toPx() }
        fun fitted(text: String, style: TextStyle): TextUnit {
            val width = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width
            return if (width <= room) style.fontSize else style.fontSize * (room / width)
        }
        val widestWord = wordLabels(keys).distinct().maxByOrNull { measurer.measure(it, wordStyle, softWrap = false, maxLines = 1).size.width }
        Fit(
            words = wordStyle.copy(fontSize = widestWord?.let { fitted(it, wordStyle) } ?: wordStyle.fontSize),
            texts = keys.filterIsInstance<BarKey.Type>().map { it.text }.filter { it.length > 1 }.distinct().associateWith { fitted(it, CharacterStyle) },
        )
    }
}

/**
 * A key as it looks on the bar, for explaining what it does: not something to tap. Arrows and the
 * panel key are drawn with the icons the bar uses, and a single character in the terminal's
 * typeface, like the character keys of the bar.
 *
 * With [word], [label] is a word in the user's language rather than the legend of a key. It is
 * then set as a word whatever it reads: a translation may happen to look like a character key, or
 * like the name of a key that has an icon.
 */
@Composable
fun KeyLegend(label: String, modifier: Modifier = Modifier, word: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val icon = if (word) null else LegendIcons[label]
    val character = !word && icon == null && label !in KeyBarLayout.NAMED && label.length <= KeyBarLayout.MAX_TEXT && label.all { it.code in 0x21..0x7e } &&
        !(label.length > 1 && label.all { it.isLetter() })
    Box(
        modifier
            .height(24.dp)
            .defaultMinSize(minWidth = 26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(lerp(colors.surface, colors.onSurface, if (character) CHARACTER_KEY_TINT else CONTROL_KEY_TINT))
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            icon != null -> Icon(painterResource(icon), label, Modifier.size(14.dp), tint = colors.onSurface.copy(alpha = 0.85f))
            character -> Text(label, color = colors.onSurface, fontFamily = CharacterFont, fontSize = 13.sp, maxLines = 1, softWrap = false)
            else -> Text(
                label,
                color = colors.onSurface.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** The keys of the bar that show an icon instead of a word, by the character that stands for them in text. */
private val LegendIcons: Map<String, Int> = mapOf(
    "↑" to R.drawable.ic_arrow_upward,
    "↓" to R.drawable.ic_arrow_downward,
    "←" to R.drawable.ic_arrow_back,
    "→" to R.drawable.ic_arrow_forward,
    "⋯" to R.drawable.ic_more_horiz,
    // The same keys by the names a layout stores them under.
    "UP" to R.drawable.ic_arrow_upward,
    "DOWN" to R.drawable.ic_arrow_downward,
    "LEFT" to R.drawable.ic_arrow_back,
    "RIGHT" to R.drawable.ic_arrow_forward,
    KeyBarLayout.MORE to R.drawable.ic_more_horiz,
)

@Composable
private fun describe(state: ModifierState): String = stringResource(
    when (state) {
        ModifierState.OFF -> R.string.keybar_modifier_off
        ModifierState.ONCE -> R.string.keybar_modifier_once
        ModifierState.LOCKED -> R.string.keybar_modifier_locked
    },
)

/**
 * A single key. [state] highlights it: tinted while it applies once (or, for the panel key, while
 * the panel is open) and solid with a dot while it is locked.
 */
@Composable
private fun KeyCap(
    modifier: Modifier,
    face: Face,
    fit: Fit,
    state: ModifierState = ModifierState.OFF,
    stateDescription: String? = null,
    repeats: Boolean = false,
    /** Answer a touch the way a keyboard key does, with the system's key click. */
    feedback: Boolean = true,
    onPress: () -> Unit,
) {
    val label = face.label
    val icon = face.icon
    val character = face.character
    val description = face.description?.let { stringResource(it) }
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        when (state) {
            ModifierState.LOCKED -> colors.primary
            ModifierState.ONCE -> colors.primaryContainer
            ModifierState.OFF -> lerp(colors.surface, colors.onSurface, if (character) CHARACTER_KEY_TINT else CONTROL_KEY_TINT)
        },
        HighlightChange,
        label = "key",
    )
    val content by animateColorAsState(
        when (state) {
            ModifierState.LOCKED -> colors.onPrimary
            ModifierState.ONCE -> colors.onPrimaryContainer
            ModifierState.OFF -> if (character) colors.onSurface else colors.onSurface.copy(alpha = 0.8f)
        },
        HighlightChange,
        label = "label",
    )
    val interaction = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val press by rememberUpdatedState(onPress)
    Box(
        modifier
            .height(KeyHeight)
            .clip(KeyShape)
            .background(background)
            .indication(interaction, ripple(color = content))
            .pointerInput(repeats) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pressed = PressInteraction.Press(down.position)
                    scope.launch { interaction.emit(pressed) }
                    // Follows the system's "touch feedback" setting, like the keyboard does.
                    if (feedback) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    var repeated = false
                    val repeating = if (!repeats) null else scope.launch {
                        delay(REPEAT_AFTER_MS)
                        while (true) {
                            repeated = true
                            press()
                            delay(REPEAT_EVERY_MS)
                        }
                    }
                    // Like a keyboard key, it counts when the finger lifts, and sliding off cancels.
                    val up = waitForUpOrCancellation()
                    repeating?.cancel()
                    scope.launch { interaction.emit(if (up == null) PressInteraction.Cancel(pressed) else PressInteraction.Release(pressed)) }
                    if (up != null) {
                        up.consume()
                        if (!repeated) press()
                    }
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (description != null) contentDescription = description
                if (stateDescription != null) this.stateDescription = stateDescription
                onClick {
                    press()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            icon != null -> Icon(painterResource(icon), null, Modifier.size(18.dp), tint = content)
            character -> Text(label, color = content, style = CharacterStyle, fontSize = fit.texts[label] ?: CharacterStyle.fontSize, maxLines = 1, softWrap = false)
            else -> Text(label, Modifier.padding(horizontal = LabelPadding), color = content, style = fit.words, maxLines = 1, softWrap = false)
        }
        if (state == ModifierState.LOCKED) {
            Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(4.dp).background(content, CircleShape))
        }
    }
}
