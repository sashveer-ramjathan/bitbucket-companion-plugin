package com.hyphentechnology.bitbucketcompanion.ui

import com.hyphentechnology.bitbucketcompanion.api.BbComment
import com.hyphentechnology.bitbucketcompanion.api.BbDiffStatEntry
import com.hyphentechnology.bitbucketcompanion.api.BbPullRequest
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
 * Full detail view for one PR: description, the list of changed files (each openable in
 * IntelliJ's own native side-by-side diff viewer), and the comment thread (view existing
 * comments, post a new general/top-level one).
 *
 * "View Diff" opens IntelliJ's diff viewer as its own top-level window via [DiffManager] rather
 * than embedding a diff panel in here - that's the platform's normal diff UI (syntax
 * highlighting, scroll sync, the works) instead of us reimplementing a chunk of it, and it
 * doesn't compete with this dialog for space since it's a separate window.
 */
class PrDetailDialog(
    private val project: Project?,
    private val client: BitbucketApiClient,
    private val repoSlug: String,
    private val pr: BbPullRequest,
) : DialogWrapper(project) {

    private val filesColumns = arrayOf("File", "Status", "+", "-")
    private val filesTableModel = object : DefaultTableModel(filesColumns, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val filesTable = JBTable(filesTableModel)
    private var currentFiles: List<BbDiffStatEntry> = emptyList()
    private val filesStatusLabel = JBLabel(" ")
    private val commentsStatusLabel = JBLabel(" ")

    private val descriptionArea = JBTextArea(pr.description.orEmpty(), 5, 60).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    private val commentsArea = JBTextArea(8, 60).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val newCommentField = JBTextArea(3, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = "PR #${pr.id}: ${pr.title}"
        setOKButtonText("Close")
        init()
        loadFiles()
        loadComments()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val header = JBLabel(
            "<html><b>#${pr.id} ${pr.title}</b><br>${pr.sourceBranch ?: "?"} &rarr; ${pr.destBranch ?: "?"} &middot; ${pr.state}</html>"
        )
        val descPanel = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(descriptionArea), BorderLayout.CENTER)
            border = BorderFactory.createTitledBorder("Description")
        }

        val filesToolbar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("View Diff").apply { addActionListener { viewDiffForSelected() } })
            add(JButton("Refresh").apply { addActionListener { loadFiles() } })
            add(filesStatusLabel)
        }
        val filesPanel = JPanel(BorderLayout()).apply {
            add(filesToolbar, BorderLayout.NORTH)
            add(JBScrollPane(filesTable), BorderLayout.CENTER)
            border = BorderFactory.createTitledBorder("Changed Files")
        }

        val commentsToolbar = JPanel(BorderLayout()).apply {
            add(commentsStatusLabel, BorderLayout.NORTH)
            add(JBScrollPane(newCommentField), BorderLayout.CENTER)
            add(JButton("Post Comment").apply { addActionListener { postComment() } }, BorderLayout.EAST)
        }
        val commentsPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(commentsArea), BorderLayout.CENTER)
            add(commentsToolbar, BorderLayout.SOUTH)
            border = BorderFactory.createTitledBorder("Comments")
        }

        val filesAndComments = JSplitPane(JSplitPane.VERTICAL_SPLIT, filesPanel, commentsPanel).apply { resizeWeight = 0.5 }
        val main = JSplitPane(JSplitPane.VERTICAL_SPLIT, descPanel, filesAndComments).apply { resizeWeight = 0.25 }

        return main.apply { preferredSize = Dimension(900, 700) }
    }

    private fun loadFiles() {
        filesStatusLabel.text = "Loading..."
        BackgroundTasks.runBackground(
            project,
            "Loading Changed Files",
            action = { client.diffstat(repoSlug, pr.id) },
            onSuccess = { entries ->
                currentFiles = entries
                filesTableModel.rowCount = 0
                entries.forEach { filesTableModel.addRow(arrayOf<Any>(it.displayPath, it.status, it.linesAdded, it.linesRemoved)) }
                filesStatusLabel.text = if (entries.isEmpty()) "No changed files reported for this PR." else "${entries.size} file(s) changed"
            },
            onFailure = { e -> filesStatusLabel.text = "Error: ${e.message}" },
        )
    }

    private fun loadComments() {
        commentsStatusLabel.text = "Loading comments..."
        BackgroundTasks.runBackground(
            project,
            "Loading Comments",
            action = { client.listComments(repoSlug, pr.id) },
            onSuccess = { comments ->
                renderComments(comments)
                commentsStatusLabel.text = " "
            },
            onFailure = { e -> commentsStatusLabel.text = "Error: ${e.message}" },
        )
    }

    private fun renderComments(comments: List<BbComment>) {
        commentsArea.text = if (comments.isEmpty()) {
            "No comments yet."
        } else {
            comments.joinToString("\n\n") { c ->
                val tag = c.inlinePath?.let { " [inline: $it]" } ?: ""
                "${c.author}$tag - ${c.createdOn}\n${c.raw}"
            }
        }
    }

    private fun postComment() {
        val text = newCommentField.text.trim()
        if (text.isBlank()) {
            BackgroundTasks.notifyError(project, "Comment can't be empty.")
            return
        }
        BackgroundTasks.runBackground(
            project,
            "Posting Comment",
            action = { client.addComment(repoSlug, pr.id, text) },
            onSuccess = {
                newCommentField.text = ""
                loadComments()
            },
        )
    }

    private fun viewDiffForSelected() {
        val row = filesTable.selectedRow
        if (row < 0 || row >= currentFiles.size) {
            BackgroundTasks.notifyError(project, "Select a file first.")
            return
        }
        val entry = currentFiles[row]
        val destCommit = pr.destCommit
        val sourceCommit = pr.sourceCommit
        if (destCommit == null || sourceCommit == null) {
            BackgroundTasks.notifyError(project, "Missing commit info for this PR - can't build a diff.")
            return
        }
        BackgroundTasks.runBackground(
            project,
            "Loading Diff",
            action = {
                val oldText = entry.oldPath?.let { client.fileContentAt(repoSlug, destCommit, it) } ?: ""
                val newText = entry.newPath?.let { client.fileContentAt(repoSlug, sourceCommit, it) } ?: ""
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
        val request = SimpleDiffRequest(entry.displayPath, oldContent, newContent, pr.destBranch ?: "destination", pr.sourceBranch ?: "source")
        DiffManager.getInstance().showDiff(project, request)
    }
}
