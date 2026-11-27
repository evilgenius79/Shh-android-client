package io.github.evilgenius79.shh.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.evilgenius79.shh.R
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
        val description: String,
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

private val Esc = BarKey.Send(TerminalKey.ESCAPE, "ESC", "Escape")
private val Tab = BarKey.Send(TerminalKey.TAB, "TAB", "Tab")
private val Home = BarKey.Send(TerminalKey.HOME, "HOME", "Home")
private val End = BarKey.Send(TerminalKey.END, "END", "End")
private val PageUp = BarKey.Send(TerminalKey.PAGE_UP, "PGUP", "Page up", repeats = true)
private val PageDown = BarKey.Send(TerminalKey.PAGE_DOWN, "PGDN", "Page down", repeats = true)
private val Delete = BarKey.Send(TerminalKey.DELETE, "DEL", "Delete", repeats = true)
private val Up = BarKey.Send(TerminalKey.UP, "↑", "Up", R.drawable.ic_arrow_upward, repeats = true)
private val Down = BarKey.Send(TerminalKey.DOWN, "↓", "Down", R.drawable.ic_arrow_downward, repeats = true)
private val Left = BarKey.Send(TerminalKey.LEFT, "←", "Left", R.drawable.ic_arrow_back, repeats = true)
private val Right = BarKey.Send(TerminalKey.RIGHT, "→", "Right", R.drawable.ic_arrow_forward, repeats = true)

private val FunctionKeys: List<BarKey> = listOf(
    TerminalKey.F1, TerminalKey.F2, TerminalKey.F3, TerminalKey.F4, TerminalKey.F5, TerminalKey.F6,
    TerminalKey.F7, TerminalKey.F8, TerminalKey.F9, TerminalKey.F10, TerminalKey.F11, TerminalKey.F12,
).mapIndexed { index, key -> BarKey.Send(key, "F${index + 1}", "F${index + 1}") }

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

/** Every label that is a word rather than a character or an icon. */
private val WordLabels: List<String> =
    (NarrowBase + NarrowMore).flatten().filterIsInstance<BarKey.Send>().filter { it.icon == null }.map { it.label } + CTRL_LABEL + ALT_LABEL

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

    val colors = MaterialTheme.colorScheme
    // The arrows and their places are physical. They do not swap sides in right-to-left languages.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxWidth().background(lerp(colors.surface, colors.onSurface, BAR_TINT))) {
            val wide = maxWidth >= WideFrom
            val rows = (if (!expanded) emptyList() else if (wide) WideMore else NarrowMore) + (if (wide) WideBase else NarrowBase)

            // Every word is set in the same size: the largest at which the widest of them still
            // fits the narrowest key. Shrinking each label on its own would look uneven.
            val wordStyle = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val columns = if (wide) WideBase[0].size else NarrowBase[0].size
            val keyWidth = (maxWidth - BarPadding * 2 - KeyGap * (columns - 1)) / columns
            val wordSize = remember(keyWidth, density, wordStyle) {
                val room = with(density) { (keyWidth - LabelPadding * 2 - 2.dp).toPx() }
                val widest = WordLabels.maxOf { measurer.measure(it, wordStyle, softWrap = false, maxLines = 1).size.width }
                if (widest <= room) wordStyle.fontSize else wordStyle.fontSize * (room / widest)
            }
            val words = wordStyle.copy(fontSize = wordSize)
            Column {
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                Column(Modifier.padding(BarPadding), verticalArrangement = Arrangement.spacedBy(KeyGap)) {
                    for (row in rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                            for (key in row) {
                                val cell = Modifier.weight(1f)
                                when (key) {
                                    is BarKey.Send -> KeyCap(cell, key.label, key.description, words, key.icon, repeats = key.repeats) { onKey(key.key) }
                                    is BarKey.Type -> KeyCap(cell, key.text, key.text, words, character = true) { onText(key.text) }
                                    BarKey.Ctrl -> KeyCap(cell, CTRL_LABEL, "Control", words, state = ctrl, stateDescription = describe(ctrl)) {
                                        onCtrl(tapped(key, ctrl))
                                    }
                                    BarKey.Alt -> KeyCap(cell, ALT_LABEL, "Alt", words, state = alt, stateDescription = describe(alt)) {
                                        onAlt(tapped(key, alt))
                                    }
                                    BarKey.More -> KeyCap(
                                        cell,
                                        label = "",
                                        description = "More keys",
                                        words = words,
                                        icon = if (expanded) R.drawable.ic_expand_more else R.drawable.ic_more_horiz,
                                        state = if (expanded) ModifierState.ONCE else ModifierState.OFF,
                                        stateDescription = if (expanded) "Shown" else "Hidden",
                                    ) { expanded = !expanded }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun describe(state: ModifierState): String = when (state) {
    ModifierState.OFF -> "Off"
    ModifierState.ONCE -> "On for the next key"
    ModifierState.LOCKED -> "Locked on"
}

/**
 * A single key. [state] highlights it: tinted while it applies once (or, for the panel key, while
 * the panel is open) and solid with a dot while it is locked.
 */
@Composable
private fun KeyCap(
    modifier: Modifier,
    label: String,
    description: String,
    words: TextStyle,
    @DrawableRes icon: Int? = null,
    character: Boolean = false,
    state: ModifierState = ModifierState.OFF,
    stateDescription: String? = null,
    repeats: Boolean = false,
    onPress: () -> Unit,
) {
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
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
                if (icon != null) contentDescription = description
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
            character -> Text(label, color = content, fontFamily = CharacterFont, fontSize = 16.sp, maxLines = 1, softWrap = false)
            else -> Text(label, Modifier.padding(horizontal = LabelPadding), color = content, style = words, maxLines = 1, softWrap = false)
        }
        if (state == ModifierState.LOCKED) {
            Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(4.dp).background(content, CircleShape))
        }
    }
}
