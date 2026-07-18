package com.yue.moku.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yue.moku.AppViewModel

private enum class Section(val label: String) { Chat("写作"), Knowledge("知识库"), Settings("设置") }

@Composable
fun MoKuApp(viewModel: AppViewModel) {
    var section by remember { mutableStateOf(Section.Chat) }
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!imeVisible) NavigationBar {
                    NavigationBarItem(
                        selected = section == Section.Chat,
                        onClick = { section = Section.Chat },
                        icon = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
                        label = { Text(Section.Chat.label) },
                    )
                    NavigationBarItem(
                        selected = section == Section.Knowledge,
                        onClick = { section = Section.Knowledge },
                        icon = { Icon(Icons.Outlined.AutoStories, null) },
                        label = { Text(Section.Knowledge.label) },
                    )
                    NavigationBarItem(
                        selected = section == Section.Settings,
                        onClick = { section = Section.Settings },
                        icon = { Icon(Icons.Outlined.Settings, null) },
                        label = { Text(Section.Settings.label) },
                    )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (section) {
                Section.Chat -> ChatScreen(viewModel)
                Section.Knowledge -> KnowledgeScreen(viewModel)
                Section.Settings -> SettingsScreen(viewModel)
            }
        }
    }
}
