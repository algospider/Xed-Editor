@file:OptIn(ExperimentalUuidApi::class)
package com.rk.ai.nativeagent.engine

import android.content.Context
import com.rk.ai.agent.files.CommandFileLoader
import com.rk.ai.agent.files.CommandDefinition
import com.rk.ai.agent.files.UnifiedConfig
import com.rk.ai.agent.files.XedConfig
import com.rk.ai.agent.files.XedConfigLoader
import com.rk.ai.agent.hooks.HookContext
import com.rk.ai.agent.hooks.HookEvent
import com.rk.ai.agent.hooks.HookManager
import com.rk.ai.agent.hooks.HookResult
import com.rk.ai.service.IdeService
import java.io.File
import kotlin.uuid.ExperimentalUuidApi

/**
 * Manages project configuration, permissions, and the commands catalog
 * for the vibe-coding engine.
 */
class EngineConfigManager(
    private val context: Context,
    private val ideService: IdeService,
    private val permissionManager: PermissionManager,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val hookManager: HookManager,
    private val updateState: (VibeCodingState.() -> VibeCodingState) -> Unit,
) {
    var xedConfig: XedConfig = XedConfig()
    private val storedCommandCatalog = mutableListOf<CommandCatalogEntry>()

    // ── Project Config ──────────────────────────────────────────────

    fun loadProjectConfig() {
        val workspace = try {
            ideService.getPrimaryWorkspacePath()
        } catch (_: Exception) { return }
        updateState { copy(workspacePath = workspace) }
        xedConfig = XedConfigLoader.loadConfig(workspace)
        applyConfigPermissions()
        xedConfig.instructions?.let {
            if (it.isNotBlank()) {
                systemPromptBuilder.projectInstructions = it
            }
        }
    }

    fun refreshProjectConfig() {
        systemPromptBuilder.reset()
        loadProjectConfig()
        loadFileCommandsIntoCatalog()
    }

    fun isToolEnabled(toolName: String): Boolean {
        return xedConfig.tools[toolName] ?: true
    }

    fun applyConfigPermissions() {
        for (rule in xedConfig.permission) {
            val action = when (rule.action.lowercase()) {
                "allow" -> PermissionAction.ALLOW
                "deny" -> PermissionAction.DENY
                else -> PermissionAction.ASK
            }
            permissionManager.addRule(PermissionAutoRespondRule(
                toolPattern = rule.tool,
                argPattern = rule.arg,
                action = action,
                description = rule.description,
            ))
        }
    }

    fun applyPermissionRules(cfg: UnifiedConfig) {
        for (rule in cfg.permissionRules) {
            val action = when (rule.action.lowercase()) {
                "allow" -> PermissionAction.ALLOW
                "deny" -> PermissionAction.DENY
                else -> PermissionAction.ASK
            }
            permissionManager.addRule(
                PermissionAutoRespondRule(
                    toolPattern = rule.toolPattern,
                    argPattern = rule.argPattern,
                    action = action,
                    description = rule.description,
                )
            )
        }
    }

    // ── Commands Catalog ────────────────────────────────────────────

    fun loadFileCommandsIntoCatalog() {
        val fileCommands = CommandFileLoader.listCommands(context)
        val workspace = try {
            ideService.getPrimaryWorkspacePath()
        } catch (_: Exception) { "" }

        val workspaceCommands = if (workspace.isNotBlank()) {
            val workspaceCommandsRoot = File(workspace, ".xed/commands")
            if (workspaceCommandsRoot.exists() && workspaceCommandsRoot.isDirectory) {
                workspaceCommandsRoot.listFiles()
                    ?.filter { it.extension == "md" }
                    ?.mapNotNull { file -> CommandFileLoader.parseFile(file) }
                    ?: emptyList()
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        val merged = mutableMapOf<String, CommandDefinition>()
        for (cmd in fileCommands) {
            merged[cmd.id] = cmd
        }
        for (cmd in workspaceCommands) {
            merged[cmd.id] = cmd
        }

        for (cmd in merged.values) {
            if (cmd.hidden) continue
            addCommandToCatalog(CommandCatalogEntry(
                id = "file:${cmd.id}",
                title = cmd.name,
                description = cmd.description,
                category = cmd.category,
                slash = cmd.id,
                prompt = cmd.prompt,
            ))
        }
    }

    fun refreshCommands() {
        storedCommandCatalog.removeAll { it.id.startsWith("file:") }
        loadFileCommandsIntoCatalog()
        updateState { copy(commandCatalog = storedCommandCatalog.toList()) }
    }

    fun addCommandToCatalog(entry: CommandCatalogEntry) {
        storedCommandCatalog.removeAll { it.id == entry.id }
        storedCommandCatalog.add(entry)
        updateState { copy(commandCatalog = storedCommandCatalog.toList()) }
    }

    fun removeCommandFromCatalog(id: String) {
        storedCommandCatalog.removeAll { it.id == id }
        updateState { copy(commandCatalog = storedCommandCatalog.toList()) }
    }

    fun getCommandCatalog(): List<CommandCatalogEntry> = storedCommandCatalog.toList()

    fun getCommandCatalogRef(): MutableList<CommandCatalogEntry> = storedCommandCatalog

    // ── Hooks & Permissions ─────────────────────────────────────────

    suspend fun evaluateHooks(event: HookEvent, context: HookContext): HookResult {
        return hookManager.checkAll(event, context)
    }

    fun addPermissionAutoRespondRule(rule: PermissionAutoRespondRule) {
        permissionManager.addRule(rule)
        updateState { copy(permissionAutoRespondRules = permissionManager.rules) }
    }

    fun removePermissionAutoRespondRule(idOrPattern: String) {
        permissionManager.removeRule(idOrPattern)
        updateState { copy(permissionAutoRespondRules = permissionManager.rules) }
    }
}
