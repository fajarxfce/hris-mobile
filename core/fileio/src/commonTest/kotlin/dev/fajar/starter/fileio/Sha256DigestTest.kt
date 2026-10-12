package dev.fajar.starter.fileio

import kotlin.test.*

class Sha256DigestTest {
    @Test
    fun standardVectorsRemainCorrectAcrossFragmentedBlocks() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256Digest().finishHex(),
        )
        val fragmented = Sha256Digest()
        "abc".forEach { fragmented.update(byteArrayOf(it.code.toByte())) }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            fragmented.finishHex(),
        )
        val streamed = Sha256Digest()
        val block = ByteArray(1000) { 'a'.code.toByte() }
        repeat(1000) { streamed.update(block) }
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            streamed.finishHex(),
        )
        assertFailsWith<IllegalStateException> { streamed.update(block) }
        assertFailsWith<IllegalStateException> { streamed.finishHex() }
    }
}
