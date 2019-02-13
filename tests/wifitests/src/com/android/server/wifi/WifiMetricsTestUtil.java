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

package com.android.server.wifi;

import static org.junit.Assert.assertEquals;

import com.android.server.wifi.nano.WifiMetricsProto.HistogramBucketInt32;
import com.android.server.wifi.nano.WifiMetricsProto.LinkProbeStats.LinkProbeFailureReasonCount;
import com.android.server.wifi.nano.WifiMetricsProto.MapEntryInt32Int32;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Utility functions for {@link WifiMetricsTest}.
 */
public class WifiMetricsTestUtil {

    /**
     * Asserts that the two arrays are equal, reporting any difference between them.
     * Note: The order of buckets in each array must match!
     */
    public static void assertHistogramBucketsEqual(HistogramBucketInt32[] expected,
            HistogramBucketInt32[] actual) {
        assertEquals("Number of buckets do not match!",
                expected.length, actual.length);

        for (int i = 0; i < expected.length; i++) {
            HistogramBucketInt32 expectedBucket = expected[i];
            HistogramBucketInt32 actualBucket = actual[i];

            assertEquals(String.format("Bucket[%d].start does not match!", i),
                    expectedBucket.start, actualBucket.start);
            assertEquals(String.format("Bucket[%d].end does not match!", i),
                    expectedBucket.end, actualBucket.end);
            assertEquals(String.format("Bucket[%d].count does not match!", i),
                    expectedBucket.count, actualBucket.count);
        }
    }

    /**
     * The constructor we wish HistogramBucketInt32 had.
     */
    public static HistogramBucketInt32 buildHistogramBucketInt32(int start, int end, int count) {
        HistogramBucketInt32 bucket = new HistogramBucketInt32();
        bucket.start = start;
        bucket.end = end;
        bucket.count = count;
        return bucket;
    }

    /**
     * Asserts that the two arrays are equal, reporting any difference between them.
     * Note: The order of key counts in each array must match!
     */
    public static void assertMapEntriesEqual(MapEntryInt32Int32[] expected,
            MapEntryInt32Int32[] actual) {
        assertEquals("Number of map entries do not match!",
                expected.length, actual.length);

        for (int i = 0; i < expected.length; i++) {
            MapEntryInt32Int32 expectedKeyCount = expected[i];
            MapEntryInt32Int32 actualKeyCount = actual[i];

            assertEquals(String.format("KeyCount[%d].key does not match!", i),
                    expectedKeyCount.key, actualKeyCount.key);
            assertEquals(String.format("KeyCount[%d].value does not match!", i),
                    expectedKeyCount.value, actualKeyCount.value);
        }
    }

    /**
     * The constructor we wish MapEntryInt32Int32 had.
     */
    public static MapEntryInt32Int32 buildMapEntryInt32Int32(int key, int count) {
        MapEntryInt32Int32 keyCount = new MapEntryInt32Int32();
        keyCount.key = key;
        keyCount.value = count;
        return keyCount;
    }

    /**
     * Asserts that the two arrays are equal, reporting any difference between them.
     */
    public static void assertLinkProbeFailureReasonCountsEqual(
            LinkProbeFailureReasonCount[] expected, LinkProbeFailureReasonCount[] actual) {
        assertEquals("Number of LinkProbeFailureReasonCounts do not match!",
                expected.length, actual.length);

        Arrays.sort(expected, Comparator.comparingInt(x -> x.failureReason));
        Arrays.sort(actual, Comparator.comparingInt(x -> x.failureReason));

        for (int i = 0; i < expected.length; i++) {
            LinkProbeFailureReasonCount expectedFailureReasonCount = expected[i];
            LinkProbeFailureReasonCount actualFailureReasonCount = actual[i];

            assertEquals(String.format("LinkProbeFailureReasonCount[%d].key does not match!", i),
                    expectedFailureReasonCount.failureReason,
                    actualFailureReasonCount.failureReason);
            assertEquals(String.format("LinkProbeFailureReasonCount[%d].count does not match!", i),
                    expectedFailureReasonCount.count, actualFailureReasonCount.count);
        }
    }

    /**
     * The constructor we wish LinkProbeFailureReasonCount had.
     */
    public static LinkProbeFailureReasonCount buildLinkProbeFailureReasonCount(int failureReason,
            int count) {
        LinkProbeFailureReasonCount failureReasonCount = new LinkProbeFailureReasonCount();
        failureReasonCount.failureReason = failureReason;
        failureReasonCount.count = count;
        return failureReasonCount;
    }
}
