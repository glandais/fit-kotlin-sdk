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
}
