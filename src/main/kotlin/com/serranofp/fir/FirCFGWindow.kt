package com.serranofp.fir

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
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
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirControlFlowGraphOwner
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNodeWithSubgraphs
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.ControlFlowGraph
import org.jetbrains.kotlin.fir.resolve.dfa.controlFlowGraph
import java.util.concurrent.Callable
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JToolBar

class FirCFGToolWindow : ToolWindowFactory {
    override fun isDumbAware(): Boolean = false

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

    }
}

fun ToolWindow.addTabForCFG(element: FirElement, editorFromTree: Editor? = null) {
    val selectedCfg = element as? FirControlFlowGraphOwner ?: return
    val controlFlowGraph = selectedCfg.controlFlowGraphReference?.controlFlowGraph ?: return
    val name = (element as? FirDeclaration)?.symbol?.shownName(true) ?: "??"

    val ui = controlFlowGraphWindow(controlFlowGraph, editorFromTree)
    val content = ContentFactory.getInstance().createContent(ui, name, false)
    contentManager.addContent(content)
    contentManager.setSelectedContent(content, true)
    activate(null)
}

fun controlFlowGraphWindow(controlFlowGraph: ControlFlowGraph, editor: Editor?): SimpleToolWindowPanel {
    var vertical = true
    var dfaComments = false
    var loaded = false

    // Build the mapping from node ID to FIR element for click navigation
    val graphGenerator = controlFlowGraph.graph()
    // We need to extract the node-to-FIR mapping. The graph() function returns a lambda,
    // but we need the mapping it builds internally. We'll build it here as well.
    val nodeToFir = buildNodeToFirMap(controlFlowGraph)

    val browser = JBCefBrowser()
    browser.component.background = JBColor.WHITE

    val zoomLabel = JLabel("100%").apply {
        isOpaque = false
        border = null
    }

    fun loadGraph() {
        val prefix = if (vertical) "TD" else "LR"
        val definition = graphGenerator(prefix, dfaComments)
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
        zoomLabel.text = "$percentage%"
        null
    }

    // Set up click handler for navigating to source code
    val clickQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    clickQuery.addHandler { nodeIdStr ->
        ReadAction.nonBlocking(Callable handler@{
            val nodeId = nodeIdStr.toString().toIntOrNull() ?: return@handler null
            val firElement = nodeToFir[nodeId] ?: return@handler null
            firElement.source
        }).finishOnUiThread(ModalityState.nonModal()) { source ->
            if (source == null) return@finishOnUiThread
            if (editor == null) return@finishOnUiThread
            if (editor.isDisposed) return@finishOnUiThread
            editor.caretModel.moveToOffset(source.startOffset)
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            editor.selectionModel.setSelection(source.startOffset, source.endOffset)
        }.submit(AppExecutorUtil.getAppExecutorService())
        null
    }

    val toolbar = JToolBar().apply {
        isFloatable = false
    }

    val ui = SimpleToolWindowPanel(true)
    ui.toolbar = toolbar
    ui.setContent(browser.component)

    // Register callbacks BEFORE loading the graph so they are available when attachClickHandlers() runs
    browser.cefBrowser.executeJavaScript(
        "window.__cfgOnZoomChange = function(pct) { " +
        zoomQuery.inject("pct") +
        " };", "about:blank", 0
    )

    // Register the click handler before loadGraph() so it exists when Mermaid renders
    browser.cefBrowser.executeJavaScript(
        "window.__cfgOnNodeClick = function(nodeId) { " +
        clickQuery.inject("nodeId") +
        " };", "about:blank", 0
    )

    loadGraph()

    toolbar.add(switch)
    toolbar.add(dfa)
    toolbar.add(Box.createHorizontalGlue())
    toolbar.add(zoomOut)
    toolbar.add(zoomLabel)
    toolbar.add(zoomIn)

    browser.component.requestFocusInWindow()

    return ui
}

// Build a mapping from node ID to FIR element, mirroring the exact logic in ControlFlowGraph.graph()
private fun buildNodeToFirMap(cfg: ControlFlowGraph): Map<Int, FirElement> {
    val map = mutableMapOf<Int, FirElement>()
    var nodeId = 0
    val cfgToNodes = mutableMapOf<CFGNode<*>, Int>()

    // Pre-fill all known nodes, exactly like graph() does
    cfg.nodes.forEach { node ->
        cfgToNodes.getOrPut(node) {
            nodeId += 1
            nodeId
        }
    }

    fun processNode(node: CFGNode<*>) {
        val thisNode = cfgToNodes.getOrPut(node) {
            nodeId += 1
            nodeId
        }
        // For enter/exit markers, map to the fir element they represent
        val fir = node.fir.withoutSmartcast
        map[thisNode] = fir

        // Process subgraphs recursively, matching the order in graph()
        if (node is CFGNodeWithSubgraphs<*>) {
            for (subgraph in node.subGraphs) {
                subgraph.nodes.forEach(::processNode)
            }
        }

        // Process previous nodes (these may create new nodes not in the initial list)
        node.previousNodes.forEach { previous ->
            if (previous !in cfgToNodes) {
                processNode(previous)
            }
        }
    }

    cfg.nodes.forEach(::processNode)
    return map
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
