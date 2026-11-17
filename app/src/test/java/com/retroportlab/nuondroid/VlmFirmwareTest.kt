package com.retroportlab.nuondroid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Needs the Samsung N501 update, which isn't in the repo: run with
 *   NUONDROID_N501=/path/to/nuon.zip (or NUON.CD)  [NUONDROID_N501_PE=/path/to/expected pe.cof]
 * and it's skipped otherwise.
 */
class VlmFirmwareTest {
    @Test fun extractsPe() {
        val src = System.getenv("NUONDROID_N501")
        assumeTrue("NUONDROID_N501 not set", src != null)
        val pe = VlmFirmware.extract(File(src!!).readBytes()).getOrThrow()
        assertTrue(pe.size > 800_000)
        System.getenv("NUONDROID_N501_PE")?.let { assertArrayEquals(File(it).readBytes(), pe) }
    }

    @Test fun rejectsOtherFiles() {
        assertTrue(VlmFirmware.extract(ByteArray(1000) { it.toByte() }).isFailure)
    }
}
