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
  private val isTerminal: (T) -> Boolean,
) {
  @Volatile
  private var currentState = initialState
  private val lock = Any()
  private val listeners = LinkedHashSet<Registration<T>>()
  private val pending = ArrayDeque<Notification<T>>()
  private var draining = false

  val state: T
    get() = currentState

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
    val shouldDrain = synchronized(lock) {
      if (isTerminal(currentState)) return false
      currentState = next
      val recipients = listeners.toList()
      if (isTerminal(next)) listeners.clear()
      enqueue(Notification(recipients, next))
    }
    if (shouldDrain) drain()
    return true
  }

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
