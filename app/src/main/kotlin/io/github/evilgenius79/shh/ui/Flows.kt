package io.github.evilgenius79.shh.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** Collects a [StateFlow] for as long as the screen is at least started. */
@Composable
fun <T> StateFlow<T>.collectAsStateCompat(): State<T> = collectAsStateWithLifecycle()
