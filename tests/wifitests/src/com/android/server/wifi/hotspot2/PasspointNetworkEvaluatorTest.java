/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.hotspot2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.LocalLog;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.CarrierNetworkConfig;
import com.android.server.wifi.NetworkUpdateResult;
import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.WifiConfigManager;
import com.android.server.wifi.WifiConfigurationTestUtil;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.WifiNetworkSelector.NetworkEvaluator.OnConnectableListener;
import com.android.server.wifi.util.ScanResultUtil;
import com.android.server.wifi.util.TelephonyUtil;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.PasspointNetworkEvaluator}.
 */
@SmallTest
public class PasspointNetworkEvaluatorTest {
    // TODO(b/140763176): should extend WifiBaseTest, but if it does then it fails with NPE
    private static final int TEST_NETWORK_ID = 1;
    private static final String TEST_SSID1 = "ssid1";
    private static final String TEST_SSID2 = "ssid2";
    private static final String TEST_BSSID1 = "01:23:45:56:78:9a";
    private static final String TEST_BSSID2 = "23:12:34:90:81:12";
    private static final String TEST_FQDN1 = "test1.com";
    private static final String TEST_FQDN2 = "test2.com";
    private static final WifiConfiguration TEST_CONFIG1 = generateWifiConfig(TEST_FQDN1);
    private static final WifiConfiguration TEST_CONFIG2 = generateWifiConfig(TEST_FQDN2);
    private static final PasspointProvider TEST_PROVIDER1 = generateProvider(TEST_CONFIG1);
    private static final PasspointProvider TEST_PROVIDER2 = generateProvider(TEST_CONFIG2);
    private ArgumentCaptor<WifiConfiguration> mWifiConfigurationArgumentCaptor =
            ArgumentCaptor.forClass(WifiConfiguration.class);

    @Mock PasspointManager mPasspointManager;
    @Mock PasspointConfiguration mPasspointConfiguration;
    @Mock WifiConfigManager mWifiConfigManager;
    @Mock OnConnectableListener mOnConnectableListener;
    @Mock SubscriptionManager mSubscriptionManager;
    @Mock CarrierNetworkConfig mCarrierNetworkConfig;
    @Mock WifiInjector mWifiInjector;
    LocalLog mLocalLog;
    PasspointNetworkEvaluator mEvaluator;

    /**
     * Helper function for generating {@link WifiConfiguration} for testing.
     *
     * @param fqdn The FQDN associated with the configuration
     * @return {@link WifiConfiguration}
     */
    private static WifiConfiguration generateWifiConfig(String fqdn) {
        WifiConfiguration config = new WifiConfiguration();
        config.FQDN = fqdn;
        return config;
    }

    /**
     * Helper function for generating {@link PasspointProvider} for testing.
     *
     * @param config The WifiConfiguration associated with the provider
     * @return {@link PasspointProvider}
     */
    private static PasspointProvider generateProvider(WifiConfiguration config) {
        PasspointProvider provider = mock(PasspointProvider.class);
        PasspointConfiguration passpointConfig = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(config.FQDN);
        passpointConfig.setHomeSp(homeSp);
        when(provider.getConfig()).thenReturn(passpointConfig);
        when(provider.getWifiConfig()).thenReturn(config);
        return provider;
    }

    /**
     * Helper function for generating {@link ScanDetail} for testing.
     *
     * @param ssid The SSID associated with the scan
     * @param bssid The BSSID associated with the scan
     * @return {@link ScanDetail}
     */
    private static ScanDetail generateScanDetail(String ssid, String bssid) {
        NetworkDetail networkDetail = mock(NetworkDetail.class);
        when(networkDetail.isInterworking()).thenReturn(true);
        when(networkDetail.getAnt()).thenReturn(NetworkDetail.Ant.FreePublic);

        ScanDetail scanDetail = mock(ScanDetail.class);
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = ssid;
        scanResult.BSSID = bssid;
        when(scanDetail.getSSID()).thenReturn(ssid);
        when(scanDetail.getBSSIDString()).thenReturn(bssid);
        when(scanDetail.getScanResult()).thenReturn(scanResult);
        when(scanDetail.getNetworkDetail()).thenReturn(networkDetail);
        return scanDetail;
    }

    /**
     * Test setup.
     */
    @Before
    public void setUp() throws Exception {
        initMocks(this);
        mLocalLog = new LocalLog(512);
        mEvaluator = new PasspointNetworkEvaluator(mPasspointManager, mWifiConfigManager, mLocalLog,
                mWifiInjector, mSubscriptionManager);
        // SIM is present
        when(mSubscriptionManager.getActiveSubscriptionInfoList())
                .thenReturn(Arrays.asList(mock(SubscriptionInfo.class)));
    }

