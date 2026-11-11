package com.dc.andagent.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** R143 ①: stub 容器解析 + chip_id/版本匹配 + stub 下载状态机（纯函数）。 */
class EspStubTest {

    /** 构造与 /tmp/gen_stub_assets.py 同构的 36B 头 + text + data 容器。 */
    private fun container(
        chipId: Int,
        entry: Int,
        textStart: Int,
        textLen: Int,
        dataStart: Int,
        dataLen: Int,
        schema: Int = EspStubAssets.SCHEMA_VERSION,
        versionTag: Int = EspStubAssets.STUB_VERSION_TAG,
        magic: ByteArray = byteArrayOf(0x45, 0x53, 0x54, 0x42),
        totalExtra: Int = 0,
    ): ByteArray {
        val h = ByteArray(EspStubAssets.HEADER_SIZE)
        magic.copyInto(h, 0)
        EspFlashCodec.putLe32(h, 4, schema)
        EspFlashCodec.putLe32(h, 8, entry)
        EspFlashCodec.putLe32(h, 12, textStart)
        EspFlashCodec.putLe32(h, 16, textLen)
        EspFlashCodec.putLe32(h, 20, dataStart)
        EspFlashCodec.putLe32(h, 24, dataLen)
        EspFlashCodec.putLe32(h, 28, chipId)
        EspFlashCodec.putLe32(h, 32, versionTag)
        val text = ByteArray(textLen) { (it and 0xff).toByte() }
        val data = ByteArray(dataLen) { (it xor 0x5a).toByte() }
        return h + text + data + ByteArray(totalExtra)
    }

    // ---------- 容器解析 ----------
    @Test fun parseContainerReturnsSegmentsAndEntry() {
        // 用官方 esp32c3.json（esptool.py set 2 / v1.4.1）抄录的头值做断言：
        // entry=1077415578, text_start=1077411840, data_start=1070165356, text=5880B, data=204B, chip_id=5
        val blob = container(5, 1077415578, 1077411840, 5880, 1070165356, 204)
        val stub = EspStubAssets.parse(blob)
        assertEquals(5, stub.chipId)
        assertEquals(1077415578, stub.entry)
        assertEquals(2, stub.segments.size)
        assertEquals(1077411840, stub.segments[0].address)
        assertEquals(5880, stub.segments[0].data.size)
        assertEquals(1070165356, stub.segments[1].address)
        assertEquals(204, stub.segments[1].data.size)
        assertEquals(6084, stub.textLength)
        // 段数据与裸字节一致（解码未改动固件本体）
        assertEquals((0 and 0xff).toByte(), stub.segments[0].data[0])
        assertEquals((0 xor 0x5a).toByte(), stub.segments[1].data[0])
    }

