package com.hyphentechnology.bitbucketcompanion.ui

import com.hyphentechnology.bitbucketcompanion.git.GitOps
import com.hyphentechnology.bitbucketcompanion.settings.BitbucketCredentials
import com.hyphentechnology.bitbucketcompanion.settings.BitbucketSettingsState
import com.hyphentechnology.bitbucketcompanion.util.WrapLayout
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

/**
 * Local / Git Status tab - covers bb.py's `status`/`pull-all`/`pull`/`branch`/`branches`/
 * `switch`/`commit`/`push`, operating on a folder of already-cloned repos.
 */
class GitStatusPanel(private val project: Project?) : JPanel(BorderLayout()) {

    private val columns = arrayOf("Repo", "Status", "Dirty", "Detail")
    private val tableModel = object : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JBTable(tableModel)

    /**
     * Bitbucket's credential.helper (see bb.py) resolves auth from a `$BB_TOKEN` env var at the
     * moment git runs. That works from a shell that sources .bashrc, but IntelliJ - and every
     * subprocess it spawns, including these - never does. Inject the same token the API calls
     * already use so `git fetch`/`pull`/`push` authenticate without the user having to set a
     * system-wide environment variable themselves.
     */
    private fun gitEnv(): Map<String, String> =
        BitbucketCredentials.getToken()?.takeIf { it.isNotBlank() }?.let { mapOf("BB_TOKEN" to it) } ?: emptyMap()

    private var currentDir: File? =
        BitbucketSettingsState.getInstance().state.lastCloneDir.takeIf { it.isNotBlank() }?.let { File(it) }

    private val dirLabel = JBLabel(currentDir?.absolutePath ?: "(no folder chosen)")

    init {
        val toolbar = JPanel(WrapLayout(FlowLayout.LEFT)).apply {
            add(JButton("Choose Folder...").apply { addActionListener { chooseFolder() } })
            add(dirLabel)
            add(JButton("Refresh").apply { addActionListener { refresh() } })
            add(JButton("Pull All").apply { addActionListener { pullAll() } })
            add(JButton("Pull Selected").apply { addActionListener { pullSelected() } })
            add(JButton("New Branch...").apply { addActionListener { newBranch() } })
            add(JButton("Branches...").apply { addActionListener { showBranches() } })
            add(JButton("Commit...").apply { addActionListener { commitSelected() } })
            add(JButton("Push").apply { addActionListener { pushSelected() } })
        }

        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)

