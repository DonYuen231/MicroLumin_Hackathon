package app.recall.phone.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.recall.phone.AppContainer

private val RecallColors = darkColorScheme(
    primary = Color(0xFF7DF0A8),
    onPrimary = Color(0xFF00391C),
    primaryContainer = Color(0xFF1B4D32),
    onPrimaryContainer = Color(0xFFB9F8CF),
    secondary = Color(0xFFB4CCBB),
    background = Color(0xFF101412),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF101412),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF29302B),
    onSurfaceVariant = Color(0xFFBFC9C1),
    surfaceContainer = Color(0xFF1A1F1C),
    error = Color(0xFFFFB4AB),
)

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val Tabs = listOf(
    Tab("people", "People", Icons.Filled.Person),
    Tab("captures", "Captures", Icons.Filled.Refresh),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun RecallRoot(container: AppContainer) {
    MaterialTheme(colorScheme = RecallColors) {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        Scaffold(
            bottomBar = {
                if (Tabs.any { it.route == route }) {
                    NavigationBar {
                        Tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = "people", Modifier.padding(padding)) {
                composable("people") {
                    PeopleScreen(container) { id -> nav.navigate("person/$id") }
                }
                composable("person/{id}") { backStack ->
                    PersonScreen(
                        container = container,
                        personId = backStack.arguments?.getString("id").orEmpty(),
                        onDone = { nav.popBackStack() },
                    )
                }
                composable("captures") { CapturesScreen(container) }
                composable("settings") { SettingsScreen(container) }
            }
        }
    }
}
