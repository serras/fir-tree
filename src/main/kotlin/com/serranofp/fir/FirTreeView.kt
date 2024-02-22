package com.serranofp.fir

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.ui.JBColor
import com.intellij.ui.components.Label
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirLabel
import org.jetbrains.kotlin.fir.FirPackageDirective
import org.jetbrains.kotlin.fir.FirPureAbstractElement
import org.jetbrains.kotlin.fir.contracts.FirContractDescription
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousObject
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirField
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirImport
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirReceiverParameter
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.FirVariable
import org.jetbrains.kotlin.fir.expressions.ExhaustivenessStatus
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirArgumentList
import org.jetbrains.kotlin.fir.expressions.FirAssignmentOperatorStatement
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirDelegatedConstructorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirLambdaArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirLazyBlock
import org.jetbrains.kotlin.fir.expressions.FirLazyExpression
import org.jetbrains.kotlin.fir.expressions.FirLoop
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirTypeOperatorCall
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
import org.jetbrains.kotlin.fir.types.FirTypeProjection
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isResolved
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.idea.KotlinIcons
import java.awt.Component
import java.awt.Font
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.event.TreeModelListener
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath
import kotlin.reflect.KProperty1
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties

data class FirTreeElement(
    val propertyName: String?,
    val propertyKind: PropertyKind,
    val value: FirElement,
) {
    enum class PropertyKind {
        SINGLE, ARRAY
    }
}

@Suppress("EmptyFunctionBlock")
class FirTreeModel(private val topLevel: List<FirElement>, internal val editor: TextEditor) : TreeModel {
    override fun getRoot(): Any = topLevel

    private fun Any?.toChildren(): List<*> = when (this) {
        is List<*> -> this
        is FirProblemElement -> listOfNotNull(message)
        is FirElement -> children()
        is FirTreeElement -> value.children()
        else -> emptyList<Any>()
    }

    override fun getChild(parent: Any?, index: Int): Any = parent.toChildren()[index]!!
    override fun getChildCount(parent: Any?): Int = parent.toChildren().size
    override fun isLeaf(node: Any?): Boolean = node.toChildren().isEmpty()
    override fun getIndexOfChild(parent: Any?, child: Any?): Int = parent.toChildren().indexOf(child)

    override fun valueForPathChanged(path: TreePath?, newValue: Any?) {}
    override fun addTreeModelListener(l: TreeModelListener?) {}
    override fun removeTreeModelListener(l: TreeModelListener?) {}
}

class FirCellRenderer(private val useFqNames: Boolean = true) : TreeCellRenderer {
    override fun getTreeCellRendererComponent(
        tree: JTree?,
        incoming: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        val (prefix, value) = splitTreeCell(incoming)
        val name = when (value) {
            null -> "?"
            is String -> value
            is List<*> -> "<list>"
            is FirProblemElement -> "error analyzing ${value.name}"
            else -> value::class.simpleName?.withoutImpl() ?: "?"
        }
        val addition = getTreeCellAddition(value)
        val label = "$prefix$name$addition"
        val labelComponent = when (val icon = (value as? FirElement)?.icon) {
            null -> Label(label)
            else -> JLabel(label, icon, SwingConstants.LEFT)
        }
        if (value is FirElement && !value.isLazy && value.source == null) {
            labelComponent.font = labelComponent.font.deriveFont(Font.ITALIC)
            labelComponent.foreground = JBColor.DARK_GRAY
        }
        return labelComponent
    }

