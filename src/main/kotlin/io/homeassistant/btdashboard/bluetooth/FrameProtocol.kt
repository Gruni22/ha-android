package io.github.gruni22.btdashboard.bluetooth

import java.io.IOException

interface FrameProtocol {
    @Throws(IOException::class)
    suspend fun readFrame(): ByteArray

    @Throws(IOException::class)
    suspend fun writeFrame(data: ByteArray)

    fun close()

    val isOpen: Boolean
}
