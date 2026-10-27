package com.theguruscat.kissa.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.theguruscat.kissa.AccountState
import com.theguruscat.kissa.BuildConfig
import com.theguruscat.kissa.data.GoogleSignIn
import com.theguruscat.kissa.data.StoriesRepository

/**
 * The account: signed out, it offers "Continue with Google" or an email and then the 6-digit code sent
 * there; signed in, it shows the username (or asks for one), the address, when this phone last synced, and
 * Sync now / Sign out.
 */
@Composable
fun AccountScreen(
    account: AccountState,
    onGoogle: () -> Unit,
    onRequestCode: (String) -> Unit,
    onVerifyCode: (String) -> Unit,
    onCancel: () -> Unit,
    onSetUsername: (String) -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    community: StoriesRepository,
    onUnblock: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            account.email != null -> {
                Username(account, onSetUsername)
                SignedIn(account, onSyncNow, onSignOut)
                BlockedAccounts(community, onUnblock)
                DeleteAccount(account, onDeleteAccount)
            }
            account.pendingEmail != null -> EnterCode(account, onVerifyCode, onRequestCode, onCancel)
            else -> EnterEmail(account, onGoogle, onRequestCode)
        }
        account.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        LegalLinks()
    }
}

@Composable
private fun EnterEmail(account: AccountState, onGoogle: () -> Unit, onRequestCode: (String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    val send = { if (email.isNotBlank()) onRequestCode(email) }
    Text("Join Kissa", style = MaterialTheme.typography.titleLarge)
    Text(
        "Reading needs no account. Sign in to write stories, comment and upvote, and to keep your liked cards, " +
            "sources and settings the same on every phone. No password needed.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (GoogleSignIn.isAvailable) {
        Button(onClick = onGoogle, enabled = !account.isBusy, modifier = Modifier.fillMaxWidth()) {
            Text("Continue with Google")
        }
        Text(
            "Or get a 6-digit code by email:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        modifier = Modifier.fillMaxWidth(),
    )
    BusyButton("Email me a code", account.isBusy, enabled = email.isNotBlank(), onClick = send)
}

@Composable
private fun EnterCode(
    account: AccountState,
    onVerifyCode: (String) -> Unit,
    onRequestCode: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    val verify = { if (code.length == 6) onVerifyCode(code) }
    Text("Check your email", style = MaterialTheme.typography.titleLarge)
    Text(
        "We sent a 6-digit code to ${account.pendingEmail}. It works for 10 minutes.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = code,
        onValueChange = { input -> code = input.filter(Char::isDigit).take(6) },
        label = { Text("Code") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { verify() }),
        modifier = Modifier.fillMaxWidth(),
    )
    BusyButton("Sign in", account.isBusy, enabled = code.length == 6, onClick = verify)
    Row {
        TextButton(onClick = { account.pendingEmail?.let(onRequestCode) }, enabled = !account.isBusy) { Text("Send again") }
        TextButton(onClick = onCancel, enabled = !account.isBusy) { Text("Use a different email") }
    }
}

/** The public name: asked for until one is picked, then shown with a way to change it. */
@Composable
private fun Username(account: AccountState, onSetUsername: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable(account.username) { mutableStateOf(account.username.orEmpty()) }
    if (account.username != null && !editing) {
        Text("u/${account.username}", style = MaterialTheme.typography.titleLarge)
        if (account.isBanned) {
            Text(
                "This account can read but can't post, comment, vote or report.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = { editing = true }, enabled = !account.isBusy) { Text("Change username") }
        return
    }
    Text("Pick a username", style = MaterialTheme.typography.titleLarge)
    Text(
        "Your stories and comments show it as u/name. 3 to 20 letters, numbers or underscores.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val save = { if (name.isNotBlank()) { onSetUsername(name); editing = false } }
    OutlinedTextField(
        value = name,
        onValueChange = { input -> name = input.filter { it.isLetterOrDigit() || it == '_' }.take(20) },
        label = { Text("Username") },
        prefix = { Text("u/") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Row {
        BusyButton("Save username", account.isBusy, enabled = name.length >= 3, onClick = save)
        if (account.username != null) TextButton(onClick = { editing = false }) { Text("Cancel") }
    }
}

@Composable
private fun SignedIn(account: AccountState, onSyncNow: () -> Unit, onSignOut: () -> Unit) {
    Text("Signed in", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(account.email.orEmpty(), style = MaterialTheme.typography.bodyLarge)
    Text(
        if (account.lastSyncedAt > 0) {
            "Last synced " + DateUtils.getRelativeTimeSpanString(
                account.lastSyncedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
        } else {
            "Not synced yet"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Liked cards, news sources, subreddits and settings sync on their own a few seconds after they change, " +
            "and when the app opens.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    BusyButton("Sync now", account.isBusy, enabled = true, onClick = onSyncNow)
    OutlinedButton(onClick = onSignOut, enabled = !account.isBusy) { Text("Sign out") }
}

/** Accounts this reader blocked, each with Unblock; hidden when there are none. */
@Composable
private fun BlockedAccounts(community: StoriesRepository, onUnblock: (String) -> Unit) {
    var blocked by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { community.blocked() }.onSuccess { blocked = it } }
    if (blocked.isEmpty()) return
    Text("Blocked accounts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    blocked.forEach { name ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("u/$name", modifier = Modifier.weight(1f))
            TextButton(onClick = { onUnblock(name); blocked = blocked - name }) { Text("Unblock") }
        }
    }
}

@Composable
private fun DeleteAccount(account: AccountState, onDeleteAccount: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    TextButton(
        onClick = { confirm = true },
        enabled = !account.isBusy,
        modifier = Modifier.padding(top = 16.dp),
    ) {
        Text("Delete account", color = MaterialTheme.colorScheme.error)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete your account?") },
            text = {
                Text(
                    "This removes your account, username, every story and comment you posted, your likes and your " +
                        "synced data from Kissa's server, right away. It can't be undone. What's on this phone stays.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; onDeleteAccount() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun LegalLinks() {
    val uriHandler = LocalUriHandler.current
    Row(Modifier.padding(top = 16.dp)) {
        TextButton(onClick = { uriHandler.openUri(BuildConfig.RELAY_URL + "/terms") }) { Text("Terms") }
        TextButton(onClick = { uriHandler.openUri(BuildConfig.RELAY_URL + "/privacy") }) { Text("Privacy") }
    }
}

@Composable
private fun BusyButton(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(label)
        }
    }
}
