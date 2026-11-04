package com.serranofp.fir

import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.codeVision.CodeVisionProviderBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.resolve.dfa.controlFlowGraph
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.awt.event.MouseEvent

class FirCfgCodeVisionProvider : CodeVisionProviderBase() {
    override val id: String = "fir-cfg-code-vision"
    override val name: String = "FIR Tree"
    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()

    override fun acceptsFile(file: PsiFile): Boolean = file.language.id == "kotlin"

    override fun acceptsElement(element: PsiElement): Boolean = element is KtNamedFunction &&
            (element.bodyExpression != null || element.bodyBlockExpression != null)

    override fun getHint(element: PsiElement, file: PsiFile): String? = null

    override fun handleClick(editor: Editor, element: PsiElement, event: MouseEvent?) {
        // Not used since we override computeForEditor
    }

    override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        if (file.project.isDefault) return emptyList()
        if (file.language.id != "kotlin") return emptyList()

        val functions = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
        val entries = mutableListOf<Pair<TextRange, CodeVisionEntry>>()

        for (function in functions) {
            if (function.bodyExpression == null && function.bodyBlockExpression == null) continue
            val firFunction = try {
                analyze(function) { function.symbol.getFirElement<FirFunction>(FirResolvePhase.BODY_RESOLVE) }
            } catch (_: Throwable) {
                null
            } ?: continue
            if (firFunction.controlFlowGraphReference?.controlFlowGraph == null) continue

            val nameIdentifier = function.nameIdentifier ?: continue
            val range = TextRange(nameIdentifier.textRange.startOffset, nameIdentifier.textRange.endOffset)
            val project = function.project

            // "Open Control Flow Graph" entry
            val cfgEntry = ClickableTextCodeVisionEntry(
                text = "CFG",
                providerId = id,
                onClick = { _, _ ->
                    val textEditor = FileEditorManager.getInstance(project).selectedTextEditor
                    ToolWindowManager.getInstance(project).getToolWindow("CFG")?.addTabForCFG(firFunction, textEditor)
                },
                icon = CfgIcons.CFG,
                tooltip = "Open the Control Flow Graph"
            )
            entries.add(range to cfgEntry)
        }

        return entries
    }
}

object CfgIcons {
    @JvmField
    val CFG = com.intellij.openapi.util.IconLoader.getIcon("/icons/cfg.svg", javaClass)
}
