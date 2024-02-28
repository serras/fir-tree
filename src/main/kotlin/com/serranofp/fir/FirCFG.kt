package com.serranofp.fir

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirControlFlowGraphOwner
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirVariable
import org.jetbrains.kotlin.fir.expressions.FirBinaryLogicExpression
import org.jetbrains.kotlin.fir.expressions.FirComparisonExpression
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirTypeOperatorCall
import org.jetbrains.kotlin.fir.references.FirReference
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNodeWithSubgraphs
import org.jetbrains.kotlin.fir.resolve.dfa.controlFlowGraph
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.EnterNodeMarker
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.ExitNodeMarker
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.renderReadable
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

fun mermaidHtml(content: String) = """
<body>
  <center>
    <pre class="mermaid">
$content
    </pre>
  </center>
  <script type="module">
    import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.esm.min.mjs';
    mermaid.initialize({ startOnLoad: true });
  </script>
</body> 
"""

val FirReference.nameIfAvailable: String get() = when (this) {
    is FirThisReference -> "this"
    is FirResolvedNamedReference -> this.name.asString()
    else -> "?"
}

val FirTypeRef.nameIfAvailable: String get() = when (this) {
    is FirResolvedTypeRef -> this.type.renderReadable()
    else -> "?"
}

val String.noAngleBrackets: String get() = this.replace("<", "&lt;").replace(">", "&gt;")

val CFGNode<*>.niceLabel: String get() {
    val name = this::class.simpleName ?: "UnknownNode"
    val expandedName = name.dropLast(4).replace(Regex("([A-Z])"), " $1")

    @Suppress("UNCHECKED_CAST")
    val valueProperty = fir::class.memberProperties.find { it.name == "value" } as? KProperty1<FirElement, Any?>
    val theValue = valueProperty?.get(fir)

    val theFir = fir
    val extra = when {
        theValue != null -> ": _${theValue}_"
        theFir is FirVariable -> ": **${theFir.name.asString().trim('<', '>')}**"
        theFir is FirSimpleFunction -> ": **${theFir.name.asString().trim('<', '>')}**"
        theFir is FirQualifiedAccessExpression -> ": **${theFir.calleeReference.nameIfAvailable.trim('<', '>')}**"
        theFir is FirBinaryLogicExpression -> ": **${theFir.kind.token}**"
        theFir is FirComparisonExpression -> ": **${theFir.operation.operator}**"
        theFir is FirEqualityOperatorCall -> ": **${theFir.operation.operator}**"
        theFir is FirTypeOperatorCall -> ": **${theFir.operation.operator} ${theFir.conversionTypeRef.nameIfAvailable.trim('<', '>')}**"
        else -> ""
    }

    return "${expandedName.lowercase().trimStart()}${extra.noAngleBrackets}"
}

fun FirControlFlowGraphOwner.graph(): String? {
    var nodeId = 0
    val nodes = mutableMapOf<CFGNode<*>, Int>()
    val cfg = controlFlowGraphReference?.controlFlowGraph ?: return null

    return buildString {
        appendLine("flowchart TD")

        fun addNode(node: CFGNode<*>) {
            val thisNode = nodes.getOrPut(node) { nodeId += 1 ; nodeId }

            when (node) {
                is EnterNodeMarker -> appendLine("  node$thisNode[/\"`${node.niceLabel}`\"\\]")
                is ExitNodeMarker -> appendLine("  node$thisNode[\\\"`${node.niceLabel}`\"/]")
                else -> appendLine("  node$thisNode[\"`${node.niceLabel}`\"]")
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
                val previousNode = nodes.getOrPut(previous) { nodeId += 1 ; nodeId }
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

        cfg.nodes.forEach (::addNode)
    }
}
