package com.patmanak.contako.qa

import android.app.Application
import com.patmanak.contako.ContakoApplication

class ContakoInstrumentationRunner : androidx.test.runner.AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        ContakoApplication.suppressStartupForInstrumentation()
        super.callApplicationOnCreate(app)
    }
}
