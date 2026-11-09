package com.dc.andagent.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException

/** Protocol encoders follow mik3y/usb-serial-for-android driver sources (master, 2026-11-09). */
object SerialCodec {
    fun cdcLineCoding(baud: Int, bits: Int, stops: Int, parity: String): ByteArray {
        require(baud > 0 && bits in 5..8 && stops in 1..2)
        val p = parityCode(parity)
        return byteArrayOf(baud.toByte(), (baud ushr 8).toByte(), (baud ushr 16).toByte(),
            (baud ushr 24).toByte(), (if (stops == 1) 0 else 2).toByte(), p.toByte(), bits.toByte())
    }
    fun parityCode(parity: String): Int = when (parity.lowercase()) {
        "none" -> 0; "odd" -> 1; "even" -> 2; "mark" -> 3; "space" -> 4
        else -> throw IllegalArgumentException("无效 parity: $parity")
    }
    /** CH34x register 0x1312 and 0x0f2c index values. */
    fun ch34xBaud(baud: Int): Pair<Int, Int> {
        require(baud > 0)
        var divisor = 3
        var factor: Long
        if (baud == 921600) { divisor = 7; factor = 0xf300 }
        else {
            factor = 1532620800L / baud
            while (factor > 0xfff0 && divisor > 0) { factor = factor shr 3; divisor-- }
            require(factor <= 0xfff0 && factor > 0) { "CH34x 不支持波特率 $baud" }
            factor = 0x10000 - factor
        }
        divisor = divisor or 0x80
        return ((factor.toInt() and 0xff00) or divisor) to (factor.toInt() and 0xff)
    }
    fun ch34xLcr(bits: Int, stops: Int, parity: String): Int {
        require(bits in 5..8 && stops in 1..2)
        val p = parityCode(parity)
        return 0xc0 or (bits - 5) or (if (stops == 2) 0x04 else 0) or
            (if (p == 0) 0 else 0x08 or (if (p == 2 || p == 4) 0x10 else 0) or (if (p >= 3) 0x20 else 0))
    }
    fun cp21xxLineControl(bits: Int, stops: Int, parity: String): Int {
        require(bits in 5..8 && stops in 1..2)
        val p = parityCode(parity)
        return (bits shl 8) or (p shl 4) or (if (stops == 2) 2 else 0)
    }
    fun cp21xxBaud(baud: Int): ByteArray {
        require(baud > 0)
        return byteArrayOf(baud.toByte(), (baud ushr 8).toByte(), (baud ushr 16).toByte(), (baud ushr 24).toByte())
    }
    /** FTDI value and index low bit; caller adds port number for H/multiport devices. */
    fun ftdiBaud(baud: Int): Pair<Int, Int> {
        require(baud > 0 && baud <= 3500000)
        var divisor: Int; var sub: Int; var effective: Int
        if (baud >= 2500000) { divisor = 0; sub = 0; effective = 3000000 }
        else if (baud >= 1750000) { divisor = 1; sub = 0; effective = 2000000 }
        else {
            val rounded = ((48000000L / baud) + 1).toInt() shr 1
            sub = rounded and 7; divisor = rounded shr 3
            require(divisor <= 0x3fff && divisor > 0) { "FTDI 不支持波特率 $baud" }
            effective = ((48000000L / ((divisor shl 3) + sub) + 1) shr 1).toInt()
        }
        require(kotlin.math.abs(1.0 - effective.toDouble() / baud) < 0.031) { "FTDI 波特率偏差过大" }
        val extra = when (sub) { 0 -> 0; 4 -> 0x4000; 2 -> 0x8000; 1 -> 0xc000
            3 -> 0; 5 -> 0x4000; 6 -> 0x8000; else -> 0xc000 }
        return (divisor or extra) to (if (sub in 3..7 && sub != 4) 1 else 0)
    }
    fun ftdiLineControl(bits: Int, stops: Int, parity: String): Int {
        require(bits in 7..8 && stops in 1..2)
        return bits or (parityCode(parity) shl 8) or (if (stops == 2) 0x1000 else 0)
    }
}

interface UsbSerialPort {
    val readEndpoint: UsbEndpoint
    val writeEndpoint: UsbEndpoint
    val dataInterface: UsbInterface
    fun open(connection: UsbDeviceConnection, iface: UsbInterface)
    fun setBaud(baud: Int, dataBits: Int, stopBits: Int, parity: String)
    fun read(buf: ByteArray, timeoutMs: Int): Int
    fun write(buf: ByteArray): Int
    fun close()
}

