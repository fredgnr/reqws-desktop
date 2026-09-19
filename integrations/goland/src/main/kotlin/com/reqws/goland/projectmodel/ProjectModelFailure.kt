package com.reqws.goland.projectmodel

enum class ProjectModelErrorCode {
  PROJECT_DISPOSED,
  UNTRUSTED_PROJECT,
  PROJECT_METADATA_NOT_READY,
  OWNERSHIP_CONFLICT,
  LIVE_FILE_INDEX_NOT_CONVERGED,
}

class ProjectModelApplyException(
  val code: ProjectModelErrorCode,
  message: String,
  cause: Throwable? = null,
) : IllegalStateException(message, cause)