    /**
     * Verify that no candidate will be nominated when evaluating scans without any matching
     * providers.
     */
    @Test
    public void evaluateScansWithNoMatch() {
        List<ScanDetail> scanDetails = Arrays.asList(new ScanDetail[] {
                generateScanDetail(TEST_SSID1, TEST_BSSID1),
                generateScanDetail(TEST_SSID2, TEST_BSSID2)});
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(null);
        mEvaluator.evaluateNetworks(
                scanDetails, null, null, false, false, mOnConnectableListener);
        verify(mOnConnectableListener, never()).onConnectable(any(), any());
    }

    /**
     * Verify that provider matching will not be performed when evaluating scans with no
     * interworking support, verify that no candidate will be nominated.
     *
     * @throws Exception
     */
    @Test
    public void evaulateScansWithNoInterworkingAP() throws Exception {
        NetworkDetail networkDetail = mock(NetworkDetail.class);
        when(networkDetail.isInterworking()).thenReturn(false);
        ScanDetail scanDetail = mock(ScanDetail.class);
        when(scanDetail.getNetworkDetail()).thenReturn(networkDetail);

        List<ScanDetail> scanDetails = Arrays.asList(new ScanDetail[] {scanDetail});
        mEvaluator.evaluateNetworks(
                scanDetails, null, null, false, false, mOnConnectableListener);
        verify(mOnConnectableListener, never()).onConnectable(any(), any());
        // Verify that no provider matching is performed.
        verify(mPasspointManager, never()).matchProvider(any(ScanResult.class));
    }