abstract class BaseSerialPort(protected val device: UsbDevice) : UsbSerialPort {
    protected lateinit var connection: UsbDeviceConnection
    protected val claimed = mutableListOf<UsbInterface>()
    protected fun connectionReady(): Boolean = ::connection.isInitialized
    override lateinit var dataInterface: UsbInterface
    override lateinit var readEndpoint: UsbEndpoint
    override lateinit var writeEndpoint: UsbEndpoint
    protected fun claim(iface: UsbInterface) {
        if (iface !in claimed) {
            if (!connection.claimInterface(iface, true)) throw IOException("claimInterface 失败: ${iface.id}")
            claimed += iface
        }
    }
    override fun open(connection: UsbDeviceConnection, iface: UsbInterface) {
        this.connection = connection; dataInterface = iface
        claim(iface)
        val endpoints = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
        readEndpoint = endpoints.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
            ?: throw IOException("接口 ${iface.id} 缺少 bulk IN")
        writeEndpoint = endpoints.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
            ?: throw IOException("接口 ${iface.id} 缺少 bulk OUT")
    }
    protected fun control(type: Int, request: Int, value: Int, index: Int, data: ByteArray? = null) {
        val n = connection.controlTransfer(type, request, value, index, data, data?.size ?: 0, 5000)
        if (n != (data?.size ?: 0)) throw IOException("USB control 0x${request.toString(16)} 失败: $n")
    }
    protected fun controlIn(request: Int, value: Int, index: Int, expected: IntArray) {
        val data = ByteArray(expected.size)
        val n = connection.controlTransfer(0xc0, request, value, index, data, data.size, 5000)
        if (n != data.size || expected.indices.any { expected[it] >= 0 && (data[it].toInt() and 255) != expected[it] })
            throw IOException("CH34x 初始化状态不符: 0x${request.toString(16)}")
    }
    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        require(buf.isNotEmpty() && timeoutMs in 1..60000)
        val n = connection.bulkTransfer(readEndpoint, buf, buf.size, timeoutMs)
        return if (n < 0) 0 else n
    }
    override fun write(buf: ByteArray): Int {
        var sent = 0
        while (sent < buf.size) {
            val chunk = buf.copyOfRange(sent, minOf(buf.size, sent + 16384))
            val n = connection.bulkTransfer(writeEndpoint, chunk, chunk.size, 5000)
            if (n <= 0) throw IOException("USB bulk OUT 失败，已发送 $sent/${buf.size}")
            sent += n
        }
        return sent
    }
    override fun close() {
        claimed.asReversed().forEach { runCatching { connection.releaseInterface(it) } }
        claimed.clear()
        if (::connection.isInitialized) connection.close()
    }
}

class CdcAcmPort(device: UsbDevice) : BaseSerialPort(device) {
    private lateinit var controlInterface: UsbInterface
    override fun open(connection: UsbDeviceConnection, iface: UsbInterface) {
        super.open(connection, iface)
        controlInterface = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM && it.interfaceSubclass == 2 }
            ?: iface
        claim(controlInterface)
        control(0x21, 0x22, 3, controlInterface.id)
    }
    override fun setBaud(baud: Int, dataBits: Int, stopBits: Int, parity: String) =
        control(0x21, 0x20, 0, controlInterface.id, SerialCodec.cdcLineCoding(baud, dataBits, stopBits, parity))
}

class Ch34xPort(device: UsbDevice) : BaseSerialPort(device) {
    override fun open(connection: UsbDeviceConnection, iface: UsbInterface) {
        super.open(connection, iface)
        for (i in 0 until device.interfaceCount) claim(device.getInterface(i))
        controlIn(0x5f, 0, 0, intArrayOf(-1, 0))
        control(0x40, 0xa1, 0, 0)
        setRate(9600)
        controlIn(0x95, 0x2518, 0, intArrayOf(-1, 0))
        control(0x40, 0x9a, 0x2518, 0xc3)
        controlIn(0x95, 0x0706, 0, intArrayOf(-1, -1))
        control(0x40, 0xa1, 0x501f, 0xd90a)
        setRate(9600)
        control(0x40, 0xa4, (0x20 or 0x40).inv(), 0)
        controlIn(0x95, 0x0706, 0, intArrayOf(-1, -1))
    }
    private fun setRate(baud: Int) {
        val (v1, v2) = SerialCodec.ch34xBaud(baud)
        control(0x40, 0x9a, 0x1312, v1); control(0x40, 0x9a, 0x0f2c, v2)
    }
    override fun setBaud(baud: Int, dataBits: Int, stopBits: Int, parity: String) {
        val lcr = SerialCodec.ch34xLcr(dataBits, stopBits, parity)
        setRate(baud); control(0x40, 0x9a, 0x2518, lcr)
    }
}

