package com.reqws.goland.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.reqws.goland.ReqwsBundle
import com.reqws.goland.ReqwsPlugin
import com.reqws.goland.diagnostics.ReqwsDiagnostics
import com.reqws.goland.project.ReqwsLifecycleState
import com.reqws.goland.project.ReqwsProjectDetector
import com.reqws.goland.project.ReqwsProjectService
import com.reqws.goland.project.ReqwsProjectState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionListener
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListModel
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

internal class ReqwsToolWindowPanel(
  private val project: Project,
  private val service: ReqwsProjectService,
) : JPanel(BorderLayout()), Disposable {
  private val disposed = AtomicBoolean(false)
  private val workspaceValue = manifestTextLabel()
  private val branchValue = manifestTextLabel()
  private val activeRepositoriesValue = CompressibleTextLabel()
  private val statusValue = CompressibleTextLabel()
  private val repositoriesTitle = JBLabel(ReqwsBundle.message("section.repositories"))
  private val repositoriesCount = JBLabel("0")
  private val repositoryModel = DefaultListModel<ReqwsRepositoryViewModel>()
  private val repositoriesList = ReqwsRepositoryList(repositoryModel).apply {
    fixedCellHeight = JBUI.scale(REPOSITORY_ROW_HEIGHT)
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    cellRenderer = ReqwsRepositoryListCellRenderer()
    emptyText.text = ReqwsBundle.message("message.noRepositories")
    accessibleContext.accessibleName = ReqwsBundle.message("section.repositories")
    isOpaque = false
  }
  private val repositoryViewport = createRepositoryViewport(repositoriesList)
  private val details = CompressibleTextLabel()
  private val syncButton = ReqwsPrimaryButton(ReqwsBundle.message("action.syncNow"))
  private val openManifestLink = ActionLink(
    ReqwsBundle.message("action.openManifest"),
    ActionListener { openManifest() },
  )
  private val copyDiagnosticsLink = ActionLink(
    ReqwsBundle.message("action.copyDiagnostics"),
    ActionListener { copyDiagnostics() },
  )
  private var currentState = service.state
  private val listenerHandle: AutoCloseable

  init {
    isOpaque = true
    background = JBUI.CurrentTheme.ToolWindow.background()
    add(createMainBody(), BorderLayout.CENTER)
    add(createFooter(), BorderLayout.SOUTH)

    syncButton.addActionListener {
      if (isUsable()) service.refresh()
    }

    listenerHandle = service.addListener(::acceptState)
  }

  private fun createMainBody(): JPanel = JPanel(GridBagLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(12, 12, 0, 12)
    add(
      createStatusRow(),
      GridBagConstraints().apply {
        gridx = 0
        gridy = 0
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
      },
    )
    add(
      createSummaryCard(),
      GridBagConstraints().apply {
        gridx = 0
        gridy = 1
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        insets = JBUI.insetsTop(8)
      },
    )
    add(
      createRepositoryCard(),
      GridBagConstraints().apply {
        gridx = 0
        gridy = 2
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        anchor = GridBagConstraints.PAGE_START
        insets = JBUI.insetsTop(12)
      },
    )
    add(
      JPanel().apply { isOpaque = false },
      GridBagConstraints().apply {
        gridx = 0
        gridy = 3
        weightx = 1.0
        weighty = 1.0
        fill = GridBagConstraints.BOTH
      },
    )
  }

  private fun createStatusRow(): JPanel = JPanel(GridBagLayout()).apply {
    isOpaque = false
    add(createStatusHolder(statusValue), GridBagConstraints().apply {
      gridx = 0
      gridy = 0
      weightx = 1.0
      fill = GridBagConstraints.HORIZONTAL
    })
  }

  private fun createSummaryCard(): JPanel {
    val identity = JPanel(GridBagLayout()).apply { isOpaque = false }
    workspaceValue.font = JBFont.h3()
    workspaceValue.accessibleContext.accessibleName = ReqwsBundle.message("field.workspace")
    branchValue.font = JBFont.small()
    branchValue.foreground = UIUtil.getContextHelpForeground()
    branchValue.accessibleContext.accessibleName = ReqwsBundle.message("field.branch")
    activeRepositoriesValue.font = JBFont.small()
    activeRepositoriesValue.foreground = UIUtil.getContextHelpForeground()
    identity.add(
      workspaceValue,
      GridBagConstraints().apply {
        gridx = 0
        gridy = 0
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        anchor = GridBagConstraints.LINE_START
      },
    )
    identity.add(
      branchValue,
      GridBagConstraints().apply {
        gridx = 0
        gridy = 1
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        anchor = GridBagConstraints.LINE_START
        insets = JBUI.insetsTop(4)
      },
    )
    identity.add(
      activeRepositoriesValue,
      GridBagConstraints().apply {
        gridx = 0
        gridy = 2
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        anchor = GridBagConstraints.LINE_START
        insets = JBUI.insetsTop(4)
      },
    )
    return createToolWindowCard(identity)
  }

  private fun createRepositoryCard(): JPanel {
    repositoriesTitle.font = JBFont.label().asBold()
    repositoriesCount.font = JBFont.label()
    repositoriesCount.foreground = UIUtil.getContextHelpForeground()
    repositoriesCount.accessibleContext.accessibleName = ReqwsBundle.message("section.repositories")
    val content = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
      isOpaque = false
      add(createRepositoryHeader(repositoriesTitle, repositoriesCount), BorderLayout.NORTH)
      add(repositoryViewport, BorderLayout.CENTER)
    }
    return createToolWindowCard(content, contentPadding = 0)
  }

  private fun createFooter(): JPanel = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
    isOpaque = true
    background = JBUI.CurrentTheme.ToolWindow.background()
    border = JBUI.Borders.compound(
      JBUI.Borders.customLineTop(JBUI.CurrentTheme.Separator.color()),
      JBUI.Borders.empty(10, 12, 12, 12),
    )
    details.font = JBFont.small()
    details.foreground = UIUtil.getContextHelpForeground()
    add(details, BorderLayout.NORTH)

    add(
      createToolWindowActions(syncButton, openManifestLink, copyDiagnosticsLink),
      BorderLayout.SOUTH,
    )
  }

  private fun acceptState(state: ReqwsProjectState) {
    if (!isUsable() || state.lifecycle == ReqwsLifecycleState.DISPOSED) return
    if (SwingUtilities.isEventDispatchThread()) {
      if (isUsable()) render(state)
    } else {
      SwingUtilities.invokeLater {
        if (isUsable()) render(state)
      }
    }
  }

  private fun render(state: ReqwsProjectState) {
    currentState = state
    val model = ReqwsToolWindowViewModel.from(state)
    workspaceValue.setManifestText(model.workspaceName.orEmpty())
    workspaceValue.setAccessibleManifestValue("field.workspace", model.workspaceName.orEmpty())
    branchValue.setManifestText(model.featureBranch.orEmpty())
    branchValue.setAccessibleManifestValue("field.branch", model.featureBranch.orEmpty())
    statusValue.applyStatus(model)
    activeRepositoriesValue.text = ReqwsBundle.message(
      "summary.activeRepositories",
      model.repositories.size,
    )
    activeRepositoriesValue.toolTipText = safeTextTooltip(activeRepositoriesValue.text)
    activeRepositoriesValue.accessibleContext.accessibleDescription = activeRepositoriesValue.text

    repositoryModel.removeAllElements()
    model.repositories.forEach(repositoryModel::addElement)
    repositoriesCount.text = model.repositories.size.toString()
    repositoriesCount.accessibleContext.accessibleDescription = repositoriesCount.text
    updateRepositoryViewportSize(repositoryViewport, repositoriesList)

    details.text = formatDetailsText(model)
    details.toolTipText = safeTextTooltip(details.text.orEmpty())
    details.accessibleContext.accessibleDescription = details.text
    syncButton.isEnabled = model.syncEnabled
    openManifestLink.isEnabled = model.openManifestEnabled
    copyDiagnosticsLink.isEnabled = model.copyDiagnosticsEnabled
    revalidate()
    repaint()
  }

  private fun JBLabel.applyStatus(model: ReqwsToolWindowViewModel) {
    applyStatusPill(ReqwsBundle.message(model.statusKey), model.statusTone)
  }

  private fun openManifest() {
    if (!isUsable()) return
    val root = ReqwsProjectDetector.projectRoot(project) ?: return
    val manifest = ReqwsProjectDetector.manifestPath(root)
    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(manifest) ?: return
    FileEditorManager.getInstance(project).openFile(virtualFile, true)
  }

  private fun copyDiagnostics() {
    if (!isUsable()) return
    val diagnostics = ReqwsDiagnostics.format(
      pluginVersion = ReqwsPlugin.VERSION,
      ideBuild = ApplicationInfo.getInstance().build.asString(),
      projectRoot = ReqwsProjectDetector.projectRoot(project),
      state = currentState,
    )
    CopyPasteManager.getInstance().setContents(StringSelection(diagnostics))
    details.text = ReqwsBundle.message("message.diagnosticsCopied")
    details.toolTipText = safeTextTooltip(details.text)
    details.accessibleContext.accessibleDescription = details.text
  }

  private fun isUsable(): Boolean = !disposed.get() && !project.isDisposed

  override fun dispose() {
    if (!disposed.compareAndSet(false, true)) return
    listenerHandle.close()
  }
}

