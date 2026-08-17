package com.reqws.goland.ui

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.RoundedLineBorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.ActionListener
import javax.swing.JButton
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder

class ReqwsToolWindowPanelTest {
  @Test
  fun `summary and repository groups use the shared card treatment`() {
    val summaryContent = JPanel()
    val repositoriesContent = JPanel()

    val summaryCard = createToolWindowCard(summaryContent)
    val repositoriesCard = createToolWindowCard(repositoriesContent)

    assertEquals(REQWS_UI_ROLE_CARD, summaryCard.getClientProperty(REQWS_UI_ROLE_PROPERTY))
    assertEquals(REQWS_UI_ROLE_CARD, repositoriesCard.getClientProperty(REQWS_UI_ROLE_PROPERTY))
    assertSame(summaryContent, summaryCard.components.single())
    assertSame(repositoriesContent, repositoriesCard.components.single())
    assertTrue(summaryCard.border.getBorderInsets(summaryCard).run {
      top > 0 && left > 0 && bottom > 0 && right > 0
    })
    assertTrue(repositoriesCard.border.getBorderInsets(repositoriesCard).run {
      top > 0 && left > 0 && bottom > 0 && right > 0
    })
    assertTrue((summaryCard.border as CompoundBorder).outsideBorder is RoundedLineBorder)
    assertTrue((repositoriesCard.border as CompoundBorder).outsideBorder is RoundedLineBorder)
  }

  @Test
  fun `repository header keeps title and count separate with the count right aligned`() {
    val title = JBLabel("Repositories")
    val count = JBLabel("3")
    val header = createRepositoryHeader(title, count)

    header.setSize(NARROW_TOOL_WINDOW_WIDTH, header.preferredSize.height)
    header.doLayout()

    assertEquals(2, header.componentCount)
    assertSame(title, header.components[0])
    assertSame(count, header.components[1])
    assertTrue(title.x < count.x)
    assertTrue(count.x + count.width < header.width)
    assertTrue(count.x + count.width > header.width / 2)
    assertFalse(title.text.contains("3"))
    assertEquals("3", count.text)
  }

  @Test
  fun `manifest text labels disable Swing automatic HTML rendering`() {
    val untrusted = "<html><img src='https://example.test/tracker'>workspace"
    val label = manifestTextLabel(untrusted)

    assertEquals(untrusted, label.text)
    assertTrue(label.getClientProperty("html.disable") == true)
    assertTrue(label.toolTipText.contains("&lt;html&gt;"))
    assertTrue(!label.toolTipText.contains("<img"))
  }

  @Test
  fun `empty manifest labels omit redundant tooltips`() {
    assertNull(manifestTextLabel().toolTipText)
  }

  @Test
  fun `long manifest labels remain horizontally compressible with complete accessible text`() {
    val value = "workspace-" + "x".repeat(1024)
    val label = manifestTextLabel(value)
    label.setAccessibleManifestValue("field.workspace", value)

    assertEquals(0, label.minimumSize.width)
    assertTrue(label.toolTipText.contains(value))
    assertTrue(label.accessibleContext.accessibleName.contains(value))
    assertEquals(value, label.accessibleContext.accessibleDescription)
  }

  @Test
  fun `status and detail labels remain horizontally compressible`() {
    val status = CompressibleTextLabel().apply {
      text = "The project is in safe mode. Trust the project to sync."
    }
    val details = CompressibleTextLabel().apply {
      text = "The last valid project model was preserved."
    }

    assertEquals(0, status.minimumSize.width)
    assertEquals(0, details.minimumSize.width)
  }

  @Test
  fun `repository lists remain horizontally compressible for long names`() {
    val repository = ReqwsRepositoryViewModel(
      name = "repository-" + "x".repeat(255),
      statusKey = "repository.active",
      statusTone = ReqwsStatusTone.SUCCESS,
    )
    val model = DefaultListModel<ReqwsRepositoryViewModel>().apply { addElement(repository) }
    val list = ReqwsRepositoryList(model)

    assertEquals(0, list.minimumSize.width)
    assertTrue(list.scrollableTracksViewportWidth)
  }

