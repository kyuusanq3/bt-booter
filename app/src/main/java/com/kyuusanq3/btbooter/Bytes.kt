package com.kyuusanq3.btbooter

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/** HCI and L2CAP fields are little-endian. */
internal fun ByteArray.u16le(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

/** SDP fields are big-endian. */
internal fun ByteArray.u16be(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

internal fun le16(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), (value shr 8 and 0xFF).toByte())

internal fun ByteArray.u32be(offset: Int): Long =
    (u16be(offset).toLong() shl 16) or u16be(offset + 2).toLong()

internal fun be32(value: Long): ByteArray = be16((value shr 16 and 0xFFFF).toInt()) + be16((value and 0xFFFF).toInt())

internal fun be16(value: Int): ByteArray = byteArrayOf((value shr 8 and 0xFF).toByte(), (value and 0xFF).toByte())
