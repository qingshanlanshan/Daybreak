package com.qshlab.daybreak.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class Tab(val label: String, val icon: ImageVector) {
    Feed("Feed", Icons.Default.WbSunny), Calendar("Calendar", Icons.Default.CalendarMonth), Tasks("Tasks", Icons.Default.CheckCircle)
}

/** Feed · Calendar · Tasks, plus Settings. A bottom bar on phones, a side rail on wide windows (the Mac). */
@Composable
fun DaybreakApp(startTab: Tab = Tab.Feed, startInSettings: Boolean = false, calendarView: CalView = CalView.Month) {
    DaybreakTheme {
        var tab by rememberSaveable { mutableStateOf(startTab) }
        var settings by rememberSaveable { mutableStateOf(startInSettings) }
        var settingsStart by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
        BackHandler(settings) { settings = false }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints {
                val wide = maxWidth >= 720.dp
                val content: @Composable () -> Unit = {
                    if (settings) androidx.compose.runtime.key(settingsStart) { SettingsScreen(onBack = { settings = false; settingsStart = null }, start = settingsStart) }
                    else when (tab) {
                        Tab.Feed -> FeedScreen(onSettings = if (wide) null else ({ settings = true }), openSettings = { settingsStart = SettingsPage.Claude; settings = true })
                        Tab.Calendar -> CalendarScreen(onSettings = if (wide) null else ({ settings = true }), startView = calendarView)
                        Tab.Tasks -> TasksScreen(onSettings = if (wide) null else ({ settings = true }))
                    }
                }
                if (wide) Row(Modifier.fillMaxSize()) {
                    NavigationRail(Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        for (t in Tab.entries) NavigationRailItem(selected = !settings && tab == t, onClick = { tab = t; settings = false; settingsStart = null },
                            icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                        Box(Modifier.weight(1f))
                        NavigationRailItem(selected = settings, onClick = { settingsStart = null; settings = true },
                            icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                    }
                    Box(Modifier.weight(1f)) { content() }
                } else Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { content() }
                    if (!settings) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        for (t in Tab.entries) NavigationBarItem(selected = tab == t, onClick = { tab = t },
                            icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                    }
                }
            }
        }
    }
}

/** A top-level screen: title, actions (Settings on phones), content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabScreen(title: String, onSettings: (() -> Unit)? = null, onBack: (() -> Unit)? = null,
              actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(title, fontWeight = FontWeight.SemiBold) },
            navigationIcon = { if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                actions()
                if (onSettings != null) IconButton(onSettings) { Icon(Icons.Default.Settings, "Settings") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        content()
    }
}