internal class ReqwsRepositoryListCellRenderer : ListCellRenderer<ReqwsRepositoryViewModel> {
  private val repositoryName = manifestTextLabel()
  private val status = JBLabel()
  private val panel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
    isOpaque = true
    add(repositoryName, BorderLayout.CENTER)
    add(status, BorderLayout.LINE_END)
  }

  override fun getListCellRendererComponent(
    list: JList<out ReqwsRepositoryViewModel>,
    value: ReqwsRepositoryViewModel,
    index: Int,
    isSelected: Boolean,
    cellHasFocus: Boolean,
  ): Component {
    val statusText = ReqwsBundle.message(value.statusKey)
    repositoryName.setManifestText(value.name)
    status.text = statusText
    status.icon = statusIcon(value.statusTone)
    status.iconTextGap = JBUI.scale(5)
    panel.border = if (index < list.model.size - 1) {
      JBUI.Borders.compound(
        JBUI.Borders.customLineBottom(JBUI.CurrentTheme.Separator.color()),
        JBUI.Borders.empty(5, 10),
      )
    } else {
      JBUI.Borders.empty(5, 10)
    }
    panel.isOpaque = isSelected
    panel.background = if (isSelected) list.selectionBackground else list.background
    repositoryName.foreground = if (isSelected) list.selectionForeground else list.foreground
    status.foreground = if (isSelected) list.selectionForeground else list.foreground
    val tooltip = safeTextTooltip("${value.name} — $statusText")
    panel.toolTipText = tooltip
    panel.accessibleContext.accessibleName = "${value.name}, $statusText"
    repositoryName.toolTipText = tooltip
    repositoryName.accessibleContext.accessibleName = value.name
    status.toolTipText = tooltip
    status.accessibleContext.accessibleName = statusText
    return panel
  }
}

