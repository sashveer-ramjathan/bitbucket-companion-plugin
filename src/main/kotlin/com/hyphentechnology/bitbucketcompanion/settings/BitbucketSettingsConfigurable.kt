package com.hyphentechnology.bitbucketcompanion.settings

import com.hyphentechnology.bitbucketcompanion.api.BitbucketApiClient
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.JPanel

class BitbucketSettingsConfigurable : Configurable {

    private val state = BitbucketSettingsState.getInstance().state

    private val workspaceField = JBTextField()
    private val emailField = JBTextField()
    private val tokenField = JBPasswordField()
    private val authorNameField = JBTextField()
    private val authorEmailField = JBTextField()
    private val destBranchField = JBTextField()
    private val watchIntervalField = JBTextField()

    override fun getDisplayName(): String = "Bitbucket Companion"

    override fun createComponent(): JComponent {
        val built: JPanel = panel {
            group("Bitbucket Cloud Credentials") {
                row("Workspace:") { cell(workspaceField).align(AlignX.FILL) }
                row("Atlassian account email:") { cell(emailField).align(AlignX.FILL) }
                row("API token:") { cell(tokenField).align(AlignX.FILL) }
                row {
                    button("Test Connection") { testConnection() }
                    button("Clear Stored Credentials") { clearCredentials() }
                }
            }
            group("Git identity (used by Commit; blank = fall back to your global git config)") {
                row("Author name:") { cell(authorNameField).align(AlignX.FILL) }
                row("Author email:") { cell(authorEmailField).align(AlignX.FILL) }
            }
            group("Defaults") {
                row("Default PR destination branch:") { cell(destBranchField).align(AlignX.FILL) }
                row("Live watch poll interval (seconds):") { cell(watchIntervalField).align(AlignX.FILL) }
            }
        }
        reset()
        return built
    }

    private fun testConnection() {
        val ws = workspaceField.text.trim()
        val email = emailField.text.trim()
        val token = String(tokenField.password)
        if (ws.isEmpty() || email.isEmpty() || token.isEmpty()) {
            Messages.showErrorDialog("Fill in workspace, email, and token first.", "Bitbucket Companion")
            return
        }
        ProgressManager.getInstance().run(object : Task.Modal(null, "Testing Bitbucket Connection", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = runCatching { BitbucketApiClient(ws, email, token).ping() }
                ApplicationManager.getApplication().invokeLater {
                    result.fold(
                        onSuccess = { Messages.showInfoMessage("Auth OK - workspace '$ws' reachable.", "Bitbucket Companion") },
                        onFailure = { e -> Messages.showErrorDialog("Connection failed: ${e.message}", "Bitbucket Companion") },
                    )
                }
            }
        })
    }

    private fun clearCredentials() {
        tokenField.text = ""
        BitbucketCredentials.clear()
        Messages.showInfoMessage("Stored API token cleared.", "Bitbucket Companion")
    }

    override fun isModified(): Boolean =
        workspaceField.text.trim() != state.workspace ||
            emailField.text.trim() != state.email ||
            String(tokenField.password) != (BitbucketCredentials.getToken() ?: "") ||
            authorNameField.text.trim() != state.gitAuthorName ||
            authorEmailField.text.trim() != state.gitAuthorEmail ||
            destBranchField.text.trim() != state.defaultDestBranch ||
            watchIntervalField.text.trim() != state.watchIntervalSeconds.toString()

    override fun apply() {
        state.workspace = workspaceField.text.trim()
        state.email = emailField.text.trim()
        state.gitAuthorName = authorNameField.text.trim()
        state.gitAuthorEmail = authorEmailField.text.trim()
        state.defaultDestBranch = destBranchField.text.trim().ifEmpty { "main" }
        state.watchIntervalSeconds = watchIntervalField.text.trim().toIntOrNull() ?: 15

        val token = String(tokenField.password)
        if (token.isNotBlank()) {
            BitbucketCredentials.setToken(token)
        }
    }

    override fun reset() {
        workspaceField.text = state.workspace
        emailField.text = state.email
        tokenField.text = BitbucketCredentials.getToken() ?: ""
        authorNameField.text = state.gitAuthorName
        authorEmailField.text = state.gitAuthorEmail
        destBranchField.text = state.defaultDestBranch
        watchIntervalField.text = state.watchIntervalSeconds.toString()
    }
}
