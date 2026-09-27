package com.reqws.goland.loading.model

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.backend.workspace.impl.WorkspaceModelInternal
import com.reqws.goland.projectmodel.ProjectModelApplyException
import com.reqws.goland.projectmodel.ProjectModelErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout

/** The sole user-approved restricted API call; do not add other members to this wrapper. */
@Service(Service.Level.PROJECT)
internal class InitialJpsSynchronization(project: Project, coroutineScope: CoroutineScope) {
  private val synchronization = InitialJpsWait(coroutineScope) { awaitPlatform(project) }

  suspend fun await() = synchronization.await()

  private suspend fun awaitPlatform(project: Project) {
    (WorkspaceModel.getInstance(project) as WorkspaceModelInternal).awaitSynchronizationWithJpsModel()
  }
}

/**
 * Share one project-owned wait: cancelling a candidate must not restart the platform's timer.
 * Fail before its one-minute soft timeout, remember failure, and never retry this API in-project.
 * A prior platform caller's sticky soft-timeout state is not observable through the allowed API.
 */
internal class InitialJpsWait(
  coroutineScope: CoroutineScope,
  timeoutMillis: Long = 30_000,
  synchronize: suspend () -> Unit,
) {
  private val completion = coroutineScope.async(start = CoroutineStart.LAZY) {
    try {
      withTimeout(timeoutMillis) { synchronize() }
      Result.success(Unit)
    } catch (failure: TimeoutCancellationException) {
      Result.failure(ProjectModelApplyException(
        ProjectModelErrorCode.PROJECT_METADATA_NOT_READY,
        "Initial JPS synchronization did not complete before the deadline.",
        failure,
      ))
    } catch (failure: Exception) {
      // Store the failure without cancelling the injected service scope; every caller sees it.
      Result.failure(failure)
    }
  }

  suspend fun await() = completion.await().getOrThrow()
}
