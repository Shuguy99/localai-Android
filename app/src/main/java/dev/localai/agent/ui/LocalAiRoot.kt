package dev.localai.agent.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.localai.agent.AppContainer
import dev.localai.agent.ui.chat.ChatScreen
import dev.localai.agent.ui.container.ContainerScreen
import dev.localai.agent.ui.models.ModelsScreen
import dev.localai.agent.ui.settings.SettingsScreen

private data class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    Destination("chat", "Chat", Icons.Outlined.ChatBubbleOutline),
    Destination("models", "Models", Icons.Outlined.CloudDownload),
    Destination("container", "Container", Icons.Outlined.Terminal),
    Destination("settings", "Settings", Icons.Outlined.Settings),
)

@Composable
fun LocalAiRoot(app: AppContainer) {
    val factory = remember { AppViewModelFactory(app) }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: "chat"

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "chat",
            modifier = Modifier.padding(padding),
        ) {
            composable("chat") {
                ChatScreen(
                    factory = factory,
                    onOpenModels = {
                        navController.navigate("models") { launchSingleTop = true }
                    },
                )
            }
            composable("models") { ModelsScreen(factory) }
            composable("container") { ContainerScreen(factory) }
            composable("settings") { SettingsScreen(factory) }
        }
    }
}