    private fun splitTreeCell(incoming: Any?): Pair<String, Any?> = when (incoming) {
        is FirTreeElement -> when {
            incoming.propertyName == null -> ""
            incoming.propertyKind == FirTreeElement.PropertyKind.SINGLE ->
                "${incoming.propertyName}: "

            incoming.propertyKind == FirTreeElement.PropertyKind.ARRAY ->
                "${incoming.propertyName} ∋ "

            else -> ""
        } to incoming.value

        else -> "" to incoming
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun getTreeCellAddition(value: Any?): String = when (value) {
        is FirPackageDirective -> " (package: ${value.packageFqName.asString()})"

        is FirImport -> {
            val imported = value.importedFqName?.let { "imported: ${it.asString()}" }
            val allUnder = if (value.isAllUnder) "*" else null
            val alias = value.aliasName?.let { "alias: ${it.asString()}" }
            parensList(imported, allUnder, alias)
        }

        is FirDeclarationStatus -> {
            parensList(
                "visibility: ${value.visibility.internalDisplayName}",
                value.modality?.let { "modality: ${it.name}" },
                "actual".takeIf { value.isActual },
                "companion".takeIf { value.isCompanion },
                "const".takeIf { value.isConst },
                "data".takeIf { value.isData },
                "expect".takeIf { value.isExpect },
                "external".takeIf { value.isExternal },
                "fun".takeIf { value.isFun },
                "infix".takeIf { value.isInfix },
                "inline".takeIf { value.isInline },
                "inner".takeIf { value.isInner },
                "lateinit".takeIf { value.isLateInit },
                "operator".takeIf { value.isOperator },
                "override".takeIf { value.isOverride },
                "static".takeIf { value.isStatic },
                "suspend".takeIf { value.isSuspend },
                "tailrec".takeIf { value.isTailRec },
            )
        }

        is FirLabel -> " (name: ${value.name})"

        is FirDeclaration -> {
            val shownName = value.symbol.shownName(useFqNames)?.let { "name: $it" }
            val origin = when (value.origin) {
                is FirDeclarationOrigin.Synthetic -> "<synthetic>"
                FirDeclarationOrigin.IntersectionOverride -> "<intersection override>"
                is FirDeclarationOrigin.SubstitutionOverride -> "<substitution override>"
                else -> null
            }
            val additionalInfo = when (value) {
                is FirConstructor -> "isPrimary: ${value.isPrimary}"
                is FirVariable -> when {
                    value.isVal -> "val"
                    value.isVar -> "var"
                    else -> null
                }

                else -> null
            }
            parensList(shownName, origin, additionalInfo)
        }

        is FirStatement -> {
            val additionalInfo = when (value) {
                is FirWhenExpression -> "exhaustiveness: ${value.exhaustivenessStatus.shown()}"
                is FirAssignmentOperatorStatement -> "operator: ${value.operation.name}"
                is FirTypeOperatorCall -> "operator: ${value.operation.name}"
                is FirThisReceiverExpression -> if (value.isImplicit) "implicit" else null
                // hack: different versions call FirConstExpression or FirLiteralExpression
                // is FirConstExpression<*> -> "value: ${value.value}"
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val valueProperty =
                        value::class.memberProperties.find { it.name == "value" } as? KProperty1<FirStatement, Any?>
                    valueProperty?.get(value)?.let { "value: $it" }
                }
            }
            val typeInfo = when (value is FirExpression && !value.isLazy && value.isResolved) {
                false -> null
                true -> "type: ${value.resolvedType.shownName(useFqNames)}"
            }
            parensList(additionalInfo, typeInfo)
        }

        is FirArgumentList ->
            if (value.arguments.isEmpty()) " (empty)" else ""

        is FirTypeRef -> when (val coneType = value.coneTypeOrNull) {
            null -> ""
            else -> " (type: ${coneType.shownName(useFqNames)})"
        }

        is FirResolvedNamedReference -> when (val symbolName = value.resolvedSymbol.shownName(useFqNames)) {
            null -> " (name: ${value.name.asString()})"
            else -> " (resolvedSymbol: $symbolName)"
        }

        is FirNamedReference -> " (name: ${value.name.asString()})"

        else -> ""
    }
}

val FirElement.isLazy get() = this is FirLazyBlock || this is FirLazyExpression

const val IMPL_SUFFIX: String = "Impl"

fun String.withoutImpl(): String =
    if (this.endsWith(IMPL_SUFFIX)) this.dropLast(IMPL_SUFFIX.length) else this

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

fun ExhaustivenessStatus?.shown(): String = when (this) {
    null -> "unknown"
    ExhaustivenessStatus.ExhaustiveAsNothing -> "ExhaustiveAsNothing"
    ExhaustivenessStatus.ProperlyExhaustive -> "ProperlyExhaustive"
    is ExhaustivenessStatus.NotExhaustive -> "NotExhaustive"
}

fun parensList(vararg elements: String?) =
    elements.filterNotNull()
        .takeIf { it.isNotEmpty() }
        ?.let { " (${it.joinToString()})" } ?: ""

