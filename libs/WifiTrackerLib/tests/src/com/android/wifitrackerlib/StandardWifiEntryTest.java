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

package com.android.wifitrackerlib;

import static com.android.wifitrackerlib.TestUtils.buildScanResult;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.verify;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.test.TestLooper;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;

public class StandardWifiEntryTest {
    public static final int GOOD_RSSI = -50;
    public static final int OKAY_RSSI = -60;
    public static final int BAD_RSSI = -70;

    @Mock private WifiEntry.WifiEntryCallback mMockListener;

    private TestLooper mTestLooper;
    private Handler mTestHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mTestLooper = new TestLooper();
        mTestHandler = new Handler(mTestLooper.getLooper());
    }

    /**
     * Tests that constructing with an empty list of scans throws an exception
     */
    @Test
    public void testConstructor_emptyScanList_throwsException() {
        try {
            new StandardWifiEntry(mTestHandler, new ArrayList<>());
            fail("Empty scan list should have thrown exception");
        } catch (IllegalArgumentException e) {
            // Test succeeded
        }
    }

    /**
     * Tests that constructing with a list of scans with differing SSIDs throws an exception
     */
    @Test
    public void testConstructor_mismatchedSsids_throwsException() {
        try {
            new StandardWifiEntry(mTestHandler, Arrays.asList(
                    buildScanResult("ssid0", "bssid0", 0, GOOD_RSSI),
                    buildScanResult("ssid1", "bssid1", 0, GOOD_RSSI)));
            fail("Scan list with different SSIDs should have thrown exception");
        } catch (IllegalArgumentException e) {
            // Test succeeded
        }
    }

    /**
     * Tests that the level is set to the level of the strongest scan
     */
    @Test
    public void testConstructor_setsBestLevel() {
        final StandardWifiEntry entry = new StandardWifiEntry(mTestHandler, Arrays.asList(
                buildScanResult("ssid", "bssid0", 0, GOOD_RSSI),
                buildScanResult("ssid", "bssid1", 0, OKAY_RSSI),
                buildScanResult("ssid", "bssid2", 0, BAD_RSSI)));

        assertThat(entry.getLevel()).isEqualTo(
                WifiManager.calculateSignalLevel(GOOD_RSSI, WifiManager.RSSI_LEVELS));
    }

    /**
     * Tests that the security is set to the security capabilities of the scan
     */
    @Test
    public void testConstructor_setsSecurity() {
        final ScanResult unsecureScan = buildScanResult("ssid", "bssid", 0, GOOD_RSSI);
        final ScanResult secureScan = buildScanResult("ssid", "bssid", 0, GOOD_RSSI);
        secureScan.capabilities = "EAP";

        final StandardWifiEntry unsecureEntry = new StandardWifiEntry(mTestHandler,
                Arrays.asList(unsecureScan));
        final StandardWifiEntry secureEntry = new StandardWifiEntry(mTestHandler,
                Arrays.asList(secureScan));

        assertThat(unsecureEntry.getSecurity()).isEqualTo(WifiEntry.SECURITY_NONE);
        assertThat(secureEntry.getSecurity()).isEqualTo(WifiEntry.SECURITY_EAP);
    }

    /**
     * Tests that updating with an empty list of scans throws an exception.
     */
    @Test
    public void testUpdateScanResultInfo_emptyScanList_throwsException() {
        final StandardWifiEntry entry = new StandardWifiEntry(mTestHandler, Arrays.asList(
                buildScanResult("ssid", "bssid", 0, GOOD_RSSI))
        );

        try {
            entry.updateScanResultInfo(new ArrayList<>());
            fail("Empty scan list should have thrown exception");
        } catch (IllegalArgumentException e) {
            // Test succeeded
        }
    }

    /**
     * Tests that updating with a list of scans with differing SSIDs throws an exception
     */
    @Test
    public void testUpdateScanResultInfo_mismatchedSsids_throwsException() {
        final StandardWifiEntry entry = new StandardWifiEntry(mTestHandler, Arrays.asList(
                buildScanResult("ssid0", "bssid0", 0, GOOD_RSSI)));

        try {
            entry.updateScanResultInfo(Arrays.asList(
                    buildScanResult("ssid1", "bssid1", 0, GOOD_RSSI)));
            fail("Scan list with different SSIDs should have thrown exception");
        } catch (IllegalArgumentException e) {
            // Test succeeded
        }
    }

    /**
     * Tests that updating with a list of scans with differing security types throws an exception.
     */
    @Test
    public void testUpdateScanResultInfo_mismatchedSecurity_throwsException() {
        final StandardWifiEntry entry = new StandardWifiEntry(mTestHandler, Arrays.asList(
                buildScanResult("ssid0", "bssid0", 0, GOOD_RSSI)));

        try {
            final ScanResult differentSecurityScan =
                    buildScanResult("ssid0", "bssid0", 0, GOOD_RSSI);
            differentSecurityScan.capabilities = "EAP";
            entry.updateScanResultInfo(Arrays.asList(differentSecurityScan));
            fail("Scan list with different SSIDs should have thrown exception");
        } catch (IllegalArgumentException e) {
            // Test succeeded
        }
    }

    /**
     * Tests that the listener is notified after an update to the scan results
     */
    @Test
    public void testUpdateScanResultInfo_notifiesListener() {
        final StandardWifiEntry entry = new StandardWifiEntry(mTestHandler, Arrays.asList(
                buildScanResult("ssid", "bssid", 0)));
        entry.setListener(mMockListener);

        entry.updateScanResultInfo(Arrays.asList(buildScanResult("ssid", "bssid", 1)));
        mTestLooper.dispatchAll();

        verify(mMockListener).onUpdated();
    }

    /**
     * Tests that the level is updated after an update to the scan results
     */
    @Test
    public void testUpdateScanResultInfo_updatesLevel() {
        final StandardWifiEntry entry = new StandardWifiEntry(mTestHandler, Arrays.asList(
                buildScanResult("ssid", "bssid", 0, BAD_RSSI)));

        assertThat(entry.getLevel()).isEqualTo(
                WifiManager.calculateSignalLevel(BAD_RSSI, WifiManager.RSSI_LEVELS));

        entry.updateScanResultInfo(Arrays.asList(buildScanResult("ssid", "bssid", 0, GOOD_RSSI)));

        assertThat(entry.getLevel()).isEqualTo(
                WifiManager.calculateSignalLevel(GOOD_RSSI, WifiManager.RSSI_LEVELS));
    }
}
