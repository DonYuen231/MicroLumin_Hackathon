package app.recall.phone.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRetryPolicyTest {
    @Test
    fun `a capture stops after three failures`() {
        val policy = SyncRetryPolicy()
        repeat(2) { policy.recordFailure("c_1") }
        assertTrue(policy.shouldTry("c_1"))
        policy.recordFailure("c_1")
        assertFalse(policy.shouldTry("c_1"))
    }

    @Test
    fun `one failed capture does not block another`() {
        val policy = SyncRetryPolicy()
        repeat(3) { policy.recordFailure("c_1") }
        assertTrue(policy.shouldTry("c_2"))
    }

    @Test
    fun `success clears the failure count`() {
        val policy = SyncRetryPolicy()
        repeat(2) { policy.recordFailure("c_1") }
        policy.recordSuccess("c_1")
        repeat(2) { policy.recordFailure("c_1") }
        assertTrue(policy.shouldTry("c_1"))
    }

    @Test
    fun `the manual button can retry a stopped capture`() {
        val policy = SyncRetryPolicy()
        repeat(3) { policy.recordFailure("c_1") }
        policy.reset()
        assertTrue(policy.shouldTry("c_1"))
    }
}
