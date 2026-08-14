package com.hyphentechnology.bitbucketcompanion.ui

import com.hyphentechnology.bitbucketcompanion.api.BbCommit
import com.hyphentechnology.bitbucketcompanion.api.BbDiffStatEntry
import com.hyphentechnology.bitbucketcompanion.api.BitbucketApiClient
import com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.table.DefaultTableModel

/**
 * Detail view for one commit: full message and the files it changed, each openable in
 * IntelliJ's native diff viewer against the commit's first parent - the same "View Diff"
 * approach as [PrDetailDialog], just scoped to a single commit instead of a whole PR.
 */
class CommitDetailDialog(
    private val project: Project?,
    private val client: BitbucketApiClient,
    private val repoSlug: String,
    private val commit: BbCommit,
) : DialogWrapper(project) {

    private val filesColumns = arrayOf("File", "Status", "+", "-")
    private val filesTableModel = object : DefaultTableModel(filesColumns, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val filesTable = JBTable(filesTableModel)
    private var currentFiles: List<BbDiffStatEntry> = emptyList()
    private val filesStatusLabel = JBLabel(" ")

    private val messageArea = JBTextArea(commit.message, 6, 60).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    /** First parent's hash - the "old" side of the diff. Resolved once detail loads. */
    private var parentHash: String? = null

    init {
        title = "Commit ${commit.hash.take(8)}"
        setOKButtonText("Close")
        init()
        loadDetailAndFiles()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val header = JBLabel("<html><b>${commit.hash.take(8)}</b> by ${commit.author} on ${commit.date}</html>")
        val messagePanel = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(messageArea), BorderLayout.CENTER)
            border = BorderFactory.createTitledBorder("Message")
        }

        val filesToolbar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("View Diff").apply { addActionListener { viewDiffForSelected() } })
            add(JButton("Refresh").apply { addActionListener { loadDetailAndFiles() } })
            add(filesStatusLabel)
        }
        val filesPanel = JPanel(BorderLayout()).apply {
            add(filesToolbar, BorderLayout.NORTH)
            add(JBScrollPane(filesTable), BorderLayout.CENTER)
            border = BorderFactory.createTitledBorder("Changed Files")
        }

        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, messagePanel, filesPanel).apply { resizeWeight = 0.3 }
        return split.apply { preferredSize = Dimension(800, 600) }
    }

    private fun loadDetailAndFiles() {
        filesStatusLabel.text = "Loading..."
        BackgroundTasks.runBackground(
            project,
            "Loading Commit Detail",
            action = {
                val detail = client.getCommit(repoSlug, commit.hash)
                val files = client.commitDiffstat(repoSlug, commit.hash)
                detail to files
            },
            onSuccess = { (detail, files) ->
                parentHash = detail.parentHash
                if (detail.message.isNotBlank()) messageArea.text = detail.message
                currentFiles = files
                filesTableModel.rowCount = 0
                files.forEach { filesTableModel.addRow(arrayOf<Any>(it.displayPath, it.status, it.linesAdded, it.linesRemoved)) }
                filesStatusLabel.text = if (files.isEmpty()) "No changed files reported for this commit." else "${files.size} file(s) changed"
            },
            onFailure = { e -> filesStatusLabel.text = "Error: ${e.message}" },
        )
    }

    private fun viewDiffForSelected() {
        val row = filesTable.selectedRow
        if (row < 0 || row >= currentFiles.size) {
            BackgroundTasks.notifyError(project, "Select a file first.")
            return
        }
        val entry = currentFiles[row]
        val parent = parentHash
        if (parent == null) {
            BackgroundTasks.notifyError(project, "This commit has no parent to diff against (likely the repo's root commit).")
            return
        }
        BackgroundTasks.runBackground(
            project,
            "Loading Diff",
            action = {
                val oldText = entry.oldPath?.let { client.fileContentAt(repoSlug, parent, it) } ?: ""
                val newText = entry.newPath?.let { client.fileContentAt(repoSlug, commit.hash, it) } ?: ""
                oldText to newText
            },
            onSuccess = { (oldText, newText) -> showDiff(entry, oldText, newText) },
        )
    }

    private fun showDiff(entry: BbDiffStatEntry, oldText: String, newText: String) {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(entry.displayPath)
        val contentFactory = DiffContentFactory.getInstance()
        val oldContent = contentFactory.create(project, oldText, fileType)
        val newContent = contentFactory.create(project, newText, fileType)
        val request = SimpleDiffRequest(entry.displayPath, oldContent, newContent, "parent", commit.hash.take(8))
        DiffManager.getInstance().showDiff(project, request)
    }
}
