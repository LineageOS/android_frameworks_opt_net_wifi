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
import android.net.wifi.WifiConfiguration;
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
    public void testConstructor_scanResults_setsBestLevel() {
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
    public void testConstructor_scanResults_setsSecurity() {
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

    @Test
    public void testConstructor_wifiConfig_setsTitle() {
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP);
        final StandardWifiEntry entry = new StandardWifiEntry(mTestHandler, config);

        assertThat(entry.getTitle()).isEqualTo("ssid");
    }

    @Test
    public void testConstructor_wifiConfig_setsSecurity() {
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP);
        final StandardWifiEntry entry = new StandardWifiEntry(mTestHandler, config);

        assertThat(entry.getSecurity()).isEqualTo(WifiEntry.SECURITY_EAP);
    }

    @Test
    public void testUpdateConfig_mismatchedSsids_throwsException() {
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP);
        final StandardWifiEntry entry = new StandardWifiEntry(mTestHandler, config);

        final WifiConfiguration config2 = new WifiConfiguration(config);
        config2.SSID = "\"ssid2\"";
        try {
            entry.updateConfig(config2);
            fail("Updating with wrong SSID config should throw exception");
        } catch (IllegalArgumentException e) {
            // Test Succeeded
        }
    }

    @Test
    public void testUpdateConfig_mismatchedSecurity_throwsException() {
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_WEP);
        final StandardWifiEntry entry = new StandardWifiEntry(mTestHandler, config);

        final WifiConfiguration config2 = new WifiConfiguration(config);
        config2.setSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP);
        try {
            entry.updateConfig(config2);
            fail("Updating with wrong security config should throw exception");
        } catch (IllegalArgumentException e) {
            // Test Succeeded
        }
    }

    @Test
    public void testUpdateConfig_unsavedToSaved() {
        final ScanResult scan = buildScanResult("ssid", "bssid", 0, GOOD_RSSI);
        scan.capabilities = "EAP";
        final StandardWifiEntry entry = new StandardWifiEntry(mTestHandler,
                Arrays.asList(scan));

        assertThat(entry.isSaved()).isFalse();

        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP);
        config.networkId = 1;
        entry.updateConfig(config);

        assertThat(entry.isSaved()).isTrue();
    }

    @Test
    public void testUpdateConfig_savedToUnsaved() {
        final WifiConfiguration config = new WifiConfiguration();
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP);
        config.SSID = "\"ssid\"";
        config.networkId = 1;
        final StandardWifiEntry entry = new StandardWifiEntry(mTestHandler, config);

        assertThat(entry.isSaved()).isTrue();

        entry.updateConfig(null);

        assertThat(entry.isSaved()).isFalse();
    }
}