    /**
     * Verify that when a network matches a home provider is found, the correct network
     * information (WifiConfiguration) is setup and nominated.
     *
     * @throws Exception
     */
    @Test
    public void evaluateScansWithNetworkMatchingHomeProvider() throws Exception {
        List<ScanDetail> scanDetails = Arrays.asList(new ScanDetail[] {
                generateScanDetail(TEST_SSID1, TEST_BSSID1),
                generateScanDetail(TEST_SSID2, TEST_BSSID2)});

        // Setup matching providers for ScanDetail with TEST_SSID1.
        Pair<PasspointProvider, PasspointMatch> homeProvider = Pair.create(
                TEST_PROVIDER1, PasspointMatch.HomeProvider);

        // Return homeProvider for the first ScanDetail (TEST_SSID1) and a null (no match) for
        // for the second (TEST_SSID2);
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider)
                .thenReturn(null);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt()))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        mEvaluator.evaluateNetworks(scanDetails, null, null, false,
                false, mOnConnectableListener);
        verify(mOnConnectableListener).onConnectable(any(), any());

        // Verify the content of the WifiConfiguration that was added to WifiConfigManager.
        ArgumentCaptor<WifiConfiguration> addedConfig =
                ArgumentCaptor.forClass(WifiConfiguration.class);
        verify(mWifiConfigManager).addOrUpdateNetwork(addedConfig.capture(), anyInt());
        assertEquals(ScanResultUtil.createQuotedSSID(TEST_SSID1), addedConfig.getValue().SSID);
        assertEquals(TEST_FQDN1, addedConfig.getValue().FQDN);
        assertNotNull(addedConfig.getValue().enterpriseConfig);
        assertEquals("", addedConfig.getValue().enterpriseConfig.getAnonymousIdentity());
        assertTrue(addedConfig.getValue().isHomeProviderNetwork);
        verify(mWifiConfigManager).enableNetwork(
                eq(TEST_NETWORK_ID), eq(false), anyInt(), any());
        verify(mWifiConfigManager).setNetworkCandidateScanResult(
                eq(TEST_NETWORK_ID), any(ScanResult.class), anyInt());
        verify(mWifiConfigManager).updateScanDetailForNetwork(
                eq(TEST_NETWORK_ID), any(ScanDetail.class));
    }

    /**
     * Verify that when a network matches a roaming provider is found, the correct network
     * information (WifiConfiguration) is setup and nominated.
     */
    @Test
    public void evaluateScansWithNetworkMatchingRoamingProvider() {
        List<ScanDetail> scanDetails = Arrays.asList(new ScanDetail[] {
                generateScanDetail(TEST_SSID1, TEST_BSSID1),
                generateScanDetail(TEST_SSID2, TEST_BSSID2)});

        // Setup matching providers for ScanDetail with TEST_SSID1.
        Pair<PasspointProvider, PasspointMatch> roamingProvider = Pair.create(
                TEST_PROVIDER1, PasspointMatch.RoamingProvider);

        // Return roamingProvider for the first ScanDetail (TEST_SSID1) and a null (no match) for
        // for the second (TEST_SSID2);
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(roamingProvider)
                .thenReturn(null);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt()))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        mEvaluator.evaluateNetworks(scanDetails, null, null, false,
                false, mOnConnectableListener);
        verify(mOnConnectableListener).onConnectable(any(), any());

        // Verify the content of the WifiConfiguration that was added to WifiConfigManager.
        ArgumentCaptor<WifiConfiguration> addedConfig =
                ArgumentCaptor.forClass(WifiConfiguration.class);
        verify(mWifiConfigManager).addOrUpdateNetwork(addedConfig.capture(), anyInt());
        assertEquals(ScanResultUtil.createQuotedSSID(TEST_SSID1), addedConfig.getValue().SSID);
        assertEquals(TEST_FQDN1, addedConfig.getValue().FQDN);
        assertNotNull(addedConfig.getValue().enterpriseConfig);
        assertEquals("", addedConfig.getValue().enterpriseConfig.getAnonymousIdentity());
        assertFalse(addedConfig.getValue().isHomeProviderNetwork);
        verify(mWifiConfigManager).enableNetwork(
                eq(TEST_NETWORK_ID), eq(false), anyInt(), any());
        verify(mWifiConfigManager).setNetworkCandidateScanResult(
                eq(TEST_NETWORK_ID), any(ScanResult.class), anyInt());
        verify(mWifiConfigManager).updateScanDetailForNetwork(
                eq(TEST_NETWORK_ID), any(ScanDetail.class));
    }

    /**
     * Verify that when a network matches a roaming provider is found for different scanDetails,
     * will nominate both as the candidates.
     *
     * @throws Exception
     */
    @Test
    public void evaluateScansWithHomeProviderNewtorkAndRoamingProviderNetwork() throws Exception {
        List<ScanDetail> scanDetails = Arrays.asList(new ScanDetail[] {
                generateScanDetail(TEST_SSID1, TEST_BSSID1),
                generateScanDetail(TEST_SSID2, TEST_BSSID2)});

        // Setup matching providers for ScanDetail with TEST_SSID1.
        Pair<PasspointProvider, PasspointMatch> homeProvider = Pair.create(
                TEST_PROVIDER1, PasspointMatch.HomeProvider);
        Pair<PasspointProvider, PasspointMatch> roamingProvider = Pair.create(
                TEST_PROVIDER2, PasspointMatch.RoamingProvider);

        // Return homeProvider for the first ScanDetail (TEST_SSID1) and
        // roamingProvider for the second (TEST_SSID2);
        when(mPasspointManager.matchProvider(any(ScanResult.class)))
                .thenReturn(homeProvider).thenReturn(roamingProvider);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt()))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID + 1));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID + 1))
                .thenReturn(TEST_CONFIG2);
        mEvaluator.evaluateNetworks(scanDetails, null, null, false,
                false, mOnConnectableListener);
        verify(mOnConnectableListener, times(2)).onConnectable(any(), any());

        verify(mWifiConfigManager, times(2))
                .addOrUpdateNetwork(any(), anyInt());
    }

    /**
     * Verify that anonymous identity is empty when matching a SIM credential provider with a
     * network that supports encrypted IMSI and anonymous identity. The anonymous identity will be
     * populated with {@code anonymous@<realm>} by ClientModeImpl's handling of the
     * CMD_START_CONNECT event.
     */
    @Test
    public void evaluateSIMProviderWithNetworkSupportingEncryptedIMSI() {
        // Setup ScanDetail and match providers.
        List<ScanDetail> scanDetails = Collections.singletonList(
                generateScanDetail(TEST_SSID1, TEST_BSSID1));
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE);
        config.networkId = TEST_NETWORK_ID;
        PasspointProvider testProvider = generateProvider(config);
        Pair<PasspointProvider, PasspointMatch> homeProvider = Pair.create(
                testProvider, PasspointMatch.HomeProvider);
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider);
        when(testProvider.isSimCredential()).thenReturn(true);
        // SIM is present
        when(mSubscriptionManager.getActiveSubscriptionInfoList())
                .thenReturn(Arrays.asList(mock(SubscriptionInfo.class)));
        when(mCarrierNetworkConfig.isCarrierEncryptionInfoAvailable()).thenReturn(true);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt()))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(config);

        mEvaluator.evaluateNetworks(scanDetails, null, null, false,
                false, mOnConnectableListener);
        verify(mOnConnectableListener).onConnectable(any(),
                mWifiConfigurationArgumentCaptor.capture());


        assertEquals("", mWifiConfigurationArgumentCaptor.getValue()
                .enterpriseConfig.getAnonymousIdentity());
        assertTrue(TelephonyUtil.isSimEapMethod(
                mWifiConfigurationArgumentCaptor.getValue().enterpriseConfig.getEapMethod()));
    }

    /**
     * Verify that when the current active network is matched, the scan info associated with
     * the network is updated.
     */
    @Test
    public void evaluateScansMatchingActiveNetworkWithDifferentBSS() {
        List<ScanDetail> scanDetails = Arrays.asList(new ScanDetail[] {
                generateScanDetail(TEST_SSID1, TEST_BSSID2)});
        // Setup matching provider.
        Pair<PasspointProvider, PasspointMatch> homeProvider = Pair.create(
                TEST_PROVIDER1, PasspointMatch.HomeProvider);

        // Setup currently connected network.
        WifiConfiguration currentNetwork = new WifiConfiguration();
        currentNetwork.networkId = TEST_NETWORK_ID;
        currentNetwork.SSID = ScanResultUtil.createQuotedSSID(TEST_SSID1);
        String currentBssid = TEST_BSSID1;

        // Match the current connected network to a home provider.
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt()))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(currentNetwork);

        mEvaluator.evaluateNetworks(scanDetails, currentNetwork,
                currentBssid, true, false, mOnConnectableListener);

        verify(mOnConnectableListener).onConnectable(any(), any());

        // Verify network candidate information is updated.
        ArgumentCaptor<ScanResult> updatedCandidateScanResult =
                ArgumentCaptor.forClass(ScanResult.class);
        verify(mWifiConfigManager).setNetworkCandidateScanResult(eq(TEST_NETWORK_ID),
                updatedCandidateScanResult.capture(), anyInt());
        assertEquals(TEST_BSSID2, updatedCandidateScanResult.getValue().BSSID);
        ArgumentCaptor<ScanDetail> updatedCandidateScanDetail =
                ArgumentCaptor.forClass(ScanDetail.class);
        verify(mWifiConfigManager).updateScanDetailForNetwork(eq(TEST_NETWORK_ID),
                updatedCandidateScanDetail.capture());
        assertEquals(TEST_BSSID2, updatedCandidateScanDetail.getValue().getBSSIDString());
    }

    /**
     * Verify that the current configuration for the passpoint network is disabled, it will not
     * nominated that network.
     */
    @Test
    public void evaluateNetworkWithDisabledWifiConfig() {
        List<ScanDetail> scanDetails = Arrays.asList(new ScanDetail[]{
                generateScanDetail(TEST_SSID1, TEST_BSSID1),
                generateScanDetail(TEST_SSID2, TEST_BSSID2)});

        WifiConfiguration disableConfig = new WifiConfiguration();
        WifiConfiguration.NetworkSelectionStatus selectionStatus =
                new WifiConfiguration.NetworkSelectionStatus();
        selectionStatus.setNetworkSelectionDisableReason(
                WifiConfiguration.NetworkSelectionStatus.DISABLED_DHCP_FAILURE);
        selectionStatus.setNetworkSelectionStatus(
                WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_TEMPORARY_DISABLED);
        disableConfig.setNetworkSelectionStatus(selectionStatus);
        disableConfig.networkId = TEST_NETWORK_ID;
        TEST_CONFIG1.networkId = TEST_NETWORK_ID;

        // Setup matching providers for ScanDetail with TEST_SSID1.
        Pair<PasspointProvider, PasspointMatch> homeProvider = Pair.create(
                TEST_PROVIDER1, PasspointMatch.HomeProvider);

        // Return homeProvider for the first ScanDetail (TEST_SSID1) and a null (no match) for
        // for the second (TEST_SSID2);
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider)
                .thenReturn(null);
        when(mWifiConfigManager.getConfiguredNetwork(anyString())).thenReturn(disableConfig);

        mEvaluator.evaluateNetworks(scanDetails, null, null, false,
                false, mOnConnectableListener);
        verify(mWifiConfigManager, never()).addOrUpdateNetwork(any(WifiConfiguration.class),
                anyInt());
        verify(mOnConnectableListener, never()).onConnectable(any(), any());
    }

    /**
     * Verify that when a network matching a home provider is found, but the network was
     * disconnected previously by user, it will not nominated that network.
     */
    @Test
    public void evaluateScanResultWithHomeMatchButPreviouslyUserDisconnected() {
        List<ScanDetail> scanDetails = Arrays.asList(new ScanDetail[]{
                generateScanDetail(TEST_SSID1, TEST_BSSID1)});

        // Setup matching providers for ScanDetail with TEST_SSID1.
        Pair<PasspointProvider, PasspointMatch> homeProvider = Pair.create(
                TEST_PROVIDER1, PasspointMatch.HomeProvider);

        // Return homeProvider for the first ScanDetail (TEST_SSID1).
        when(mPasspointManager.matchProvider(any(ScanResult.class))).thenReturn(homeProvider);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt()))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        when(mWifiConfigManager.wasEphemeralNetworkDeleted("\"" + TEST_SSID1 + "\""))
                .thenReturn(true);
        mEvaluator.evaluateNetworks(
                scanDetails, null, null, false, false, mOnConnectableListener);
        verify(mOnConnectableListener, never()).onConnectable(any(), any());
    }
}
