package com.reqws.goland.projectmodel

import com.intellij.openapi.components.Service
import java.util.concurrent.atomic.AtomicInteger

@Service(Service.Level.PROJECT)
internal class ReqwsProjectModelMutationGuard {
  private val activeScopes = AtomicInteger()

  val isActive: Boolean
    get() = activeScopes.get() > 0

  fun <T> withMutation(block: () -> T): T {
    activeScopes.incrementAndGet()
    return try {
      block()
    } finally {
      activeScopes.decrementAndGet()
    }
  }

  suspend fun <T> withSuspendingMutation(block: suspend () -> T): T {
    activeScopes.incrementAndGet()
    return try {
      block()
    } finally {
      activeScopes.decrementAndGet()
    }
  }
}
