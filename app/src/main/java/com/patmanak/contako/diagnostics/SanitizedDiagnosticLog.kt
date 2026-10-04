package com.patmanak.contako.diagnostics

import com.patmanak.contako.BuildConfig

/** The sole Android log sink. Ordinary debug/preview/release builds keep this disabled. */
internal object SanitizedDiagnosticLog {
    fun write(event: SanitizedDiagnosticEvent) {
        if (BuildConfig.IMPORT_INVESTIGATION) runCatching { InvestigationTrace.record(event) }
        if (BuildConfig.SANITIZED_DIAGNOSTICS || (BuildConfig.SYNC_DIAGNOSTICS && isSyncDiagnostic(event))) {
            runCatching { android.util.Log.i("ContakoDiagnostic", renderDiagnostic(event)) }
        }
    }
}
