@file:OptIn(ExperimentalUuidApi::class)
package com.rk.ai.agent

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Centralized constants for the vibe-coding agent system.
 *
 * All magic numbers, limits, and thresholds across tools and execution
 * should be referenced from here to ensure consistency and easy tuning.
 */
object VibeCodingConstants {

    // ── Tool Execution ──────────────────────────────────────────────
    const val DEFAULT_TOOL_TIMEOUT_MS = 60_000L
    const val COMMAND_TOOL_TIMEOUT_MS = 120_000L
    const val MAX_TOOL_RETRIES = 2
    const val MAX_CONSECUTIVE_ERRORS = 0
    const val TOOL_CACHE_MAX_ENTRIES = 100
    const val TOOL_CACHE_TTL_MS = 120_000L

    // ── Generation / Steps ─────────────────────────────────────────
    const val MAX_GENERATION_STEPS = 256
    const val MAX_COMPACTIONS = 3
    const val DOOM_LOOP_THRESHOLD = 3
    const val MAX_TOOL_OUTPUT_CHARS = 10_000
    const val CONTEXT_OVERFLOW_WARNING_CHARS = 500_000

    // ── File Operations ────────────────────────────────────────────
    const val MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024   // 50 MB
    const val MAX_FILE_CONTENT_CHARS = 250_000
    const val ACTIVE_FILE_TRUNCATION_CHARS = 500_000
    const val MAX_BATCH_EDIT_FILES = 20
    const val MAX_LINES_PER_FILE = 100_000

    // ── Search ─────────────────────────────────────────────────────
    const val MAX_SEARCH_RESULTS = 100
    const val DEFAULT_SEARCH_LIMIT = 50
    const val MAX_FILE_GLOB_RESULTS = 5_000
    const val MAX_SYMBOL_SEARCH_RESULTS = 50

    // ── Web Tools ──────────────────────────────────────────────────
    const val WEB_FETCH_DEFAULT_TIMEOUT_SEC = 30
    const val WEB_FETCH_MAX_TIMEOUT_SEC = 60
    const val WEB_DOWNLOAD_DEFAULT_TIMEOUT_SEC = 60
    const val WEB_DOWNLOAD_MAX_TIMEOUT_SEC = 120
    const val WEB_FETCH_DEFAULT_MAX_BYTES = 5 * 1024 * 1024       // 5 MB
    const val WEB_FETCH_ABSOLUTE_MAX_BYTES = 20 * 1024 * 1024     // 20 MB
    const val WEB_DOWNLOAD_DEFAULT_MAX_BYTES = 100 * 1024 * 1024  // 100 MB
    const val WEB_DOWNLOAD_ABSOLUTE_MAX_BYTES = 500 * 1024 * 1024 // 500 MB
    const val WEB_RESEARCH_DEFAULT_PAGE_CHARS = 4_000
    const val WEB_RESEARCH_MAX_PAGE_CHARS = 20_000
    const val WEB_RESEARCH_DEFAULT_TIMEOUT_SEC = 20
    const val WEB_RESEARCH_MAX_TIMEOUT_SEC = 60
    const val WEB_SEARCH_DEFAULT_NUM_RESULTS = 8
    const val WEB_SEARCH_MAX_NUM_RESULTS = 20
    const val WEB_RESEARCH_DEFAULT_NUM_RESULTS = 5
    const val WEB_RESEARCH_MAX_NUM_RESULTS = 10

    // ── Memory & Context ───────────────────────────────────────────
    const val MAX_SESSION_LOG_ENTRIES = 200
    const val MAX_RECENT_EDITS = 50
    const val MAX_WORKING_MEMORY_EDITS = 50
    const val CONTEXT_BUNDLE_CHAR_LIMIT = 50_000

    // ── Indexer ────────────────────────────────────────────────────
    const val INDEXER_MAX_FILES_PER_EXT = 2_000
    const val INDEXER_MAX_SYMBOLS = 10_000
    const val INDEXER_CACHE_TTL_MS = 300_000L // 5 min

    // ── Self-Review ────────────────────────────────────────────────
    const val REVIEW_MAX_RETRIES = 2
    const val REVIEW_SHORT_OUTPUT_THRESHOLD = 100
    const val REVIEW_LARGE_OUTPUT_THRESHOLD = 5_000
    const val REVIEW_VERY_LARGE_OUTPUT_THRESHOLD = 10_000
    const val REVIEW_MIN_SCORE_PASS = 50
    const val REVIEW_SCORE_LOW = 30
    const val REVIEW_SCORE_MEDIUM = 50
    const val REVIEW_SCORE_HIGH = 70
    const val REVIEW_SCORE_GOOD = 85

    // ── Loop Detection ─────────────────────────────────────────────
    const val LOOP_DETECTOR_WINDOW_SIZE = 12
    const val LOOP_DETECTOR_REPEAT_THRESHOLD = 3
    const val LOOP_DETECTOR_PATTERN_WINDOW = 6
    const val LOOP_DETECTOR_PATTERN_REPEAT_THRESHOLD = 2
    const val LOOP_DETECTOR_PROJECT_READ_THRESHOLD = 4

    // ── Token / Cost ───────────────────────────────────────────────
    const val DEFAULT_CONTEXT_WINDOW = 128_000
    const val DEFAULT_MAX_OUTPUT_TOKENS = 8_192
    const val ESTIMATED_CHARS_PER_TOKEN = 4

    // ── Agent Limits ───────────────────────────────────────────────
    const val MAX_AGENT_DELEGATION_DEPTH = 3
    const val MAX_AGENT_TOOLS = 50
}
