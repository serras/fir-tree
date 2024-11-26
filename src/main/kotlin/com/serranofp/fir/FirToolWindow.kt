package com.serranofp.fir

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.concurrency.CancellablePromise
import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirControlFlowGraphOwner
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.expressions.FirLazyBlock
import org.jetbrains.kotlin.fir.resolve.dfa.controlFlowGraph
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhaseRecursively
import org.jetbrains.kotlin.fir.visitors.FirTransformer
import org.jetbrains.kotlin.fir.visitors.FirVisitor
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.toKtPsiSourceElement
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Callable
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JToolBar
import javax.swing.event.TreeModelListener
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

class FirToolWindow : ToolWindowFactory, DumbAware {
    private val tree = Tree(EmptyTreeModel).also {
        it.cellRenderer = FirCellRenderer(false)
        it.isRootVisible = false
    }
    private val choices = ComboBox(
        FirResolvePhase.entries.toTypedArray()
    )
    private var inProgressAction: CancellablePromise<*>? = null
    private var status: Pair<VirtualFile, TextEditor>? = null

    private val currentResolveChoice: FirResolvePhase
        get() = choices.selectedItem as FirResolvePhase

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        fun showCfgOfSelectedElement() {
            val lastComponent = tree.lastSelectedPathComponent
            val selected = (lastComponent as? FirTreeElement)?.value ?: (lastComponent as? FirElement)
            val selectedCfg = selected as? FirControlFlowGraphOwner ?: return
            val graph = selectedCfg.graph() ?: return
            val frame = controlFlowGraphWindow((selected as? FirDeclaration)?.symbol?.shownName(true), graph)
            frame.isVisible = true
        }

        fun refreshWindowContents() {
            inProgressAction?.cancel()
            if (status != null) {
                val (file, editor) = status!!
                refresh(project, file, editor)
            }
        }

        choices.selectedItem = FirResolvePhase.BODY_RESOLVE
        choices.addActionListener {
            refreshWindowContents()
        }