private fun statusIcon(tone: ReqwsStatusTone) = when (tone) {
  ReqwsStatusTone.NEUTRAL -> AllIcons.General.Information
  ReqwsStatusTone.INFO -> AllIcons.General.Information
  ReqwsStatusTone.SUCCESS -> AllIcons.General.InspectionsOK
  ReqwsStatusTone.WARNING -> AllIcons.General.Warning
  ReqwsStatusTone.ERROR -> AllIcons.General.Error
}

private fun statusColor(tone: ReqwsStatusTone) = when (tone) {
  ReqwsStatusTone.NEUTRAL -> JBColor.foreground()
  ReqwsStatusTone.INFO -> UIUtil.getLabelInfoForeground()
  ReqwsStatusTone.SUCCESS -> UIUtil.getLabelSuccessForeground()
  ReqwsStatusTone.WARNING -> JBColor.namedColor(
    "Label.warningForeground",
    JBColor(0x9B6A00, 0xE5A931),
  )
  ReqwsStatusTone.ERROR -> UIUtil.getErrorForeground()
}

private const val HTML_DISABLE_PROPERTY = "html.disable"
private const val STATUS_TONE_COLOR_PROPERTY = "reqws.status.toneColor"
internal const val REPOSITORY_ROW_HEIGHT = 40
private const val MAX_VISIBLE_REPOSITORY_ROWS = 6
internal const val REQWS_UI_ROLE_PROPERTY = "reqws.ui.role"
internal const val REQWS_UI_ROLE_CARD = "card"
internal const val REQWS_UI_ROLE_STATUS_PILL = "status-pill"
internal const val REQWS_UI_ROLE_PRIMARY_ACTION = "primary-action"

internal fun manifestTextLabel(text: String = ""): JBLabel = CompressibleTextLabel().apply {
  setManifestText(text)
}

