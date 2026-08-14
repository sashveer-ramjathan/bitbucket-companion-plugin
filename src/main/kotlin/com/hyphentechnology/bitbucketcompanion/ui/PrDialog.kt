package com.hyphentechnology.bitbucketcompanion.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Shared modal for both creating a new PR and editing an existing one - title/destination/
 * description are always editable; source branch only appears (and is required) when creating,
 * since Bitbucket doesn't allow changing a PR's source branch after the fact.
 */
class PrDialog(
    project: Project?,
    private val isCreate: Boolean,
    initialTitle: String = "",
    initialSource: String = "",
    initialDest: String = "main",
    initialDescription: String = "",
) : DialogWrapper(project) {

    private val titleField = JBTextField(initialTitle)
    private val sourceField = JBTextField(initialSource)
    private val destField = JBTextField(initialDest)
    private val descriptionArea = JBTextArea(initialDescription, 6, 40)

    init {
        title = if (isCreate) "New Pull Request" else "Edit Pull Request"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Title:") { cell(titleField).align(AlignX.FILL) }
        if (isCreate) {
            row("Source branch:") { cell(sourceField).align(AlignX.FILL) }
        }
        row("Destination branch:") { cell(destField).align(AlignX.FILL) }
        row("Description:") { cell(JBScrollPane(descriptionArea)).align(AlignX.FILL) }
    }

    val titleValue: String get() = titleField.text.trim()
    val sourceValue: String get() = sourceField.text.trim()
    val destValue: String get() = destField.text.trim()
    val descriptionValue: String get() = descriptionArea.text
}
