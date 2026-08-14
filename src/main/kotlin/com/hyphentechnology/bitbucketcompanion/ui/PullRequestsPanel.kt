package com.hyphentechnology.bitbucketcompanion.ui

import com.hyphentechnology.bitbucketcompanion.api.BbPullRequest
import com.hyphentechnology.bitbucketcompanion.api.BitbucketApiClient
import com.hyphentechnology.bitbucketcompanion.settings.BitbucketSettingsState
import com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks
import com.hyphentechnology.bitbucketcompanion.util.WrapLayout
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.FlowLayout
import java.net.URI
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.Timer
import javax.swing.table.DefaultTableModel

private val PR_STATUS_TERMINAL = setOf("SUCCESSFUL", "FAILED", "STOPPED")

/**
 * Pull Requests tab - covers bb.py's `pr-create`/`pr-list`/`pr-get`/`pr-update`/`pr-status`
 * (including `--watch`)/`pr-url`.
 */
class PullRequestsPanel(private val project: Project?) : JPanel(BorderLayout()) {

    private val settings = BitbucketSettingsState.getInstance().state

    private val repoCombo = JComboBox<String>().apply {
        isEditable = true
        settings.lastRepo.takeIf { it.isNotBlank() }?.let { addItem(it); selectedItem = it }
    }
    private val stateCombo = JComboBox(arrayOf("OPEN", "MERGED", "DECLINED"))

    private val prColumns = arrayOf("ID", "State", "Title")
    private val prTableModel = object : DefaultTableModel(prColumns, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val prTable = JBTable(prTableModel)
    private var currentPrs: List<BbPullRequest> = emptyList()

    private val checksColumns = arrayOf("State", "Check", "URL")
    private val checksTableModel = object : DefaultTableModel(checksColumns, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val checksTable = JBTable(checksTableModel)

    private val detailLabel = JBLabel(" ")
    private val watchButton = JButton("Watch")
    private var watchTimer: Timer? = null

    init {
        val toolbar = JPanel(WrapLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Repo:"))
            add(repoCombo)
            add(JBLabel("State:"))
            add(stateCombo)
            add(JButton("Refresh").apply { addActionListener { refreshList() } })
            add(JButton("New PR...").apply { addActionListener { createPr() } })
            add(JButton("Edit...").apply { addActionListener { editSelected() } })
            add(JButton("View Details...").apply { addActionListener { openDetail() } })
            add(JButton("Open in Browser").apply { addActionListener { openSelectedInBrowser() } })
        }

        prTable.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) onPrSelected() }

        val checksToolbar = JPanel(WrapLayout(FlowLayout.LEFT)).apply {
            add(detailLabel)
            add(watchButton.apply { addActionListener { toggleWatch() } })
        }
        val checksPanel = JPanel(BorderLayout()).apply {
            add(checksToolbar, BorderLayout.NORTH)
            add(JBScrollPane(checksTable), BorderLayout.CENTER)
            border = javax.swing.BorderFactory.createTitledBorder("Checks")
        }

        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(prTable), checksPanel).apply { resizeWeight = 0.6 }

        add(toolbar, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)

