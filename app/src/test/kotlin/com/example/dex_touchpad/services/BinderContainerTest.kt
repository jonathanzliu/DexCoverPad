package com.example.dex_touchpad.services

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import java.io.FileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test

class BinderContainerTest {

    private class MockBinder : IBinder {
        override fun getInterfaceDescriptor(): String? = null
        override fun pingBinder(): Boolean = true
        override fun isBinderAlive(): Boolean = true
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        override fun dump(fd: FileDescriptor, args: Array<out String>?) {}
        override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {}
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {}
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = false
    }

    @Test
    fun testBinderContainerInitialization() {
        val mockBinder = MockBinder()
        val container = BinderContainer(mockBinder)
        assertEquals(mockBinder, container.binder)
    }
}
