/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_PSK;
import static com.android.server.wifi.WifiConfigurationTestUtil.generateWifiConfig;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiNetworkSuggestion;
import android.net.wifi.WifiSsid;
import android.util.LocalLog;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.NetworkSuggestionEvaluator}.
 */
@SmallTest
public class NetworkSuggestionEvaluatorTest {
    private static final int TEST_UID = 3555;
    private static final int TEST_UID_OTHER = 3545;
    private static final int TEST_NETWORK_ID = 55;

    private @Mock WifiConfigManager mWifiConfigManager;
    private @Mock WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    private @Mock Clock mClock;
    private NetworkSuggestionEvaluator mNetworkSuggestionEvaluator;

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mNetworkSuggestionEvaluator = new NetworkSuggestionEvaluator(
                mWifiNetworkSuggestionsManager, mWifiConfigManager, new LocalLog(100));
    }

    /**
     * Ensure that we ignore all scan results not matching the network suggestion.
     * Expected candidate: null
     * Expected connectable Networks: {}
     */
    @Test
    public void testSelectNetworkSuggestionForNoMatch() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-67, -76};
        String[] suggestionSsids = {};
        int[] securities = {};
        boolean[] appInteractions = {};
        boolean[] meteredness = {};
        int[] priorities = {};
        int[] uids = {};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        WifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids, securities,
                appInteractions, meteredness, priorities, uids);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        WifiConfiguration candidate = mNetworkSuggestionEvaluator.evaluateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration, int score) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });

        assertNull(candidate);
        assertTrue(connectableNetworks.isEmpty());
    }

    /**
     * Ensure that we select the only matching network suggestion.
     * Expected candidate: suggestionSsids[0]
     * Expected connectable Networks: {suggestionSsids[0]}
     */
    @Test
    public void testSelectNetworkSuggestionForOneMatch() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-67, -76};
        String[] suggestionSsids = {"\"" + scanSsids[0] + "\""};
        int[] securities = {SECURITY_PSK};
        boolean[] appInteractions = {true};
        boolean[] meteredness = {true};
        int[] priorities = {0};
        int[] uids = {TEST_UID};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        WifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids, securities,
                appInteractions, meteredness, priorities, uids);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);
        // setup config manager interactions.
        setupAddToWifiConfigManager(suggestions[0].wifiConfiguration);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        WifiConfiguration candidate = mNetworkSuggestionEvaluator.evaluateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration, int score) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });

        assertNotNull(candidate);
        assertEquals(suggestionSsids[0] , candidate.SSID);

        assertEquals(1, connectableNetworks.size());
        assertEquals(suggestionSsids[0], connectableNetworks.get(0).second.SSID);
        assertEquals(scanSsids[0], connectableNetworks.get(0).first.getScanResult().SSID);

        verifyAddToWifiConfigManager(suggestions[0].wifiConfiguration,
                scanDetails[0].getScanResult());
    }

    /**
     * Ensure that we select the network suggestion corresponding to the scan result with
     * highest RSSI.
     * Expected candidate: suggestionSsids[1]
     * Expected connectable Networks: {suggestionSsids[0], suggestionSsids[1]}
     */
    @Test
    public void testSelectNetworkSuggestionForMultipleMatch() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-56, -45};
        String[] suggestionSsids = {"\"" + scanSsids[0] + "\"", "\"" + scanSsids[1] + "\""};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};
        boolean[] appInteractions = {true, true};
        boolean[] meteredness = {true, true};
        int[] priorities = {0, 1};
        int[] uids = {TEST_UID, TEST_UID};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        WifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids, securities,
                appInteractions, meteredness, priorities, uids);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);
        // setup config manager interactions.
        setupAddToWifiConfigManager(suggestions[1].wifiConfiguration);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        WifiConfiguration candidate = mNetworkSuggestionEvaluator.evaluateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration, int score) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });

        assertNotNull(candidate);
        assertEquals(suggestionSsids[1] , candidate.SSID);

        assertEquals(2, connectableNetworks.size());
        assertEquals(suggestionSsids[0], connectableNetworks.get(0).second.SSID);
        assertEquals(suggestionSsids[1], connectableNetworks.get(1).second.SSID);
        assertEquals(scanSsids[0], connectableNetworks.get(0).first.getScanResult().SSID);
        assertEquals(scanSsids[1], connectableNetworks.get(1).first.getScanResult().SSID);

        verifyAddToWifiConfigManager(suggestions[1].wifiConfiguration,
                scanDetails[1].getScanResult());
    }

    /**
     * Ensure that we select the network suggestion corresponding to the scan result with
     * highest RSSI. The lower RSSI scan result has multiple matching suggestions
     * (should pick any one in the connectable networks).
     *
     * Expected candidate: suggestionSsids[0]
     * Expected connectable Networks: {suggestionSsids[0],
     *                                 (suggestionSsids[1] || suggestionSsids[2]}
     */
    @Test
    public void testSelectNetworkSuggestionForMultipleMatchWithMultipleSuggestions() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-23, -45};
        String[] suggestionSsids = {"\"" + scanSsids[0] + "\"", "\"" + scanSsids[1] + "\"",
                "\"" + scanSsids[1] + "\""};
        int[] securities = {SECURITY_PSK, SECURITY_PSK, SECURITY_PSK};
        boolean[] appInteractions = {true, true, false};
        boolean[] meteredness = {true, true, false};
        int[] priorities = {0, 1, 0};
        int[] uids = {TEST_UID, TEST_UID, TEST_UID_OTHER};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        WifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids, securities,
                appInteractions, meteredness, priorities, uids);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);
        // setup config manager interactions.
        setupAddToWifiConfigManager(suggestions[0].wifiConfiguration);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        WifiConfiguration candidate = mNetworkSuggestionEvaluator.evaluateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration, int score) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });

        assertNotNull(candidate);
        assertEquals(suggestionSsids[0] , candidate.SSID);

        assertEquals(2, connectableNetworks.size());
        assertEquals(suggestionSsids[0], connectableNetworks.get(0).second.SSID);
        assertTrue(suggestionSsids[1].equals(connectableNetworks.get(1).second.SSID)
                || suggestionSsids[2].equals(connectableNetworks.get(1).second.SSID));
        assertEquals(scanSsids[0], connectableNetworks.get(0).first.getScanResult().SSID);
        assertEquals(scanSsids[1], connectableNetworks.get(1).first.getScanResult().SSID);

        verifyAddToWifiConfigManager(suggestions[0].wifiConfiguration,
                scanDetails[0].getScanResult());
    }

    /**
     * Ensure that we select the only matching network suggestion, but return null because
     * we failed the {@link WifiConfigManager} interactions.
     * Expected candidate: null.
     * Expected connectable Networks: {suggestionSsids[0], suggestionSsids[1]}
     */
    @Test
    public void testSelectNetworkSuggestionForOneMatchButFailToAddToWifiConfigManager() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-67, -76};
        String[] suggestionSsids = {"\"" + scanSsids[0] + "\""};
        int[] securities = {SECURITY_PSK};
        boolean[] appInteractions = {true};
        boolean[] meteredness = {true};
        int[] priorities = {0};
        int[] uids = {TEST_UID};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        WifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids, securities,
                appInteractions, meteredness, priorities, uids);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);
        // Fail add to WifiConfigManager
        when(mWifiConfigManager.addOrUpdateNetwork(any(), anyInt()))
                .thenReturn(new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID));

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        WifiConfiguration candidate = mNetworkSuggestionEvaluator.evaluateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration, int score) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });

        assertNull(candidate);

        // Connectable networks should still be populated.
        assertEquals(1, connectableNetworks.size());
        assertEquals(suggestionSsids[0], connectableNetworks.get(0).second.SSID);
        assertEquals(scanSsids[0], connectableNetworks.get(0).first.getScanResult().SSID);

        verify(mWifiConfigManager).addOrUpdateNetwork(any(), anyInt());
    }

    /**
     * Ensure that we select the only matching network suggestion, but that matches an existing
     * saved network.
     * Expected candidate: suggestionSsids[0]
     * Expected connectable Networks: {suggestionSsids[0]}
     */
    @Test
    public void testSelectNetworkSuggestionForOneMatchForExistingSavedNetwork() {
        String[] scanSsids = {"test1", "test2"};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-67, -76};
        String[] suggestionSsids = {"\"" + scanSsids[0] + "\""};
        int[] securities = {SECURITY_PSK};
        boolean[] appInteractions = {true};
        boolean[] meteredness = {true};
        int[] priorities = {0};
        int[] uids = {TEST_UID};

        ScanDetail[] scanDetails =
                buildScanDetails(scanSsids, bssids, freqs, caps, levels, mClock);
        WifiNetworkSuggestion[] suggestions = buildNetworkSuggestions(suggestionSsids, securities,
                appInteractions, meteredness, priorities, uids);
        // Link the scan result with suggestions.
        linkScanDetailsWithNetworkSuggestions(scanDetails, suggestions);
        // Existing saved network matching the credentials.
        when(mWifiConfigManager.getConfiguredNetwork(suggestions[0].wifiConfiguration.configKey()))
                .thenReturn(suggestions[0].wifiConfiguration);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        WifiConfiguration candidate = mNetworkSuggestionEvaluator.evaluateNetworks(
                Arrays.asList(scanDetails), null, null, true, false,
                (ScanDetail scanDetail, WifiConfiguration configuration, int score) -> {
                    connectableNetworks.add(Pair.create(scanDetail, configuration));
                });

        assertNotNull(candidate);
        assertEquals(suggestionSsids[0] , candidate.SSID);

        assertEquals(1, connectableNetworks.size());
        assertEquals(suggestionSsids[0], connectableNetworks.get(0).second.SSID);
        assertEquals(scanSsids[0], connectableNetworks.get(0).first.getScanResult().SSID);

        // check for any saved networks.
        verify(mWifiConfigManager).getConfiguredNetwork(candidate.configKey());
        // Verify we did not try to add any new networks or other interactions with
        // WifiConfigManager.
        verifyNoMoreInteractions(mWifiConfigManager);
    }

    private void setupAddToWifiConfigManager(WifiConfiguration candidate) {
        // setup & verify the WifiConfigmanager interactions for adding/enabling the network.
        when(mWifiConfigManager.addOrUpdateNetwork(any(), anyInt()))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.updateNetworkSelectionStatus(eq(TEST_NETWORK_ID), anyInt()))
                .thenReturn(true);
        candidate.networkId  = TEST_NETWORK_ID;
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID))
                .thenReturn(candidate);
    }

    private void verifyAddToWifiConfigManager(WifiConfiguration candidate,
                                              ScanResult scanResultCandidate) {
        // check for any saved networks.
        verify(mWifiConfigManager).getConfiguredNetwork(candidate.configKey());

        ArgumentCaptor<WifiConfiguration> wifiConfigurationCaptor =
                ArgumentCaptor.forClass(WifiConfiguration.class);
        verify(mWifiConfigManager).addOrUpdateNetwork(wifiConfigurationCaptor.capture(), anyInt());
        WifiConfiguration addedWifiConfiguration = wifiConfigurationCaptor.getValue();
        assertNotNull(addedWifiConfiguration);
        assertTrue(addedWifiConfiguration.ephemeral);
        assertEquals(candidate.SSID, addedWifiConfiguration.SSID);

        verify(mWifiConfigManager).updateNetworkSelectionStatus(eq(TEST_NETWORK_ID), anyInt());

        ArgumentCaptor<ScanResult> scanResultCaptor =
                ArgumentCaptor.forClass(ScanResult.class);
        verify(mWifiConfigManager).setNetworkCandidateScanResult(
                eq(TEST_NETWORK_ID), scanResultCaptor.capture(), anyInt());
        ScanResult setScanResult = scanResultCaptor.getValue();
        assertNotNull(setScanResult);
        assertEquals(scanResultCandidate.SSID, setScanResult.SSID);

        verify(mWifiConfigManager).getConfiguredNetwork(eq(TEST_NETWORK_ID));
    }

    /**
     * Build an array of scanDetails based on the caller supplied network SSID, BSSID,
     * frequency, capability and RSSI level information.
     */
    public static ScanDetail[] buildScanDetails(String[] ssids, String[] bssids, int[] freqs,
                                                    String[] caps, int[] levels, Clock clock) {
        if (ssids == null || ssids.length == 0) return new ScanDetail[0];

        ScanDetail[] scanDetails = new ScanDetail[ssids.length];
        long timeStamp = clock.getElapsedSinceBootMillis();
        for (int index = 0; index < ssids.length; index++) {
            scanDetails[index] = new ScanDetail(WifiSsid.createFromAsciiEncoded(ssids[index]),
                    bssids[index], caps[index], levels[index], freqs[index], timeStamp, 0);
        }
        return scanDetails;
    }

    /**
     * Generate an array of {@link android.net.wifi.WifiConfiguration} based on the caller
     * supplied network SSID and security information.
     */
    public static WifiConfiguration[] buildWifiConfigurations(String[] ssids, int[] securities) {
        if (ssids == null || ssids.length == 0) return new WifiConfiguration[0];

        WifiConfiguration[] configs = new WifiConfiguration[ssids.length];
        for (int index = 0; index < ssids.length; index++) {
            configs[index] = generateWifiConfig(-1, 0, ssids[index], false, true, null,
                    null, securities[index]);
        }
        return configs;
    }

    private WifiNetworkSuggestion[] buildNetworkSuggestions(
            String[] ssids, int[] securities, boolean[] appInteractions, boolean[] meteredness,
            int[] priorities, int[] uids) {
        WifiConfiguration[] configs = buildWifiConfigurations(ssids, securities);
        WifiNetworkSuggestion[] suggestions = new WifiNetworkSuggestion[configs.length];
        for (int i = 0; i < configs.length; i++) {
            configs[i].priority = priorities[i];
            configs[i].meteredOverride = meteredness[i]
                    ? WifiConfiguration.METERED_OVERRIDE_METERED
                    : WifiConfiguration.METERED_OVERRIDE_NONE;
            suggestions[i] = new WifiNetworkSuggestion(configs[i], appInteractions[i],
                    false, uids[i]);
        }
        return suggestions;
    }

    /**
     * Link scan results to the network suggestions.
     *
     * The shorter of the 2 input params will be used to loop over so the inputs don't
     * need to be of equal length.
     * If there are more scan details than suggestions, the remaining
     * scan details will be associated with a NULL suggestions.
     * If there are more suggestions than scan details, the remaining
     * suggestions will be associated with the last scan detail.
     */
    private void linkScanDetailsWithNetworkSuggestions(
            ScanDetail[] scanDetails, WifiNetworkSuggestion[] suggestions) {
        if (suggestions == null || scanDetails == null) {
            return;
        }
        int minLength = Math.min(scanDetails.length, suggestions.length);

        // 1 to 1 mapping from scan detail to suggestion.
        for (int i = 0; i < minLength; i++) {
            ScanDetail scanDetail = scanDetails[i];
            final WifiNetworkSuggestion matchingSuggestion = suggestions[i];
            HashSet<WifiNetworkSuggestion> matchingSuggestions =
                    new HashSet<WifiNetworkSuggestion>() {{
                        add(matchingSuggestion);
                    }};
            when(mWifiNetworkSuggestionsManager.getNetworkSuggestionsForScanDetail(eq(scanDetail)))
                    .thenReturn((matchingSuggestions));
        }
        if (scanDetails.length > suggestions.length) {
            // No match for the remaining scan details.
            for (int i = minLength; i < scanDetails.length; i++) {
                ScanDetail scanDetail = scanDetails[i];
                when(mWifiNetworkSuggestionsManager.getNetworkSuggestionsForScanDetail(
                        eq(scanDetail))).thenReturn(null);
            }
        } else if (suggestions.length > scanDetails.length) {
            // All the additional suggestions match the last scan detail.
            HashSet<WifiNetworkSuggestion> matchingSuggestions = new HashSet<>();
            for (int i = minLength; i < suggestions.length; i++) {
                matchingSuggestions.add(suggestions[i]);
            }
            ScanDetail lastScanDetail = scanDetails[minLength - 1];
            when(mWifiNetworkSuggestionsManager.getNetworkSuggestionsForScanDetail(
                    eq(lastScanDetail))).thenReturn((matchingSuggestions));
        }
    }
}
