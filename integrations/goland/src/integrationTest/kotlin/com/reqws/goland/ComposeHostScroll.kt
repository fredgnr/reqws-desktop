package com.reqws.goland

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.client.service
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.remote.SwingHierarchyService
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.w3c.dom.Element
import com.intellij.driver.model.OnDispatcher
import java.awt.Point
import java.awt.Rectangle
import kotlin.math.abs

/** Observes native Compose scroll state through read-only standard Java Accessibility. */
internal data class NativeScrollRange(val value: NativeAccessibleValue, val bounds: Rectangle) {
  fun current() = requireNotNull(value.getCurrentAccessibleValue()).toDouble()
  fun maximum() = requireNotNull(value.getMaximumAccessibleValue()).toDouble()
}

internal fun Driver.nativeVerticalScroll(target: Rectangle, text: String? = null): NativeScrollRange =
  withContext(OnDispatcher.EDT) {
    var visited = 0
    fun search(context: NativeAccessibleContext, depth: Int): NativeScrollRange? {
      check(++visited <= 3000 && depth <= 40) { "Accessible scroll search exceeded its bound" }
      val component = context.getAccessibleComponent()
      val bounds = if (component?.isShowing() == true) Rectangle(component.getLocationOnScreen(), component.getBounds().size) else null
      if (bounds != null && !bounds.intersects(target)) return null
      val count = context.getAccessibleChildrenCount()
      check(count in 0..3000)
      val children = (0 until count).mapNotNull { context.getAccessibleChild(it)?.getAccessibleContext() }
      val matches = if (text != null) context.getAccessibleName() == text else bounds != null &&
        abs(bounds.x - target.x) <= 2 && abs(bounds.y - target.y) <= 2 &&
        abs(bounds.width - target.width) <= 2 && abs(bounds.height - target.height) <= 2
      if (matches && bounds != null) {
        for (child in children) {
          if (child.getAccessibleRole().toString() == "scroll bar" &&
            "vertical" in child.getAccessibleStateSet().toString()) {
            val value = child.getAccessibleValue() ?: continue
            return NativeScrollRange(value, bounds)
          }
        }
      }
      for (child in children) search(child, depth + 1)?.let { return it }
      return null
    }
    val windows = utility<NativeAccessibleWindow>().getWindows().filter { it.isShowing() && it.getBounds().intersects(target) }
      .sortedBy { it.getBounds().let { bounds -> bounds.width.toLong() * bounds.height } }
    var found: NativeScrollRange? = null
    for (window in windows) {
      found = window.getAccessibleContext()?.let { search(it, 0) }
      if (found != null) break
    }
    requireNotNull(found) { "No public vertical scroll range matched bounds=$target text=$text (visited=$visited)" }
  }

@Remote("java.awt.Window")
internal interface NativeAccessibleWindow {
  fun getWindows(): Array<NativeAccessibleWindow>
  fun isShowing(): Boolean
  fun getBounds(): Rectangle
  fun getAccessibleContext(): NativeAccessibleContext?
}
@Remote("javax.accessibility.Accessible")
internal interface NativeAccessible { fun getAccessibleContext(): NativeAccessibleContext? }
@Remote("javax.accessibility.AccessibleContext")
internal interface NativeAccessibleContext {
  fun getAccessibleName(): String?
  fun getAccessibleChildrenCount(): Int
  fun getAccessibleChild(index: Int): NativeAccessible?
  fun getAccessibleRole(): NativeAccessibleRole
  fun getAccessibleStateSet(): NativeAccessibleStates
  fun getAccessibleComponent(): NativeAccessibleComponent?
  fun getAccessibleValue(): NativeAccessibleValue?
}
@Remote("javax.accessibility.AccessibleRole")
internal interface NativeAccessibleRole { override fun toString(): String }
@Remote("javax.accessibility.AccessibleStateSet")
internal interface NativeAccessibleStates { override fun toString(): String }
@Remote("javax.accessibility.AccessibleComponent")
internal interface NativeAccessibleComponent {
  fun isShowing(): Boolean
  fun getLocationOnScreen(): Point
  fun getBounds(): Rectangle
}
@Remote("javax.accessibility.AccessibleValue")
internal interface NativeAccessibleValue {
  fun getCurrentAccessibleValue(): Number?
  fun getMaximumAccessibleValue(): Number?
}
@Remote("java.awt.MouseInfo")
internal interface NativeMouseInfo { fun getPointerInfo(): NativePointerInfo }
@Remote("java.awt.PointerInfo")
internal interface NativePointerInfo { fun getLocation(): Point }

/** Read the same real Compose attributes used by Driver XPath; Swing text painting omits them. */
internal fun Driver.composeAttributes(tag: String): Map<String, String> {
  val xml = service<SwingHierarchyService>().getSwingHierarchyAsDOM(ideFrame().component, true)
  check(xml.length <= 16 * 1024 * 1024)
  val factory = DocumentBuilderFactory.newInstance().apply {
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeature("http://xml.org/sax/features/external-general-entities", false)
    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    isXIncludeAware = false
    isExpandEntityReferences = false
  }
  val nodes = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).getElementsByTagName("*")
  val matches = (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.getAttribute("testtag") == tag }
  check(matches.size <= 1) { "Duplicate production Compose tag: $tag" }
  return matches.singleOrNull()?.let { node ->
    mapOf("text" to node.getAttribute("text"), "contentdescription" to node.getAttribute("contentdescription"),
      "focused" to node.getAttribute("focused"))
  }.orEmpty()
}
