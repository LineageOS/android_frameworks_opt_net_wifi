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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.fail;

import android.net.wifi.ScanResult;
import android.os.SystemClock;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ScanResultUpdaterTest {
    private static final String BSSID_1 = "11:11:11:11:11:11";
    private static final String BSSID_2 = "22:22:22:22:22:22";
    private static final String BSSID_3 = "33:33:33:33:33:33";

    private static ScanResult buildScanResult(String bssid, long timestampMs) {
        return new ScanResult(
                null,
                bssid,
                0, // hessid
                0, //anqpDomainId
                null, // osuProviders
                "", // capabilities
                0,
                0, // frequency
                timestampMs /* microsecond timestamp */);
    }

    /**
     * Verify that scan results of the same BSSID are merged to latest one.
     */
    @Test
    public void testGetScanResults_mergeSameBssid() {
        ScanResult oldResult = buildScanResult(
                BSSID_1, 10);
        ScanResult newResult = buildScanResult(
                BSSID_1, 20);

        // Add initial scan result. List should have 1 scan.
        ScanResultUpdater sru = new ScanResultUpdater();
        sru.update(Arrays.asList(oldResult));
        assertThat(sru.getScanResults().size(), equalTo(1));
        assertThat(sru.getScanResults().get(0), equalTo(oldResult));

        // Add new scan result. Old scan result should be replaced.
        sru.update(Arrays.asList(newResult));
        assertThat(sru.getScanResults().size(), equalTo(1));
        assertThat(sru.getScanResults().get(0), equalTo(newResult));

        // Add old scan result back. New scan result should still remain.
        sru.update(Arrays.asList(oldResult));
        assertThat(sru.getScanResults().size(), equalTo(1));
        assertThat(sru.getScanResults().get(0), equalTo(newResult));
    }

    /**
     * Verify that scan results are filtered out by age.
     */
    @Test
    public void testGetScanResults_filtersOldScans() {
        long now = SystemClock.elapsedRealtime();
        long maxScanAge = 15_000;

        ScanResult oldResult = buildScanResult(
                BSSID_1, now - (maxScanAge + 1));
        ScanResult newResult = buildScanResult(
                BSSID_2, now);

        // Add a new scan result and an out-of-date scan result.
        ScanResultUpdater sru = new ScanResultUpdater();
        sru.update(Arrays.asList(newResult, oldResult));

        // New scan result should remain and out-of-date scan result should not be returned.
        assertThat(sru.getScanResults(maxScanAge).size(), equalTo(1));
        assertThat(sru.getScanResults(maxScanAge).get(0), equalTo(newResult));
    }

    /**
     * Verify that an exception is thrown if the getScanResults max scan age is larger than the
     * constructor's max scan age.
     */
    @Test
    public void testGetScanResults_invalidMaxScanAgeMillis_throwsException() {
        ScanResultUpdater sru = new ScanResultUpdater(15_000);
        try {
            sru.getScanResults(20_000);
            fail("Should have thrown exception for maxScanAgeMillis too large.");
        } catch (IllegalArgumentException ok) {
            // Expected
        }
    }

    /**
     * Verify that the constructor max scan age is obeyed when getting scan results.
     */
    @Test
    public void testConstructor_maxScanAge_filtersOldScans() {
        ScanResultUpdater sru = new ScanResultUpdater(15_000);
        long now = SystemClock.elapsedRealtime();

        ScanResult scan1 = buildScanResult(BSSID_1, now - 10_000);
        ScanResult scan2 = buildScanResult(BSSID_2, now - 12_000);
        ScanResult scan3 = buildScanResult(BSSID_3, now - 20_000);

        sru.update(Arrays.asList(scan1, scan2, scan3));

        List<ScanResult> scanResults = sru.getScanResults();

        assertThat(scanResults.size(), equalTo(2));
        assertThat(scanResults, contains(scan1, scan2));
    }

    /**
     * Verify that getScanResults returns results aged by the passed in max scan age even if there
     * is a max scan age set by the constructor.
     */
    @Test
    public void testGetScanResults_overridesConstructorMaxScanAge() {
        ScanResultUpdater sru = new ScanResultUpdater(15_000);
        long now = SystemClock.elapsedRealtime();

        ScanResult scan1 = buildScanResult(BSSID_1, now - 10_000);
        ScanResult scan2 = buildScanResult(BSSID_2, now - 12_000);
        ScanResult scan3 = buildScanResult(BSSID_3, now - 20_000);

        sru.update(Arrays.asList(scan1, scan2, scan3));

        // Aged getScanResults should override the constructor max scan age.
        List<ScanResult> scanResults = sru.getScanResults(11_000);
        assertThat(scanResults.size(), equalTo(1));
        assertThat(scanResults, contains(scan1));

        // Non-aged getScanResults should revert to the constructor max scan age.
        scanResults = sru.getScanResults();
        assertThat(scanResults.size(), equalTo(2));
        assertThat(scanResults, contains(scan1, scan2));
    }
}
