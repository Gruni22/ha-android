package io.github.gruni22.btdashboard.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mirrors the Python protocol.py packet format:
 *
 *   [0xAA][0xBB]           HEADER      (2 bytes)
 *   [passcode: uint32 BE]  PASSCODE    (4 bytes)
 *   [cmd: uint8]           COMMAND     (1 byte)
 *   [flags: uint8]         FLAGS       (1 byte, 0x00)
 *   [payload_len: uint16 BE] LENGTH    (2 bytes)
 *   [payload: bytes]       PAYLOAD     (variable, UTF-8 JSON)
 *   [crc16: uint16 BE]     CRC16-CCITT (2 bytes, covers bytes 2..-4)
 *   [0xCC][0xDD]           END HEADER  (2 bytes)
 *
 * Total overhead: 14 bytes.
 */
object PacketCodec {

    // Command codes (must match Python const.py)
    const val CMD_ACK: Byte              = 0x01
    const val CMD_NACK: Byte             = 0x02
    const val CMD_REQ_AREAS: Byte        = 0x10
    const val CMD_ANS_AREAS: Byte        = 0x11
    const val CMD_REQ_DEVICES: Byte      = 0x12
    const val CMD_ANS_DEVICES: Byte      = 0x13
    const val CMD_REQ_DASHBOARDS: Byte   = 0x14
    const val CMD_ANS_DASHBOARDS: Byte   = 0x15
    const val CMD_REQ_STATE: Byte        = 0x20
    const val CMD_ANS_STATE: Byte        = 0x21
    const val CMD_CALL_SERVICE: Byte     = 0x22
    const val CMD_ANS_CALL_SERVICE: Byte = 0x23
    const val CMD_STATE_CHANGE: Byte     = 0x30

    private val HEADER_MAGIC   = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
    private val END_MAGIC      = byteArrayOf(0xCC.toByte(), 0xDD.toByte())
    private const val MIN_SIZE = 14  // 2 + 4 + 1 + 1 + 2 + 0 + 2 + 2

    data class Packet(val passcode: Int, val cmd: Byte, val payload: ByteArray) {
        fun payloadString(): String = String(payload, Charsets.UTF_8)
    }

    fun encode(passcode: Int, cmd: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        val inner = ByteBuffer.allocate(4 + 1 + 1 + 2 + payload.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(passcode)
            put(cmd)
            put(0x00)                        // flags
            putShort(payload.size.toShort())
            put(payload)
        }.array()
        val crc = crc16Ccitt(inner)
        return ByteBuffer.allocate(2 + inner.size + 2 + 2).apply {
            put(HEADER_MAGIC)
            put(inner)
            putShort(crc.toShort())
            put(END_MAGIC)
        }.array()
    }

    fun encodeJson(passcode: Int, cmd: Byte, json: String): ByteArray =
        encode(passcode, cmd, json.toByteArray(Charsets.UTF_8))

    fun decode(data: ByteArray): Packet? {
        if (data.size < MIN_SIZE) return null
        if (data[0] != HEADER_MAGIC[0] || data[1] != HEADER_MAGIC[1]) return null
        if (data[data.size - 2] != END_MAGIC[0] || data[data.size - 1] != END_MAGIC[1]) return null

        val inner = data.copyOfRange(2, data.size - 4)
        val crcReceived = ByteBuffer.wrap(data, data.size - 4, 2)
            .order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        if (crc16Ccitt(inner) != crcReceived) return null

        if (inner.size < 8) return null
        val buf = ByteBuffer.wrap(inner).order(ByteOrder.BIG_ENDIAN)
        val passcode = buf.int
        val cmd = buf.get()
        buf.get()  // flags
        val payloadLen = buf.short.toInt() and 0xFFFF
        if (inner.size < 8 + payloadLen) return null
        val payload = inner.copyOfRange(8, 8 + payloadLen)
        return Packet(passcode, cmd, payload)
    }

    private fun crc16Ccitt(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}
