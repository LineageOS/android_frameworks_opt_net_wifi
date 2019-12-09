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
import static com.android.wifitrackerlib.Utils.filterScanResultsByCapabilities;
import static com.android.wifitrackerlib.Utils.getBestScanResultByLevel;

import static com.google.common.truth.Truth.assertThat;

import android.net.wifi.ScanResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class UtilsTest {

    @Test
    public void testGetBestScanResult_emptyList_returnsNull() {
        assertThat(getBestScanResultByLevel(new ArrayList<>())).isNull();
    }

    @Test
    public void testGetBestScanResult_returnsBestRssiScan() {
        final ScanResult bestResult = buildScanResult("ssid", "bssid", 0, -50);
        final ScanResult okayResult = buildScanResult("ssid", "bssid", 0, -60);
        final ScanResult badResult = buildScanResult("ssid", "bssid", 0, -70);

        assertThat(getBestScanResultByLevel(Arrays.asList(bestResult, okayResult, badResult)))
                .isEqualTo(bestResult);
    }

    @Test
    public void testGetBestScanResult_singleScan_returnsScan() {
        final ScanResult scan = buildScanResult("ssid", "bssid", 0, -50);

        assertThat(getBestScanResultByLevel(Arrays.asList(scan))).isEqualTo(scan);
    }

    @Test
    public void testfilterScanResultsByCapabilities_filtersUnsupportedCapabilities() {
        final ScanResult wpa3SaeScan = new ScanResult();
        final ScanResult wpa3SuiteBScan = new ScanResult();
        final ScanResult enhancedOpenScan = new ScanResult();
        wpa3SaeScan.capabilities = "[SAE]";
        wpa3SuiteBScan.capabilities = "[EAP_SUITE_B_192]";
        enhancedOpenScan.capabilities = "[OWE]";

        final List<ScanResult> filteredScans = filterScanResultsByCapabilities(
                Arrays.asList(wpa3SaeScan, wpa3SuiteBScan, enhancedOpenScan),
                false /* isWpa3SaeSupported */,
                false /* isWpa3SuiteBSupported */,
                false /* isEnhancedOpenSupported */);

        assertThat(filteredScans).isEmpty();
    }

    @Test
    public void testfilterScanResultsByCapabilities_keepsTransitionModeScans() {
        final ScanResult wpa3TransitionScan = new ScanResult();
        final ScanResult enhancedOpenTransitionScan = new ScanResult();
        wpa3TransitionScan.capabilities = "[PSK+SAE]";
        enhancedOpenTransitionScan.capabilities = "[OWE_TRANSITION]";

        final List<ScanResult> filteredScans = filterScanResultsByCapabilities(
                Arrays.asList(wpa3TransitionScan, enhancedOpenTransitionScan),
                false /* isWpa3SaeSupported */,
                false /* isWpa3SuiteBSupported */,
                false /* isEnhancedOpenSupported */);

        assertThat(filteredScans).containsExactly(wpa3TransitionScan, enhancedOpenTransitionScan);
    }
}
