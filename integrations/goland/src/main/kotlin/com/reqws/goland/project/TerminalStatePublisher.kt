package com.reqws.goland.project

import java.util.ArrayDeque
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes state delivery without invoking listeners while holding the publisher lock.
 * Once a terminal value is published, later values are rejected permanently.
 */
internal class TerminalStatePublisher<T>(
  initialState: T,
  private val isStable: (T) -> Boolean = { true },
  private val isTerminal: (T) -> Boolean,
) {
  @Volatile
  private var currentState = initialState
  private var currentStableState = initialState
  private var currentVersion = 0L
  private val lock = Any()
  private val listeners = LinkedHashSet<Registration<T>>()
  private val pending = ArrayDeque<Notification<T>>()
  private var draining = false

  val state: T
    get() = currentState

  fun snapshot(): VersionedState<T> = synchronized(lock) {
    snapshotLocked()
  }

  fun addListener(listener: (T) -> Unit): AutoCloseable {
    val registration = Registration(listener)
    val shouldDrain = synchronized(lock) {
      if (!isTerminal(currentState)) listeners.add(registration)
      enqueue(Notification(listOf(registration), currentState))
    }
    if (shouldDrain) drain()
    return AutoCloseable {
      registration.close()
      synchronized(lock) { listeners.remove(registration) }
    }
  }

  fun publish(next: T): Boolean {
    val publication = preparePublish(next) ?: return false
    publication.deliver()
    return true
  }

  /**
   * Commits a versioned transition without invoking listeners. The caller must invoke
   * [StatePublication.deliver] outside any service/coordinator lock, even when later work fails.
   */
  fun preparePublish(next: T): StatePublication<T>? = synchronized(lock) {
    preparePublishLocked(next)
  }

  /** Commits [next] only while [expectedVersion] is still current; listeners remain deferred. */
  fun prepareCompareAndPublish(
    expectedVersion: Long,
    next: T,
  ): StatePublication<T>? = synchronized(lock) {
    if (currentVersion != expectedVersion) return@synchronized null
    preparePublishLocked(next)
  }

  /**
   * Derives and commits the next state from the exact current state under the publisher lock.
   * [next] must be a small, non-blocking state transformation and must not invoke listeners.
   */
  fun prepareUpdate(
    next: (VersionedState<T>) -> T,
  ): StatePublication<T>? = synchronized(lock) {
    if (isTerminal(currentState)) return@synchronized null
    val before = snapshotLocked()
    preparePublishLocked(next(before), before)
  }

  private fun preparePublishLocked(
    next: T,
    before: VersionedState<T> = snapshotLocked(),
  ): StatePublication<T>? {
    if (isTerminal(currentState)) return null
    currentVersion += 1
    currentState = next
    if (isStable(next)) currentStableState = next
    val after = snapshotLocked()
    val recipients = listeners.toList()
    if (isTerminal(next)) listeners.clear()
    val shouldDrain = enqueue(Notification(recipients, next))
    return StatePublication(
      before = before,
      after = after,
      delivery = if (shouldDrain) ::drain else null,
    )
  }

  private fun snapshotLocked(): VersionedState<T> = VersionedState(
    state = currentState,
    stableState = currentStableState,
    version = currentVersion,
  )

  private fun enqueue(notification: Notification<T>): Boolean {
    pending.addLast(notification)
    if (draining) return false
    draining = true
    return true
  }

  private fun drain() {
    var firstFailure: Throwable? = null
    while (true) {
      val notification = synchronized(lock) {
        if (pending.isEmpty()) {
          draining = false
          null
        } else {
          pending.removeFirst()
        }
      }
      if (notification == null) {
        firstFailure?.let { throw it }
        return
      }
      notification.recipients.forEach { registration ->
        try {
          registration.deliver(notification.state)
        } catch (failure: Throwable) {
          if (firstFailure == null) firstFailure = failure
        }
      }
    }
  }

  private data class Notification<T>(
    val recipients: List<Registration<T>>,
    val state: T,
  )

  private class Registration<T>(
    private val listener: (T) -> Unit,
  ) {
    private val active = AtomicBoolean(true)

    fun deliver(state: T) {
      if (active.get()) listener(state)
    }

    fun close() {
      active.set(false)
    }
  }
}

internal data class VersionedState<T>(
  val state: T,
  val stableState: T,
  val version: Long,
)

internal class StatePublication<T> internal constructor(
  val before: VersionedState<T>,
  val after: VersionedState<T>,
  private val delivery: (() -> Unit)?,
) {
  private val delivered = AtomicBoolean(false)

  fun deliver() {
    if (delivered.compareAndSet(false, true)) delivery?.invoke()
  }
}
