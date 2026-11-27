package io.github.evilgenius79.shh.terminal

/** How Ctrl or Alt from the on-screen key bar applies to what is typed next. */
enum class ModifierState {
    OFF,

    /** To the next key only, then it switches itself off. */
    ONCE,

    /** To every key until it is tapped again. */
    LOCKED;

    val isOn: Boolean get() = this != OFF

    /**
     * The state after the key was tapped. A second tap right after the first ([again]) locks it,
     * like double-tapping Shift on a phone keyboard; any other tap on an active key releases it.
     */
    fun tapped(again: Boolean): ModifierState = when (this) {
        OFF -> ONCE
        ONCE -> if (again) LOCKED else OFF
        LOCKED -> OFF
    }
}
