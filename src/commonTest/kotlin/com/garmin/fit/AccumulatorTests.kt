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
     * Documents current behaviour rather than prescribing it: [Accumulator] has
     * no notion of a component's invalid sentinel (all-ones for its bit width).
     * [Mesg.expandComponents] only recognises invalidity *after* unscaling the
     * accumulated result, to decide whether to store null in the destination
     * field — the raw sentinel has already been folded into the running total by
     * then. fit-python-sdk's `_expand_components` does the same (it calls
     * `self._accumulator.accumulate(...)` before checking `raw_value ==
     * invalid_value`), so this is a shared trait of the reference decoders, not
     * a Kotlin-only bug. See concerns for why this is left untested-as-correct.
     */
    @Test
    fun anInvalidComponentValueStillFeedsTheRunningTotal() {
        val accumulator = Accumulator()
        accumulator.create(0u, 0u, 100)

        val bits = 8
        val allOnes = (1L shl bits) - 1 // 255: the uint8 invalid sentinel.
        val afterInvalid = accumulator.accumulate(0u, 0u, allOnes, bits)
        assertEquals(255L, afterInvalid, "the accumulator has no concept of invalidity of its own")

        // The next real sample's delta is computed against the sentinel just
        // folded in, not against the last real sample (100).
        val next = accumulator.accumulate(0u, 0u, 10, bits)
        assertEquals(266L, next)
    }
}
