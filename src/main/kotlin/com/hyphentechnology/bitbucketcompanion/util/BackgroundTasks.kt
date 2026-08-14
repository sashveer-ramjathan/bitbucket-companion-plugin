package com.hyphentechnology.bitbucketcompanion.util

import com.hyphentechnology.bitbucketcompanion.api.BitbucketApiClient
import com.hyphentechnology.bitbucketcompanion.settings.BitbucketCredentials
import com.hyphentechnology.bitbucketcompanion.settings.BitbucketSettingsState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * Shared plumbing every tab uses to talk to Bitbucket/git without blocking the EDT and without
 * duplicating error-notification boilerplate: build an authenticated API client from the current
 * Settings, run work on a background thread, and surface failures as IDE notifications.
 */
object BackgroundTasks {

    private const val NOTIFICATION_GROUP_ID = "Bitbucket Companion"

    /**
     * Builds an API client from the current Settings + stored token, or returns null (after
     * notifying the user) if workspace/token/identity (username or email) aren't fully
     * configured yet.
     */
    fun buildApiClient(project: Project?): BitbucketApiClient? {
        val state = BitbucketSettingsState.getInstance().state
        val token = BitbucketCredentials.getToken()
        if (!state.hasIdentity() || token.isNullOrBlank()) {
            notifyError(project, "Bitbucket Companion isn't configured yet - set workspace, username or email, and token in Settings > Tools > Bitbucket Companion.")
            return null
        }
        return BitbucketApiClient(state.workspace, state.authIdentity(), token)
    }

    /** Runs [action] on a background thread with a progress indicator, then [onSuccess] on the EDT; failures become an error notification. */
    fun <T> runBackground(project: Project?, title: String, action: () -> T, onSuccess: (T) -> Unit) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val result = runCatching(action)
                ApplicationManager.getApplication().invokeLater {
                    result.fold(
                        onSuccess = onSuccess,
                        onFailure = { e -> notifyError(project, "$title failed: ${e.message}") },
                    )
                }
            }
        })
    }

    fun notifyError(project: Project?, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }

    fun notifyInfo(project: Project?, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }
}
