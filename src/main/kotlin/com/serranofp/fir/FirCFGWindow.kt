package com.serranofp.fir

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirControlFlowGraphOwner
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.resolve.dfa.controlFlowGraph
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JToolBar

class FirCFGToolWindow : ToolWindowFactory {
    override fun isDumbAware(): Boolean = false

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

    }
}

fun ToolWindow.addTabForCFG(element: FirElement) {
    val selectedCfg = element as? FirControlFlowGraphOwner ?: return
    val graph = selectedCfg.controlFlowGraphReference?.controlFlowGraph ?: return
    val name = (element as? FirDeclaration)?.symbol?.shownName(true) ?: "??"

    val ui = controlFlowGraphWindow(graph.graph())
    val content = ContentFactory.getInstance().createContent(ui, name, false)
    contentManager.addContent(content)
    contentManager.setSelectedContent(content, true)
    activate(null)
}

fun controlFlowGraphWindow(graph: (String, Boolean) -> String): SimpleToolWindowPanel {
    var vertical = true
    var dfaComments = false
    var loaded = false

    val browser = JBCefBrowser()
    browser.component.background = JBColor.WHITE

    val zoomLabel = JLabel("100%").apply {
        isOpaque = false
        border = null
    }

    fun loadGraph() {
        val prefix = if (vertical) "TD" else "LR"
        val definition = graph(prefix, dfaComments)
        if (!loaded) {
            loaded = true
            browser.loadHTML(mermaidHtml(definition))
        } else {
            val js = "window.__cfgUpdate && window.__cfgUpdate(${definition.asJsStringLiteral()})"
            browser.cefBrowser.executeJavaScript(js, "about:blank", 0)
        }
    }

    val switch = JButton("Switch Direction", AllIcons.Actions.SyncPanels).apply {
        toolTipText = "Switch Direction"
        border = null
        addActionListener {
            vertical = !vertical
            loadGraph()
        }
    }

    val dfa = JBCheckBox("DFA", dfaComments).apply {
        toolTipText = "Show data flow analysis information"
        addActionListener {
            dfaComments = isSelected
            loadGraph()
        }
    }

    val zoomIn = JButton(AllIcons.General.ZoomIn).apply {
        toolTipText = "Zoom In"
        border = null
        addActionListener {
            browser.cefBrowser.executeJavaScript(
                "window.__cfgZoomIn && window.__cfgZoomIn()", "about:blank", 0
            )
        }
    }

    val zoomOut = JButton(AllIcons.General.ZoomOut).apply {
        toolTipText = "Zoom Out"
        border = null
        addActionListener {
            browser.cefBrowser.executeJavaScript(
                "window.__cfgZoomOut && window.__cfgZoomOut()", "about:blank", 0
            )
        }
    }

    val zoomQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    zoomQuery.addHandler { percentage ->
        zoomLabel.text = percentage + "%"
        null
    }

    val toolbar = JToolBar().apply {
        isFloatable = false
    }

    val ui = SimpleToolWindowPanel(true)
    ui.toolbar = toolbar
    ui.setContent(browser.component)

    loadGraph()

    toolbar.add(switch)
    toolbar.add(dfa)
    toolbar.add(Box.createHorizontalGlue())
    toolbar.add(zoomOut)
    toolbar.add(zoomLabel)
    toolbar.add(zoomIn)

    browser.cefBrowser.executeJavaScript(
        "window.__cfgOnZoomChange = function(pct) { " +
        zoomQuery.inject("pct") +
        " };", "about:blank", 0
    )

    browser.component.requestFocusInWindow()

    return ui
}

private fun String.asJsStringLiteral(): String =
    buildString {
        append('"')
        for (c in this@asJsStringLiteral) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }
