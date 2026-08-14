package com.hyphentechnology.bitbucketcompanion.ui

import com.hyphentechnology.bitbucketcompanion.api.BbPipeline
import com.hyphentechnology.bitbucketcompanion.api.BbPipelineStep
import com.hyphentechnology.bitbucketcompanion.settings.BitbucketSettingsState
import com.hyphentechnology.bitbucketcompanion.util.AnsiUtil
import com.hyphentechnology.bitbucketcompanion.util.BackgroundTasks
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.FlowLayout
import java.net.URI
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.Timer
import javax.swing.table.DefaultTableModel

/**
 * Pipelines tab - covers bb.py's `pipelines`/`pipeline-steps` (including `--watch`)/
 * `pipeline-log`/`pipeline-url`.
 */
class PipelinesPanel(private val project: Project?) : JPanel(BorderLayout()) {

    private val settings = BitbucketSettingsState.getInstance().state

    private val repoField = JBTextField(settings.lastRepo, 16)
    private val limitField = JBTextField("10", 4)

    private val pipelineColumns = arrayOf("Build #", "State", "Result", "UUID")
    private val pipelineTableModel = object : DefaultTableModel(pipelineColumns, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val pipelineTable = JBTable(pipelineTableModel)
    private var currentPipelines: List<BbPipeline> = emptyList()

    private val stepColumns = arrayOf("Step", "State", "Result")
    private val stepTableModel = object : DefaultTableModel(stepColumns, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val stepTable = JBTable(stepTableModel)
    private var currentSteps: List<BbPipelineStep> = emptyList()

    private val watchButton = JButton("Watch Steps")
    private var watchTimer: Timer? = null

    private val logArea = JBTextArea().apply { isEditable = false; lineWrap = false }

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Repo:"))
            add(repoField)
            add(JBLabel("Limit:"))
            add(limitField)
            add(JButton("Refresh").apply { addActionListener { refreshPipelines() } })
            add(JButton("Open in Browser").apply { addActionListener { openSelectedPipelineInBrowser() } })
        }

        pipelineTable.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) onPipelineSelected() }
        stepTable.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) onStepSelected() }

        val stepsToolbar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(watchButton.apply { addActionListener { toggleWatch() } })
        }
        val stepsPanel = JPanel(BorderLayout()).apply {
            add(stepsToolbar, BorderLayout.NORTH)
            add(JBScrollPane(stepTable), BorderLayout.CENTER)
            border = javax.swing.BorderFactory.createTitledBorder("Steps")
        }
        val logPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(logArea), BorderLayout.CENTER)
            border = javax.swing.BorderFactory.createTitledBorder("Log")
        }

        val stepsAndLog = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, stepsPanel, logPanel).apply { resizeWeight = 0.35 }
        val mainSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(pipelineTable), stepsAndLog).apply { resizeWeight = 0.4 }

        add(toolbar, BorderLayout.NORTH)
        add(mainSplit, BorderLayout.CENTER)

        if (repoField.text.isNotBlank()) refreshPipelines()
    }

    private fun refreshPipelines() {
        val repo = repoField.text.trim()
        if (repo.isBlank()) {
            BackgroundTasks.notifyError(project, "Enter a repo slug first.")
            return
        }
        settings.lastRepo = repo
        val limit = limitField.text.trim().toIntOrNull() ?: 10
        val client = BackgroundTasks.buildApiClient(project) ?: return
        BackgroundTasks.runBackground(
            project,
            "Loading Pipelines",
            action = { client.listPipelines(repo, limit) },
            onSuccess = { pipelines ->
                currentPipelines = pipelines
                pipelineTableModel.rowCount = 0
                pipelines.forEach {
                    pipelineTableModel.addRow(arrayOf<Any>(it.buildNumber, it.stateName, it.resultName ?: "-", it.uuid))
                }
                stepTableModel.rowCount = 0
                logArea.text = ""
                stopWatch()
            },
        )
    }

    private fun selectedPipeline(): BbPipeline? {
        val row = pipelineTable.selectedRow
        if (row < 0 || row >= currentPipelines.size) return null
        return currentPipelines[row]
    }

    private fun selectedStep(): BbPipelineStep? {
        val row = stepTable.selectedRow
        if (row < 0 || row >= currentSteps.size) return null
        return currentSteps[row]
    }

    private fun onPipelineSelected() {
        stopWatch()
        logArea.text = ""
        val pipeline = selectedPipeline() ?: return
        loadSteps(pipeline)
    }

    private fun loadSteps(pipeline: BbPipeline) {
        val repo = repoField.text.trim()
        val client = BackgroundTasks.buildApiClient(project) ?: return
        BackgroundTasks.runBackground(
            project,
            "Loading Steps",
            action = { client.pipelineSteps(repo, pipeline.uuid) },
            onSuccess = { steps ->
                currentSteps = steps
                stepTableModel.rowCount = 0
                steps.forEach { stepTableModel.addRow(arrayOf<Any>(it.name, it.stateName, it.resultName ?: "-")) }
                if (watchTimer != null && steps.isNotEmpty() && steps.all { it.stateName == "COMPLETED" }) {
                    stopWatch()
                    val failed = steps.count { it.resultName != null && it.resultName != "SUCCESSFUL" }
                    BackgroundTasks.notifyInfo(project, if (failed == 0) "Pipeline #${pipeline.buildNumber} finished - all steps passed" else "Pipeline #${pipeline.buildNumber} finished - $failed step(s) failed")
                }
            },
        )
    }

    private fun onStepSelected() {
        val pipeline = selectedPipeline() ?: return
        val step = selectedStep() ?: return
        loadLog(pipeline, step)
    }

    private fun loadLog(pipeline: BbPipeline, step: BbPipelineStep) {
        val repo = repoField.text.trim()
        val client = BackgroundTasks.buildApiClient(project) ?: return
        logArea.text = "Loading..."
        BackgroundTasks.runBackground(
            project,
            "Loading Log",
            action = { AnsiUtil.strip(client.pipelineLog(repo, pipeline.uuid, step.uuid)) },
            onSuccess = { text -> logArea.text = text; logArea.caretPosition = 0 },
        )
    }

    private fun toggleWatch() {
        if (watchTimer != null) {
            stopWatch()
            return
        }
        val pipeline = selectedPipeline() ?: run {
            BackgroundTasks.notifyError(project, "Select a pipeline first.")
            return
        }
        val intervalMs = (settings.watchIntervalSeconds.takeIf { it > 0 } ?: 15) * 1000
        watchButton.text = "Stop Watching"
        watchTimer = Timer(intervalMs) { loadSteps(pipeline) }.apply { isRepeats = true; start() }
    }

    private fun stopWatch() {
        watchTimer?.stop()
        watchTimer = null
        watchButton.text = "Watch Steps"
    }

    private fun openSelectedPipelineInBrowser() {
        val repo = repoField.text.trim()
        val pipeline = selectedPipeline() ?: run {
            BackgroundTasks.notifyError(project, "Select a pipeline first.")
            return
        }
        val client = BackgroundTasks.buildApiClient(project) ?: return
        BackgroundTasks.runBackground(
            project,
            "Resolving Pipeline URL",
            action = { client.pipelineWebUrl(repo, pipeline.uuid) },
            onSuccess = { url ->
                if (url.isBlank()) {
                    BackgroundTasks.notifyError(project, "No URL available for this pipeline.")
                } else {
                    runCatching { Desktop.getDesktop().browse(URI(url)) }
                        .onFailure { BackgroundTasks.notifyError(project, "Couldn't open browser: ${it.message}") }
                }
            },
        )
    }
}
