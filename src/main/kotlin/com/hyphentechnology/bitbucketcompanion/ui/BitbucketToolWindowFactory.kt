package com.hyphentechnology.bitbucketcompanion.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Entry point for the "Bitbucket Companion" tool window - one tab per area of bb.py's
 * command set (Repos / Local Git Status / Pull Requests / Pipelines).
 */
class BitbucketToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()
        val tabs = com.intellij.ui.components.JBTabbedPane()

        tabs.addTab("Repos", ReposPanel(project))
        tabs.addTab("Local / Git Status", GitStatusPanel(project))
        tabs.addTab("Pull Requests", comingSoon("Pull Requests"))
        tabs.addTab("Pipelines", comingSoon("Pipelines"))

        val content = contentFactory.createContent(tabs, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun comingSoon(name: String): JPanel =
        JPanel(BorderLayout()).apply {
            add(JBLabel("$name - coming soon", SwingConstants.CENTER), BorderLayout.CENTER)
        }
}