  @Test
  fun `repository viewport constrains long rows to the visible width`() {
    val model = DefaultListModel<ReqwsRepositoryViewModel>().apply {
      addElement(repository("repository-" + "x".repeat(512)))
    }
    val list = ReqwsRepositoryList(model)
    val viewport = createRepositoryViewport(list)

    viewport.setSize(NARROW_TOOL_WINDOW_WIDTH, REPOSITORY_ROW_HEIGHT)
    viewport.doLayout()
    viewport.viewport.doLayout()

    assertEquals(viewport.viewport.extentSize.width, list.width)
    val row = ReqwsRepositoryListCellRenderer().getListCellRendererComponent(
      list,
      model.firstElement(),
      0,
      false,
      false,
    ) as JComponent
    row.setSize(list.width, REPOSITORY_ROW_HEIGHT)
    row.doLayout()
    val status = row.descendants()
      .filterIsInstance<JBLabel>()
      .single { it.text == "Active" }

    assertTrue(status.x >= 0)
    assertTrue(status.x + status.width <= row.width)
  }

  @Test
  fun `repository viewport follows content height and caps itself at six rows`() {
    assertEquals(40, REPOSITORY_ROW_HEIGHT)
    listOf(
      0 to REPOSITORY_ROW_HEIGHT,
      3 to 3 * REPOSITORY_ROW_HEIGHT,
      6 to 6 * REPOSITORY_ROW_HEIGHT,
      7 to 6 * REPOSITORY_ROW_HEIGHT,
      12 to 6 * REPOSITORY_ROW_HEIGHT,
    ).forEach { (repositoryCount, expectedHeight) ->
      val model = DefaultListModel<ReqwsRepositoryViewModel>().apply {
        repeat(repositoryCount) { index -> addElement(repository("repo-$index")) }
      }
      val list = ReqwsRepositoryList(model)
      val viewport = createRepositoryViewport(list)

      assertEquals(REPOSITORY_ROW_HEIGHT, list.fixedCellHeight)
      assertSame(list, viewport.viewport.view)
      val expectedVerticalPolicy = if (repositoryCount > 6) {
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
      } else {
        ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
      }
      assertEquals(expectedVerticalPolicy, viewport.verticalScrollBarPolicy)
      assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, viewport.horizontalScrollBarPolicy)
      assertEquals(expectedHeight, viewport.preferredSize.height)
      assertEquals(expectedHeight, viewport.minimumSize.height)
      assertEquals(expectedHeight, viewport.maximumSize.height)
      viewport.verticalScrollBar.isVisible =
        expectedVerticalPolicy == ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
      viewport.setSize(NARROW_TOOL_WINDOW_WIDTH, expectedHeight)
      viewport.doLayout()
      assertEquals(
        expectedVerticalPolicy == ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        viewport.verticalScrollBar.isVisible,
      )
    }
  }

  @Test
  fun `repository rows stay compact separated and preserve text status`() {
    val repositories = arrayOf(repository("repo-a"), repository("repo-b"))
    val list = JList(repositories).apply { fixedCellHeight = REPOSITORY_ROW_HEIGHT }
    val firstRow = ReqwsRepositoryListCellRenderer().getListCellRendererComponent(
      list,
      repositories[0],
      0,
      false,
      false,
    ) as JComponent
    val firstBorder = firstRow.border as CompoundBorder
    val lastRow = ReqwsRepositoryListCellRenderer().getListCellRendererComponent(
      list,
      repositories[1],
      1,
      false,
      false,
    ) as JComponent

    assertEquals(REPOSITORY_ROW_HEIGHT, list.fixedCellHeight)
    val tooltip = firstRow.toolTipText
    assertTrue(tooltip.contains("repo-a"))
    assertTrue(tooltip.contains("Active"))
    assertTrue(firstRow.accessibleContext.accessibleName.contains("repo-a"))
    assertTrue(firstRow.accessibleContext.accessibleName.contains("Active"))

    assertFalse(firstBorder.outsideBorder is EmptyBorder)
    assertTrue(firstBorder.insideBorder is EmptyBorder)
    assertTrue(lastRow.border is EmptyBorder)
  }

  @Test
  fun `status pill keeps semantic text and its intrinsic compact width`() {
    val status = CompressibleTextLabel()
    status.applyStatusPill("Synced", ReqwsStatusTone.SUCCESS)
    val holder = JPanel(BorderLayout()).apply {
      add(status, BorderLayout.LINE_END)
      setSize(NARROW_TOOL_WINDOW_WIDTH, status.preferredSize.height)
      doLayout()
    }

    assertEquals(REQWS_UI_ROLE_STATUS_PILL, status.getClientProperty(REQWS_UI_ROLE_PROPERTY))
    assertEquals("Synced", status.text)
    assertTrue(status.accessibleContext.accessibleName.contains("Synced"))
    assertEquals("Synced", status.accessibleContext.accessibleDescription)
    assertTrue(status.icon != null)
    assertTrue(status.width < holder.width)
    assertEquals(status.preferredSize.width, status.width)
  }

  @Test
  fun `longest production status pill remains fully visible inside the padded narrow body`() {
    val status = CompressibleTextLabel().apply {
      applyStatusPill("Partially Available", ReqwsStatusTone.WARNING)
    }
    val holder = createStatusHolder(status)

    holder.setSize(PADDED_NARROW_BODY_WIDTH, status.preferredSize.height)
    holder.doLayout()

    assertTrue(status.preferredSize.width <= holder.width)
    assertTrue(status.x >= 0)
    assertTrue(status.x + status.width <= holder.width)
    assertEquals(status.preferredSize.width, status.width)
  }

  @Test
  fun `long untrusted repository names fit a narrow row without losing safe accessible text`() {
    val name = "<html><img src='https://example.test/tracker'>repository-" + "x".repeat(512)
    val repository = repository(name)
    val list = JList(arrayOf(repository)).apply { fixedCellHeight = REPOSITORY_ROW_HEIGHT }
    val row = ReqwsRepositoryListCellRenderer().getListCellRendererComponent(
      list,
      repository,
      0,
      false,
      false,
    ) as JComponent

    row.setSize(NARROW_TOOL_WINDOW_WIDTH, REPOSITORY_ROW_HEIGHT)
    row.doLayout()
    val nameLabel = row.descendants()
      .filterIsInstance<JBLabel>()
      .single { it.text == name }

    assertEquals(0, nameLabel.minimumSize.width)
    assertTrue(nameLabel.x >= 0)
    assertTrue(nameLabel.x + nameLabel.width <= row.width)
    assertTrue(nameLabel.getClientProperty("html.disable") == true)
    assertTrue(nameLabel.toolTipText.contains("&lt;html&gt;"))
    assertFalse(nameLabel.toolTipText.contains("<img"))
    assertEquals(name, nameLabel.accessibleContext.accessibleName)
    assertTrue(row.accessibleContext.accessibleName.contains(name))
  }

  @Test
  fun `primary and secondary actions preserve hierarchy in a narrow vertical layout`() {
    val sync = ReqwsPrimaryButton("Sync Now")
    val openManifest = ActionLink("Open Manifest File", ActionListener {})
    val copyDiagnostics = ActionLink("Copy Diagnostics", ActionListener {})
    val actions = createToolWindowActions(
      sync,
      openManifest,
      copyDiagnostics,
    )
    actions.setSize(NARROW_TOOL_WINDOW_WIDTH, actions.preferredSize.height)
    actions.doLayout()

    assertEquals(3, actions.componentCount)
    assertEquals(REQWS_UI_ROLE_PRIMARY_ACTION, sync.getClientProperty(REQWS_UI_ROLE_PROPERTY))
    assertTrue(sync.isDefaultButton)
    assertTrue(sync.preferredSize.height >= 36)
    assertEquals(0, sync.x)
    assertEquals(actions.width, sync.width)
    assertEquals((actions.width - openManifest.width) / 2, openManifest.x)
    assertEquals((actions.width - copyDiagnostics.width) / 2, copyDiagnostics.x)
    assertTrue(openManifest.width < sync.width)
    assertTrue(copyDiagnostics.width < sync.width)
    assertNull(openManifest.icon)
    assertNull(copyDiagnostics.icon)
    assertTrue(actions.components.all { component ->
      component.y >= 0 && component.y + component.height <= actions.height
    })
  }

  private fun repository(name: String) = ReqwsRepositoryViewModel(
    name = name,
    statusKey = "repository.active",
    statusTone = ReqwsStatusTone.SUCCESS,
  )

  private fun Component.descendants(): Sequence<Component> = sequence {
    yield(this@descendants)
    if (this@descendants is java.awt.Container) {
      this@descendants.components.forEach { child -> yieldAll(child.descendants()) }
    }
  }

  private companion object {
    const val NARROW_TOOL_WINDOW_WIDTH = 280
    const val PADDED_NARROW_BODY_WIDTH = 256
  }
}