        if (currentDir != null) refresh()
    }

    private fun selectedRepoDir(): File? {
        val row = table.selectedRow
        if (row < 0) {
            com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.notifyError(project, "Select a repo first.")
            return null
        }
        val name = tableModel.getValueAt(row, 0) as String
        return File(currentDir, name)
    }

    private fun chooseFolder() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        val chosen = FileChooserFactory.getInstance().createFileChooser(descriptor, project, this).choose(project)
        val dir = chosen.firstOrNull() ?: return
        currentDir = File(dir.path)
        dirLabel.text = currentDir!!.absolutePath
        BitbucketSettingsState.getInstance().state.lastCloneDir = currentDir!!.absolutePath
        refresh()
    }

    private fun refresh() {
        val dir = currentDir ?: return
        com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.runBackground(
            project,
            "Checking Repo Status",
            action = {
                val env = gitEnv()
                dir.listFiles { f -> f.isDirectory && File(f, ".git").isDirectory }
                    ?.sortedBy { it.name }
                    ?.map { GitOps.status(it, env) }
                    ?: emptyList()
            },
            onSuccess = { statuses ->
                tableModel.rowCount = 0
                statuses.forEach { tableModel.addRow(arrayOf(it.name, it.state, if (it.dirty) "yes" else "", it.detail)) }
            },
        )
    }

    private fun pullAll() {
        val dir = currentDir ?: return
        com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.runBackground(
            project,
            "Pull All",
            action = {
                val env = gitEnv()
                val log = StringBuilder()
                dir.listFiles { f -> f.isDirectory && File(f, ".git").isDirectory }
                    ?.sortedBy { it.name }
                    ?.forEach { repoDir ->
                        val result = GitOps.pull(repoDir, env)
                        log.appendLine("${if (result.ok) "OK" else "SKIP/FAIL"}  ${repoDir.name}: ${(if (result.ok) result.stdout else result.stderr).trim().ifBlank { "done" }}")
                    }
                log.toString()
            },
            onSuccess = {
                com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.notifyInfo(project, it.ifBlank { "No repos found." })
                refresh()
            },
        )
    }

    private fun pullSelected() {
        val repoDir = selectedRepoDir() ?: return
        com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.runBackground(
            project,
            "Pull ${repoDir.name}",
            action = { GitOps.pull(repoDir, gitEnv()) },
            onSuccess = { result ->
                if (result.ok) {
                    com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.notifyInfo(project, "Pulled ${repoDir.name}")
                } else {
                    com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.notifyError(project, result.stderr.ifBlank { "Pull failed" })
                }
                refresh()
            },
        )
    }

    private fun newBranch() {
        val repoDir = selectedRepoDir() ?: return
        val name = Messages.showInputDialog(project, "New branch name:", "New Branch", null) ?: return
        if (name.isBlank()) return
        com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.runBackground(
            project,
            "Creating Branch",
            action = { GitOps.createBranch(repoDir, name.trim()) },
            onSuccess = { result ->
                if (result.ok) {
                    com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.notifyInfo(project, "Switched to new branch '$name' in ${repoDir.name}")
                } else {
                    com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.notifyError(project, result.stderr.ifBlank { "Failed to create branch" })
                }
            },
        )
    }

    private fun showBranches() {
        val repoDir = selectedRepoDir() ?: return
        com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.runBackground(
            project,
            "Loading Branches",
            action = { GitOps.listBranches(repoDir, localOnly = false) },
            onSuccess = { branches ->
                val labels = branches.map { b -> (if (b.current) "* " else "  ") + b.name }
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(labels)
                    .setTitle("Branches - ${repoDir.name} (select a local branch to switch)")
                    .setItemChosenCallback { label ->
                        val index = labels.indexOf(label)
                        val branch = branches[index]
                        if (branch.remote) {
                            com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.notifyError(project, "Pick a local branch to switch to (not a remote-tracking one).")
                            return@setItemChosenCallback
                        }
                        switchTo(repoDir, branch.name)
                    }
                    .createPopup()
                    .showInFocusCenter()
            },
        )
    }

    private fun switchTo(repoDir: File, branchName: String) {
        com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.runBackground(
            project,
            "Switching Branch",
            action = { GitOps.switchBranch(repoDir, branchName) },
            onSuccess = { result ->
                if (result.ok) {
                    com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.notifyInfo(project, "Switched ${repoDir.name} to '$branchName'")
                } else {
                    com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.notifyError(project, result.stderr.ifBlank { "Switch failed" })
                }
            },
        )
    }

    private fun commitSelected() {
        val repoDir = selectedRepoDir() ?: return
        val message = Messages.showInputDialog(project, "Commit message:", "Commit", null) ?: return
        if (message.isBlank()) return
        val state = BitbucketSettingsState.getInstance().state
        com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.runBackground(
            project,
            "Committing",
            action = { GitOps.commit(repoDir, message.trim(), state.gitAuthorName.ifBlank { null }, state.gitAuthorEmail.ifBlank { null }) },
            onSuccess = { result ->
                if (result.ok) {
                    com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.notifyInfo(project, result.stdout.ifBlank { "Committed" })
                } else {
                    com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.notifyError(project, result.stderr.ifBlank { "Commit failed" })
                }
                refresh()
            },
        )
    }

    private fun pushSelected() {
        val repoDir = selectedRepoDir() ?: return
        com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.runBackground(
            project,
            "Pushing",
            action = { GitOps.push(repoDir, gitEnv()) },
            onSuccess = { result ->
                if (result.ok) {
                    com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.notifyInfo(project, "Pushed ${repoDir.name}")
                } else {
                    com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks.notifyError(project, result.stderr.ifBlank { "Push failed" })
                }
                refresh()
            },
        )
    }
}
