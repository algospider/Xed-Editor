# Tool Test Report
**Date:** 2026-07-19  
**Project:** Xed-Editor  
**Branch:** dev  

## Summary
Testing all available tools in the IDE environment. Results categorized by status.

---

## ✅ TESTED & WORKING (27 tools)

### Project Orientation (7 tools)
| Tool | Status | Notes |
|------|--------|-------|
| `getProjectSummary` | ✅ | Returns full project tree, README, git status, open tabs |
| `getProjectConfig` | ✅ | Detected JS/TS, npm/yarn/pnpm |
| `getProjectStructure` | ✅ | Hierarchical directory tree, respects maxDepth/maxItems |
| `getIdeInfo` | ✅ | Returns IDE name, workspace path, open files |
| `getEnvironment` | ✅ | Returns all environment variables |
| `getClipboard` | ✅ | Returns clipboard content (workspace path) |
| `getGuidelines` | ✅ | Returns full VibeCoding developer guide |

### File Reading (4 tools)
| Tool | Status | Notes |
|------|--------|-------|
| `readFile` | ✅ | Supports line ranges (1-indexed) |
| `readFiles` | ✅ | Batch reads multiple files at once |
| `stat` | ✅ | File metadata: size, permissions, modified time |
| `wc` | ✅ | Line/word/char/byte count |
| `tail` | ✅ | Reads last N lines of a file |

### Search (3 tools)
| Tool | Status | Notes |
|------|--------|-------|
| `searchCode` | ✅ | Text/regex search across project |
| `searchSymbols` | ✅ | Declaration search by name |
| `searchAndRead` | ✅ | Search + read matching files in one call |

### Git (4 tools)
| Tool | Status | Notes |
|------|--------|-------|
| `getGitStatus` | ✅ | Shows branch, staged/modified/untracked |
| `getGitDiff` | ✅ | Shows unstaged diff |
| `gitLog` | ✅ | Commit history with hashes, authors, dates |
| `gitBranch` | ✅ | List/create/delete branches |

### Lists & Navigation (2 tools)
| Tool | Status | Notes |
|------|--------|-------|
| `listFiles` | ✅ | Directory listing with recursive support |
| `findFiles` | ✅ | Glob-based file search |

### Time & Info (1 tool)
| Tool | Status | Notes |
|------|--------|-------|
| `get_time_info` | ✅ | Returns date, time, timezone, timestamp |

### User Interaction (1 tool)
| Tool | Status | Notes |
|------|--------|-------|
| `showMessage` | ✅ | Displays toast notification |

### Web Search (1 tool)
| Tool | Status | Notes |
|------|--------|-------|
| `webSearch` | ✅ | DuckDuckGo search returning titles, URLs, snippets |

### Package Search (1 tool)
| Tool | Status | Notes |
|------|--------|-------|
| `npmSearch` | ✅ | Returns package name, version, publisher |

### Sub-Agents (1 tool)
| Tool | Status | Notes |
|------|--------|-------|
| `listAgents` | ✅ | Lists built-in + custom agents with capabilities |

### Commands (1 tool)
| Tool | Status | Notes |
|------|--------|-------|
| `listCustomCommands` | ✅ | Lists all custom commands from .xed/commands/ |

### Terminal (1 tool)
| Tool | Status | Notes |
|------|--------|-------|
| `getTerminalOutput` | ✅ | Returns recent terminal transcript |

---

## ❌ TESTED WITH ERRORS (2 tools)

| Tool | Status | Notes |
|------|--------|-------|
| `createFile` | ❌ | Failed: File already exists (temp file from earlier test) |
| `mavenSearch` | ❌ | HTTP 400 - needs specific groupId:artifactId format |

---

## ⏳ NOT YET TESTED (44 tools)

### File Editing (7 tools)
| Tool | Status | Notes |
|------|--------|-------|
| `writeFile` | ⏳ | Needs a file path + content |
| `editFile` | ⏳ | Surgical find-and-replace |
| `readAndEdit` | ⏳ | Read + edit in one call |
| `multiEditFile` | ⏳ | Multiple edits in one file atomically |
| `applyBatchEdits` | ⏳ | Multi-file batch edits |
| `deleteFile` | ⏳ | File deletion |
| `renameFile` | ⏳ | File/directory rename or move |