fun FirElement.children(): List<FirTreeElement> = when (this) {
    is FirLazyBlock -> emptyList()
    is FirLazyExpression -> emptyList()
    is FirPureAbstractElement -> children()
    else -> emptyList()
}

fun FirPureAbstractElement.children(): List<FirTreeElement> =
    ReadAction.compute<_, Throwable> {
        @Suppress("UNCHECKED_CAST")
        val properties: List<KProperty1<FirPureAbstractElement, *>> =
            this::class.memberProperties.toList() as List<KProperty1<FirPureAbstractElement, *>>
        val propertiesWithValues =
            properties.filter { it.visibility == KVisibility.PUBLIC }.map { it to it.get(this) }
        buildList {
            this@children.acceptChildren(object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    val singleProperty =
                        propertiesWithValues.firstOrNull { it.second == element }
                    if (singleProperty != null) {
                        add(FirTreeElement(singleProperty.first.name, FirTreeElement.PropertyKind.SINGLE, element))
                        return
                    }
                    val arrayProperty =
                        propertiesWithValues.firstOrNull {
                            val collection = it.second as? Collection<*>
                            collection != null && element in collection
                        }
                    if (arrayProperty != null) {
                        add(FirTreeElement(arrayProperty.first.name, FirTreeElement.PropertyKind.ARRAY, element))
                        return
                    }
                }

                override fun visitControlFlowGraphReference(controlFlowGraphReference: FirControlFlowGraphReference) {
                    /* do nothing */
                }
            })
        }
    }

val FirElement.icon: Icon?
    get() = when (this) {
        is FirErrorTypeRef, is FirErrorNamedReference -> AllIcons.Nodes.WarningIntroduction
        is FirAnonymousFunction -> AllIcons.Nodes.Lambda
        is FirLambdaArgumentExpression -> AllIcons.Debugger.LambdaBreakpoint
        is FirConstructor -> AllIcons.Nodes.ClassInitializer
        is FirFunction -> AllIcons.Nodes.Function
        is FirRegularClass -> AllIcons.Nodes.Class
        is FirAnonymousObject -> AllIcons.Nodes.AnonymousClass
        is FirTypeParameter -> AllIcons.Nodes.Type
        is FirReceiverParameter, is FirValueParameter -> AllIcons.Nodes.Parameter
        is FirTypeAlias -> KotlinIcons.TYPE_ALIAS
        is FirField -> AllIcons.Nodes.Field
        is FirProperty -> AllIcons.Nodes.Property
        is FirVariable -> AllIcons.Nodes.Variable
        is FirTypeRef -> AllIcons.Actions.GroupByTestProduction
        is FirReference -> AllIcons.Actions.GroupByModule
        // is FirConstExpression<*> -> AllIcons.Nodes.Constant
        is FirReturnExpression -> AllIcons.Actions.StepOut
        is FirVariableAssignment -> AllIcons.Vcs.Equal
        is FirDelegatedConstructorCall -> AllIcons.Actions.Forward
        is FirLoop -> AllIcons.Gutter.RecursiveMethod
        is FirWhenExpression -> AllIcons.Vcs.Merge
        is FirBlock -> AllIcons.FileTypes.Json
        is FirWhenBranch -> AllIcons.Vcs.CommitNode
        is FirAnnotationCall -> AllIcons.Gutter.ExtAnnotation
        is FirExpression -> AllIcons.Debugger.Value
        is FirStatement -> AllIcons.Debugger.Db_muted_disabled_method_breakpoint
        is FirTypeProjection -> AllIcons.Nodes.Type
        is FirContractDescription -> AllIcons.Nodes.Template
        is FirArgumentList -> AllIcons.Debugger.VariablesTab
        is FirImport -> AllIcons.ToolbarDecorator.Import
        is FirPackageDirective -> AllIcons.Nodes.Package
        is FirLabel -> AllIcons.Nodes.Tag
        is FirAnnotationContainer -> AllIcons.Gutter.ExtAnnotation
        is FirDeclarationStatus -> AllIcons.Actions.GroupBy
        is FirProblemElement -> AllIcons.Nodes.ErrorIntroduction
        else -> null
    }
