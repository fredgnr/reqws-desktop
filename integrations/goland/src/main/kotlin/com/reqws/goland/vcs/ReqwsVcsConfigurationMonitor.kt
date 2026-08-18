package com.reqws.goland.vcs

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.VcsMappingListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Public-topic revision tracker used to detect and compensate for whole-list VCS mapping races.
 *
 * The platform exposes no compare-and-set mapping API. This monitor therefore supplies bounded
 * quiescence detection plus an external-change signal for eventual forced reconciliation; it does
 * not claim that the final read/set pair is atomic with arbitrary background writers.
 */
@Service(Service.Level.PROJECT)
internal class ReqwsVcsConfigurationMonitor(
  private val project: Project,
) : Disposable {
  private val revision = AtomicLong(0)
  private val expectedPluginWrite = AtomicReference<List<VcsDirectoryMapping>?>(null)
  private val recentPluginWrite = AtomicReference<List<VcsDirectoryMapping>?>(null)
  private val pendingExternal = AtomicReference<ExternalVcsMappings?>(null)
  private val externalListeners = CopyOnWriteArrayList<() -> Unit>()

  private val vcsManager: ProjectLevelVcsManager
    get() = ProjectLevelVcsManager.getInstance(project)

  init {
    project.messageBus.connect(this).subscribe(
      ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED,
      VcsMappingListener(::configurationChanged),
    )
  }

  fun snapshot(): VersionedVcsMappings {
    repeat(MAX_SNAPSHOT_ATTEMPTS - 1) {
      val before = revision.get()
      val mappings = vcsManager.getDirectoryMappings().toList()
      val external = pendingExternal.get()
      val after = revision.get()
      if (before == after) {
        return VersionedVcsMappings(after, mappings, pendingExternal = external)
      }
    }
    return VersionedVcsMappings(
      revision = revision.get(),
      mappings = vcsManager.getDirectoryMappings().toList(),
      pendingExternal = pendingExternal.get(),
    )
  }

  fun awaitQuiescentSnapshot(): VersionedVcsMappings = awaitQuiescentSnapshot(
    snapshot = ::snapshot,
    pause = { Thread.sleep(QUIESCENCE_SAMPLE_MILLIS) },
  )

  fun runPluginWrite(expectedMappings: List<VcsDirectoryMapping>, action: () -> Unit) {
    val expected = expectedMappings.toList()
    check(expectedPluginWrite.compareAndSet(null, expected)) {
      "Nested ReqWS VCS mapping writes are not supported"
    }
    try {
      action()
    } finally {
      // Some platform implementations publish synchronously and some coalesce notification work.
      // Explicitly advancing the revision makes the write visible in either case; retain one exact
      // expected snapshot so a delayed self-event cannot replace an earlier external baseline.
      revision.incrementAndGet()
      recentPluginWrite.set(expected)
      expectedPluginWrite.compareAndSet(expected, null)
    }
  }

  fun addExternalChangeListener(listener: () -> Unit): AutoCloseable {
    externalListeners.add(listener)
    return AutoCloseable { externalListeners.remove(listener) }
  }

  fun acknowledgeExternalSnapshot(externalRevision: Long) {
    while (true) {
      val pending = pendingExternal.get() ?: return
      if (pending.revision != externalRevision) return
      if (pendingExternal.compareAndSet(pending, null)) return
    }
  }

  private fun configurationChanged() {
    val eventRevision = revision.incrementAndGet()
    val mappings = vcsManager.getDirectoryMappings().toList()
    val expected = expectedPluginWrite.get()
    if (expected != null && mappings == expected) return
    val recent = recentPluginWrite.get()
    if (recent != null && mappings == recent && recentPluginWrite.compareAndSet(recent, null)) return
    recentPluginWrite.set(null)
    // Retain the complete external list (including rootSettings) until the adapter proves that a
    // later ReqWS list was planned from this exact baseline and explicitly acknowledges it.
    val recorded = recordNewerExternalSnapshot(
      pendingExternal,
      ExternalVcsMappings(eventRevision, mappings),
    )
    if (!recorded) return
    externalListeners.forEach { listener ->
      try {
        listener()
      } catch (_: Exception) {
        // A faulty observer must not break the platform's VCS configuration publisher.
      }
    }
  }

  override fun dispose() {
    expectedPluginWrite.set(null)
    recentPluginWrite.set(null)
    pendingExternal.set(null)
    externalListeners.clear()
  }

  companion object {
    internal fun recordNewerExternalSnapshot(
      target: AtomicReference<ExternalVcsMappings?>,
      candidate: ExternalVcsMappings,
    ): Boolean {
      while (true) {
        val current = target.get()
        if (current != null && current.revision >= candidate.revision) return false
        if (target.compareAndSet(current, candidate)) return true
      }
    }

    internal fun awaitQuiescentSnapshot(
      snapshot: () -> VersionedVcsMappings,
      pause: () -> Unit,
      maxSamples: Int = MAX_QUIESCENCE_SAMPLES,
      requiredStableSamples: Int = REQUIRED_STABLE_SAMPLES,
    ): VersionedVcsMappings {
      require(maxSamples > 0)
      require(requiredStableSamples > 0)
      var previous = snapshot()
      var stableSamples = 0
      repeat(maxSamples) {
        pause()
        val current = snapshot()
        if (
          current.revision == previous.revision &&
          current.mappings == previous.mappings &&
          current.pendingExternal == previous.pendingExternal
        ) {
          stableSamples += 1
          if (stableSamples >= requiredStableSamples) return current.copy(quiescent = true)
        } else {
          stableSamples = 0
          previous = current
        }
      }
      return previous.copy(quiescent = false)
    }

    private const val MAX_SNAPSHOT_ATTEMPTS = 3
    private const val MAX_QUIESCENCE_SAMPLES = 8
    private const val REQUIRED_STABLE_SAMPLES = 2
    private const val QUIESCENCE_SAMPLE_MILLIS = 25L
  }
}
