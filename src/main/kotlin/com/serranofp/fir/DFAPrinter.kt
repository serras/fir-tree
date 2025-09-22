package com.serranofp.fir

import org.jetbrains.kotlin.fir.DfaType
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.resolve.dfa.DataFlowVariable
import org.jetbrains.kotlin.fir.resolve.dfa.Implication
import org.jetbrains.kotlin.fir.resolve.dfa.Operation
import org.jetbrains.kotlin.fir.resolve.dfa.OperationStatement
import org.jetbrains.kotlin.fir.resolve.dfa.PersistentFlow
import org.jetbrains.kotlin.fir.resolve.dfa.RealVariable
import org.jetbrains.kotlin.fir.resolve.dfa.Statement
import org.jetbrains.kotlin.fir.resolve.dfa.SyntheticVariable
import org.jetbrains.kotlin.fir.resolve.dfa.TypeStatement
import kotlin.reflect.full.functions
import kotlin.reflect.full.memberProperties

fun DataFlowVariable.toMermaid(firToNodes: Map<FirElement, Int>): String = (when (this) {
    is RealVariable -> symbol.shownName(false)
    is SyntheticVariable -> firToNodes[fir.withoutSmartcast]?.let { "#$it" }
}  ?: "<unknown>").escapedForMermaid

fun Statement.toMermaid(firToNodes: Map<FirElement, Int>): String = when (this) {
    is OperationStatement -> toMermaid(firToNodes)
    is TypeStatement -> toMermaid(firToNodes)
}

fun OperationStatement.toMermaid(firToNodes: Map<FirElement, Int>): String {
    val operationString = when (operation) {
        Operation.EqTrue -> "== true"
        Operation.EqFalse -> "== false"
        Operation.EqNull -> "== null"
        Operation.NotEqNull -> "!= null"
    }
    return "**${variable.toMermaid(firToNodes)}** $operationString"
}

fun TypeStatement.toMermaid(firToNodes: Map<FirElement, Int>): String {
    val typeInfo = upperTypes.map { "is ${it.shownName(false)}" } + lowerTypes.map {
        when (it) {
            is DfaType.BooleanLiteral -> "!= ${it.value}"
            is DfaType.Cone -> "!is ${it.type.shownName(false)}"
            is DfaType.Symbol -> "!= ${it.symbol.shownName(false)}"
        }
    }
    return "**${variableThatWorks.toMermaid(firToNodes)}** ${typeInfo.joinToString(" & ")}"
}

fun Implication.toMermaid(firToNodes: Map<FirElement, Int>): String =
    "${condition.toMermaid(firToNodes)} ⇒ ${effect.toMermaid(firToNodes)}"

fun PersistentFlow.getTypeStatementThatWorks(variable: RealVariable): TypeStatement? =
    this::class.functions.find { it.name == "getTypeStatement" }?.call(this, variable) as TypeStatement?

val TypeStatement.variableThatWorks: DataFlowVariable
    get() = this::class.memberProperties.find { it.name == "variable" }!!.call(this) as DataFlowVariable
