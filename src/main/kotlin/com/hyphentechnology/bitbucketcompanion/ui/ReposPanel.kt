package com.hyphentechnology.bitbucketcompanion.ui

import com.hyphentechnology.bitbucketcompanion.git.GitOps
import com.hyphentechnology.bitbucketcompanion.settings.BitbucketCredentials
import com.hyphentechnology.bitbucketcompanion.settings.BitbucketSettingsState
import com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks
import com.hyphentechnology.bitbucketcompanion.util.WrapLayout
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.FlowLayout
import java.io.File
import java.net.URI
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.table.DefaultTableModel

/**
 * Repos tab - covers bb.py's `repos`/`url`/`urls`/`clone`/`clone-all`/`verify`.
 */
class ReposPanel(private val project: Project?) : JPanel(BorderLayout()) {

    private val columns = arrayOf("Slug", "Project", "URL")
    private val tableModel = object : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JBTable(tableModel)

    private val settings = BitbucketSettingsState.getInstance().state

    private val projectFilterField = JBTextField(settings.lastProjectFilter, 12).apply {
        toolTipText = "Optional project key filter, e.g. MATRIX"
    }
    private val outputArea = JBTextArea(6, 40).apply { isEditable = false }

    init {
        val toolbar = JPanel(WrapLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Project filter:"))
            add(projectFilterField)
            add(JButton("Refresh").apply { addActionListener { refresh() } })
            add(JButton("Open URL").apply { addActionListener { openSelectedUrl() } })
            add(JButton("Copy URL").apply { addActionListener { copySelectedUrl() } })
            add(JButton("Clone Selected...").apply { addActionListener { cloneSelected() } })
            add(JButton("Clone All...").apply { addActionListener { cloneAll() } })
            add(JButton("Verify...").apply { addActionListener { verify() } })
        }

        val split = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JBScrollPane(table),
            JBScrollPane(outputArea).apply { border = javax.swing.BorderFactory.createTitledBorder("Output") },
        ).apply { resizeWeight = 0.75 }

        add(toolbar, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)

        if (settings.hasIdentity() && !BitbucketCredentials.getToken().isNullOrBlank()) {
            refresh()
        }
    }

    private fun selectedRow(): Int {
        val row = table.selectedRow
        if (row < 0) {
            BackgroundTasks.notifyError(project, "Select a repo first.")
        }
        return row
    }

    private fun refresh() {
        val client = BackgroundTasks.buildApiClient(project) ?: return
        val projectFilter = projectFilterField.text.trim().ifBlank { null }
        BackgroundTasks.runBackground(
            project,
            "Loading Repos",
            action = { client.listRepos(projectFilter) },
            onSuccess = { repos ->
                tableModel.rowCount = 0
                repos.forEach { tableModel.addRow(arrayOf(it.slug, it.projectKey, it.htmlUrl)) }
                settings.lastProjectFilter = projectFilter ?: ""
            },
        )
    }

    private fun openSelectedUrl() {
        val row = selectedRow()
        if (row < 0) return
        val url = tableModel.getValueAt(row, 2) as String
        runCatching { Desktop.getDesktop().browse(URI(url)) }
            .onFailure { BackgroundTasks.notifyError(project, "Couldn't open browser: ${it.message}") }
    }

    private fun copySelectedUrl() {
        val row = selectedRow()
        if (row < 0) return
        val url = tableModel.getValueAt(row, 2) as String
        CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(url))
        BackgroundTasks.notifyInfo(project, "Copied: $url")
    }

    private fun cloneSelected() {
        val row = selectedRow()
        if (row < 0) return
        val slug = tableModel.getValueAt(row, 0) as String
        val targetDir = chooseDirectory("Choose destination folder for '$slug'") ?: return
        val client = BackgroundTasks.buildApiClient(project) ?: return

        BackgroundTasks.runBackground(
            project,
            "Cloning $slug",
            action = {
                val target = File(targetDir, slug)
                val result = GitOps.clone(client.cloneUrlWithToken(slug), target)
                if (result.ok) GitOps.setOriginUrl(target, client.cloneUrlPlain(slug))
                result
            },
            onSuccess = { result ->
                if (result.ok) {
                    BackgroundTasks.notifyInfo(project, "Cloned $slug")
                } else {
                    BackgroundTasks.notifyError(project, "Clone failed: ${result.stderr.ifBlank { result.stdout }}")
                }
            },
        )
    }

    private fun cloneAll() {
        val projectFilter = projectFilterField.text.trim().ifBlank { null }
        val targetDir = chooseDirectory("Choose destination folder for Clone All") ?: return
        val client = BackgroundTasks.buildApiClient(project) ?: return

        outputArea.text = "Cloning all repos into $targetDir ...\n"
        BackgroundTasks.runBackground(
            project,
            "Clone All Repos",
            action = {
                val slugs = client.listRepos(projectFilter).map { it.slug }
                val log = StringBuilder()
                var ok = 0
                for (slug in slugs) {
                    val target = File(targetDir, slug)
                    if (File(target, ".git").isDirectory) {
                        log.appendLine("skip (already cloned): $slug")
                        ok++
                        continue
                    }
                    val result = GitOps.clone(client.cloneUrlWithToken(slug), target)
                    if (result.ok) {
                        GitOps.setOriginUrl(target, client.cloneUrlPlain(slug))
                        log.appendLine("OK: $slug")
                        ok++
                    } else {
                        log.appendLine("FAIL: $slug - ${result.stderr.ifBlank { result.stdout }}")
                    }
                }
                log.appendLine("\nDone: $ok/${slugs.size} cloned successfully.")
                log.toString()
            },
            onSuccess = { log -> outputArea.text = log },
        )
    }

    private fun verify() {
        val targetDir = chooseDirectory("Choose folder to verify") ?: return
        outputArea.text = "Verifying repos in $targetDir ...\n"
        BackgroundTasks.runBackground(
            project,
            "Verify Repos",
            action = {
                val log = StringBuilder()
                var ok = 0
                var fail = 0
                targetDir.listFiles { f -> f.isDirectory && File(f, ".git").isDirectory }
                    ?.sortedBy { it.name }
                    ?.forEach { repoDir ->
                        val (isOk, branch, commits) = GitOps.verify(repoDir)
                        val status = if (isOk) "OK" else "FAIL"
                        if (isOk) ok++ else fail++
                        log.appendLine("$status  ${repoDir.name}  branch=$branch  commits=$commits")
                    }
                log.appendLine("\n$ok ok, $fail failed")
                log.toString()
            },
            onSuccess = { log -> outputArea.text = log },
        )
    }

    /** Opens a folder picker, defaulting to and remembering the last-used clone directory (shared with the Local / Git Status tab). */
    private fun chooseDirectory(title: String): File? {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply { this.title = title }
        val toSelect = settings.lastCloneDir.takeIf { it.isNotBlank() }
            ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val chosen = FileChooserFactory.getInstance()
            .createFileChooser(descriptor, project, this)
            .choose(project, *listOfNotNull(toSelect).toTypedArray())
        val dir = chosen.firstOrNull() ?: return null
        settings.lastCloneDir = dir.path
        return File(dir.path)
    }
}
