package com.rk.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rk.activities.main.MainActivity
import com.rk.filetree.FileTreeTab
import com.rk.filetree.currentDrawerTab
import com.rk.resources.drawables
import com.rk.resources.strings
import com.rk.tabs.editor.EditorTab
import com.rk.utils.findGitRoot
import com.rk.utils.toast
import java.io.File
import kotlinx.coroutines.launch
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

@Composable
fun GitPanel(
    gitViewModel: GitViewModel,
    onRefresh: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    var showBranchesMenu by remember { mutableStateOf(false) }
    var showNewBranchDialog by remember { mutableStateOf(false) }
    var newBranch by remember { mutableStateOf("") }
    var newBranchError by remember { mutableStateOf<String?>(null) }
    var showPushDialog by remember { mutableStateOf(false) }
    var pushForce by remember { mutableStateOf(false) }
    var showRemoteDialog by remember { mutableStateOf(false) }
    val invalidBranchMsg = stringResource(strings.value_invalid)

    val gitChanges = gitViewModel.currentRoot.value?.absolutePath?.let { gitViewModel.changes[it] } ?: emptyList()
    val hasCheckedChanges by remember(gitChanges) {
        derivedStateOf { gitChanges.count { it.isChecked } > 0 }
    }

    val staged = remember(gitChanges) { gitChanges.filter { it.isChecked } }
    val unstaged = remember(gitChanges) { gitChanges.filter { !it.isChecked && it.type != ChangeType.UNTRACKED && it.type != ChangeType.CONFLICTING } }
    val untrackedChanges = remember(gitChanges) { gitChanges.filter { !it.isChecked && it.type == ChangeType.UNTRACKED } }
    val conflicts = remember(gitChanges) { gitChanges.filter { it.type == ChangeType.CONFLICTING } }

    var stagedExpanded by remember { mutableStateOf(true) }
    var changesExpanded by remember { mutableStateOf(true) }
    var untrackedExpanded by remember { mutableStateOf(true) }
    var conflictsExpanded by remember { mutableStateOf(true) }

    LaunchedEffect(gitViewModel.currentRoot.value) {
        if (gitViewModel.currentRoot.value != null) {
            gitViewModel.loadCommitLog(maxCount = 10)
        }
    }

    val commitMessage = gitViewModel.currentRoot.value?.absolutePath?.let { gitViewModel.commitMessages[it] } ?: ""
    val amend = gitViewModel.currentRoot.value?.absolutePath?.let { gitViewModel.amends[it] } ?: false

    Column(modifier = Modifier.fillMaxSize().background(colorScheme.surface)) {
        if (gitViewModel.currentRoot.value == null) {
            NoGitRepository(gitViewModel = gitViewModel)
        } else {
            GitBranchHeader(
                gitViewModel = gitViewModel,
                showBranchesMenu = showBranchesMenu,
                onToggleBranchesMenu = { showBranchesMenu = !showBranchesMenu },
                onSelectBranch = { branch ->
                    gitViewModel.checkout(branch)
                    showBranchesMenu = false
                },
                onNewBranch = { showNewBranchDialog = true },
                onPull = {
                    scope.launch {
                        gitViewModel.pull().join()
                        MainActivity.instance?.viewModel?.tabs?.filterIsInstance<EditorTab>()?.forEach {
                            if (findGitRoot(it.file.getAbsolutePath()) != null) it.refresh()
                        }
                    }
                },
                onFetch = { gitViewModel.fetch() },
                onPush = { showPushDialog = true },
                onRefresh = onRefresh,
                onManageRemotes = { showRemoteDialog = true },
            )

            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.1f), thickness = 0.5.dp)

            // Operation-specific loading bar
            val loadingOp = when {
                gitViewModel.isPulling -> "Pulling..."
                gitViewModel.isPushing -> "Pushing..."
                gitViewModel.isCommitting -> "Committing..."
                gitViewModel.isCheckingOut -> "Checking out..."
                gitViewModel.isFetching -> "Fetching..."
                gitViewModel.isStashing -> "Stashing..."
                else -> null
            }
            if (loadingOp != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            } else if (gitViewModel.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            }

            // Error banner with dismiss
            val errorMsg = gitViewModel.errorMessage
            if (errorMsg != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.small,
                    color = colorScheme.errorContainer.copy(alpha = 0.6f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = errorMsg,
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { gitViewModel.clearError() },
                            modifier = Modifier.size(20.dp),
                        ) {
                            Icon(
                                painter = painterResource(drawables.close),
                                contentDescription = "Dismiss",
                                tint = colorScheme.onErrorContainer,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }

            if (gitChanges.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = rememberLazyListState(),
                ) {
                    if (conflicts.isNotEmpty()) {
                        item {
                            ConflictGroup(conflicts, conflictsExpanded, gitViewModel) { conflictsExpanded = !conflictsExpanded }
                        }
                    }
                    if (staged.isNotEmpty()) {
                        item {
                            ChangeGroup(
                                label = "Staged Changes",
                                items = staged,
                                expanded = stagedExpanded,
                                gitViewModel = gitViewModel,
                                isStaged = true,
                                onToggle = { stagedExpanded = !stagedExpanded },
                            )
                        }
                    }
                    if (unstaged.isNotEmpty()) {
                        item {
                            ChangeGroup(
                                label = stringResource(strings.changes),
                                items = unstaged,
                                expanded = changesExpanded,
                                gitViewModel = gitViewModel,
                                onToggle = { changesExpanded = !changesExpanded },
                            )
                        }
                    }
                    if (untrackedChanges.isNotEmpty()) {
                        item {
                            ChangeGroup(
                                label = stringResource(strings.untracked),
                                items = untrackedChanges,
                                expanded = untrackedExpanded,
                                gitViewModel = gitViewModel,
                                onToggle = { untrackedExpanded = !untrackedExpanded },
                            )
                        }
                    }
                }
            } else {
                Box(Modifier.weight(1f)) {
                    RepositoryOverview(gitViewModel = gitViewModel, colorScheme = colorScheme)
                }
            }

            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.1f), thickness = 0.5.dp)

            GitCommitArea(
                amend = amend,
                commitMessage = commitMessage,
                hasCheckedChanges = hasCheckedChanges,
                isLoading = gitViewModel.isCommitting,
                onToggleAmend = { gitViewModel.toggleAmend(it) },
                onChangeCommitMessage = { gitViewModel.changeCommitMessage(it) },
                onCommit = { gitViewModel.commit() },
                onCommitAndPush = {
                    scope.launch {
                        gitViewModel.commit().join()
                        showPushDialog = true
                    }
                },
            )
        }
    }

    if (showNewBranchDialog) {
        NewBranchDialog(
            currentBranch = gitViewModel.currentBranch,
            newBranch = newBranch,
            error = newBranchError,
            onValueChange = {
                newBranch = it
                newBranchError = if (it.isBlank()) invalidBranchMsg else null
            },
            onConfirm = {
                gitViewModel.checkoutNew(newBranch, gitViewModel.currentBranch)
                showNewBranchDialog = false
                newBranch = ""
                newBranchError = null
            },
            onDismiss = {
                showNewBranchDialog = false
                newBranch = ""
                newBranchError = null
            },
        )
    }

    if (showPushDialog) {
        PushConfirmationDialog(
            remoteName = "origin",
            onConfirm = { force ->
                gitViewModel.push(force)
                showPushDialog = false
                pushForce = false
            },
            onDismiss = {
                showPushDialog = false
                pushForce = false
            },
        )
    }

    if (showRemoteDialog) {
        ManageRemotesDialog(
            gitViewModel = gitViewModel,
            onDismiss = { showRemoteDialog = false },
        )
    }
}

