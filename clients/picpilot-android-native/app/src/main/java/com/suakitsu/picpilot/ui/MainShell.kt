package com.suakitsu.picpilot.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.suakitsu.picpilot.feature.billing.BillingRoute
import com.suakitsu.picpilot.feature.aicreate.AiCreateRoute
import com.suakitsu.picpilot.feature.canvas.CanvasRoute
import com.suakitsu.picpilot.feature.commerce.CommerceRoute
import com.suakitsu.picpilot.feature.history.HistoryRoute
import com.suakitsu.picpilot.feature.messages.MessagesRoute
import com.suakitsu.picpilot.feature.profile.ProfileRoute
import com.suakitsu.picpilot.feature.video.VideoRoute
import com.suakitsu.picpilot.feature.workspace.WorkspaceRoute
import com.suakitsu.picpilot.feature.detail.DetailKind
import com.suakitsu.picpilot.feature.detail.DetailRoute
import com.suakitsu.picpilot.feature.history.HistoryEntryType
import com.suakitsu.picpilot.integration.NativeIntegrationViewModel

private data class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val primaryDestinations = listOf(
    Destination("workspace", "工作台", Icons.Outlined.SpaceDashboard),
    Destination("commerce", "电商制图", Icons.Outlined.Collections),
    Destination("video", "商品视频", Icons.Outlined.MovieCreation),
    Destination("ai-create", "AI 创作", Icons.Outlined.AutoAwesome),
    Destination("more", "更多", Icons.Outlined.MoreHoriz),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    onLogout: () -> Unit,
    integration: NativeIntegrationViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val isDetailRoute = currentRoute?.startsWith("detail/") == true
    val ownsSystemBars = currentRoute in setOf("canvas", "commerce") || isDetailRoute
    val showPrimaryNavigation = currentRoute != "canvas" && !isDetailRoute
    val balance by integration.balance.collectAsStateWithLifecycle()
    val aiCatalog by integration.aiCatalog.collectAsStateWithLifecycle()
    ForegroundRefreshEffect(onRefresh = integration::refreshAll)

    Row(Modifier.fillMaxSize()) {
        if (wide && showPrimaryNavigation) {
            NavigationRail(
                containerColor = MaterialTheme.colorScheme.surface,
                header = {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = "PicPilot",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 18.dp).size(32.dp),
                    )
                },
            ) {
                primaryDestinations.forEach { destination ->
                    NavigationRailItem(
                        selected = currentRoute == destination.route,
                        onClick = { navController.navigateSingleTop(destination.route) },
                        icon = { Icon(destination.icon, null, modifier = Modifier.size(22.dp)) },
                        label = { Text(destination.label) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        }

        Scaffold(
            modifier = Modifier.weight(1f),
            contentWindowInsets = if (ownsSystemBars) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            bottomBar = {
                if (!wide && showPrimaryNavigation) {
                    CompactPrimaryNavigationBar(
                        currentRoute = currentRoute,
                        onNavigate = navController::navigateSingleTop,
                    )
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "workspace",
                modifier = Modifier.fillMaxSize().padding(padding),
                enterTransition = { fadeIn(tween(150)) },
                exitTransition = { fadeOut(tween(100)) },
                popEnterTransition = { fadeIn(tween(150)) },
                popExitTransition = { fadeOut(tween(100)) },
            ) {
                composable("workspace") {
                    WorkspaceRoute(
                        onProjectClick = { navController.navigate("detail/project/$it") },
                        onHistoryClick = { navController.navigateSingleTop("history") },
                        onMessagesClick = { navController.navigateSingleTop("messages") },
                        onCommerceClick = { navController.navigateSingleTop("commerce") },
                        onVideoClick = { navController.navigateSingleTop("video") },
                        onAiCreateClick = { navController.navigateSingleTop("ai-create") },
                        onCanvasClick = { navController.navigateSingleTop("canvas") },
                    )
                }
                composable("commerce") {
                    CommerceRoute(
                        submitter = integration.commerceSubmitter,
                        onOpenTask = { navController.navigate("detail/project/$it") },
                    )
                }
                composable("video") { VideoRoute() }
                composable("ai-create") {
                    AiCreateRoute(
                        gateway = integration.aiCreateGateway,
                        catalog = aiCatalog,
                        initialCredits = balance,
                    )
                }
                composable("more") {
                    MoreScreen(
                        onCanvas = { navController.navigateSingleTop("canvas") },
                        onHistory = { navController.navigateSingleTop("history") },
                        onBilling = { navController.navigateSingleTop("billing") },
                        onMessages = { navController.navigateSingleTop("messages") },
                        onProfile = { navController.navigateSingleTop("profile") },
                    )
                }
                composable("history") {
                    HistoryRoute(onEntryClick = { type, id ->
                        val kind = when (type) {
                            HistoryEntryType.Project -> "project"
                            HistoryEntryType.Chat -> "chat"
                            HistoryEntryType.Video -> "video"
                        }
                        navController.navigate("detail/$kind/$id")
                    })
                }
                composable("billing") { BillingRoute() }
                composable("messages") { MessagesRoute() }
                composable("profile") { ProfileRoute(onLogout = onLogout) }
                composable("canvas") { CanvasRoute(onBack = { navController.popBackStack() }) }
                composable("detail/{kind}/{id}") { entry ->
                    val kind = when (entry.arguments?.getString("kind")) {
                        "chat" -> DetailKind.CreativeChat
                        "video" -> DetailKind.Video
                        else -> DetailKind.Project
                    }
                    DetailRoute(
                        kind = kind,
                        itemId = entry.arguments?.getString("id").orEmpty(),
                        onNavigateBack = { navController.popBackStack() },
                        gateway = integration.detailGateway,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CompactPrimaryNavigationBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().height(62.dp)) {
                primaryDestinations.forEach { destination ->
                    val isSelected = currentRoute == destination.route
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(role = Role.Tab) { onNavigate(destination.route) }
                            .semantics(mergeDescendants = true) {
                                role = Role.Tab
                                selected = isSelected
                                contentDescription = destination.label
                            }
                            .padding(top = 5.dp, bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 38.dp, height = 30.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                destination.icon,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            destination.label,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.SemiBold
                            else androidx.compose.ui.text.font.FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.navigation.NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.startDestinationId) { saveState = true }
    }
}

@Composable
private fun MoreScreen(
    onCanvas: () -> Unit,
    onHistory: () -> Unit,
    onBilling: () -> Unit,
    onMessages: () -> Unit,
    onProfile: () -> Unit,
) {
    val entries = listOf(
        Triple("AI 画布", Icons.Outlined.AccountTree, onCanvas),
        Triple("历史记录", Icons.Outlined.History, onHistory),
        Triple("积分与充值", Icons.Outlined.AccountBalanceWallet, onBilling),
        Triple("站内信", Icons.AutoMirrored.Outlined.Chat, onMessages),
        Triple("个人中心", Icons.Outlined.Person, onProfile),
    )
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("更多", style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("创作工具、资产与账号", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column {
                    entries.forEachIndexed { index, entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClick = entry.third)
                                .padding(horizontal = 16.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                modifier = Modifier.size(36.dp),
                                color = when (index % 3) {
                                    1 -> MaterialTheme.colorScheme.secondaryContainer
                                    2 -> MaterialTheme.colorScheme.tertiaryContainer
                                    else -> MaterialTheme.colorScheme.primaryContainer
                                },
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(entry.second, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(Modifier.width(13.dp))
                            Text(entry.first, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Icon(Icons.Outlined.ChevronRight, contentDescription = "进入${entry.first}", tint = MaterialTheme.colorScheme.outline)
                        }
                        if (index != entries.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 65.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderFeature(title: String, description: String, icon: ImageVector) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
