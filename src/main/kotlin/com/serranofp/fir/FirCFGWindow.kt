package com.serranofp.fir

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.Dimension
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JToolBar

fun controlFlowGraphWindow(name: String?, graph: (String) -> String): JFrame {
    var vertical = true

    val browser = JBCefBrowser()
    browser.component.background = JBColor.WHITE

    fun loadGraph() {
        val prefix = if (vertical) "TD" else "LR"
        val content = mermaidHtml(graph(prefix))
        browser.loadHTML(content)
    }

    val switch = JButton("Switch Direction", AllIcons.Actions.SyncPanels).apply {
        toolTipText = "Switch Direction"
        addActionListener {
            vertical = !vertical
            loadGraph()
        }
        background = JBColor.WHITE
    }

    val frame = JFrame()
    val toolbar = JToolBar().apply {
        isFloatable = false
        background = JBColor.WHITE
    }

    val ui = SimpleToolWindowPanel(true)
    ui.toolbar = toolbar
    ui.setContent(browser.component)
    frame.contentPane = ui
    frame.size =
        @Suppress("MagicNumber")
        Dimension(800, 800)
    frame.title = when (name) {
        null -> "Control Flow Graph"
        else -> "Control Flow Graph of '$name'"
    }

    loadGraph()

    if (SystemInfo.isMac) {
        switch.border = null
        toolbar.add(@Suppress("MagicNumber") Box.createHorizontalStrut(70), 0)
        frame.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        frame.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
    }
    toolbar.add(switch)

    return frame
}
