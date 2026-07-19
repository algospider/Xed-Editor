@file:OptIn(ExperimentalUuidApi::class)
package com.rk.ai.nativeagent.engine

import android.util.Log
import com.rk.ai.agent.events.VibeCodingEvent
import com.rk.ai.agent.events.VibeCodingEventBus
import com.rk.ai.core.MessageRole
import com.rk.ai.models.UIMessage
import com.rk.ai.models.toMessageNode
import com.rk.ai.persistence.repo.ConversationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "SessionManager"

/**
 * Manages session lifecycle, branching, and conversation persistence
 * for the vibe-coding engine.
 */
class SessionManager(
    private val getState: () -> VibeCodingState,
    private val updateState: (VibeCodingState.() -> VibeCodingState) -> Unit,
    private val conversationRepo: ConversationRepository,
    private val engineScope: CoroutineScope,
    private val vibeEventBus: VibeCodingEventBus,
    private val getCurrentAssistantId: () -> Uuid,
    private val getCommandCatalogSnapshot: () -> List<CommandCatalogEntry>,
    private val getPermissionRulesSnapshot: () -> List<PermissionAutoRespondRule>,
) {

    // ── Session Tree ────────────────────────────────────────────────

    fun createBranchSession(parentSessionId: Uuid, title: String = "Branch"): Uuid {
        val newId = Uuid.random()
        val node = SessionNode(
            id = newId,
            parentId = parentSessionId,
            title = title,
        )
        updateState {
            copy(
                sessionTree = sessionTree + node,
                activeSessionId = newId,
                parentSessionId = parentSessionId,
            )
        }
        engineScope.launch {
            vibeEventBus.emit(VibeCodingEvent.SessionCreated(newId, parentSessionId))
        }
        return newId
    }

    fun switchToSession(sessionId: Uuid) {
        val node = getState().sessionById[sessionId] ?: return
        updateState {
            copy(
                messages = node.messages,
                activeSessionId = sessionId,
                parentSessionId = node.parentId,
            )
        }
    }

    fun closeSession(sessionId: Uuid) {
        val current = getState().activeSessionId
        val tree = getState().sessionTree.toMutableList()
        tree.removeAll { it.id == sessionId }
        val newActive = if (current == sessionId) tree.lastOrNull()?.id else current
        updateState {
            copy(
                sessionTree = tree,
                activeSessionId = newActive,
                parentSessionId = newActive?.let { getState().sessionById[it]?.parentId },
            )
        }
    }

    fun renameSession(sessionId: Uuid, newTitle: String) {
        val tree = getState().sessionTree.toMutableList()
        val idx = tree.indexOfFirst { it.id == sessionId }
        if (idx >= 0) {
            tree[idx] = tree[idx].copy(title = newTitle.take(80))
            updateState { copy(sessionTree = tree) }
        }
    }

    fun ensureSessionExists(titleHint: String) {
        if (getState().activeSessionId == null) {
            val sessionId = Uuid.random()
            val node = SessionNode(id = sessionId, title = titleHint.take(80))
            updateState {
                copy(
                    sessionTree = sessionTree + node,
                    activeSessionId = sessionId,
                )
            }
        }
    }

    fun saveCurrentSessionMessages() {
        val sessionId = getState().activeSessionId ?: return
        val tree = getState().sessionTree.toMutableList()
        val idx = tree.indexOfFirst { it.id == sessionId }
        if (idx >= 0) {
            tree[idx] = tree[idx].copy(messages = getState().messages)
            updateState { copy(sessionTree = tree) }
        }
    }

    // ── Conversation Persistence ────────────────────────────────────

    suspend fun saveConversation() {
        try {
            val state = getState()
            val messages = state.messages
            if (messages.isEmpty()) return

            val existingId = state.currentConversationId
            val convId = existingId ?: Uuid.random()
            val assistantId = getCurrentAssistantId()

            val title = messages.firstOrNull { it.role == MessageRole.USER }
                ?.toText()?.take(100)?.trim() ?: "VibeCoding"

            val conversation = com.rk.ai.models.Conversation(
                id = convId,
                assistantId = assistantId,
                title = title,
                messageNodes = messages.map { msg -> msg.toMessageNode() },
            )

            if (existingId != null && conversationRepo.existsConversationById(convId)) {
                conversationRepo.updateConversation(conversation)
            } else {
                conversationRepo.insertConversation(conversation)
            }

            updateState { copy(currentConversationId = convId) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save conversation", e)
        }
    }

    fun loadConversation(conversation: com.rk.ai.models.Conversation) {
        engineScope.launch(Dispatchers.IO) {
            try {
                val loaded = conversationRepo.getConversationById(conversation.id)
                if (loaded != null) {
                    val messages = loaded.currentMessages
                    updateState {
                        copy(
                            messages = messages,
                            currentConversationId = loaded.id,
                            error = null,
                        )
                    }
                    saveCurrentSessionMessages()
                }
            } catch (e: Exception) {
                updateState { copy(error = "Failed to load conversation: ${e.message}") }
            }
        }
    }

    fun deleteConversation(conversationId: Uuid) {
        engineScope.launch(Dispatchers.IO) {
            try {
                conversationRepo.getConversationById(conversationId)?.let { conv ->
                    conversationRepo.deleteConversation(conv)
                }
                val state = getState()
                if (state.currentConversationId == conversationId) {
                    updateState {
                        VibeCodingState(
                            commandCatalog = getCommandCatalogSnapshot(),
                            permissionAutoRespondRules = getPermissionRulesSnapshot(),
                        )
                    }
                }
            } catch (e: Exception) {
                updateState { copy(error = "Failed to delete conversation: ${e.message}") }
            }
        }
    }
}
