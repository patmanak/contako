package com.patmanak.contako.data.sync

internal fun interface AndroidCanonicalGroupProjectionCoordinator {
    suspend fun project(context: AndroidInteroperabilityContext): AndroidBoundedPageResult
}
