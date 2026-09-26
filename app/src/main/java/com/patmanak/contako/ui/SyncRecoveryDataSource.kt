package com.patmanak.contako.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

typealias SyncDashboardState = com.patmanak.contako.domain.sync.SyncDashboardState
typealias SyncActivity = com.patmanak.contako.domain.sync.SyncActivity
typealias SyncDashboardSnapshot = com.patmanak.contako.domain.sync.SyncDashboardSnapshot
typealias RepairPhase = com.patmanak.contako.domain.sync.RepairPhase
typealias RepairProgress = com.patmanak.contako.domain.sync.RepairProgress
typealias RepairStartResult = com.patmanak.contako.domain.sync.RepairStartResult
typealias SyncRecoveryDataSource = com.patmanak.contako.domain.sync.SyncRecoveryDataSource

interface ContactsPermissionBoundary {
    val granted: Flow<Boolean>
    val action: Flow<ContactsPermissionAction>
    fun requestPermission()
}

enum class ContactsPermissionAction { REQUEST, OPEN_SETTINGS }

internal val EmptySyncRecoveryDataSource = com.patmanak.contako.domain.sync.EmptySyncRecoveryDataSource

internal object GrantedContactsPermissionBoundary : ContactsPermissionBoundary {
    override val granted: Flow<Boolean> = flowOf(true)
    override val action: Flow<ContactsPermissionAction> = flowOf(ContactsPermissionAction.REQUEST)
    override fun requestPermission() = Unit
}
