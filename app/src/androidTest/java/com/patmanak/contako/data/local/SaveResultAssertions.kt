package com.patmanak.contako.data.local

import com.patmanak.contako.domain.repository.SaveResult

internal fun <T> SaveResult<T>.requireSavedForTest(): T = when (this) {
    is SaveResult.Saved -> value
    is SaveResult.Rejected -> error("Expected save success but was rejected: $issues")
}