    @Test fun parseContainerRejectsBadMagicSchemaVersionOrLength() {
        assertThrows(IllegalArgumentException::class.java) {
            EspStubAssets.parse(container(5, 1, 2, 4, 3, 4, magic = byteArrayOf(0, 0, 0, 0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EspStubAssets.parse(container(5, 1, 2, 4, 3, 4, schema = 99))
        }
        assertThrows(IllegalArgumentException::class.java) {
            // stub 版本标签不符（v1.4.1 匹配校验）
            EspStubAssets.parse(container(5, 1, 2, 4, 3, 4, versionTag = 0x00010300))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EspStubAssets.parse(container(5, 1, 2, 4, 3, 4, totalExtra = 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EspStubAssets.parse(ByteArray(10))
        }
    }

    // ---------- chip_id / stub 版本匹配校验 ----------
    @Test fun chipIdAndStubVersionValidation() {
        val c3 = EspChip("ESP32-C3", 5)
        val good = container(5, 0x40000000, 0x40000000, 8, 0x3FC00000, 8)
        assertEquals(5, EspStubAssets.load(c3, good)!!.chipId)
        assertEquals("esp32c3", EspTargets.stubKeyFor(c3))

        // chip_id 不匹配（把 C6 的 stub 13 灌给 C3）→ null，触发 ROM 降级
        val wrongId = container(13, 0x40000000, 0x40000000, 8, 0x3FC00000, 8)
        assertNull(EspStubAssets.load(c3, wrongId))
        // 容器自身被检测出的 chip_id 与 GET_SECURITY_INFO 读回值不一致
        assertNull(EspStubAssets.load(EspChip("ESP32-C3", 13), good))

        // 版本不符的容器在 parse 阶段即失败 → load 返回 null（安全降级）
        val wrongVer = container(5, 1, 2, 4, 3, 4, versionTag = 0)
        assertNull(EspStubAssets.load(c3, wrongVer))

        // ESP8266 / ESP32 走 magic 识别（chipId=null），容器用 -1 哨兵，按名字匹配
        val esp8266 = container(-1, 1, 2, 4, 3, 4)
        assertEquals(-1, EspStubAssets.load(EspChip("ESP8266", null), esp8266)!!.chipId)
        assertNull(EspStubAssets.load(EspChip("UNKNOWN", null), good))
    }

    @Test fun stubKeyForUnknownChipIdOrMismatchIsNull() {
        assertEquals("esp32p4", EspTargets.stubKeyFor(EspChip("ESP32-P4", 18)))
        assertNull(EspTargets.stubKeyFor(EspChip("ESP32-C3", 999)))
        assertNull(EspTargets.stubKeyFor(EspChip("ESP32-C5", 23)))   // 上游新芯片未打包 stub
        assertNull(EspTargets.stubKeyFor(EspChip("UNKNOWN", null)))
    }

    // ---------- 下载状态机（纯函数）----------
    @Test fun buildLoadSequenceMatchesEsptoolUploadSegmentAndMemFinish() {
        val text = ByteArray(EspCmd.ESP_RAM_BLOCK + 10) { (it and 0xff).toByte() }  // 2 块
        val data = ByteArray(204) { (it xor 0x5a).toByte() }                       // 1 块
        val entry = 0x403D1234
        val stub = EspStub(5, entry, listOf(
            EspStubSegment(0x403C0000, text),
            EspStubSegment(0x3FCF0000, data),
        ))
        val ops = EspStubLoader.buildLoadSequence(stub)

        // 期望：MEM_BEGIN + 2×MEM_DATA（text）+ MEM_BEGIN + 1×MEM_DATA（data）+ MEM_END
        assertEquals(6, ops.size)
        assertEquals(
            listOf(EspCmd.MEM_BEGIN, EspCmd.MEM_DATA, EspCmd.MEM_DATA,
                EspCmd.MEM_BEGIN, EspCmd.MEM_DATA, EspCmd.MEM_END),
            ops.map { it.op })

        // text 段 MEM_BEGIN <IIII>(size, blocks, ESP_RAM_BLOCK, addr)  py loader.py:1146
        val mb0 = ops[0].data
        assertEquals(16, mb0.size)
        assertEquals(text.size, EspFlashCodec.le32(mb0, 0))
        assertEquals(2, EspFlashCodec.le32(mb0, 4))
        assertEquals(EspCmd.ESP_RAM_BLOCK, EspFlashCodec.le32(mb0, 8))
        assertEquals(0x403C0000, EspFlashCodec.le32(mb0, 12))

        // MEM_DATA 头 <IIII>(len, seq, 0, 0) + chunk，chk = 0xEF XOR chunk（py loader.py:1149-1156）
        val md0 = ops[1]
        assertEquals(16 + EspCmd.ESP_RAM_BLOCK, md0.data.size)
        assertEquals(EspCmd.ESP_RAM_BLOCK, EspFlashCodec.le32(md0.data, 0))
        assertEquals(0, EspFlashCodec.le32(md0.data, 4))
        assertEquals(0, EspFlashCodec.le32(md0.data, 8))
        assertEquals(0, EspFlashCodec.le32(md0.data, 12))
        assertArrayEquals(text.copyOfRange(0, EspCmd.ESP_RAM_BLOCK),
            md0.data.copyOfRange(16, 16 + EspCmd.ESP_RAM_BLOCK))
        assertEquals(EspFlashCodec.checksum(text.copyOfRange(0, EspCmd.ESP_RAM_BLOCK)), md0.chk)
        assertEquals(1, EspFlashCodec.le32(ops[2].data, 4))  // seq=1
        assertEquals(10, EspFlashCodec.le32(ops[2].data, 0)) // 尾块长度
        assertArrayEquals(text.copyOfRange(EspCmd.ESP_RAM_BLOCK, text.size),
            ops[2].data.copyOfRange(16, 16 + 10))

        // data 段 MEM_BEGIN/MEM_DATA
        assertEquals(204, EspFlashCodec.le32(ops[3].data, 0))
        assertEquals(1, EspFlashCodec.le32(ops[3].data, 4))
        assertEquals(0x3FCF0000, EspFlashCodec.le32(ops[3].data, 12))
        assertEquals(204, EspFlashCodec.le32(ops[4].data, 0))
        assertEquals(0, EspFlashCodec.le32(ops[4].data, 4))
        assertArrayEquals(data, ops[4].data.copyOfRange(16, 16 + 204))
        assertEquals(EspFlashCodec.checksum(data), ops[4].chk)

        // MEM_END <II>(int(entry==0)=0, entry)，fire-and-forget（py loader.py:1165）
        val me = ops[5]
        assertFalse("MEM_END 不等应答", me.awaitReply)
        assertArrayEquals(EspFlashCodec.memFinishParams(entry), me.data)
        assertEquals(8, me.data.size)
        assertEquals(0, EspFlashCodec.le32(me.data, 0))
        assertEquals(entry, EspFlashCodec.le32(me.data, 4))
        // 其余命令都要等应答
        assertTrue(ops.subList(0, 5).all { it.awaitReply })
    }

    @Test fun memFinishParamsAndOhaiMarker() {
        // entry==0（不执行）时第一字=1；非 0 时第一字=0，第二字=entry
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0), EspFlashCodec.memFinishParams(0))
        assertEquals(EspCmd.OHAI_FIRST_BYTE, 0x4F)
        assertArrayEquals(byteArrayOf(0x4F, 0x48, 0x41, 0x49), EspStubLoader.OHAI)
        assertTrue(EspStubLoader.isOhai(byteArrayOf(0x4F, 0x48, 0x41, 0x49)))
        assertFalse(EspStubLoader.isOhai(byteArrayOf(0x4F, 0x48, 0x41)))
        assertFalse(EspStubLoader.isOhai(byteArrayOf(0, 0)))
        // SLIP 帧 C0 4F 48 41 49 C0 解出来正是 OHAI
        assertArrayEquals(EspStubLoader.OHAI,
            EspFlashCodec.slipDecode(byteArrayOf(0xC0.toByte(), 0x4F, 0x48, 0x41, 0x49, 0xC0.toByte())))
    }

    @Test fun stubMd5RawDigestHexRoundTrip() {
        // py loader.py:1625 / :1638：stub 回 16B 原始 digest，需 hexify 后与文件 md5 比对。
        val data = ByteArray(1234) { (it % 251).toByte() }
        val raw = MessageDigest.getInstance("MD5").digest(data)
        assertEquals(16, raw.size)
        assertEquals(EspCmd.MD5_RESP_DATA_LEN_STUB, raw.size)
        assertEquals(EspFlashCodec.md5Hex(data), EspFlashCodec.hex(raw))
        assertEquals(32, EspCmd.MD5_RESP_DATA_LEN_ROM)  // 0x20：ROM 的 32B ASCII hex
    }

    /** 真正随包打进 assets 的 9 个 .bin 全部能被 Kotlin 解析器读出，且 chip_id/版本校验通过。 */
    @Test fun packagedStubAssetsAllParseAndMatchChipId() {
        val dir = java.io.File("src/main/assets/esp_stub")
        assertTrue("assets/esp_stub 应存在（单测工作目录为 app/）：${dir.absolutePath}", dir.isDirectory)
        val entries = listOf(
            Triple("esp32", "ESP32", 0), Triple("esp32s2", "ESP32-S2", 2),
            Triple("esp32s3", "ESP32-S3", 9), Triple("esp32c2", "ESP32-C2", 12),
            Triple("esp32c3", "ESP32-C3", 5), Triple("esp32c6", "ESP32-C6", 13),
            Triple("esp32h2", "ESP32-H2", 16), Triple("esp32p4", "ESP32-P4", 18),
            Triple("esp8266", "ESP8266", -1),
        )
        for ((key, name, chipId) in entries) {
            val blob = java.io.File(dir, "$key.bin").readBytes()
            val stub = EspStubAssets.parse(blob)     // magic/schema/版本/长度全校验
            assertEquals("$key chip_id", chipId, stub.chipId)
            assertEquals("$key 两段", 2, stub.segments.size)
            assertTrue("$key text 非空", stub.segments[0].data.isNotEmpty())
            assertTrue("$key data 非空", stub.segments[1].data.isNotEmpty())
            assertTrue("$key entry 非零", stub.entry != 0)
            // 经 load() 做 chip_id ↔ stub 版本匹配校验
            val matched = EspStubAssets.load(EspChip(name, chipId), blob)
            assertEquals("$key 匹配", chipId, matched!!.chipId)
        }
    }
}