        loadRepoOptions()
        if (repoText().isNotBlank()) refreshList()
    }

    private fun repoText(): String = (repoCombo.editor.item as? String)?.trim().orEmpty()

    /** Populates the Repo dropdown from the workspace's repo list so users can pick instead of typing a slug. */
    private fun loadRepoOptions() {
        val client = BackgroundTasks.buildApiClient(project) ?: return
        BackgroundTasks.runBackground(
            project,
            "Loading Repos",
            action = { client.listRepos() },
            onSuccess = { repos ->
                val current = repoText()
                repoCombo.removeAllItems()
                repos.map { it.slug }.sorted().forEach { repoCombo.addItem(it) }
                repoCombo.selectedItem = current.ifBlank { settings.lastRepo }
            },
        )
    }

    private fun rememberRepo() {
        settings.lastRepo = repoText()
    }

    private fun refreshList() {
        val repo = repoText()
        if (repo.isBlank()) {
            BackgroundTasks.notifyError(project, "Enter a repo slug first.")
            return
        }
        rememberRepo()
        val client = BackgroundTasks.buildApiClient(project) ?: return
        val state = stateCombo.selectedItem as String
        BackgroundTasks.runBackground(
            project,
            "Loading Pull Requests",
            action = { client.listPullRequests(repo, state) },
            onSuccess = { prs ->
                currentPrs = prs
                prTableModel.rowCount = 0
                prs.forEach { prTableModel.addRow(arrayOf<Any>(it.id, it.state, it.title)) }
                checksTableModel.rowCount = 0
                detailLabel.text = " "
            },
        )
    }

    private fun selectedPr(): BbPullRequest? {
        val row = prTable.selectedRow
        if (row < 0 || row >= currentPrs.size) return null
        return currentPrs[row]
    }

    private fun onPrSelected() {
        stopWatch()
        val pr = selectedPr() ?: return
        detailLabel.text = "#${pr.id}  ${pr.sourceBranch ?: "?"} -> ${pr.destBranch ?: "?"}"
        loadChecks(pr)
    }

    private fun loadChecks(pr: BbPullRequest) {
        val repo = repoText()
        val client = BackgroundTasks.buildApiClient(project) ?: return
        BackgroundTasks.runBackground(
            project,
            "Loading Checks",
            action = { client.pullRequestStatuses(repo, pr.id) },
            onSuccess = { statuses ->
                checksTableModel.rowCount = 0
                statuses.forEach { checksTableModel.addRow(arrayOf(it.state, it.name, it.url ?: "")) }
                if (watchTimer != null && statuses.isNotEmpty() && statuses.all { it.state in PR_STATUS_TERMINAL }) {
                    stopWatch()
                    val failed = statuses.count { it.state == "FAILED" || it.state == "STOPPED" }
                    BackgroundTasks.notifyInfo(project, if (failed == 0) "All checks passed for PR #${pr.id}" else "$failed check(s) failed for PR #${pr.id}")
                }
            },
        )
    }

    private fun toggleWatch() {
        if (watchTimer != null) {
            stopWatch()
            return
        }
        val pr = selectedPr() ?: run {
            BackgroundTasks.notifyError(project, "Select a PR first.")
            return
        }
        val intervalMs = (settings.watchIntervalSeconds.takeIf { it > 0 } ?: 15) * 1000
        watchButton.text = "Stop Watching"
        watchTimer = Timer(intervalMs) { loadChecks(pr) }.apply { isRepeats = true; start() }
    }

    private fun stopWatch() {
        watchTimer?.stop()
        watchTimer = null
        watchButton.text = "Watch"
    }

    private fun createPr() {
        val repo = repoText()
        if (repo.isBlank()) {
            BackgroundTasks.notifyError(project, "Enter a repo slug first.")
            return
        }
        val client = BackgroundTasks.buildApiClient(project) ?: return
        BackgroundTasks.runBackground(
            project,
            "Loading Branches",
            action = { client.listBranches(repo) },
            onSuccess = { branches -> openCreateDialog(repo, client, branches) },
        )
    }

    private fun openCreateDialog(repo: String, client: BitbucketApiClient, branches: List<String>) {
        val dialog = PrDialog(project, isCreate = true, branches = branches, initialDest = settings.defaultDestBranch.ifBlank { "main" })
        if (!dialog.showAndGet()) return
        if (dialog.titleValue.isBlank() || dialog.sourceValue.isBlank()) {
            BackgroundTasks.notifyError(project, "Title and source branch are required.")
            return
        }
        BackgroundTasks.runBackground(
            project,
            "Creating Pull Request",
            action = { client.createPullRequest(repo, dialog.titleValue, dialog.sourceValue, dialog.destValue.ifBlank { "main" }, dialog.descriptionValue) },
            onSuccess = { pr ->
                BackgroundTasks.notifyInfo(project, "Created PR #${pr.id}: ${pr.htmlUrl ?: ""}")
                refreshList()
            },
        )
    }

    private fun editSelected() {
        val pr = selectedPr() ?: run {
            BackgroundTasks.notifyError(project, "Select a PR first.")
            return
        }
        if (pr.state != "OPEN") {
            BackgroundTasks.notifyError(project, "Only open PRs can be edited (PR #${pr.id} is ${pr.state.lowercase()}).")
            return
        }
        val repo = repoText()
        val client = BackgroundTasks.buildApiClient(project) ?: return
        BackgroundTasks.runBackground(
            project,
            "Loading Branches",
            action = { client.listBranches(repo) },
            onSuccess = { branches -> openEditDialog(repo, pr, client, branches) },
        )
    }

    private fun openEditDialog(repo: String, pr: BbPullRequest, client: BitbucketApiClient, branches: List<String>) {
        val dialog = PrDialog(
            project,
            isCreate = false,
            branches = branches,
            initialTitle = pr.title,
            initialDest = pr.destBranch ?: settings.defaultDestBranch.ifBlank { "main" },
            initialDescription = pr.description ?: "",
        )
        if (!dialog.showAndGet()) return
        BackgroundTasks.runBackground(
            project,
            "Updating Pull Request",
            action = { client.updatePullRequest(repo, pr.id, dialog.titleValue.ifBlank { null }, dialog.descriptionValue, dialog.destValue.ifBlank { null }) },
            onSuccess = {
                BackgroundTasks.notifyInfo(project, "Updated PR #${pr.id}")
                refreshList()
            },
        )
    }

    private fun openDetail() {
        val pr = selectedPr() ?: run {
            BackgroundTasks.notifyError(project, "Select a PR first.")
            return
        }
        val repo = repoText()
        val client = BackgroundTasks.buildApiClient(project) ?: return
        PrDetailDialog(project, client, repo, pr).show()
    }

    private fun openSelectedInBrowser() {
        val pr = selectedPr() ?: run {
            BackgroundTasks.notifyError(project, "Select a PR first.")
            return
        }
        val url = pr.htmlUrl
        if (url == null) {
            BackgroundTasks.notifyError(project, "No URL available for this PR.")
            return
        }
        runCatching { Desktop.getDesktop().browse(URI(url)) }
            .onFailure { BackgroundTasks.notifyError(project, "Couldn't open browser: ${it.message}") }
    }
}
