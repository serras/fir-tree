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
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirControlFlowGraphOwner
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.resolve.dfa.controlFlowGraph
import javax.swing.JButton
import javax.swing.JToolBar

class FirCFGToolWindow : ToolWindowFactory {
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

    val browser = JBCefBrowser()
    browser.component.background = JBColor.WHITE

    fun loadGraph() {
        val prefix = if (vertical) "TD" else "LR"
        val content = mermaidHtml(graph(prefix, dfaComments))
        browser.loadHTML(content)
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

    val toolbar = JToolBar().apply {
        isFloatable = false
    }

    val ui = SimpleToolWindowPanel(true)
    ui.toolbar = toolbar
    ui.setContent(browser.component)

    loadGraph()

    toolbar.add(switch)
    toolbar.add(dfa)

    browser.component.requestFocusInWindow()

    return ui
}
