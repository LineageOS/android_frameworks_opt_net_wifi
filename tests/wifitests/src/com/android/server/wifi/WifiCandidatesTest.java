/*
 * Copyright 2018 The Android Open Source Project
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

import static com.android.server.wifi.util.NativeUtil.removeEnclosingQuotes;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.support.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link com.android.server.wifi.WifiCandidates}.
 */
@SmallTest
public class WifiCandidatesTest {

    @Mock ScanDetail mScanDetail1;
    @Mock ScanDetail mScanDetail2;

    ScanResult mScanResult1;
    ScanResult mScanResult2;

    WifiConfiguration mConfig1;
    WifiConfiguration mConfig2;

    WifiCandidates mWifiCandidates;

    /**
     * Sets up for unit test
     */
    @Before
    public void setUp() throws Exception {
        mWifiCandidates = new WifiCandidates();
        MockitoAnnotations.initMocks(this);
        mConfig1 = WifiConfigurationTestUtil.createOpenNetwork();
        mScanResult1 = new ScanResult() {{
                SSID = removeEnclosingQuotes(mConfig1.SSID);
                capabilities = "[ESS]";
            }};
        mConfig2 = WifiConfigurationTestUtil.createEphemeralNetwork();
        mScanResult2 = new ScanResult() {{
                SSID = removeEnclosingQuotes(mConfig2.SSID);
                capabilities = "[ESS]";
            }};
        doReturn(mScanResult1).when(mScanDetail1).getScanResult();
        doReturn(mScanResult2).when(mScanDetail2).getScanResult();
    }

    /**
     * Test for absence of null pointer exceptions
     */
    @Test
    public void testDontDieFromNulls() throws Exception {
        mWifiCandidates.add(null, mConfig1, 1, 42);
        mWifiCandidates.add(mScanDetail1, null, 2, 16);
        doReturn(null).when(mScanDetail2).getScanResult();
        mWifiCandidates.add(mScanDetail2, mConfig2, 3, 314);

        assertEquals(0, mWifiCandidates.size());
    }

    /**
     * Add just one thing
     */
    @Test
    public void testAddJustOne() throws Exception {
        assertTrue(mWifiCandidates.add(mScanDetail1, mConfig1, 2, 14));

        assertEquals(1, mWifiCandidates.size());
        assertEquals(0, mWifiCandidates.getFaultCount());
        assertNull(mWifiCandidates.getLastFault());
    }

    /**
     * Make sure we catch SSID mismatch due to quoting error
     */
    @Test
    public void testQuotingBotch() throws Exception {
        // Unfortunately ScanResult.SSID is not quoted; make sure we catch that
        mScanResult1.SSID = mConfig1.SSID;
        mWifiCandidates.add(mScanDetail1, mConfig1, 2, 14);

        // Should not have added this one
        assertEquals(0, mWifiCandidates.size());
        // The failure should have been recorded
        assertEquals(1, mWifiCandidates.getFaultCount());
        // The record of the failure should contain the culprit
        String blah = mWifiCandidates.getLastFault().toString();
        assertTrue(blah, blah.contains(mConfig1.SSID));

        // Now check that we can clear the faults
        mWifiCandidates.clearFaults();

        assertEquals(0, mWifiCandidates.getFaultCount());
        assertNull(mWifiCandidates.getLastFault());
    }

    /**
     * Test that picky mode works
     */
    @Test
    public void testPickyMode() throws Exception {
        // Set picky mode, make sure that it returns the object itself (so that
        // method chaining may be used).
        assertTrue(mWifiCandidates == mWifiCandidates.setPicky(true));
        try {
            mScanResult1.SSID = mConfig1.SSID; // As in testQuotingBotch()
            mWifiCandidates.add(mScanDetail1, mConfig1, 2, 14);
            fail("Exception not raised in picky mode");
        } catch (IllegalArgumentException e) {
            assertEquals(1, mWifiCandidates.getFaultCount());
            assertEquals(e, mWifiCandidates.getLastFault());
        }
    }

    /**
     * Try cases where we don't overwrite existing candidates
     */
    @Test
    public void testNoOverwriteCases() throws Exception {
        // Setup is to add the first candidate
        mWifiCandidates.add(mScanDetail1, mConfig1, 2, 14);
        assertEquals(1, mWifiCandidates.size());

        // Same evaluator, same score. Should not add.
        assertFalse(mWifiCandidates.add(mScanDetail1, mConfig1, 2, 14));
        assertEquals(0, mWifiCandidates.getFaultCount()); // But not considered a fault
        // Same evaluator, lower score. Should not add.
        assertFalse(mWifiCandidates.add(mScanDetail1, mConfig1, 2, 13));
        assertEquals(0, mWifiCandidates.getFaultCount()); // Also not a fault
        // Later evaluator. Should not add (regardless of score).
        assertFalse(mWifiCandidates.add(mScanDetail1, mConfig1, 5, 13));
        assertFalse(mWifiCandidates.add(mScanDetail1, mConfig1, 5, 15));
        assertEquals(0, mWifiCandidates.getFaultCount()); // Still no faults
        // Evaluator out of order. Should not add (regardless of score).
        assertFalse(mWifiCandidates.add(mScanDetail1, mConfig1, 1, 12));
        assertNotNull(mWifiCandidates.getLastFault()); // This one is considered a caller error
        assertFalse(mWifiCandidates.add(mScanDetail1, mConfig1, 1, 15));
        assertEquals(2, mWifiCandidates.getFaultCount());
        // After all that, only one candidate should be there.
        assertEquals(1, mWifiCandidates.size());
    }

}
