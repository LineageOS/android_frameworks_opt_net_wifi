/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi.util;

import static com.android.server.wifi.WifiMetricsTestUtil.assertMapEntriesEqual;
import static com.android.server.wifi.WifiMetricsTestUtil.buildMapEntryInt32Int32;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.nano.WifiMetricsProto.MapEntryInt32Int32;

import org.junit.Test;


/**
 * Unit tests for IntCounter.
 */
@SmallTest
public class IntCounterTest {

    private static final int[] TEST_KEYS = {
            100, 20, 34, 5656, 3535, 6456, -1231, -4235, 20, 3535, -5, 100, 6456, 34, -4235, -4235
    };

    /**
     * Tests when the counter is empty.
     */
    @Test
    public void testEmpty() {
        IntCounter counter = new IntCounter();
        assertMapEntriesEqual(new MapEntryInt32Int32[0], counter.toProto());
    }

    /**
     * Tests adding to the counter.
     */
    @Test
    public void testAddToCounter() {
        IntCounter counter = new IntCounter();

        for (int k : TEST_KEYS) {
            counter.increment(k);
        }

        MapEntryInt32Int32[] expected = {
                buildMapEntryInt32Int32(-4235, 3),
                buildMapEntryInt32Int32(-1231, 1),
                buildMapEntryInt32Int32(-5, 1),
                buildMapEntryInt32Int32(20, 2),
                buildMapEntryInt32Int32(34, 2),
                buildMapEntryInt32Int32(100, 2),
                buildMapEntryInt32Int32(3535, 2),
                buildMapEntryInt32Int32(5656, 1),
                buildMapEntryInt32Int32(6456, 2),
        };
        assertMapEntriesEqual(expected, counter.toProto());
    }

    /**
     * Tests adding to clamped counter.
     */
    @Test
    public void testAddToClampedCounter() {
        IntCounter counter = new IntCounter(-5, 100);

        for (int k : TEST_KEYS) {
            counter.increment(k);
        }

        MapEntryInt32Int32[] expected = {
                buildMapEntryInt32Int32(-5, 5),
                buildMapEntryInt32Int32(20, 2),
                buildMapEntryInt32Int32(34, 2),
                buildMapEntryInt32Int32(100, 7),
        };
        assertMapEntriesEqual(expected, counter.toProto());
    }
}
