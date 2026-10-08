package com.rk.settings.ai

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.rk.ai.AiProvider
import com.rk.ai.AiSettingsSync
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.components.SettingsToggle
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.utils.toast

@Composable
fun AiSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    var apiKey by remember { mutableStateOf(Settings.ai_api_key) }
    var showApiKey by remember { mutableStateOf(false) }
    var selectedAgent by remember { mutableStateOf(Settings.ai_agent) }
    var inlineCompletion by remember { mutableStateOf(Settings.ai_inline_completion) }
    var autoApply by remember { mutableStateOf(Settings.ai_auto_apply) }
    var projectConfig by remember { mutableStateOf(Settings.ai_project_config_enabled) }
    var showGuide by remember { mutableStateOf(Settings.ai_api_key.isBlank()) }

    PreferenceLayout(label = stringResource(strings.ai), backArrowVisible = true) {
        // ── Quick-Start Guide Card for Beginners ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.primaryContainer.copy(alpha = 0.35f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Beginner Quick-Start",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = "Get coding with AI in under 1 minute",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "1. Get a free Google Gemini key from Google AI Studio (no credit card needed).\n" +
                                "2. Paste the key below — it automatically powers both VibeCoding and terminal agents.\n" +
                                "3. Tap 'Save API Key' and start building!",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )

                    Spacer(Modifier.height(14.dp))

                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Get Free Gemini API Key")
                    }
                }
            }
        }

        // ── API Key Configuration ──
        PreferenceGroup(heading = "API Configuration") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                // Key status badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    val isConfigured = apiKey.isNotBlank()
                    Icon(
                        imageVector = if (isConfigured) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = if (isConfigured) colorScheme.primary else colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isConfigured) "API Key Configured & Ready" else "API Key Required for AI Features",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isConfigured) colorScheme.primary else colorScheme.error
                    )
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Gemini API Key") },
                    placeholder = { Text("AIzaSy...") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle visibility"
                                )
                            }
                            IconButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = cm?.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0).text?.toString().orEmpty().trim()
                                    if (text.isNotBlank()) {
                                        apiKey = text
                                        AiSettingsSync.syncApiKey(text, scope)
                                        toast("API key pasted and saved!")
                                    }
                                }
                            }) {
                                Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste from clipboard")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            AiSettingsSync.syncApiKey(apiKey, scope)
                            toast("API Key saved successfully!")
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save API Key")
                    }

                    if (apiKey.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                apiKey = ""
                                AiSettingsSync.syncApiKey("", scope)
                                toast("API Key cleared")
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }
        }

        // ── Default Assistant Selector ──
        PreferenceGroup(heading = "Coding Assistant") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistantCard(
                    title = "VibeCoding (Visual Assistant)",
                    badge = "Beginner Friendly",
                    description = "In-app chat, code explanation, file edits, and one-tap diffs. No terminal knowledge needed.",
                    icon = Icons.Outlined.AutoFixHigh,
                    selected = selectedAgent == "vibecoding",
                    onClick = {
                        selectedAgent = "vibecoding"
                        Settings.ai_agent = "gemini" // Keep fallback CLI valid
                    }
                )

                AssistantCard(
                    title = "Antigravity CLI (Terminal Agent)",
                    badge = "By Google DeepMind",
                    description = "Autonomous coding agent in the terminal with full tool calling, command execution, and codebase edits.",
                    icon = Icons.Outlined.Terminal,
                    selected = selectedAgent == "antigravity",
                    onClick = {
                        selectedAgent = "antigravity"
                        Settings.ai_agent = "antigravity"
                        AiProvider.sessionManager?.switchAgent("antigravity")
                    }
                )

                AssistantCard(
                    title = "Gemini CLI",
                    badge = null,
                    description = "Official Google Gemini coding agent in the terminal for interactive guidance.",
                    icon = Icons.Outlined.Psychology,
                    selected = selectedAgent == "gemini",
                    onClick = {
                        selectedAgent = "gemini"
                        Settings.ai_agent = "gemini"
                        AiProvider.sessionManager?.switchAgent("gemini")
                    }
                )

                AssistantCard(
                    title = "Claude Code",
                    badge = null,
                    description = "Anthropic's terminal agent for high-precision refactoring and reasoning.",
                    icon = Icons.Outlined.SmartToy,
                    selected = selectedAgent == "claude",
                    onClick = {
                        selectedAgent = "claude"
                        Settings.ai_agent = "claude"
                        AiProvider.sessionManager?.switchAgent("claude")
                    }
                )

                AssistantCard(
                    title = "OpenCode",
                    badge = null,
                    description = "Open-source terminal coding assistant.",
                    icon = Icons.Outlined.Code,
                    selected = selectedAgent == "opencode",
                    onClick = {
                        selectedAgent = "opencode"
                        Settings.ai_agent = "opencode"
                        AiProvider.sessionManager?.switchAgent("opencode")
                    }
                )
            }
        }

        // ── Editor Features ──
        PreferenceGroup(heading = "Editor Features") {
            SettingsToggle(
                label = "Inline Code Completion",
                description = "Show real-time AI code suggestions as you type in the editor",
                state = remember { mutableStateOf(inlineCompletion) },
                default = inlineCompletion,
                sideEffect = { isChecked ->
                    inlineCompletion = isChecked
                    Settings.ai_inline_completion = isChecked
                }
            )

            SettingsToggle(
                label = "Auto-Apply Edits",
                description = "Automatically accept and apply clean AI code edits",
                state = remember { mutableStateOf(autoApply) },
                default = autoApply,
                sideEffect = { isChecked ->
                    autoApply = isChecked
                    Settings.ai_auto_apply = isChecked
                }
            )

            SettingsToggle(
                label = "Project Config Support",
                description = "Read repo-level agent instructions from .xed/agent.json",
                state = remember { mutableStateOf(projectConfig) },
                default = projectConfig,
                sideEffect = { isChecked ->
                    projectConfig = isChecked
                    Settings.ai_project_config_enabled = isChecked
                }
            )
        }
    }
}

@Composable
private fun AssistantCard(
    title: String,
    badge: String?,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val borderColor = if (selected) colorScheme.primary else colorScheme.outlineVariant.copy(alpha = 0.5f)
    val bgColor = if (selected) colorScheme.primaryContainer.copy(alpha = 0.25f) else colorScheme.surfaceContainerHigh

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        modifier = Modifier
            .fillMaxWidth()
            .border(width = if (selected) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (selected) colorScheme.primaryContainer else colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = colorScheme.onSurface
                    )
                    if (badge != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = colorScheme.primary.copy(alpha = 0.15f),
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                color = colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    lineHeight = 16.sp
                )
            }

            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .padding(start = 4.dp)
                )
            }
        }
    }
}
