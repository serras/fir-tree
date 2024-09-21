package com.serranofp.fir

import org.jetbrains.kotlin.contracts.description.LogicOperationKind
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirControlFlowGraphOwner
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirVariable
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirTypeOperatorCall
import org.jetbrains.kotlin.fir.references.FirReference
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNodeWithSubgraphs
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.EnterNodeMarker
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.ExitNodeMarker
import org.jetbrains.kotlin.fir.resolve.dfa.controlFlowGraph
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.coneType // needed in 2024.2.1
import org.jetbrains.kotlin.fir.types.renderReadable
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

fun mermaidHtml(content: String) = """
<body>
  <center>
    <pre class="mermaid" id="graph">
$content
    </pre>
  </center>
  <script type="module">
    import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.esm.min.mjs';
    mermaid.initialize({ startOnLoad: true });
  </script>
  
</body> 
"""

val FirReference.nameIfAvailable: String
    get() = when (this) {
        is FirThisReference -> "this"
        is FirResolvedNamedReference -> this.name.asString()
        else -> "?"
    }

val FirTypeRef.nameIfAvailable: String
    get() = when (this) {
        is FirResolvedTypeRef -> this.coneType.renderReadable()
        else -> "?"
    }

val String.noAngleBrackets: String get() = this.replace("<", "&lt;").replace(">", "&gt;")

val CFGNode<*>.niceLabel: String
    get() {
        val name = this::class.simpleName ?: "UnknownNode"
        val expandedName =
            @Suppress("MagicNumber")
            name.dropLast(4).replace(Regex("([A-Z])"), " $1")

        @Suppress("UNCHECKED_CAST")
        val valueProperty = fir::class.memberProperties.find { it.name == "value" } as? KProperty1<FirElement, Any?>
        val theValue = valueProperty?.get(fir)

        @Suppress("UNCHECKED_CAST")
        val kindProperty = fir::class.memberProperties.find { it.name == "kind" } as? KProperty1<FirElement, Any?>
        val theKind = kindProperty?.get(fir) as? LogicOperationKind

        @Suppress("UNCHECKED_CAST")
        val operationProperty = fir::class.memberProperties.find { it.name == "operation" } as? KProperty1<FirElement, Any?>
        val theOperation = operationProperty?.get(fir) as? FirOperation

        val theFir = fir
        val extra = try {
            when {
                theFir is FirVariable -> ": **${theFir.name.asString().trim('<', '>')}**"
                theFir is FirSimpleFunction -> ": **${theFir.name.asString().trim('<', '>')}**"
                theFir is FirQualifiedAccessExpression -> ": **${theFir.calleeReference.nameIfAvailable.trim('<', '>')}**"
                theFir is FirTypeOperatorCall ->
                    ": **${theFir.operation.operator} ${theFir.conversionTypeRef.nameIfAvailable.trim('<', '>')}**"
                theValue != null -> ": $theValue"
                theKind != null -> ": **${theKind.token}**"
                theOperation != null -> ": **${theOperation.operator}**"
                else -> ""
            }
        } catch (_: ReflectiveOperationException) { "" }

        return "${expandedName.lowercase().trimStart()}${extra.noAngleBrackets}"
    }

@Suppress("CyclomaticComplexMethod")
fun FirControlFlowGraphOwner.graph(): ((String) -> String)? {
    var nodeId = 0
    val nodes = mutableMapOf<CFGNode<*>, Int>()
    val cfg = controlFlowGraphReference?.controlFlowGraph ?: return null

    val theActualContent = buildString {
        fun addNode(node: CFGNode<*>) {
            val thisNode = nodes.getOrPut(node) {
                nodeId += 1
                nodeId
            }

            when (node) {
                is EnterNodeMarker -> appendLine("  node$thisNode[/\"`${node.niceLabel}`\"\\]")
                is ExitNodeMarker -> appendLine("  node$thisNode[\\\"`${node.niceLabel}`\"/]")
                else -> when {
                    node.isUnion -> appendLine("  node$thisNode{{\"`${node.niceLabel}`\"}}")
                    else -> appendLine("  node$thisNode[\"`${node.niceLabel}`\"]")
                }
            }

            if (node.previousNodes.isEmpty() || node.followingNodes.isEmpty()) {
                appendLine("  style node$thisNode stroke-width:2px")
            }

            if (node.isDead) {
                appendLine("  style node$thisNode stroke-dasharray: 5 5")
            }

            if (node is CFGNodeWithSubgraphs<*> && node.subGraphs.isNotEmpty()) {
                appendLine("  subgraph node${thisNode}subgraphs [ ]")
                for ((ix, subgraph) in node.subGraphs.withIndex()) {
                    appendLine("  subgraph node${thisNode}subgraph$ix [ ]")
                    subgraph.nodes.forEach(::addNode)
                    appendLine("  end")
                }
                appendLine("  end")
            }

            node.previousNodes.forEach { previous ->
                val previousNode = nodes.getOrPut(previous) {
                    nodeId += 1
                    nodeId
                }
                val edge = node.edgeFrom(previous)
                val edgeLabel = edge.label.label
                val specialElements = listOfNotNull(
                    "dead".takeIf { edge.kind.isDead },
                    "back".takeIf { edge.kind.isBack }
                )
                val (inEdge, outEdge) = if (edge.kind.isDead) "-." to ".->" else "--" to "-->"
                val singleEdge = if (edge.kind.isDead) "-.->" else "-->"
                val specialElementsString = specialElements.joinToString()
                when {
                    edgeLabel != null && specialElements.isNotEmpty() ->
                        @Suppress("MaxLineLength")
                        appendLine("  node$previousNode $inEdge $edgeLabel ($specialElementsString) $outEdge node$thisNode")

                    edgeLabel != null ->
                        appendLine("  node$previousNode $inEdge $edgeLabel $outEdge node$thisNode")

                    specialElements.isNotEmpty() ->
                        appendLine("  node$previousNode $inEdge ($specialElementsString) $outEdge node$thisNode")

                    else ->
                        appendLine("  node$previousNode $singleEdge node$thisNode")
                }
            }
        }

        cfg.nodes.forEach(::addNode)
    }

    return { prefix -> "flowchart $prefix\n$theActualContent" }
}
