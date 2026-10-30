package com.theguruscat.kissa

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theguruscat.kissa.data.GoogleSignIn
import com.theguruscat.kissa.data.RedditPost
import com.theguruscat.kissa.notify.ActivityNotifier
import com.theguruscat.kissa.share.CardSharer
import com.theguruscat.kissa.ui.AccountScreen
import com.theguruscat.kissa.ui.CommentsScreen
import com.theguruscat.kissa.ui.ModerationScreen
import com.theguruscat.kissa.ui.NewsSourcesScreen
import com.theguruscat.kissa.ui.NotificationsScreen
import com.theguruscat.kissa.ui.ProfileScreen
import com.theguruscat.kissa.ui.SavedScreen
import com.theguruscat.kissa.ui.SettingsScreen
import com.theguruscat.kissa.ui.StatsScreen
import com.theguruscat.kissa.ui.SubredditsScreen
import com.theguruscat.kissa.ui.SwipeBackContainer
import com.theguruscat.kissa.ui.SwipeScreen
import com.theguruscat.kissa.ui.WriteScreen
import com.theguruscat.kissa.ui.theme.KissaTheme
import com.theguruscat.kissa.update.UpdateDialog
import com.theguruscat.kissa.update.UpdateViewModel
import kotlinx.coroutines.launch

enum class Screen(val title: String, val icon: ImageVector) {
    Swipe("Swipe", Icons.Filled.Style),
    Saved("Liked", Icons.Filled.Favorite),
    // Shown only when signed in.
    Notifications("Notifications", Icons.Filled.Notifications),
    News("News sources", Icons.Filled.Newspaper),
    Subreddits("Subreddits", Icons.Filled.Forum),
    Stats("Stats", Icons.Filled.BarChart),
    Write("Write a story", Icons.Filled.Edit),
    // Shown only to admins.
    Moderation("Moderation", Icons.Filled.Shield),
    Account("Account", Icons.Filled.AccountCircle),
    Settings("Settings", Icons.Filled.Settings),
}

