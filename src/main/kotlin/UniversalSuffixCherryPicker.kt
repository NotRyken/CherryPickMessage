package com.notryken

import com.intellij.dvcs.cherrypick.VcsCherryPicker
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.vcs.log.VcsCommitMetadata
import git4idea.GitVcs
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.config.GitSharedSettings
import java.util.concurrent.TimeUnit

class UniversalSuffixCherryPicker(project: Project) : VcsCherryPicker() {
    private val log = Logger.getInstance(UniversalSuffixCherryPicker::class.java)

    override fun getActionTitle(): String = "Cherry-Pick (with Suffix)"

    override fun getSupportedVcs(): VcsKey = GitVcs.getKey()

    override fun canHandleForRoots(roots: Collection<VirtualFile>): Boolean {
        if (roots.isEmpty()) return false
        val project = ProjectLocator.getInstance().guessProjectForFile(roots.first()) ?: return false

        if (!project.isInitialized) return false

        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        return roots.all { root ->
            vcsManager.getVcsFor(root)?.keyInstanceMethod == GitVcs.getKey()
        }
    }

    override fun cherryPick(commits: List<VcsCommitMetadata?>): Boolean {
        val validCommits = commits.filterNotNull()
        if (validCommits.isEmpty()) return false

        val root = validCommits.first().root
        val project = ProjectLocator.getInstance().guessProjectForFile(root)

        if (project == null || !project.isInitialized) {
            log.warn("Could not determine a valid initialized Project from commit root.")
            return false
        }

        // Safely find the default Git cherry picker implementation using the proper EP name container
        val defaultPicker = EXTENSION_POINT_NAME.getExtensionList(project).find {
            it.supportedVcs == GitVcs.getKey() && it !is UniversalSuffixCherryPicker
        }

        if (defaultPicker == null) {
            log.warn("Default VcsCherryPicker for Git not found.")
            return false
        }

        // Find the source branch of the commit being pulled/cherry-picked
        val commitHash = validCommits.first().id.asString()
        val sourceBranchName = getSourceBranchForCommit(project, root, commitHash)

        val gitSettings = GitSharedSettings.getInstance(project)
        val originalPatterns = gitSettings.forcePushProhibitedPatterns.toList()

        // Temporarily set forcePushProhibitedPatterns to match the source branch name
        gitSettings.forcePushProhibitedPatterns = if (sourceBranchName != null) {
            listOf(sourceBranchName)
        } else {
            originalPatterns
        }

        log.info("Temporarily set forcePushProhibitedPatterns to source branch: ${gitSettings.forcePushProhibitedPatterns} (Original: $originalPatterns)")

        var result: Boolean
        try {
            result = defaultPicker.cherryPick(commits)
        } finally {
            restoreSettingsAfterInit(gitSettings, originalPatterns)
        }

        return result
    }

    private fun restoreSettingsAfterInit(
        gitSettings: GitSharedSettings,
        originalPatterns: List<String>
    ) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            gitSettings.forcePushProhibitedPatterns = originalPatterns
            log.info("Restored forcePushProhibitedPatterns to: $originalPatterns")
        }, 1, TimeUnit.SECONDS)
    }

    private fun getSourceBranchForCommit(project: Project, root: VirtualFile, commitHash: String): String? {
        try {
            // Run a quick git command to find local/remote branches containing this commit
            val handler = GitLineHandler(project, root, GitCommand.BRANCH)
            handler.addParameters("--contains", commitHash, "--format=%(refname:short)")

            val result = Git.getInstance().runCommand(handler)
            if (!result.success()) return null

            // Output can contain multiple lines (multiple branches). Pick the first sensible one.
            val branches = result.output.map { it.trim() }.filter { it.isNotEmpty() }

            // Prefer a branch that isn't 'HEAD' or detached states
            return branches.firstOrNull { !it.contains("HEAD") }
        } catch (e: Exception) {
            log.warn("Failed to determine source branch for commit $commitHash", e)
            return null
        }
    }
}