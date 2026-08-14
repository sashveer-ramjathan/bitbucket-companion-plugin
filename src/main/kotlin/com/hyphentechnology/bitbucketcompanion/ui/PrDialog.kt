package com.hyphentechnology.bitbucketcompanion.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.Dimension
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Shared modal for both creating a new PR and editing an existing one - title/destination/
 * description are always editable; source branch only appears (and is required) when creating,
 * since Bitbucket doesn't allow changing a PR's source branch after the fact.
 *
 * Source/destination are editable combo boxes pre-populated with [branches] (both ends of a PR
 * have to be branches that already exist on the remote) rather than free-text fields - still
 * typable, in case a branch was just pushed and hasn't shown up in the fetched list yet.
 */
class PrDialog(
    project: Project?,
    private val isCreate: Boolean,
    branches: List<String> = emptyList(),
    initialTitle: String = "",
    initialSource: String = "",
    initialDest: String = "main",
    initialDescription: String = "",
) : DialogWrapper(project) {

    private val titleField = JBTextField(initialTitle)
    private val sourceCombo = JComboBox(branches.toTypedArray()).apply {
        isEditable = true
        selectedItem = initialSource
    }
    private val destCombo = JComboBox(branches.toTypedArray()).apply {
        isEditable = true
        selectedItem = initialDest
    }
    private val descriptionArea = JBTextArea(initialDescription, 12, 60)

    init {
        title = if (isCreate) "New Pull Request" else "Edit Pull Request"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Title:") { cell(titleField).align(AlignX.FILL) }
        if (isCreate) {
            row("Source branch:") { cell(sourceCombo).align(AlignX.FILL) }
        }
        row("Destination branch:") { cell(destCombo).align(AlignX.FILL) }
        row("Description:") { cell(JBScrollPane(descriptionArea)).align(AlignX.FILL) }
    }.apply { preferredSize = Dimension(650, 500) }

    val titleValue: String get() = titleField.text.trim()
    val sourceValue: String get() = (sourceCombo.editor.item as? String)?.trim().orEmpty()
    val destValue: String get() = (destCombo.editor.item as? String)?.trim().orEmpty()
    val descriptionValue: String get() = descriptionArea.text
}