### Editor State (9 tools)
| Tool | Status | Notes |
|------|--------|-------|
| `getOpenFiles` | ✅ Already tested above | - |
| `getActiveFile` | ✅ Already tested above | - |
| `getSelection` | ✅ Already tested above | - |
| `replaceSelection` | ⏳ | Replace selected text |
| `insertAtCursor` | ⏳ | Insert at cursor position |
| `saveOpenFiles` | ⏳ | Save all unsaved tabs |
| `refreshOpenEditors` | ⏳ | Reload non-dirty tabs |
| `refreshFile` | ⏳ | Reload specific tab |
| `getSymbolUnderCursor` | ⏳ | Symbol at cursor |

### Code Quality (2 tools)
| Tool | Status | Notes |
|------|--------|-------|
| `getDiagnostics` | ⏳ | LSP errors/warnings |
| `formatDocument` | ⏳ | Format via LSP |

### LSP Navigation (2 tools)
| Tool | Status | Notes |
|------|--------|-------|
| `findDefinitions` | ⏳ | Go-to-definition |
| `findReferences` | ⏳ | Find all usages |

### Semantic Analysis (2 tools)
| Tool | Status | Notes |
|------|--------|-------|
| `semanticSearch` | ⏳ | Concept-based search |
| `indexCodebase` | ⏳ | Build/search codebase index |

### Suggestions (3 tools)
| Tool | Status | Notes |
|------|--------|-------|
| `getSuggestions` | ⏳ | Generate coding suggestions |
| `applySuggestion` | ⏳ | Apply a suggestion |
| `recordSuggestionFeedback` | ⏳ | Record acceptance/rejection |

### Git (4 tools)
| Tool | Status | Notes |
|------|--------|-------|
| `gitCheckout` | ⏳ | Switch branches/restore files |
| `gitCommit` | ⏳ | Commit staged changes |
| `gitPush` | ⏳ | Push commits |
| `gitPull` | ⏳ | Pull from remote |

### GitHub (5 tools)
| Tool | Status | Notes |
|------|--------|-------|
| `githubRepoInfo` | ⏳ | Repo metadata |
| `` ⏳ | Fetch repo README |
| `githubFileFetch` | ⏳ | Fetch file from GitHub |
| `githubSearch a PR |

### Web (3 tools)
| Tool | Status | Notes |
|------|--------|-------|
| |
Research` | ⏳ | Search + fetch pages |

### Package (3 tools)
| | Status | Notes |
|------|---------------|
| `pipSearch` ⏳ |PI search |
| | Go package search ``roredneeds retry) |

### Diff (3 tools)
| Tool | Status | Notes |
|------|--------|-------|
| `openDiff` | ⏳ | Open side-by-side diff |
| `getDiffResult` | ⏳ | Get file after diff review |
| `rejectDiff` | ⏳ | Reject a diff |

### Terminal (1 tool)
| Tool | Status | Notes |
|------|--------|-------|
| `runCommand` | ⏳ | Run shell commands |

### Task Management (2 tools)
| Tool | Status | Notes |
|------|--------|-------|
| `todowrite` | ⏳ | Task list management |
| `planMode` | ⏳ | Structured execution plans |

### Delegation (1 tool)
| Tool | Status | Notes |
|------|--------|-------|
| `delegateTask` | ⏳ | Delegate to sub-agents |

### Clipboard (1 tool)
| Tool | Status | Notes |
|------|--------|-------|
| `writeToClipboard` | ⏳ | Copy text to clipboard |

### File Open (1 tool)
| Tool | Status | Notes |
|------|--------|-------|
| `openFile` | ⏳ | Open file in editor tab |

### Project Instructions (2 tools)
| Tool | Status | Notes |
|------|--------|-------|
| `getProjectInstructions` | ⏳ | Read AI instruction files |
| `searchProjectInstructions` | ⏳ | Find AGENTS.md near directory |

---

## Notes
- **`createFile`** failed because the temp file existed from a previous test run
- **`mavenSearch`** returned HTTP 400 - needs correct `groupId:artifactId` format
- All 27 tested tools returned valid, usable results
- The IDE environment is running on **Android** (Xed-Editor itself)
- Git branch: **dev** | Latest commit: `9505e7c` "fix compilation error"
