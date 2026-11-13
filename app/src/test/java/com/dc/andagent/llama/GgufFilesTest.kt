package com.dc.andagent.llama

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GgufFilesTest {
    @Test fun rejectsBadMagicAndShortHeader() {
        val file = File.createTempFile("gguf", ".gguf")
        try {
            file.writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46))
            assertFalse(GgufFiles.valid(file))
            file.writeBytes(ByteArray(32))
            assertFalse(GgufFiles.valid(file))
            file.writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46) + ByteArray(28))
            assertTrue(GgufFiles.valid(file))
        } finally { file.delete() }
    }

    @Test fun rejectsTraversalNames() {
        assertFalse(GgufFiles.safeName("../model.gguf"))
        assertFalse(GgufFiles.safeName(".hidden"))
        assertTrue(GgufFiles.safeName("qwen-0.5b.gguf"))
    }
}
