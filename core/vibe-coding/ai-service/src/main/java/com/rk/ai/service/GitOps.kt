package com.rk.ai.service

import com.google.gson.JsonObject

interface GitOps {
    suspend fun getGitStatus(workspacePath: String): JsonObject
    suspend fun getGitDiff(workspacePath: String): String
    suspend fun gitCommit(workspacePath: String, message: String, all: Boolean): String
    suspend fun gitCheckout(workspacePath: String, target: String): String
    suspend fun gitLog(workspacePath: String, maxCount: Int, branch: String?): String
    suspend fun gitBranch(workspacePath: String, action: String, branchName: String?): String
    suspend fun gitPush(workspacePath: String, remote: String, branch: String?, setUpstream: Boolean, force: Boolean): String
    suspend fun gitPull(workspacePath: String, remote: String, branch: String?): String
    suspend fun getGitRoot(workspacePath: String): String?
}
