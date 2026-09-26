package com.patmanak.contako.data.proton

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingLoginPasswordTest {
    @Test fun expiresAndNeverCrossesSessions() = runTest {
        val holder = PendingLoginPassword(this)
        val session = SessionId("synthetic-session")
        val source = "synthetic-password".toCharArray()
        holder.retain(session, source)
        assertNull(holder.take(SessionId("different-session")))
        assertNull(holder.take(session))
        holder.retain(session, source)
        advanceUntilIdle()
        assertNull(holder.take(session))
        holder.retain(session, source)
        holder.clear()
        assertNull(holder.take(session))
        holder.retain(session, source)
        val secret = holder.take(session)!!
        var consumed: CharArray? = null
        secret.consume { consumed = it; assertArrayEquals(source, it) }
        assertTrue(consumed!!.all { it == '\u0000' })
        assertNull(holder.take(session))
        source.fill('\u0000')
    }
}
