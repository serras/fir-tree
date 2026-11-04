package com.serranofp.fir

import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhaseRecursively
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

@Suppress("UNCHECKED_CAST")
fun <E : FirDeclaration> KaSymbol.getFirElement(phase: FirResolvePhase): E? {
    val firSymbolProperty = this::class.memberProperties.find { it.name == "firSymbol" } as? KProperty1<KaSymbol, Any> ?: return null
    val firSymbol = firSymbolProperty.get(this) as FirBasedSymbol<*>
    firSymbol.lazyResolveToPhaseRecursively(phase)
    val firProperty = firSymbol::class.memberProperties.find { it.name == "fir" } as? KProperty1<Any, E> ?: return null
    return firProperty.get(firSymbol)
}