package com.serranofp.fir

import com.intellij.ui.JBColor
import org.jetbrains.kotlin.contracts.description.LogicOperationKind
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirTypeOperatorCall
import org.jetbrains.kotlin.fir.references.FirReference
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.resolve.dfa.Implication
import org.jetbrains.kotlin.fir.resolve.dfa.PersistentFlow
import org.jetbrains.kotlin.fir.resolve.dfa.RealVariable
import org.jetbrains.kotlin.fir.resolve.dfa.TypeStatement
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNodeWithSubgraphs
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.ControlFlowGraph
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.EnterNodeMarker
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.ExitNodeMarker
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.renderReadable
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

fun mermaidHtml(content: String) = """
<html>
<head>
<style>
  html, body { margin: 0; overflow: auto; }
  #zoomRoot { width: max-content; margin: 0 auto; }
</style>
</head>
<body>
<div id="zoomRoot">
  <pre class="mermaid" id="graph">
$content
  </pre>
</div>
<script type="module">
  import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@12/dist/mermaid.esm.min.mjs';
  mermaid.initialize({
    startOnLoad: true,
    securityLevel: 'loose',
    flowchart: { curve: "linear", useMaxWidth: false },
    theme: "${if (JBColor.isBright()) "redux-color" else "redux-dark-color"}",
    look: "neo",
    fontFamily: "JetBrains Mono, Trebuchet MS, Verdana, Arial"
  });

  // Callback function for Mermaid click events
  window.cfgNodeClicked = function(nodeId) {
    if (typeof window.__cfgOnNodeClick === 'function') {
      window.__cfgOnNodeClick(nodeId);
    }
  };

  let zoom = 1;
  const MIN = 0.2, MAX = 3.0, STEP = 1.2, WHEEL_STEP = 1.1;
  const root = document.getElementById('zoomRoot');

  function applyZoom() { root.style.zoom = zoom; }
  function setZoom(z) {
    zoom = Math.min(MAX, Math.max(MIN, z));
    applyZoom();
    if (typeof window.__cfgOnZoomChange === 'function') {
      window.__cfgOnZoomChange(Math.round(zoom * 100));
    }
  }

  window.__cfgZoomIn = () => setZoom(zoom * STEP);
  window.__cfgZoomOut = () => setZoom(zoom / STEP);
  window.__cfgZoomReset = () => setZoom(1);
  window.__cfgSetZoom = (z) => setZoom(z);
  window.__cfgGetZoom = () => Math.round(zoom * 100);

  window.__cfgFit = () => {
    const rect = root.getBoundingClientRect();
    const naturalWidth = rect.width / zoom;
    const fit = Math.min(1, window.innerWidth / naturalWidth);
    setZoom(fit);
  };

  window.addEventListener('wheel', (e) => {
    if (e.ctrlKey) {
      e.preventDefault();
      setZoom(zoom * (e.deltaY < 0 ? WHEEL_STEP : 1 / WHEEL_STEP));
    }
  }, { passive: false });

  window.addEventListener('keydown', (e) => {
    if (e.ctrlKey) {
      if (e.key === '+' || e.key === '=') { e.preventDefault(); setZoom(zoom * STEP); }
      else if (e.key === '-') { e.preventDefault(); setZoom(zoom / STEP); }
      else if (e.key === '0') { e.preventDefault(); setZoom(1); }
    }
  });

  window.__cfgUpdate = (def) => {
    mermaid.render('cfgSvg', def)
      .then(({ svg }) => {
        root.innerHTML = svg;
        window.__cfgFit();
        attachClickHandlers();
      })
      .catch((err) => {
        root.innerHTML = '<pre>' + String(err) + '</pre>';
      });
  };

  // Mermaid handles click events natively through the click syntax in the graph definition.
  // We just need to fit the view after the initial render.
  const observer = new MutationObserver(() => {
    const svg = root.querySelector('svg');
    if (svg) {
      observer.disconnect();
      window.__cfgFit();
    }
  });
  observer.observe(root, { childList: true });
</script>
</body>
</html>
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

val replacementsForMermaid = listOf(
    "<" to "#lt;",
    ">" to "#gt;",
    "\"" to "#quot;",
    "\n" to "#bsol;n"
)

val String.escapedForMermaid: String get() =
    replacementsForMermaid.fold(this) { acc, (c, x) -> acc.replace(c, x) }

fun CFGNode<*>.niceLabel(nodes: Map<CFGNode<*>, Int>, firToNodes: Map<FirElement, Int>, dfa: Boolean): String {
    val name = this::class.simpleName ?: "UnknownNode"
    val expandedName =
        @Suppress("MagicNumber")
        name.dropLast(4).replace(Regex("([A-Z])"), " $1")

    @Suppress("UNCHECKED_CAST")
    val nameProperty = fir::class.memberProperties.find { it.name == "name" } as? KProperty1<FirElement, Any?>
    val theName = nameProperty?.get(fir) as? String

    @Suppress("UNCHECKED_CAST")
    val valueProperty = fir::class.memberProperties.find { it.name == "value" } as? KProperty1<FirElement, Any?>
    val theValue = valueProperty?.get(fir)

    @Suppress("UNCHECKED_CAST")
    val kindProperty = fir::class.memberProperties.find { it.name == "kind" } as? KProperty1<FirElement, Any?>
    val theKind = kindProperty?.get(fir) as? LogicOperationKind

    @Suppress("UNCHECKED_CAST")
    val operationProperty = fir::class.memberProperties.find { it.name == "operation" } as? KProperty1<FirElement, Any?>
    val theOperation = operationProperty?.get(fir) as? FirOperation

    val typeStatements = allTypeStatements.toMutableSet()
    val implications = allImplications.toMutableSet()
    for (node in previousNotDeadOrBack) {
        typeStatements.removeAll(node.allTypeStatements)
        implications.removeAll(node.allImplications)
    }

    val theFir = fir.withoutSmartcast
    val extra = try {
        when {
            theFir is FirQualifiedAccessExpression -> ": **${theFir.calleeReference.nameIfAvailable.escapedForMermaid}**"
            theFir is FirTypeOperatorCall ->
                ": **${theFir.operation.operator} ${theFir.conversionTypeRef.nameIfAvailable.escapedForMermaid}**"
            theName != null -> ": **${theName.escapedForMermaid}**"
            theValue != null -> ": ${theValue.toString().escapedForMermaid}"
            theKind != null -> ": **${theKind.token}**"
            theOperation != null -> ": **${theOperation.operator}**"
            else -> ""
        }
    } catch (_: ReflectiveOperationException) { "" }

    val flowInfo = when {
        !dfa || isDead -> ""
        typeStatements.isEmpty() && implications.isEmpty() -> ""
        else -> {
            val info = typeStatements.map { it.toMermaid(firToNodes) } + implications.map { it.toMermaid(firToNodes) }
            "<hr> " + info.joinToString("\n\n") { "<small>$it</small>" }
        }
    }

    return "<small>(#${nodes[this]})</small>\n\n${expandedName.lowercase().trimStart()}${extra.escapedForMermaid}$flowInfo"
}

val CFGNode<*>.previousNotDeadOrBack: List<CFGNode<*>>
    get() = previousNodes.filter { this.edgeFrom(it).kind.let { kind -> !kind.isDead && !kind.isBack } }

val CFGNode<*>.flowOrNull: PersistentFlow?
    get() = try { flow } catch (_: IllegalStateException) { null }

val CFGNode<*>.allTypeStatements: Set<TypeStatement>
    get() = flowOrNull?.allVariablesForDebug.orEmpty()
        .filterIsInstance<RealVariable>().mapNotNullTo(mutableSetOf()) { flowOrNull?.getTypeStatementThatWorks(it) }

val CFGNode<*>.allImplications: Set<Implication>
    get() = flowOrNull?.allVariablesForDebug.orEmpty()
        .flatMapTo(mutableSetOf()) { flowOrNull?.getImplications(it).orEmpty() }

val FirElement.withoutSmartcast: FirElement
    get() = when (this) {
        is FirSmartCastExpression -> originalExpression.withoutSmartcast
        else -> this
    }

@Suppress("CyclomaticComplexMethod")
fun ControlFlowGraph.graph(): ((String, Boolean) -> String) {
    var nodeId = 0
    val cfgToNodes = mutableMapOf<CFGNode<*>, Int>()
    val firToNodes = mutableMapOf<FirElement, Int>()

    // pre-fill all known nodes
    nodes.forEach { node ->
        cfgToNodes.getOrPut(node) {
            nodeId += 1
            nodeId
        }
        firToNodes[node.fir.withoutSmartcast] = cfgToNodes[node]!!
    }

    fun theActualContent(dfa: Boolean) = buildString {
        fun addNode(node: CFGNode<*>) {
            val thisNode = cfgToNodes.getOrPut(node) {
                nodeId += 1
                nodeId
            }

            val niceLabel = node.niceLabel(cfgToNodes, firToNodes, dfa)
            when (node) {
                is EnterNodeMarker -> appendLine("  node$thisNode[/\"`$niceLabel`\"\\]")
                is ExitNodeMarker -> appendLine("  node$thisNode[\\\"`$niceLabel`\"/]")
                else if node.isUnion -> appendLine("  node$thisNode{{\"`$niceLabel`\"}}")
                else -> appendLine("  node$thisNode[\"`$niceLabel`\"]")
            }

            // Add click event for each node using Mermaid's native click syntax
            appendLine("  click node$thisNode call cfgNodeClicked($thisNode)")

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
                val previousNode = cfgToNodes.getOrPut(previous) {
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

        nodes.forEach(::addNode)
    }

    return { prefix, dfa -> "flowchart $prefix\n${theActualContent(dfa)}" }
}
