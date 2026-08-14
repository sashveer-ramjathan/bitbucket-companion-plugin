package com.hyphentechnology.bitbucketcompanion.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory

/**
 * Entry point for the "Bitbucket Companion" tool window - one tab per area of bb.py's
 * command set (Repos / Local Git Status / Pull Requests / Pipelines).
 */
class BitbucketToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()
        val tabs = JBTabbedPane()

        tabs.addTab("Repos", ReposPanel(project))
        tabs.addTab("Local / Git Status", GitStatusPanel(project))
        tabs.addTab("Pull Requests", PullRequestsPanel(project))
        tabs.addTab("Pipelines", PipelinesPanel(project))

        val content = contentFactory.createContent(tabs, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