@Composable
fun ManageRemotesDialog(
    gitViewModel: GitViewModel,
    onDismiss: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    var remotes by remember { mutableStateOf(gitViewModel.getRemotes()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newRemoteName by remember { mutableStateOf("") }
    var newRemoteUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(drawables.git), contentDescription = null, tint = colorScheme.primary) },
        title = { Text(stringResource(strings.remotes)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 300.dp)) {
                if (remotes.isEmpty()) {
                    Text(
                        "No remotes configured",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                } else {
                    remotes.forEach { (name, url) ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        name,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = colorScheme.onSurface,
                                    )
                                    Text(
                                        url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        gitViewModel.removeRemote(name)
                                        remotes = gitViewModel.getRemotes()
                                    },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(drawables.close),
                                        contentDescription = stringResource(strings.remove_remote),
                                        tint = colorScheme.error.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(stringResource(strings.add_remote), color = colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(strings.add_remote)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newRemoteName,
                        onValueChange = { newRemoteName = it },
                        label = { Text(stringResource(strings.remote_name)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                    )
                    OutlinedTextField(
                        value = newRemoteUrl,
                        onValueChange = { newRemoteUrl = it },
                        label = { Text(stringResource(strings.remote_url)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newRemoteName.isNotBlank() && newRemoteUrl.isNotBlank(),
                    onClick = {
                        gitViewModel.addRemote(newRemoteName, newRemoteUrl)
                        remotes = gitViewModel.getRemotes()
                        newRemoteName = ""
                        newRemoteUrl = ""
                        showAddDialog = false
                    },
                ) {
                    Text(stringResource(strings.add_remote))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(strings.cancel))
                }
            },
        )
    }
}

@Composable
fun PushConfirmationDialog(
    remoteName: String,
    onConfirm: (force: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var force by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(drawables.push), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(strings.push_confirm_title)) },
        text = {
            Column {
                Text(stringResource(strings.push_confirm_message, remoteName))
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(32.dp).clickable { force = !force },
                ) {
                    Checkbox(checked = force, onCheckedChange = { force = it }, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(strings.force_push),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(force) }) {
                Text(
                    if (force) stringResource(strings.push_confirm_title) else stringResource(strings.push),
                    color = if (force) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(strings.cancel))
            }
        },
    )
}

@Composable
private fun NoGitRepository(gitViewModel: GitViewModel) {
    val root = currentDrawerTab.let { tab ->
        if (tab is FileTreeTab) File(tab.root.getAbsolutePath()) else null
    }
    val hasGitDir = root != null && FileRepositoryBuilder().findGitDir(root).gitDir != null

    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painter = painterResource(drawables.git),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
            Text(
                "No git repository",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Open a project with a .git directory",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            if (hasGitDir) {
                Button(onClick = { gitViewModel.loadRepository(root!!.absolutePath) },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("Load repository")
                }
            }
        }
    }
}
