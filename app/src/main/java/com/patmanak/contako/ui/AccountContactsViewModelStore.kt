package com.patmanak.contako.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Retains directory state across rotations, and destroys it before another owner can see it. */
internal class AccountContactsViewModelStore : ViewModel() {
    private var generation: Long? = null
    private var current = Scope()

    fun forGeneration(value: Long): Scope {
        if (generation != value) {
            current.viewModelStore.clear()
            current = Scope()
            generation = value
        }
        return current
    }

    override fun onCleared() { current.viewModelStore.clear() }

    class Scope : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
        val navigationState = SavedStateHandle()
    }
}
