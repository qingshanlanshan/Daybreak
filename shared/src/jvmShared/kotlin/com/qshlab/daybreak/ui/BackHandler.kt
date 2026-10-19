package com.qshlab.daybreak.ui

import androidx.compose.runtime.Composable

/** Android's back gesture; nothing on the Mac (screens there have their own back arrow). */
@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)