class Cp21xxPort(device: UsbDevice) : BaseSerialPort(device) {
    private var portIndex = 0
    override fun open(connection: UsbDeviceConnection, iface: UsbInterface) {
        super.open(connection, iface)
        portIndex = (0 until device.interfaceCount).indexOfFirst { device.getInterface(it) == iface }
        control(0x41, 0x00, 1, portIndex)
        control(0x41, 0x07, 0x303, portIndex)
        // Match upstream's no-flow-control payload, with DTR and RTS asserted.
        val flow = ByteArray(16); flow[0] = 1; flow[4] = 0x40
        control(0x41, 0x13, 0, portIndex, flow)
    }
    override fun setBaud(baud: Int, dataBits: Int, stopBits: Int, parity: String) {
        val config = SerialCodec.cp21xxLineControl(dataBits, stopBits, parity)
        control(0x41, 0x1e, 0, portIndex, SerialCodec.cp21xxBaud(baud))
        control(0x41, 0x03, config, portIndex)
    }
    override fun close() { if (connectionReady()) runCatching { control(0x41, 0x00, 0, portIndex) }; super.close() }
}

class FtdiPort(device: UsbDevice) : BaseSerialPort(device) {
    private var portIndex = 0
    private var baudWithPort = false
    override fun open(connection: UsbDeviceConnection, iface: UsbInterface) {
        super.open(connection, iface)
        portIndex = (0 until device.interfaceCount).indexOfFirst { device.getInterface(it) == iface }
        val port = portIndex + 1
        control(0x40, 0, 0, port)
        control(0x40, 1, 0x303, port)
        control(0x40, 2, 0, port)
        val raw = connection.rawDescriptors ?: throw IOException("FTDI 设备描述符不可用")
        if (raw.size < 14) throw IOException("FTDI 设备描述符过短")
        val type = raw[13].toInt() and 255
        baudWithPort = type in 7..9 || device.interfaceCount > 1
    }
    override fun setBaud(baud: Int, dataBits: Int, stopBits: Int, parity: String) {
        val config = SerialCodec.ftdiLineControl(dataBits, stopBits, parity)
        val (value, lowIndex) = SerialCodec.ftdiBaud(baud)
        val index = if (baudWithPort) (lowIndex shl 8) or (portIndex + 1) else lowIndex
        control(0x40, 3, value, index)
        control(0x40, 4, config, portIndex + 1)
    }
    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val raw = ByteArray(buf.size + 2)
        val n = super.read(raw, timeoutMs)
        var written = 0; var pos = 0
        val packet = readEndpoint.maxPacketSize
        while (pos < n) {
            val end = minOf(n, pos + packet)
            if (end - pos < 2) throw IOException("FTDI 状态包过短")
            val length = minOf(end - pos - 2, buf.size - written)
            raw.copyInto(buf, written, pos + 2, pos + 2 + length)
            written += length; pos = end
        }
        return written
    }
}

object SerialDeviceKind {
    fun detect(device: UsbDevice): String? = when {
        device.vendorId == 0x1a86 && device.productId in setOf(0x7523, 0x5523) -> "CH34x"
        device.vendorId == 0x10c4 && device.productId in setOf(0xea60, 0xea70, 0xea71) -> "CP210x"
        device.vendorId == 0x0403 && device.productId in setOf(0x6001, 0x6010, 0x6011, 0x6014, 0x6015) -> "FTDI"
        (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_COMM && device.getInterface(it).interfaceSubclass == 2 } &&
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA } -> "CDC-ACM"
        else -> null
    }
    fun dataInterface(device: UsbDevice, kind: String): UsbInterface? {
        val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
        return interfaces.filter { iface ->
            val endpoints = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
            endpoints.any { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN } &&
                endpoints.any { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
        }.let { eligible -> if (kind == "CDC-ACM") eligible.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA } else eligible.firstOrNull() }
    }
    fun create(device: UsbDevice, kind: String): UsbSerialPort = when (kind) {
        "CDC-ACM" -> CdcAcmPort(device); "CH34x" -> Ch34xPort(device)
        "CP210x" -> Cp21xxPort(device); "FTDI" -> FtdiPort(device)
        else -> error("unsupported kind")
    }
}
