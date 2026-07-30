/////////////////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Garmin International, Inc.
// Licensed under the Flexible and Interoperable Data Transfer (FIT) Protocol License; you
// may not use this file except in compliance with the Flexible and Interoperable Data
// Transfer (FIT) Protocol License.
/////////////////////////////////////////////////////////////////////////////////////////////

package com.garmin.fit

import kotlin.test.Test
import kotlin.test.assertEquals

class AccumulatorTests {
    @Test
    fun singleFieldAccumulatesEachValue() {
        val accumulator = Accumulator()
        accumulator.create(0u, 0u, 0)

        assertEquals(1L, accumulator.accumulate(0u, 0u, 1, 8))
        assertEquals(2L, accumulator.accumulate(0u, 0u, 2, 8))
        assertEquals(4L, accumulator.accumulate(0u, 0u, 4, 8))
        assertEquals(5L, accumulator.accumulate(0u, 0u, 5, 8))
    }

    @Test
    fun fieldsAccumulateIndependentlyOfEachOther() {
        val accumulator = Accumulator()

        accumulator.create(0u, 0u, 250)
        assertEquals(254L, accumulator.accumulate(0u, 0u, 254, 8))

        accumulator.create(1u, 1u, 0)
        assertEquals(2L, accumulator.accumulate(1u, 1u, 2, 8))

        // Interleaving the other field did not disturb this one's running total.
        assertEquals(256L, accumulator.accumulate(0u, 0u, 0, 8))
    }

    @Test
    fun counterRolloverKeepsTheRunningTotalMonotonic() {
        val accumulator = Accumulator()
        accumulator.create(0u, 0u, 0)

        assertEquals(254L, accumulator.accumulate(0u, 0u, 254, 8))
        assertEquals(255L, accumulator.accumulate(0u, 0u, 255, 8))
        // The 8-bit counter wrapped to 0; the accumulated value must not.
        assertEquals(256L, accumulator.accumulate(0u, 0u, 0, 8))
        assertEquals(259L, accumulator.accumulate(0u, 0u, 3, 8))
    }

    /**
     * A field whose first sample arrives without a preceding seed starts from
     * that sample, not from zero: FIT counters are relative, so treating the
     * first one as a delta from zero would invent distance that was never
     * travelled.
     */
    @Test
    fun accumulatingAnUnseenFieldSeedsItFromItsFirstSample() {
        val accumulator = Accumulator()
        assertEquals(100L, accumulator.accumulate(20u, 5u, 100, 16))
        assertEquals(110L, accumulator.accumulate(20u, 5u, 110, 16))
    }

    /**
     * [Accumulator] itself is sentinel-agnostic by design: it sums whatever it
     * is handed. Recognising the all-ones invalid pattern is the expansion
     * layer's job — [Mesg.expandComponents] never feeds a sentinel in, which
     * the decode-level test below pins down.
     */
    @Test
    fun theAccumulatorItselfIsSentinelAgnostic() {
        val accumulator = Accumulator()
        accumulator.create(0u, 0u, 100)

        val bits = 8
        val allOnes = (1L shl bits) - 1
        assertEquals(255L, accumulator.accumulate(0u, 0u, allOnes, bits))
        assertEquals(266L, accumulator.accumulate(0u, 0u, 10, bits))
    }

    /**
     * An all-ones accumulated component carries no sample: it must neither
     * reach the destination field as a value nor feed the running total, so
     * the next real sample's delta is computed against the last real one.
     *
     * This is a deliberate divergence from fit-python-sdk, whose
     * `_expand_components` folds the sentinel into the accumulator before
     * testing invalidity — there, the record after the invalid one inherits a
     * total corrupted by the sentinel.
     *
     * `record.compressed_speed_distance` is the natural case: a 3-byte field
     * packing 12 bits of speed and 12 accumulated bits of distance, where the
     * distance bits can be all-ones while the field as a whole stays valid.
     */
    @Test
    fun anInvalidAccumulatedComponentDoesNotCorruptTheRunningTotal() {
        // 12 speed bits then 12 distance bits, LSB first over 3 bytes.
        fun compressed(speedRaw: Int, distanceRaw: Int) = intArrayOf(
            speedRaw and 0xFF,
            ((speedRaw shr 8) and 0x0F) or ((distanceRaw and 0x0F) shl 4),
            (distanceRaw shr 4) and 0xFF,
        )

        val body = ByteWriter()
        // Definition, local 0: record (20), one field: compressed_speed_distance (8), 3 bytes of BYTE.
        body.writeByte(0x40u)
        body.writeByte(0u)
        body.writeByte(Endianness.LITTLE.value)
        body.writeUShort(20u)
        body.writeByte(1u)
        body.writeByte(8u)
        body.writeByte(3u)
        body.writeByte(BaseType.BYTE.id)
        // Distance raw is in 1/16 m: 16 = 1 m, then the sentinel, then 32 = 2 m.
        for (distanceRaw in intArrayOf(16, 0xFFF, 32)) {
            body.writeByte(0u)
            compressed(0, distanceRaw).forEach { body.writeByte(it.toUByte()) }
        }
        val data = body.toByteArray()

        val header = FileHeader(
            headerSize = Fit.HEADER_WITH_CRC_SIZE,
            protocolVersionByte = ProtocolVersion.V2_0.value,
            profileVersion = Fit.PROFILE_VERSION,
            dataSize = data.size.toUInt(),
        )
        header.updateCrc()
        val file = ByteWriter()
        file.writeBytes(header.toByteArray())
        file.writeBytes(data)
        val soFar = file.toByteArray()
        file.writeUShort(Crc.calculate(soFar, 0, soFar.size))

        val result = FitDecoder(file.toByteArray()).decode()
        assertEquals(emptyList(), result.errors)

        // 1 m, no sample, then 2 m — not the 255.94 m and 258 m that
        // accumulating the sentinel would produce.
        assertEquals(listOf(1.0, null, 2.0), result.messages.recordMesgs.map { it.distance })
    }
}
