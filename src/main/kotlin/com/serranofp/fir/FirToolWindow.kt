package com.serranofp.fir

import com.intellij.icons.AllIcons
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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiManager
import com.intellij.ui.components.CheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.layout.selected
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.concurrency.CancellablePromise
import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirResolveSessionService
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.getOrBuildFirFile
import org.jetbrains.kotlin.analysis.project.structure.ProjectStructureProvider
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirControlFlowGraphOwner
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.expressions.FirLazyBlock
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.visitors.FirTransformer
import org.jetbrains.kotlin.fir.visitors.FirVisitor
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.toKtPsiSourceElement
import java.awt.Color
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Callable
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.event.TreeModelListener
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath

class FirToolWindow : ToolWindowFactory, DumbAware {
    private val tree = Tree(EmptyTreeModel).also {
        it.cellRenderer = FirCellRenderer(false)
        it.isRootVisible = false
    }
    private val choices = ComboBox(
        FirResolvePhase.entries.toTypedArray()
    )
    private val fq = CheckBox("Fully qualified names", selected = false)
    private val cfg = JButton(AllIcons.Graph.Layout).apply {
        this.toolTipText = "Control Flow Graph"
        this.isEnabled = false
    }

    private var inProgressAction: CancellablePromise<*>? = null
    private var status: Pair<VirtualFile, TextEditor>? = null

    private val currentResolveChoice: FirResolvePhase
        get() = choices.selectedItem as FirResolvePhase

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // set up UI
        val ui = panel {
            row {
                label("Resolve up to:").customize(UnscaledGaps(left = 10))
                cell(choices).align(Align.FILL).customize(UnscaledGaps(left = 5, right = 5)).resizableColumn()
                cell(fq).customize(UnscaledGaps(left = 5, right = 10))
                cell(cfg).customize(UnscaledGaps(0)).align(Align.CENTER)
            }
            row {
                val scrollPane = JBScrollPane(tree).also {
                    it.border = BorderFactory.createEmptyBorder()
                }
                cell(scrollPane).align(Align.FILL)
            }.resizableRow()
        }
        choices.selectedItem = FirResolvePhase.BODY_RESOLVE
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(ui, "", false))

        choices.addActionListener {
            inProgressAction?.cancel()
            if (status != null) {
                val (file, editor) = status!!
                refresh(project, file, editor)
            }
        }
        fq.addActionListener {
            tree.cellRenderer = FirCellRenderer(fq.selected())
        }

        // add listener for changes in tree selection
        tree.addTreeSelectionListener { event ->
            // move to and select the node in the code
            val model = tree.model as? FirTreeModel
            val lastComponent = event.path.lastPathComponent
            val selected = (lastComponent as? FirTreeElement)?.value ?: (lastComponent as? FirElement)
            val source = selected?.takeIf { it !is FirLazyBlock }?.source

            cfg.isEnabled = selected != null && selected is FirControlFlowGraphOwner

            if (model == null || source == null) return@addTreeSelectionListener
            model.editor.editor.caretModel.moveToOffset(source.startOffset)
            model.editor.editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            model.editor.editor.selectionModel.setSelection(source.startOffset, source.endOffset)
        }

        fun showCfgOfSelectedElement() {
            val selected = tree.lastSelectedPathComponent as? FirControlFlowGraphOwner ?: return
            val graph = selected.graph() ?: return

            val browser = JBCefBrowser()
            browser.loadHTML(mermaidHtml(graph))
            val frame = JFrame()
            browser.component.background = Color.white
            frame.contentPane = browser.component
            frame.size = Dimension(800, 800)
            frame.title = when (selected) {
                is FirDeclaration -> "Control Flow Graph of '${selected.symbol.shownName(true)}'"
                else -> "Control Flow Graph"
            }
            frame.isVisible = true
        }

        cfg.addActionListener {
            showCfgOfSelectedElement()
        }

        tree.addMouseListener(object: MouseAdapter() {
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
            val module = ProjectStructureProvider.getModule(project, ktFile, null)
            val session =
                project.getService(LLFirResolveSessionService::class.java)
                    .getFirResolveSessionNoCaching(module)
            val allButDeclarations = computeAllButDeclarations(ktFile.getOrBuildFirFile(session))
            val declarations = ktFile.declarations.map {
                try {
                    session.resolveToFirSymbol(it, currentResolveChoice).fir
                } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                    FirProblemElement(it, e)
                }
            }
            return allButDeclarations + declarations
        } catch (_: Throwable) {
            return null
        }
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

class FirProblemElement(declaration: KtDeclaration, problem: Throwable) : FirElement {
    override val source: KtSourceElement =
        declaration.toKtPsiSourceElement(KtRealSourceElementKind)

    val name: String? = declaration.name
    val message: String? = problem.message

    @Suppress("EmptyFunctionBlock")
    override fun <R, D> acceptChildren(visitor: FirVisitor<R, D>, data: D) { }
    override fun <D> transformChildren(transformer: FirTransformer<D>, data: D): FirElement = this
}
