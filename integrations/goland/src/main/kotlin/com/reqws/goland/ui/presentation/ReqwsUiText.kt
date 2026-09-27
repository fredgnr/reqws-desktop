package com.reqws.goland.ui.presentation

import com.reqws.goland.ReqwsBundle
import com.reqws.goland.ui.state.ReqwsUiState

internal fun formatDetailsText(model: ReqwsUiState): String? = when {
  model.errorCode != null && model.preservedSnapshot ->
    listOfNotNull(
      model.errorCode,
      model.errorDetailKey?.let { ReqwsBundle.message(it) },
      ReqwsBundle.message("message.preservedModel"),
    ).joinToString(" · ")
  model.errorCode != null -> listOfNotNull(
    model.errorCode,
    model.errorDetailKey?.let { ReqwsBundle.message(it) },
  ).joinToString(" · ")
  model.vcsDiagnosticCode != null && model.statusDetailKey != null ->
    "${model.vcsDiagnosticCode} · ${ReqwsBundle.message(model.statusDetailKey)}"
  model.vcsDiagnosticCode != null -> model.vcsDiagnosticCode
  model.statusDetailKey != null -> ReqwsBundle.message(model.statusDetailKey)
  model.digest != null -> ReqwsBundle.message("message.currentDigest", model.digest)
  !model.visible -> ReqwsBundle.message("message.noManifest")
  else -> null
}