internal fun JBLabel.setManifestText(value: String) {
  putClientProperty(HTML_DISABLE_PROPERTY, true)
  text = value
  toolTipText = safeTextTooltip(value)
}

internal fun JBLabel.setAccessibleManifestValue(fieldKey: String, value: String) {
  val field = ReqwsBundle.message(fieldKey)
  accessibleContext.accessibleName = "$field $value".trim()
  accessibleContext.accessibleDescription = value
}

internal class CompressibleTextLabel : JBLabel() {
  override fun getMinimumSize(): Dimension = super.getMinimumSize().let { minimum ->
    Dimension(0, minimum.height)
  }

  override fun paintComponent(graphics: Graphics) {
    val toneColor = getClientProperty(STATUS_TONE_COLOR_PROPERTY) as? Color
    if (toneColor != null && width > 0 && height > 0) {
      val copy = graphics.create() as Graphics2D
      try {
        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        copy.color = UIUtil.mix(UIUtil.getPanelBackground(), toneColor, 0.12)
        val arc = JBUI.scale(STATUS_PILL_ARC)
        copy.fillRoundRect(0, 0, width, height, arc, arc)
      } finally {
        copy.dispose()
      }
    }
    super.paintComponent(graphics)
  }
}

internal class ReqwsRepositoryList(
  model: ListModel<ReqwsRepositoryViewModel>,
) : JBList<ReqwsRepositoryViewModel>(model) {
  override fun getMinimumSize(): Dimension = super.getMinimumSize().let { minimum ->
    Dimension(0, minimum.height)
  }

  override fun getScrollableTracksViewportWidth(): Boolean = true
}

internal class ReqwsRepositoryScrollPane(
  list: ReqwsRepositoryList,
) : JBScrollPane(
  list,
  ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
  ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
) {
  override fun doLayout() {
    super.doLayout()
    if (verticalScrollBarPolicy == ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER) {
      verticalScrollBar.isVisible = false
    }
  }
}

internal fun createToolWindowActions(
  sync: Component,
  openManifest: Component,
  copyDiagnostics: Component,
): JPanel = JPanel(GridBagLayout()).apply {
  isOpaque = false
  (sync as? JComponent)?.putClientProperty(
    REQWS_UI_ROLE_PROPERTY,
    REQWS_UI_ROLE_PRIMARY_ACTION,
  )
  (openManifest as? ActionLink)?.icon = null
  (copyDiagnostics as? ActionLink)?.icon = null
  add(
    sync,
    GridBagConstraints().apply {
      gridx = 0
      gridy = 0
      weightx = 1.0
      fill = GridBagConstraints.HORIZONTAL
    },
  )
  add(
    openManifest,
    GridBagConstraints().apply {
      gridx = 0
      gridy = 1
      weightx = 1.0
      anchor = GridBagConstraints.CENTER
      insets = JBUI.insetsTop(12)
    },
  )
  add(
    copyDiagnostics,
    GridBagConstraints().apply {
      gridx = 0
      gridy = 2
      weightx = 1.0
      anchor = GridBagConstraints.CENTER
      insets = JBUI.insetsTop(6)
    },
  )
}

internal fun createToolWindowCard(
  content: Component,
  contentPadding: Int = 12,
): JPanel = RoundedSurfacePanel(
  layout = BorderLayout(),
  fill = UIUtil::getPanelBackground,
).apply {
  putClientProperty(REQWS_UI_ROLE_PROPERTY, REQWS_UI_ROLE_CARD)
  border = JBUI.Borders.compound(
    RoundedLineBorder(
      JBColor.border(),
      JBUI.scale(CARD_ARC),
      JBUI.scale(1),
    ),
    JBUI.Borders.empty(contentPadding),
  )
  add(content, BorderLayout.CENTER)
}

internal fun createRepositoryHeader(title: Component, count: Component): JPanel =
  JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.compound(
      JBUI.Borders.customLineBottom(JBUI.CurrentTheme.Separator.color()),
      JBUI.Borders.empty(10, 12, 10, 12),
    )
    add(title, BorderLayout.LINE_START)
    add(count, BorderLayout.LINE_END)
  }

internal fun createRepositoryViewport(list: ReqwsRepositoryList): JBScrollPane =
  ReqwsRepositoryScrollPane(list).apply {
    border = JBUI.Borders.empty()
    viewportBorder = JBUI.Borders.empty()
    isOpaque = false
    viewport.isOpaque = false
    list.fixedCellHeight = JBUI.scale(REPOSITORY_ROW_HEIGHT)
    updateRepositoryViewportSize(this, list)
  }