        val refresh = object : AnAction("Refresh", "Re-analyze the file", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshWindowContents()
            }
        }

        val fq = object : ToggleAction("FQ Names", "Use fully-qualified names", AllIcons.Nodes.Package) {
            var selected = false

            override fun isSelected(e: AnActionEvent): Boolean = selected
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                selected = state
                tree.cellRenderer = FirCellRenderer(state)
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            // override fun displayTextInToolbar(): Boolean = true
        }

        @Suppress("DialogTitleCapitalization")
        val cfg = object : AnAction("Control Flow Graph", "Show the Control Flow Graph", AllIcons.Graph.Layout) {
            override fun actionPerformed(e: AnActionEvent) {
                showCfgOfSelectedElement()
            }

            override fun update(e: AnActionEvent) {
                val lastComponent = tree.lastSelectedPathComponent
                val selected = (lastComponent as? FirTreeElement)?.value ?: (lastComponent as? FirElement)
                e.presentation.isEnabled =
                    selected is FirControlFlowGraphOwner && selected.controlFlowGraphReference?.controlFlowGraph != null
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

        // set up UI
        val toolbar = JToolBar().apply {
            isFloatable = false
            layout = GridBagLayout()
        }
        toolbar.add(JLabel("Resolve up to phase:"))
        toolbar.add(
            choices,
            GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                weighty = 1.0
            }
        )
        val ui = SimpleToolWindowPanel(true)
        ui.toolbar = toolbar
        ui.setContent(
            JBScrollPane(tree).also {
                it.border = BorderFactory.createEmptyBorder()
            }
        )
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(ui, "", false))
        toolWindow.setTitleActions(listOf(refresh, cfg, fq))

        // add listener for changes in tree selection
        tree.addTreeSelectionListener { event ->
            // move to and select the node in the code
            val model = tree.model as? FirTreeModel
            val lastComponent = event.path.lastPathComponent
            val selected = (lastComponent as? FirTreeElement)?.value ?: (lastComponent as? FirElement)
            val source = selected?.takeIf { it !is FirLazyBlock }?.source
            if (model == null || source == null) return@addTreeSelectionListener
            model.editor.editor.caretModel.moveToOffset(source.startOffset)
            model.editor.editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            model.editor.editor.selectionModel.setSelection(source.startOffset, source.endOffset)
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                if (e == null || e.clickCount != 2) return
                showCfgOfSelectedElement()
            }
        })

        // start listening for selected file editors
        project.messageBus.connect()
            .subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                        // refresh(project, file)
                    }

                    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                        // refresh(project, file)
                    }

                    override fun selectionChanged(event: FileEditorManagerEvent) {
                        inProgressAction?.cancel()
                        val newTextEditor = event.newEditor as? TextEditor
                        if (event.newFile == null || newTextEditor == null) {
                            status = null
                            tree.model = EmptyTreeModel
                        } else {
                            status = event.newFile!! to newTextEditor
                            refresh(project, event.newFile!!, newTextEditor)
                        }
                    }
                }
            )
    }

    private fun refresh(project: Project, file: VirtualFile, editor: TextEditor) {
        inProgressAction = ReadAction
            .nonBlocking(Callable { computeInfo(project, file) })
            .inSmartMode(project)
            .finishOnUiThread(ModalityState.nonModal()) { info ->
                tree.model = when (info) {
                    null -> EmptyTreeModel
                    else -> FirTreeModel(info, editor)
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    @Suppress("ReturnCount")
    @OptIn(SymbolInternals::class)
    private fun computeInfo(project: Project, file: VirtualFile): List<FirElement>? {
        try {
            val ktFile = PsiManager.getInstance(project).findFile(file) as? KtFile ?: return null
            analyze(ktFile) {
                val firFile = ktFile.symbol.getFirElement<FirFile>() ?: return null
                val allButDeclarations = computeAllButDeclarations(firFile)
                // val declarations = ktFile.declarations.map {
                //     it.symbol.getFirElement<FirDeclaration>() ?: FirProblemElement(it, null)
                // }
                return allButDeclarations + firFile.declarations // declarations
            }
        } catch (_: Throwable) {
            return null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <E: FirDeclaration> KaSymbol.getFirElement(): E? {
        val firSymbolProperty = this::class.memberProperties.find { it.name == "firSymbol" } as? KProperty1<KaSymbol, Any> ?: return null
        val firSymbol = firSymbolProperty.get(this) as FirBasedSymbol<*>
        firSymbol.lazyResolveToPhaseRecursively(currentResolveChoice)
        val firProperty = firSymbol::class.memberProperties.find { it.name == "fir" } as? KProperty1<Any, E> ?: return null
        return firProperty.get(firSymbol)
    }

    private fun computeAllButDeclarations(file: FirFile): List<FirElement> = buildList {
        file.acceptChildren(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (element !is FirDeclaration) {
                    add(element)
                }
            }
        })
    }
}

@Suppress("EmptyFunctionBlock")
object EmptyTreeModel : TreeModel {
    override fun getRoot(): Any = "<root>"
    override fun getChild(parent: Any?, index: Int): Any =
        throw IllegalArgumentException("no children")

    override fun getChildCount(parent: Any?): Int = 0
    override fun getIndexOfChild(parent: Any?, child: Any?): Int = -1
    override fun isLeaf(node: Any?): Boolean = true

    override fun valueForPathChanged(path: TreePath?, newValue: Any?) {}
    override fun addTreeModelListener(l: TreeModelListener?) {}
    override fun removeTreeModelListener(l: TreeModelListener?) {}
}

class FirProblemElement(declaration: KtDeclaration, problem: Throwable?) : FirElement {
    override val source: KtSourceElement =
        declaration.toKtPsiSourceElement(KtRealSourceElementKind)

    val name: String? = declaration.name
    val message: String? = problem?.message

    @Suppress("EmptyFunctionBlock")
    override fun <R, D> acceptChildren(visitor: FirVisitor<R, D>, data: D) {
    }

    override fun <D> transformChildren(transformer: FirTransformer<D>, data: D): FirElement = this
}
