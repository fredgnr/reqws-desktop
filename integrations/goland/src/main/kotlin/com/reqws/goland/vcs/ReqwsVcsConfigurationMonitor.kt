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
  private val activePluginWrite = AtomicReference<PluginWriteMarker?>(null)
  private val synchronousPluginWrite = ThreadLocal<PluginWriteMarker?>()
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
      val mappings = canonicalizeVcsMappings(vcsManager.getDirectoryMappings().toList())
      val external = pendingExternal.get()?.platformCanonicalized()
      val after = revision.get()
      if (before == after) {
        return VersionedVcsMappings(after, mappings, pendingExternal = external)
      }
    }
    return VersionedVcsMappings(
      revision = revision.get(),
      mappings = canonicalizeVcsMappings(vcsManager.getDirectoryMappings().toList()),
      pendingExternal = pendingExternal.get()?.platformCanonicalized(),
    )
  }

  fun awaitQuiescentSnapshot(): VersionedVcsMappings = awaitQuiescentSnapshot(
    snapshot = ::snapshot,
    pause = { Thread.sleep(QUIESCENCE_SAMPLE_MILLIS) },
  )

  fun runPluginWrite(expectedMappings: List<VcsDirectoryMapping>, action: () -> Unit) {
    val expected = canonicalizeVcsMappings(expectedMappings)
    val marker = PluginWriteMarker(expected)
    check(activePluginWrite.compareAndSet(null, marker)) {
      "Nested ReqWS VCS mapping writes are not supported"
    }
    check(synchronousPluginWrite.get() == null) { "Nested ReqWS VCS mapping thread marker" }
    synchronousPluginWrite.set(marker)
    try {
      action()
    } finally {
      synchronousPluginWrite.remove()
      // Explicitly advance the revision even when the platform publishes no synchronous event.
      // A delayed callback is intentionally external: list equality cannot distinguish a delayed
      // self-event from an equal-list user/pooled ABA replacement and must not mint deletion rights.
      revision.incrementAndGet()
      activePluginWrite.compareAndSet(marker, null)
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
    val mappings = canonicalizeVcsMappings(vcsManager.getDirectoryMappings().toList())
    val marker = synchronousPluginWrite.get()
    if (marker != null && mappings == marker.expectedMappings) return
    // Retain the complete external list (including rootSettings) until the adapter proves that a
    // later ReqWS list was planned from this exact baseline and explicitly acknowledges it.
    val recorded = recordNewerExternalSnapshot(
      pendingExternal,
      ExternalVcsMappings(eventRevision, mappings).platformCanonicalized(),
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
    activePluginWrite.set(null)
    synchronousPluginWrite.remove()
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
      var previous = snapshot().platformCanonicalized()
      var stableSamples = 0
      repeat(maxSamples) {
        pause()
        val current = snapshot().platformCanonicalized()
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

private data class PluginWriteMarker(
  val expectedMappings: List<VcsDirectoryMapping>,
)