/** A story's comments or someone's profile, opened on top of the current screen; back closes it. */
private sealed interface Detail {
    data class Comments(val post: RedditPost) : Detail
    data class Profile(val username: String) : Detail
}

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light status and navigation bar icons, since the app is dark gray whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            KissaTheme {
                val vm: SwipeViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                // A tapped "liked your story" notification opens straight on Notifications.
                val fromNotification = intent.getBooleanExtra(ActivityNotifier.EXTRA_OPEN_NOTIFICATIONS, false)
                var screen by rememberSaveable { mutableStateOf(if (fromNotification) Screen.Notifications else Screen.Swipe) }
                // Comments and profiles can lead to each other, so they stack; the last one shows.
                val details = remember { mutableStateListOf<Detail>() }
                val detail = details.lastOrNull()
                val openComments = { post: RedditPost -> details += Detail.Comments(post) }
                val openProfile = { name: String -> details += Detail.Profile(name) }
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                val snackbar = remember { SnackbarHostState() }

                val updates: UpdateViewModel = viewModel()
                val updateState by updates.state.collectAsStateWithLifecycle()
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    updates.onResume()
                    vm.onAppVisible()
                    vm.refreshNotifications()
                }
                // Once signed in, ask (once) to show likes and comments as Android notifications.
                val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(state.account.email) {
                    val prefs = getSharedPreferences("notifier_permission", Context.MODE_PRIVATE)
                    if (state.account.email != null && Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked", false)) {
                        prefs.edit().putBoolean("asked", true).apply()
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onAppHidden() }
                UpdateDialog(updateState, onUpdate = updates::update, onDismiss = updates::dismiss)

                // Back closes the open menu; on other screens, SwipeBackContainer takes back to Swipe.
                BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

                // The drawer only slides in from the start edge, so it's laid out right-to-left to put the menu
                // on the right, within thumb reach; its sheet and the screens switch back to left-to-right.
                val ltr = LocalLayoutDirection.current
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        // Edge swipes would fight with card swipes, so the menu opens only from its button.
                        gesturesEnabled = drawerState.isOpen,
                        drawerContent = {
                            CompositionLocalProvider(LocalLayoutDirection provides ltr) {
                                ModalDrawerSheet {
                                    Text(
                                        stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleLarge,
                                        modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 16.dp),
                                    )
                                    Screen.entries.filter { item ->
                                        when (item) {
                                            Screen.Moderation -> state.account.isAdmin
                                            Screen.Notifications -> state.account.email != null
                                            else -> true
                                        }
                                    }.forEach { item ->
                                        NavigationDrawerItem(
                                            label = { Text(item.title) },
                                            icon = { Icon(item.icon, contentDescription = null) },
                                            badge = {
                                                if (item == Screen.Saved && state.saved.isNotEmpty()) Text("${state.saved.size}")
                                                if (item == Screen.Notifications && state.unreadNotifications > 0) {
                                                    Text("${state.unreadNotifications}", color = MaterialTheme.colorScheme.primary)
                                                }
                                            },
                                            selected = screen == item,
                                            onClick = { screen = item; details.clear(); scope.launch { drawerState.close() } },
                                            modifier = Modifier.padding(horizontal = 12.dp),
                                        )
                                    }
                                    HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 16.dp))
                                    Text(
                                        "Reddit story length",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 28.dp, bottom = 8.dp),
                                    )
                                    // Picking a length opens Reddit at that length.
                                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                                        ReadMode.STORY_MODES.forEachIndexed { index, mode ->
                                            SegmentedButton(
                                                selected = mode == state.storyMode,
                                                onClick = {
                                                    vm.setReadMode(mode)
                                                    screen = Screen.Swipe
                                                    scope.launch { drawerState.close() }
                                                },
                                                shape = SegmentedButtonDefaults.itemShape(index, ReadMode.STORY_MODES.size),
                                                label = { Text(mode.label) },
                                            )
                                        }
                                    }
                                    Text(
                                        "Quick: short posts like shower thoughts. Long: full stories. All: both.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 8.dp),
                                    )
                                }
                            }
                        },
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides ltr) {
                            Scaffold(
                                snackbarHost = { SnackbarHost(snackbar) },
                                topBar = {
                                    TopAppBar(
                                        title = {
                                            Text(
                                                when (detail) {
                                                    is Detail.Comments -> "Comments"
                                                    is Detail.Profile -> "u/${detail.username}"
                                                    null -> screen.title
                                                },
                                            )
                                        },
                                        navigationIcon = {
                                            if (detail != null) {
                                                IconButton(onClick = { details.removeLastOrNull() }) {
                                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                                }
                                            } else if (screen == Screen.Swipe) {
                                                IconButton(onClick = vm::undo, enabled = state.canUndo) {
                                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo last swipe")
                                                }
                                            }
                                        },
                                        actions = {
                                            if (screen == Screen.Swipe && detail == null) {
                                                val top = state.visibleDeck.firstOrNull()
                                                IconButton(
                                                    onClick = { top?.let { scope.launch { CardSharer.share(context, it) } } },
                                                    enabled = top != null,
                                                ) {
                                                    Icon(Icons.Filled.Share, contentDescription = "Share this card")
                                                }
                                            }
                                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                                Icon(Icons.Filled.Menu, contentDescription = "Menu")
                                            }
                                        },
                                    )
                                },
                            ) { padding ->
                                val content = Modifier.padding(padding)
                                if (detail != null) {
                                    key(detail) {
                                        SwipeBackContainer(
                                            enabled = !drawerState.isOpen,
                                            onBack = { details.removeLastOrNull() },
                                            modifier = content,
                                        ) {
                                            when (detail) {
                                                is Detail.Comments -> CommentsScreen(
                                                    post = detail.post,
                                                    account = state.account,
                                                    community = vm.community,
                                                    onOpenProfile = openProfile,
                                                    onBlock = vm::setBlocked,
                                                    onStoryRemoved = { post ->
                                                        vm.forgetStory(post.id)
                                                        details.removeLastOrNull()
                                                    },
                                                )
                                                is Detail.Profile -> ProfileScreen(
                                                    username = detail.username,
                                                    account = state.account,
                                                    community = vm.community,
                                                    onOpenStory = openComments,
                                                    onBlock = vm::setBlocked,
                                                )
                                            }
                                        }
                                    }
                                } else if (screen == Screen.Swipe) {
                                    SwipeScreen(
                                        state = state,
                                        onSwipeLeft = vm::swipeLeft,
                                        onSwipeRight = vm::swipeRight,
                                        onRetry = vm::loadMore,
                                        onOpenSubreddits = { screen = Screen.Subreddits },
                                        onOpenNews = { screen = Screen.News },
                                        onTextScale = vm::setTextScale,
                                        onReadMode = vm::setReadMode,
                                        onOpenComments = openComments,
                                        onOpenProfile = openProfile,
                                        modifier = content,
                                    )
                                } else {
                                    // A fresh container per screen, so a half-swiped page never carries over to the next one.
                                    key(screen) {
                                        SwipeBackContainer(
                                            // While the menu is open, back closes it instead.
                                            enabled = !drawerState.isOpen,
                                            onBack = { screen = Screen.Swipe },
                                            modifier = content,
                                        ) {
                                            when (screen) {
                                                Screen.Saved -> SavedScreen(
                                                    saved = state.saved,
                                                    onOpenComments = openComments,
                                        onShare = { post -> scope.launch { CardSharer.share(context, post) } },
                                                    onRemove = { post ->
                                                        val index = state.saved.indexOf(post)
                                                        vm.removeSaved(post)
                                                        snackbar.currentSnackbarData?.dismiss()
                                                        scope.launch {
                                                            val result = snackbar.showSnackbar(
                                                                "Removed from liked",
                                                                actionLabel = "Undo",
                                                                duration = SnackbarDuration.Short,
                                                            )
                                                            if (result == SnackbarResult.ActionPerformed) vm.restoreSaved(post, index)
                                                        }
                                                    },
                                                )
                                                Screen.News -> NewsSourcesScreen(
                                                    newsFeeds = state.newsFeeds,
                                                    onSetFeeds = vm::setNewsFeeds,
                                                )
                                                Screen.Subreddits -> SubredditsScreen(
                                                    subreddits = state.subreddits,
                                                    isChecking = state.isCheckingSubreddit,
                                                    error = state.subredditError,
                                                    onAdd = vm::addSubreddit,
                                                    onRemove = vm::removeSubreddit,
                                                    onRestoreDefaults = vm::restoreDefaultSubreddits,
                                                )
                                                Screen.Stats -> StatsScreen(stats = state.stats)
                                                Screen.Write -> WriteScreen(
                                                    account = state.account,
                                                    writing = state.writing,
                                                    onOpenAccount = { screen = Screen.Account },
                                                    onLoad = vm::loadMyStories,
                                                    onSave = vm::saveStory,
                                                    onDelete = vm::deleteStory,
                                                )
                                                Screen.Notifications -> NotificationsScreen(
                                                    community = vm.community,
                                                    onSeen = vm::markNotificationsRead,
                                                    onOpenStory = openComments,
                                                )
                                                Screen.Moderation -> ModerationScreen(
                                                    community = vm.community,
                                                    onStoryRemoved = vm::forgetStory,
                                                )
                                                Screen.Account -> AccountScreen(
                                                    account = state.account,
                                                    onGoogle = {
                                                        scope.launch {
                                                            runCatching { GoogleSignIn.idToken(context) }
                                                                .onSuccess { token -> token?.let(vm::signInWithGoogle) }
                                                                .onFailure { vm.showAccountError(it.message ?: "Google sign-in didn't work.") }
                                                        }
                                                    },
                                                    onRequestCode = vm::requestSignInCode,
                                                    onVerifyCode = vm::verifySignInCode,
                                                    onCancel = vm::cancelSignIn,
                                                    onSetUsername = vm::setUsername,
                                                    onDeleteAccount = vm::deleteAccount,
                                                    community = vm.community,
                                                    onUnblock = { name -> vm.setBlocked(name, false) },
                                                    onSyncNow = vm::syncNow,
                                                    onSignOut = vm::signOut,
                                                )
                                                Screen.Settings -> SettingsScreen(
                                                    reading = state.reading,
                                                    showNsfw = state.showNsfw,
                                                    storiesReady = state.deck.size,
                                                    isDownloading = state.isDownloading,
                                                    onTextScale = vm::setTextScale,
                                                    onSerif = vm::setSerif,
                                                    onShowNsfw = vm::setShowNsfw,
                                                    onDownload = vm::downloadForOffline,
                                                )
                                                Screen.Swipe -> {}
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
