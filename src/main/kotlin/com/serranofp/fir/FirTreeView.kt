package com.serranofp.fir

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.TextEditor
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirPureAbstractElement
import org.jetbrains.kotlin.fir.contracts.FirContractDescription
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousObject
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirResolvedDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.declarations.FirVariable
import org.jetbrains.kotlin.fir.expressions.FirArgumentList
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirConstExpression
import org.jetbrains.kotlin.fir.expressions.FirDelegatedConstructorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirLambdaArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirLazyBlock
import org.jetbrains.kotlin.fir.expressions.FirLazyExpression
import org.jetbrains.kotlin.fir.expressions.FirLoop
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenBranch
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.references.FirControlFlowGraphReference
import org.jetbrains.kotlin.fir.references.FirErrorNamedReference
import org.jetbrains.kotlin.fir.references.FirNamedReference
import org.jetbrains.kotlin.fir.references.FirReference
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirErrorTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isResolved
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.idea.KotlinIcons
import java.awt.Component
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.event.TreeModelListener
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath

class FirTreeModel(private val declarations: List<FirElement>, internal val editor: TextEditor): TreeModel {
    override fun getRoot(): Any = declarations

    private fun Any?.toChildren(): List<*> = when (this) {
        is List<*> -> this
        is FirElement -> children()
        else -> emptyList<Any>()
    }

    override fun getChild(parent: Any?, index: Int): Any = parent.toChildren()[index]!!
    override fun getChildCount(parent: Any?): Int = parent.toChildren().size
    override fun isLeaf(node: Any?): Boolean = node.toChildren().isEmpty()
    override fun getIndexOfChild(parent: Any?, child: Any?): Int = parent.toChildren().indexOf(child)

    override fun valueForPathChanged(path: TreePath?, newValue: Any?) { }
    override fun addTreeModelListener(l: TreeModelListener?) { }
    override fun removeTreeModelListener(l: TreeModelListener?) { }
}

class FirCellRenderer(private val useFqNames: Boolean = true): TreeCellRenderer {
    override fun getTreeCellRendererComponent(
        tree: JTree?, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ): Component {
        val name = when (value) {
            null -> "?"
            is String -> value
            is List<*> -> "declarations"
            else -> value::class.simpleName?.withoutImpl() ?: "?"
        }
        val addition = when (value) {
            is FirDeclaration -> {
                val shownName = value.symbol.shownName(useFqNames)?.let { " (name = $it)" } ?: ""
                val origin = when (value.origin) {
                    is FirDeclarationOrigin.Synthetic -> " <synthetic>"
                    FirDeclarationOrigin.IntersectionOverride -> " <intersection override>"
                    is FirDeclarationOrigin.SubstitutionOverride -> " <substitution override>"
                    else -> ""
                }
                "$shownName$origin"
            }
            is FirConstExpression<*> -> " (value = ${value.value})"
            is FirExpression -> when (value !is FirLazyBlock && value !is FirLazyExpression && value.isResolved) {
                false -> ""
                true -> " (type = ${value.resolvedType.shownName(useFqNames)})"
            }
            is FirArgumentList -> if (value.arguments.isEmpty()) " (empty)" else ""
            is FirTypeRef -> when (val coneType = value.coneTypeOrNull) {
                null -> ""
                else -> " (type = ${coneType.shownName(useFqNames)})"
            }
            is FirResolvedNamedReference -> when (val symbolName = value.resolvedSymbol.shownName(useFqNames)) {
                null -> " (name = ${value.name.asString()})"
                else -> " (resolvedSymbol = $symbolName)"
            }
            is FirNamedReference -> " (name = ${value.name.asString()})"
            else -> ""
        }
        val label = "$name$addition"
        return when (val icon = (value as? FirElement)?.icon) {
            null -> JLabel(label)
            else -> JLabel(label, icon, SwingConstants.LEFT)
        }
    }
}

fun String.withoutImpl(): String =
    if (this.endsWith("Impl")) this.dropLast(4) else this

fun ConeKotlinType.shownName(useFqNames: Boolean): String =
    if (useFqNames) renderReadableWithFqNames() else renderReadable()

fun FirBasedSymbol<*>.shownName(useFqNames: Boolean): String? =
    if (useFqNames) {
        when (this) {
            is FirCallableSymbol<*> -> callableId.asSingleFqName().asString()
            is FirClassLikeSymbol<*> -> classId.asSingleFqName().asString()
            is FirTypeParameterSymbol -> name.asString()
            else -> null
        }
    } else {
        when (this) {
            is FirCallableSymbol<*> -> name.asString()
            is FirClassLikeSymbol<*> -> name.asString()
            is FirTypeParameterSymbol -> name.asString()
            else -> null
        }
    }

fun FirElement.children(): List<FirElement> = when (this) {
    is FirPureAbstractElement -> children()
    else -> emptyList()
}

fun FirPureAbstractElement.children(): List<FirElement> =
    ReadAction.compute<_, Throwable> {
        buildList {
            this@children.acceptChildren(object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    add(element)
                }

                override fun visitDeclarationStatus(declarationStatus: FirDeclarationStatus) {
                    /* do nothing */
                }

                override fun visitResolvedDeclarationStatus(resolvedDeclarationStatus: FirResolvedDeclarationStatus) {
                    /* do nothing */
                }

                override fun visitControlFlowGraphReference(controlFlowGraphReference: FirControlFlowGraphReference) {
                    /* do nothing */
                }
            })
        }
    }

val FirElement.icon: Icon?
    get() = when (this) {
        is FirErrorTypeRef, is FirErrorNamedReference -> AllIcons.Nodes.ErrorIntroduction
        is FirAnonymousFunction -> AllIcons.Nodes.Lambda
        is FirLambdaArgumentExpression -> AllIcons.Debugger.LambdaBreakpoint
        is FirFunction -> KotlinIcons.FUNCTION
        is FirRegularClass -> KotlinIcons.CLASS
        is FirAnonymousObject -> KotlinIcons.OBJECT
        is FirTypeParameter -> KotlinIcons.PARAMETER
        is FirTypeAlias -> KotlinIcons.TYPE_ALIAS
        is FirVariable -> KotlinIcons.VAL
        is FirTypeRef -> AllIcons.Actions.InlayRenameInComments
        is FirReference -> AllIcons.Diff.ApplyNotConflicts
        is FirConstExpression<*> -> AllIcons.Nodes.Constant
        is FirReturnExpression -> AllIcons.Actions.StepOut
        is FirVariableAssignment -> AllIcons.Vcs.Equal
        is FirDelegatedConstructorCall -> AllIcons.Nodes.Alias
        is FirLoop -> AllIcons.Gutter.RecursiveMethod
        is FirWhenExpression -> AllIcons.Vcs.Merge
        is FirBlock -> AllIcons.FileTypes.Json
        is FirWhenBranch -> AllIcons.Vcs.CommitNode
        is FirExpression -> AllIcons.Debugger.Value
        is FirStatement -> AllIcons.Debugger.Db_muted_disabled_method_breakpoint
        is FirContractDescription -> AllIcons.Nodes.Template
        is FirArgumentList -> AllIcons.Debugger.VariablesTab
        else -> null
    }