internal fun JBLabel.applyStatusPill(text: String, tone: ReqwsStatusTone) {
  val color = statusColor(tone)
  this.text = text
  icon = statusIcon(tone)
  iconTextGap = JBUI.scale(5)
  foreground = color
  putClientProperty(REQWS_UI_ROLE_PROPERTY, REQWS_UI_ROLE_STATUS_PILL)
  putClientProperty(STATUS_TONE_COLOR_PROPERTY, color)
  border = JBUI.Borders.compound(
    RoundedLineBorder(
      color,
      JBUI.scale(STATUS_PILL_ARC),
      JBUI.scale(1),
    ),
    JBUI.Borders.empty(2, 8),
  )
  toolTipText = safeTextTooltip(text)
  accessibleContext.accessibleName = "${ReqwsBundle.message("field.status")} $text".trim()
  accessibleContext.accessibleDescription = text
}

internal fun formatDetailsText(model: ReqwsToolWindowViewModel): String? = when {
  model.errorCode != null && model.preservedSnapshot ->
    "${model.errorCode} · ${ReqwsBundle.message("message.preservedModel")}"
  model.errorCode != null -> model.errorCode
  model.vcsDiagnosticCode != null && model.statusDetailKey != null ->
    "${model.vcsDiagnosticCode} · ${ReqwsBundle.message(model.statusDetailKey)}"
  model.vcsDiagnosticCode != null -> model.vcsDiagnosticCode
  model.statusDetailKey != null -> ReqwsBundle.message(model.statusDetailKey)
  model.digest != null -> ReqwsBundle.message("message.currentDigest", model.digest)
  !model.visible -> ReqwsBundle.message("message.noManifest")
  else -> null
}

internal fun createStatusHolder(status: Component): JPanel = JPanel(GridBagLayout()).apply {
  isOpaque = false
  add(
    status,
    GridBagConstraints().apply {
      gridx = 0
      gridy = 0
      weightx = 1.0
      anchor = GridBagConstraints.LINE_END
    },
  )
}

private fun updateRepositoryViewportSize(
  viewport: JBScrollPane,
  list: ReqwsRepositoryList,
) {
  val visibleRows = list.model.size.coerceIn(1, MAX_VISIBLE_REPOSITORY_ROWS)
  val height = JBUI.scale(REPOSITORY_ROW_HEIGHT * visibleRows)
  viewport.verticalScrollBarPolicy = if (list.model.size > MAX_VISIBLE_REPOSITORY_ROWS) {
    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
  } else {
    ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
  }
  if (viewport.verticalScrollBarPolicy == ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER) {
    viewport.verticalScrollBar.isVisible = false
  }
  viewport.preferredSize = Dimension(0, height)
  viewport.minimumSize = Dimension(0, height)
  viewport.maximumSize = Dimension(Int.MAX_VALUE, height)
}

private class RoundedSurfacePanel(
  layout: LayoutManager,
  private val fill: () -> Color,
) : JPanel(layout) {
  init {
    isOpaque = false
  }

  override fun paintComponent(graphics: Graphics) {
    super.paintComponent(graphics)
    if (width <= 0 || height <= 0) return
    val copy = graphics.create() as Graphics2D
    try {
      copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      copy.color = fill()
      val arc = JBUI.scale(CARD_ARC)
      copy.fillRoundRect(0, 0, width, height, arc, arc)
    } finally {
      copy.dispose()
    }
  }
}

internal class ReqwsPrimaryButton(text: String) : JButton(text) {
  override fun isDefaultButton(): Boolean = true

  override fun getPreferredSize(): Dimension = super.getPreferredSize().let { preferred ->
    Dimension(preferred.width, maxOf(preferred.height, JBUI.scale(PRIMARY_BUTTON_HEIGHT)))
  }
}

internal fun safeTextTooltip(value: String): String? {
  if (value.isEmpty()) return null
  val escaped = StringUtil.escapeXmlEntities(value).replace("\n", "<br>")
  return "<html>$escaped</html>"
}

private const val CARD_ARC = 8
private const val STATUS_PILL_ARC = 8
private const val PRIMARY_BUTTON_HEIGHT = 36
