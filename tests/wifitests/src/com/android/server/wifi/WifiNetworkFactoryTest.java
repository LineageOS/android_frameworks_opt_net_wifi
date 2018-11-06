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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE;

import static com.android.server.wifi.WifiNetworkFactory.PERIODIC_SCAN_INTERVAL_MS;
import static com.android.server.wifi.util.NativeUtil.addEnclosingQuotes;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AlarmManager.OnAlarmListener;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.MacAddress;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.INetworkRequestMatchCallback;
import android.net.wifi.INetworkRequestUserSelectionCallback;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.ScanListener;
import android.net.wifi.WifiScanner.ScanSettings;
import android.os.IBinder;
import android.os.PatternMatcher;
import android.os.WorkSource;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Pair;

import com.android.server.wifi.util.WifiPermissionsUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.WifiNetworkFactory}.
 */
@SmallTest
public class WifiNetworkFactoryTest {
    private static final int TEST_UID_1 = 10423;
    private static final int TEST_UID_2 = 10424;
    private static final int TEST_CALLBACK_IDENTIFIER = 123;
    private static final String TEST_PACKAGE_NAME_1 = "com.test.networkrequest.1";
    private static final String TEST_PACKAGE_NAME_2 = "com.test.networkrequest.2";
    private static final String TEST_SSID_1 = "test1234";
    private static final String TEST_SSID_2 = "test12345678";
    private static final String TEST_SSID_3 = "abc1234";
    private static final String TEST_SSID_4 = "abc12345678";
    private static final String TEST_BSSID_1 = "12:34:23:23:45:ac";
    private static final String TEST_BSSID_2 = "12:34:23:32:12:67";
    private static final String TEST_BSSID_3 = "45:34:34:12:bb:dd";
    private static final String TEST_BSSID_4 = "45:34:34:56:ee:ff";
    private static final String TEST_BSSID_1_2_OUI = "12:34:23:00:00:00";
    private static final String TEST_BSSID_OUI_MASK = "ff:ff:ff:00:00:00";

    @Mock WifiConnectivityManager mWifiConnectivityManager;
    @Mock Context mContext;
    @Mock ActivityManager mActivityManager;
    @Mock AlarmManager mAlarmManager;
    @Mock Clock mClock;
    @Mock WifiInjector mWifiInjector;
    @Mock WifiPermissionsUtil mWifiPermissionsUtil;
    @Mock WifiScanner mWifiScanner;
    @Mock PackageManager mPackageManager;
    @Mock IBinder mAppBinder;
    @Mock INetworkRequestMatchCallback mNetworkRequestMatchCallback;
    NetworkCapabilities mNetworkCapabilities;
    TestLooper mLooper;
    NetworkRequest mNetworkRequest;
    WifiScanner.ScanData[] mTestScanDatas;
    ArgumentCaptor<ScanSettings> mScanSettingsArgumentCaptor =
            ArgumentCaptor.forClass(ScanSettings.class);
    ArgumentCaptor<WorkSource> mWorkSourceArgumentCaptor =
            ArgumentCaptor.forClass(WorkSource.class);

    private WifiNetworkFactory mWifiNetworkFactory;

    /**
     * Setup the mocks.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mLooper = new TestLooper();
        mNetworkCapabilities = new NetworkCapabilities();
        mNetworkCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        mTestScanDatas = ScanTestUtil.createScanDatas(new int[][]{ { 2417, 2427, 5180, 5170 } });

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getNameForUid(TEST_UID_1)).thenReturn(TEST_PACKAGE_NAME_1);
        when(mPackageManager.getNameForUid(TEST_UID_2)).thenReturn(TEST_PACKAGE_NAME_2);
        when(mActivityManager.getPackageImportance(TEST_PACKAGE_NAME_1))
                .thenReturn(IMPORTANCE_FOREGROUND_SERVICE);
        when(mActivityManager.getPackageImportance(TEST_PACKAGE_NAME_2))
                .thenReturn(IMPORTANCE_FOREGROUND_SERVICE);
        when(mWifiInjector.getWifiScanner()).thenReturn(mWifiScanner);

        mWifiNetworkFactory = new WifiNetworkFactory(mLooper.getLooper(), mContext,
                mNetworkCapabilities, mActivityManager, mAlarmManager, mClock, mWifiInjector,
                mWifiConnectivityManager, mWifiPermissionsUtil);

        mNetworkRequest = new NetworkRequest.Builder()
                .setCapabilities(mNetworkCapabilities)
                .build();
    }

    /**
     * Called after each test
     */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    /**
     * Validates handling of needNetworkFor.
     */
    @Test
    public void testHandleNetworkRequestWithNoSpecifier() {
        assertFalse(mWifiNetworkFactory.hasConnectionRequests());
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        // First network request should turn on auto-join.
        verify(mWifiConnectivityManager).setTrustedConnectionAllowed(true);
        assertTrue(mWifiNetworkFactory.hasConnectionRequests());

        // Subsequent ones should do nothing.
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);
        verifyNoMoreInteractions(mWifiConnectivityManager);
    }

    /**
     * Validates handling of releaseNetwork.
     */
    @Test
    public void testHandleNetworkReleaseWithNoSpecifier() {
        // Release network with out a corresponding request should be ignored.
        mWifiNetworkFactory.releaseNetworkFor(mNetworkRequest);
        assertFalse(mWifiNetworkFactory.hasConnectionRequests());

        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);
        assertTrue(mWifiNetworkFactory.hasConnectionRequests());
        verify(mWifiConnectivityManager).setTrustedConnectionAllowed(true);

        mWifiNetworkFactory.releaseNetworkFor(mNetworkRequest);
        assertFalse(mWifiNetworkFactory.hasConnectionRequests());
        verify(mWifiConnectivityManager).setTrustedConnectionAllowed(false);
    }

    /**
     * Validates handling of acceptNetwork for requests with no network specifier.
     */
    @Test
    public void testHandleAcceptNetworkRequestWithNoSpecifier() {
        assertTrue(mWifiNetworkFactory.acceptRequest(mNetworkRequest, 0));
    }

    /**
     * Validates handling of acceptNetwork with a network specifier from a non foreground
     * app/service.
     */
    @Test
    public void testHandleAcceptNetworkRequestFromNonFgAppOrSvcWithSpecifier() {
        when(mActivityManager.getPackageImportance(TEST_PACKAGE_NAME_1))
                .thenReturn(IMPORTANCE_FOREGROUND_SERVICE + 1);

        WifiNetworkSpecifier specifier = createWifiNetworkSpecifier(TEST_UID_1, false);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier);

        assertFalse(mWifiNetworkFactory.acceptRequest(mNetworkRequest, 0));
    }

    /**
     * Validates handling of acceptNetwork with a network specifier from a foreground
     * app.
     */
    @Test
    public void testHandleAcceptNetworkRequestFromFgAppWithSpecifier() {
        when(mActivityManager.getPackageImportance(TEST_PACKAGE_NAME_1))
                .thenReturn(IMPORTANCE_FOREGROUND);

        WifiNetworkSpecifier specifier = createWifiNetworkSpecifier(TEST_UID_1, false);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier);

        assertTrue(mWifiNetworkFactory.acceptRequest(mNetworkRequest, 0));
    }

    /**
     * Validates handling of acceptNetwork with a network specifier from apps holding
     * NETWORK_SETTINGS.
     */
    @Test
    public void testHandleAcceptNetworkRequestFromNetworkSettingAppWithSpecifier() {
        when(mActivityManager.getPackageImportance(TEST_PACKAGE_NAME_1))
                .thenReturn(IMPORTANCE_GONE);
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(TEST_UID_1))
                .thenReturn(true);

        WifiNetworkSpecifier specifier = createWifiNetworkSpecifier(TEST_UID_1, false);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier);

        assertTrue(mWifiNetworkFactory.acceptRequest(mNetworkRequest, 0));
    }

    /**
     * Validates handling of acceptNetwork with a network specifier from a foreground
     * app.
     */
    @Test
    public void testHandleAcceptNetworkRequestFromFgAppWithSpecifierWithPendingRequestFromFgSvc() {
        when(mActivityManager.getPackageImportance(TEST_PACKAGE_NAME_1))
                .thenReturn(IMPORTANCE_FOREGROUND_SERVICE);
        when(mActivityManager.getPackageImportance(TEST_PACKAGE_NAME_2))
                .thenReturn(IMPORTANCE_FOREGROUND);

        // Handle request 1.
        WifiNetworkSpecifier specifier1 = createWifiNetworkSpecifier(TEST_UID_1, false);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier1);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        // Make request 2 which will be accepted because a fg app request can
        // override a fg service request.
        WifiNetworkSpecifier specifier2 = createWifiNetworkSpecifier(TEST_UID_2, false);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier2);
        assertTrue(mWifiNetworkFactory.acceptRequest(mNetworkRequest, 0));
    }

    /**
     * Validates handling of acceptNetwork with a network specifier from a foreground
     * app.
     */
    @Test
    public void testHandleAcceptNetworkRequestFromFgSvcWithSpecifierWithPendingRequestFromFgSvc() {
        when(mActivityManager.getPackageImportance(TEST_PACKAGE_NAME_1))
                .thenReturn(IMPORTANCE_FOREGROUND_SERVICE);
        when(mActivityManager.getPackageImportance(TEST_PACKAGE_NAME_2))
                .thenReturn(IMPORTANCE_FOREGROUND_SERVICE);

        // Handle request 1.
        WifiNetworkSpecifier specifier1 = createWifiNetworkSpecifier(TEST_UID_1, false);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier1);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        // Make request 2 which will be accepted because a fg service request can
        // override an existing fg service request.
        WifiNetworkSpecifier specifier2 = createWifiNetworkSpecifier(TEST_UID_2, false);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier2);
        assertTrue(mWifiNetworkFactory.acceptRequest(mNetworkRequest, 0));
    }

    /**
     * Validates handling of acceptNetwork with a network specifier from a foreground
     * app.
     */
    @Test
    public void testHandleAcceptNetworkRequestFromFgAppWithSpecifierWithPendingRequestFromFgApp() {
        when(mActivityManager.getPackageImportance(TEST_PACKAGE_NAME_1))
                .thenReturn(IMPORTANCE_FOREGROUND);
        when(mActivityManager.getPackageImportance(TEST_PACKAGE_NAME_2))
                .thenReturn(IMPORTANCE_FOREGROUND);

        // Handle request 1.
        WifiNetworkSpecifier specifier1 = createWifiNetworkSpecifier(TEST_UID_1, false);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier1);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        // Make request 2 which will be accepted because a fg app request can
        // override an existing fg app request.
        WifiNetworkSpecifier specifier2 = createWifiNetworkSpecifier(TEST_UID_2, false);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier2);
        assertTrue(mWifiNetworkFactory.acceptRequest(mNetworkRequest, 0));
    }

    /**
     * Validates handling of acceptNetwork with a network specifier from a foreground
     * service when we're in the midst of processing a request from a foreground app.
     */
    @Test
    public void testHandleAcceptNetworkRequestFromFgSvcWithSpecifierWithPendingRequestFromFgApp() {
        when(mActivityManager.getPackageImportance(TEST_PACKAGE_NAME_1))
                .thenReturn(IMPORTANCE_FOREGROUND);
        when(mActivityManager.getPackageImportance(TEST_PACKAGE_NAME_2))
                .thenReturn(IMPORTANCE_FOREGROUND_SERVICE);

        // Handle request 1.
        WifiNetworkSpecifier specifier1 = createWifiNetworkSpecifier(TEST_UID_1, false);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier1);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        // Make request 2 which will be rejected because a fg service request cannot
        // override a fg app request.
        WifiNetworkSpecifier specifier2 = createWifiNetworkSpecifier(TEST_UID_2, false);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier2);
        assertFalse(mWifiNetworkFactory.acceptRequest(mNetworkRequest, 0));
    }

    /**
     * Verify handling of new network request with network specifier.
     */
    @Test
    public void testHandleNetworkRequestWithSpecifier() {
        WifiNetworkSpecifier specifier = createWifiNetworkSpecifier(TEST_UID_1, false);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        // Disable connectivity manager .
        verify(mWifiConnectivityManager).enable(false);
        verify(mWifiScanner).startScan(mScanSettingsArgumentCaptor.capture(), any(),
                mWorkSourceArgumentCaptor.capture());

        // Verify scan settings.
        ScanSettings scanSettings = mScanSettingsArgumentCaptor.getValue();
        assertNotNull(scanSettings);
        assertEquals(WifiScanner.WIFI_BAND_BOTH_WITH_DFS, scanSettings.band);
        assertEquals(WifiScanner.TYPE_HIGH_ACCURACY, scanSettings.type);
        assertEquals(WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN, scanSettings.reportEvents);
        assertNull(scanSettings.hiddenNetworks);
        WorkSource workSource = mWorkSourceArgumentCaptor.getValue();
        assertNotNull(workSource);
        assertEquals(TEST_UID_1, workSource.get(0));
    }

    /**
     * Verify handling of new network request with network specifier for a hidden network.
     */
    @Test
    public void testHandleNetworkRequestWithSpecifierForHiddenNetwork() {
        WifiNetworkSpecifier specifier = createWifiNetworkSpecifier(TEST_UID_1, true);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        // Disable connectivity manager .
        verify(mWifiConnectivityManager).enable(false);
        verify(mWifiScanner).startScan(mScanSettingsArgumentCaptor.capture(), any(),
                mWorkSourceArgumentCaptor.capture());

        // Verify scan settings.
        ScanSettings scanSettings = mScanSettingsArgumentCaptor.getValue();
        assertNotNull(scanSettings);
        assertEquals(WifiScanner.WIFI_BAND_BOTH_WITH_DFS, scanSettings.band);
        assertEquals(WifiScanner.TYPE_HIGH_ACCURACY, scanSettings.type);
        assertEquals(WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN, scanSettings.reportEvents);
        assertNotNull(scanSettings.hiddenNetworks);
        assertNotNull(scanSettings.hiddenNetworks[0]);
        assertEquals(scanSettings.hiddenNetworks[0].ssid,
                addEnclosingQuotes(specifier.ssidPatternMatcher.getPath()));
        WorkSource workSource = mWorkSourceArgumentCaptor.getValue();
        assertNotNull(workSource);
        assertEquals(TEST_UID_1, workSource.get(0));
    }

    /**
     * Verify handling of new network request with network specifier for a non-hidden network
     * after processing a previous hidden network requst.
     * Validates that the scan settings was properly reset between the 2 request
     * {@link ScanSettings#hiddenNetworks}
     */
    @Test
    public void testHandleNetworkRequestWithSpecifierAfterPreviousHiddenNetworkRequest() {
        testHandleNetworkRequestWithSpecifierForHiddenNetwork();
        mWifiNetworkFactory.releaseNetworkFor(mNetworkRequest);
        reset(mWifiScanner, mWifiConnectivityManager);
        testHandleNetworkRequestWithSpecifier();
    }

    /**
     * Verify handling of release of the active network request with network specifier.
     */
    @Test
    public void testHandleNetworkReleaseWithSpecifier() {
        // Make a generic request first to ensure that we re-enable auto-join after release.
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        WifiNetworkSpecifier specifier = createWifiNetworkSpecifier(TEST_UID_1, false);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier);

        // Make the network request with specifier.
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);
        // Disable connectivity manager .
        verify(mWifiConnectivityManager).enable(false);
        verify(mWifiScanner).startScan(any(), any(), any());

        // Release the network request.
        mWifiNetworkFactory.releaseNetworkFor(mNetworkRequest);
        // Re-enable connectivity manager .
        verify(mWifiConnectivityManager).enable(true);
    }

    /**
     * Verify the periodic scan to find a network matching the network specifier.
     * Simulates the case where the network is not found in any of the scan results.
     */
    @Test
    public void testPeriodicScanNetworkRequestWithSpecifier() {
        WifiNetworkSpecifier specifier = createWifiNetworkSpecifier(TEST_UID_1, false);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        verify(mWifiConnectivityManager).enable(false);
        verifyPeriodicScans(0,
                PERIODIC_SCAN_INTERVAL_MS,     // 10s
                PERIODIC_SCAN_INTERVAL_MS,     // 10s
                PERIODIC_SCAN_INTERVAL_MS,     // 10s
                PERIODIC_SCAN_INTERVAL_MS);    // 10s
    }

    /**
     * Verify the periodic scan back off to find a network matching the network specifier
     * is cancelled when the active network request is released.
     */
    @Test
    public void testPeriodicScanCancelOnReleaseNetworkRequestWithSpecifier() {
        WifiNetworkSpecifier specifier = createWifiNetworkSpecifier(TEST_UID_1, false);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        verify(mWifiConnectivityManager).enable(false);
        verifyPeriodicScans(0,
                PERIODIC_SCAN_INTERVAL_MS,     // 10s
                PERIODIC_SCAN_INTERVAL_MS);    // 10s

        mWifiNetworkFactory.releaseNetworkFor(mNetworkRequest);
        // Cancel the alarm set for the next scan.
        verify(mAlarmManager).cancel(any(OnAlarmListener.class));
    }

    /**
     * Verify callback registration/unregistration.
     */
    @Test
    public void testHandleCallbackRegistrationAndUnregistration() throws Exception {
        mWifiNetworkFactory.addCallback(mAppBinder, mNetworkRequestMatchCallback,
                TEST_CALLBACK_IDENTIFIER);

        //Ensure that we register the user selection callback using the newly registered callback.
        verify(mNetworkRequestMatchCallback).onUserSelectionCallbackRegistration(
                any(INetworkRequestUserSelectionCallback.class));

        // TBD: Need to hook up the matching logic to invoke these callbacks to actually
        // verify that they're in the database.
        mWifiNetworkFactory.removeCallback(TEST_CALLBACK_IDENTIFIER);
    }

    /**
     * Verify network specifier matching for a specifier containing a specific SSID match using
     * 4 WPA_PSK scan results, each with unique SSID.
     */
    @Test
    public void testNetworkSpecifierMatchSuccessUsingLiteralSsidMatch() throws Exception {
        mWifiNetworkFactory.addCallback(mAppBinder, mNetworkRequestMatchCallback,
                TEST_CALLBACK_IDENTIFIER);
        // Setup scan data for open networks.
        setupScanData(SCAN_RESULT_TYPE_WPA_PSK,
                TEST_SSID_1, TEST_SSID_2, TEST_SSID_3, TEST_SSID_4);

        // Setup network specifier for open networks.
        PatternMatcher ssidPatternMatch =
                new PatternMatcher(TEST_SSID_1, PatternMatcher.PATTERN_LITERAL);
        Pair<MacAddress, MacAddress> bssidPatternMatch =
                Pair.create(MacAddress.ALL_ZEROS_ADDRESS, MacAddress.ALL_ZEROS_ADDRESS);
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier(
                ssidPatternMatch, bssidPatternMatch, wifiConfiguration, TEST_UID_1);

        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        verify(mWifiConnectivityManager).enable(false);
        verifyPeriodicScans(0, PERIODIC_SCAN_INTERVAL_MS);

        ArgumentCaptor<List<WifiConfiguration>> matchedNetworksCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mNetworkRequestMatchCallback).onMatch(matchedNetworksCaptor.capture());

        assertNotNull(matchedNetworksCaptor.getValue());
        // We only expect 1 network match in this case.
        assertEquals(1, matchedNetworksCaptor.getValue().size());
        WifiConfiguration matchedNetwork = matchedNetworksCaptor.getValue().get(0);
        assertEquals("\"" + TEST_SSID_1 + "\"", matchedNetwork.SSID);
        assertEquals(TEST_BSSID_1, matchedNetwork.BSSID);
        assertTrue(matchedNetwork.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK));
    }

    /**
     * Verify network specifier matching for a specifier containing a Prefix SSID match using
     * 4 open scan results, each with unique SSID.
     */
    @Test
    public void testNetworkSpecifierMatchSuccessUsingPrefixSsidMatch() throws Exception {
        mWifiNetworkFactory.addCallback(mAppBinder, mNetworkRequestMatchCallback,
                TEST_CALLBACK_IDENTIFIER);
        // Setup scan data for open networks.
        setupScanData(SCAN_RESULT_TYPE_OPEN,
                TEST_SSID_1, TEST_SSID_2, TEST_SSID_3, TEST_SSID_4);

        // Setup network specifier for open networks.
        PatternMatcher ssidPatternMatch =
                new PatternMatcher(TEST_SSID_1, PatternMatcher.PATTERN_PREFIX);
        Pair<MacAddress, MacAddress> bssidPatternMatch =
                Pair.create(MacAddress.ALL_ZEROS_ADDRESS, MacAddress.ALL_ZEROS_ADDRESS);
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier(
                ssidPatternMatch, bssidPatternMatch, wifiConfiguration, TEST_UID_1);

        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        verify(mWifiConnectivityManager).enable(false);
        verifyPeriodicScans(0, PERIODIC_SCAN_INTERVAL_MS);

        ArgumentCaptor<List<WifiConfiguration>> matchedNetworksCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mNetworkRequestMatchCallback).onMatch(matchedNetworksCaptor.capture());

        assertNotNull(matchedNetworksCaptor.getValue());
        // We expect 2 network matches in this case.
        assertEquals(2, matchedNetworksCaptor.getValue().size());
        for (WifiConfiguration matchedNetwork : matchedNetworksCaptor.getValue()) {
            if (matchedNetwork.SSID.equals("\"" + TEST_SSID_1 + "\"")) {
                assertEquals(TEST_BSSID_1, matchedNetwork.BSSID);
                assertTrue(matchedNetwork.allowedKeyManagement
                        .get(WifiConfiguration.KeyMgmt.NONE));
            } else if (matchedNetwork.SSID.equals("\"" + TEST_SSID_2 + "\"")) {
                assertEquals(TEST_BSSID_2, matchedNetwork.BSSID);
                assertTrue(matchedNetwork.allowedKeyManagement
                        .get(WifiConfiguration.KeyMgmt.NONE));
            } else {
                fail();
            }
        }
    }

    /**
     * Verify network specifier matching for a specifier containing a specific BSSID match using
     * 4 WPA_PSK scan results, each with unique SSID.
     */
    @Test
    public void testNetworkSpecifierMatchSuccessUsingLiteralBssidMatch() throws Exception {
        mWifiNetworkFactory.addCallback(mAppBinder, mNetworkRequestMatchCallback,
                TEST_CALLBACK_IDENTIFIER);
        // Setup scan data for open networks.
        setupScanData(SCAN_RESULT_TYPE_WPA_PSK,
                TEST_SSID_1, TEST_SSID_2, TEST_SSID_3, TEST_SSID_4);

        // Setup network specifier for open networks.
        PatternMatcher ssidPatternMatch =
                new PatternMatcher(".*", PatternMatcher.PATTERN_SIMPLE_GLOB);
        Pair<MacAddress, MacAddress> bssidPatternMatch =
                Pair.create(MacAddress.fromString(TEST_BSSID_1), MacAddress.BROADCAST_ADDRESS);
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier(
                ssidPatternMatch, bssidPatternMatch, wifiConfiguration, TEST_UID_1);

        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        verify(mWifiConnectivityManager).enable(false);
        verifyPeriodicScans(0, PERIODIC_SCAN_INTERVAL_MS);

        ArgumentCaptor<List<WifiConfiguration>> matchedNetworksCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mNetworkRequestMatchCallback).onMatch(matchedNetworksCaptor.capture());

        assertNotNull(matchedNetworksCaptor.getValue());
        // We only expect 1 network match in this case.
        assertEquals(1, matchedNetworksCaptor.getValue().size());
        WifiConfiguration matchedNetwork = matchedNetworksCaptor.getValue().get(0);
        assertEquals("\"" + TEST_SSID_1 + "\"", matchedNetwork.SSID);
        assertEquals(TEST_BSSID_1, matchedNetwork.BSSID);
        assertTrue(matchedNetwork.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK));
    }

    /**
     * Verify network specifier matching for a specifier containing a prefix BSSID match using
     * 4 WPA_EAP scan results, each with unique SSID.
     */
    @Test
    public void testNetworkSpecifierMatchSuccessUsingOuiPrefixBssidMatch() throws Exception {
        mWifiNetworkFactory.addCallback(mAppBinder, mNetworkRequestMatchCallback,
                TEST_CALLBACK_IDENTIFIER);
        // Setup scan data for open networks.
        setupScanData(SCAN_RESULT_TYPE_WPA_EAP,
                TEST_SSID_1, TEST_SSID_2, TEST_SSID_3, TEST_SSID_4);

        // Setup network specifier for open networks.
        PatternMatcher ssidPatternMatch =
                new PatternMatcher(".*", PatternMatcher.PATTERN_SIMPLE_GLOB);
        Pair<MacAddress, MacAddress> bssidPatternMatch =
                Pair.create(MacAddress.fromString(TEST_BSSID_1_2_OUI),
                        MacAddress.fromString(TEST_BSSID_OUI_MASK));
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier(
                ssidPatternMatch, bssidPatternMatch, wifiConfiguration, TEST_UID_1);

        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        verify(mWifiConnectivityManager).enable(false);
        verifyPeriodicScans(0, PERIODIC_SCAN_INTERVAL_MS);

        ArgumentCaptor<List<WifiConfiguration>> matchedNetworksCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mNetworkRequestMatchCallback).onMatch(matchedNetworksCaptor.capture());

        assertNotNull(matchedNetworksCaptor.getValue());
        // We expect 2 network matches in this case.
        assertEquals(2, matchedNetworksCaptor.getValue().size());
        for (WifiConfiguration matchedNetwork : matchedNetworksCaptor.getValue()) {
            if (matchedNetwork.SSID.equals("\"" + TEST_SSID_1 + "\"")) {
                assertEquals(TEST_BSSID_1, matchedNetwork.BSSID);
                assertTrue(matchedNetwork.allowedKeyManagement
                        .get(WifiConfiguration.KeyMgmt.WPA_EAP));
            } else if (matchedNetwork.SSID.equals("\"" + TEST_SSID_2 + "\"")) {
                assertEquals(TEST_BSSID_2, matchedNetwork.BSSID);
                assertTrue(matchedNetwork.allowedKeyManagement
                        .get(WifiConfiguration.KeyMgmt.WPA_EAP));
            } else {
                fail();
            }
        }
    }

    /**
     * Verify network specifier matching for a specifier containing a specific SSID match using
     * 4 WPA_PSK scan results, 3 of which have the same SSID.
     */
    @Test
    public void testNetworkSpecifierMatchSuccessUsingLiteralSsidMatchWithMultipleBssidMatches()
            throws Exception {
        mWifiNetworkFactory.addCallback(mAppBinder, mNetworkRequestMatchCallback,
                TEST_CALLBACK_IDENTIFIER);
        // Setup scan data for open networks.
        setupScanData(SCAN_RESULT_TYPE_WPA_PSK,
                TEST_SSID_1, TEST_SSID_1, TEST_SSID_1, TEST_SSID_2);

        // Setup network specifier for open networks.
        PatternMatcher ssidPatternMatch =
                new PatternMatcher(TEST_SSID_1, PatternMatcher.PATTERN_LITERAL);
        Pair<MacAddress, MacAddress> bssidPatternMatch =
                Pair.create(MacAddress.ALL_ZEROS_ADDRESS, MacAddress.ALL_ZEROS_ADDRESS);
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier(
                ssidPatternMatch, bssidPatternMatch, wifiConfiguration, TEST_UID_1);

        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        verify(mWifiConnectivityManager).enable(false);
        verifyPeriodicScans(0, PERIODIC_SCAN_INTERVAL_MS);

        ArgumentCaptor<List<WifiConfiguration>> matchedNetworksCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mNetworkRequestMatchCallback).onMatch(matchedNetworksCaptor.capture());

        assertNotNull(matchedNetworksCaptor.getValue());
        // We still only expect 1 network match in this case.
        assertEquals(1, matchedNetworksCaptor.getValue().size());
        WifiConfiguration matchedNetwork = matchedNetworksCaptor.getValue().get(0);
        assertEquals("\"" + TEST_SSID_1 + "\"", matchedNetwork.SSID);
        assertTrue(TEST_BSSID_1.equals(matchedNetwork.BSSID)
                || TEST_BSSID_2.equals(matchedNetwork.BSSID)
                || TEST_BSSID_3.equals(matchedNetwork.BSSID));
        assertTrue(matchedNetwork.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK));
    }

    /**
     * Verify network specifier match failure for a specifier containing a specific SSID match using
     * 4 WPA_PSK scan results, 2 of which SSID_1 and the other 2 SSID_2. But, none of the scan
     * results' SSID match the one requested in the specifier.
     */
    @Test
    public void testNetworkSpecifierMatchFailUsingLiteralSsidMatchWhenSsidNotFound()
            throws Exception {
        mWifiNetworkFactory.addCallback(mAppBinder, mNetworkRequestMatchCallback,
                TEST_CALLBACK_IDENTIFIER);
        // Setup scan data for open networks.
        setupScanData(SCAN_RESULT_TYPE_WPA_PSK,
                TEST_SSID_1, TEST_SSID_1, TEST_SSID_2, TEST_SSID_2);

        // Setup network specifier for open networks.
        PatternMatcher ssidPatternMatch =
                new PatternMatcher(TEST_SSID_3, PatternMatcher.PATTERN_LITERAL);
        Pair<MacAddress, MacAddress> bssidPatternMatch =
                Pair.create(MacAddress.ALL_ZEROS_ADDRESS, MacAddress.ALL_ZEROS_ADDRESS);
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier(
                ssidPatternMatch, bssidPatternMatch, wifiConfiguration, TEST_UID_1);

        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        verify(mWifiConnectivityManager).enable(false);
        verifyPeriodicScans(0, PERIODIC_SCAN_INTERVAL_MS);

        ArgumentCaptor<List<WifiConfiguration>> matchedNetworksCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mNetworkRequestMatchCallback).onMatch(matchedNetworksCaptor.capture());

        assertNotNull(matchedNetworksCaptor.getValue());
        // We expect no network match in this case.
        assertEquals(0, matchedNetworksCaptor.getValue().size());
    }

    /**
     * Verify network specifier match failure for a specifier containing a specific SSID match using
     * 4 open scan results, each with unique SSID. But, none of the scan
     * results' key mgmt match the one requested in the specifier.
     */
    @Test
    public void testNetworkSpecifierMatchFailUsingLiteralSsidMatchWhenKeyMgmtDiffers()
            throws Exception {
        mWifiNetworkFactory.addCallback(mAppBinder, mNetworkRequestMatchCallback,
                TEST_CALLBACK_IDENTIFIER);
        // Setup scan data for open networks.
        setupScanData(SCAN_RESULT_TYPE_OPEN,
                TEST_SSID_1, TEST_SSID_2, TEST_SSID_3, TEST_SSID_4);

        // Setup network specifier for open networks.
        PatternMatcher ssidPatternMatch =
                new PatternMatcher(TEST_SSID_1, PatternMatcher.PATTERN_PREFIX);
        Pair<MacAddress, MacAddress> bssidPatternMatch =
                Pair.create(MacAddress.ALL_ZEROS_ADDRESS, MacAddress.ALL_ZEROS_ADDRESS);
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier(
                ssidPatternMatch, bssidPatternMatch, wifiConfiguration, TEST_UID_1);

        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        verify(mWifiConnectivityManager).enable(false);
        verifyPeriodicScans(0, PERIODIC_SCAN_INTERVAL_MS);

        ArgumentCaptor<List<WifiConfiguration>> matchedNetworksCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mNetworkRequestMatchCallback).onMatch(matchedNetworksCaptor.capture());

        assertNotNull(matchedNetworksCaptor.getValue());
        // We expect no network match in this case.
        assertEquals(0, matchedNetworksCaptor.getValue().size());
    }

    // Simulates the periodic scans performed to find a matching network.
    // a) Start scan
    // b) Scan results received.
    // c) Set alarm for next scan at the expected interval.
    // d) Alarm fires, go to step a) again and repeat.
    private void verifyPeriodicScans(long...expectedIntervalsInSeconds) {
        when(mClock.getElapsedSinceBootMillis()).thenReturn(0L);

        ArgumentCaptor<OnAlarmListener> alarmListenerArgumentCaptor =
                ArgumentCaptor.forClass(OnAlarmListener.class);
        OnAlarmListener alarmListener = null;
        ArgumentCaptor<ScanListener> scanListenerArgumentCaptor =
                ArgumentCaptor.forClass(ScanListener.class);
        ScanListener scanListener = null;

        InOrder inOrder = inOrder(mWifiScanner, mAlarmManager);

        for (int i = 0; i < expectedIntervalsInSeconds.length - 1; i++) {
            long expectedCurrentIntervalInMs = expectedIntervalsInSeconds[i];
            long expectedNextIntervalInMs = expectedIntervalsInSeconds[i + 1];

            // First scan is immediately fired, so need for the alarm to fire.
            if (expectedCurrentIntervalInMs != 0) {
                // Fire the alarm and ensure that we started the next scan.
                alarmListener.onAlarm();
            }
            inOrder.verify(mWifiScanner).startScan(
                    any(), scanListenerArgumentCaptor.capture(), any());
            scanListener = scanListenerArgumentCaptor.getValue();
            assertNotNull(scanListener);

            // Now trigger the scan results callback and verify the alarm set for the next scan.
            scanListener.onResults(mTestScanDatas);

            inOrder.verify(mAlarmManager).set(eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
                    eq(expectedNextIntervalInMs), any(),
                    alarmListenerArgumentCaptor.capture(), any());
            alarmListener = alarmListenerArgumentCaptor.getValue();
            assertNotNull(alarmListener);
        }

        verifyNoMoreInteractions(mWifiScanner, mAlarmManager);
    }

    private WifiNetworkSpecifier createWifiNetworkSpecifier(int uid, boolean isHidden) {
        PatternMatcher ssidPatternMatch =
                new PatternMatcher(TEST_SSID_1, PatternMatcher.PATTERN_LITERAL);
        Pair<MacAddress, MacAddress> bssidPatternMatch =
                Pair.create(MacAddress.ALL_ZEROS_ADDRESS, MacAddress.ALL_ZEROS_ADDRESS);
        WifiConfiguration wifiConfiguration;
        if (isHidden) {
            wifiConfiguration = WifiConfigurationTestUtil.createPskHiddenNetwork();
        } else {
            wifiConfiguration = WifiConfigurationTestUtil.createPskNetwork();
        }
        return new WifiNetworkSpecifier(
                ssidPatternMatch, bssidPatternMatch, wifiConfiguration, uid);
    }

    private static final int SCAN_RESULT_TYPE_OPEN = 0;
    private static final int SCAN_RESULT_TYPE_WPA_PSK = 1;
    private static final int SCAN_RESULT_TYPE_WPA_EAP = 2;

    private String getScanResultCapsForType(int scanResultType) {
        switch (scanResultType) {
            case SCAN_RESULT_TYPE_OPEN:
                return WifiConfigurationTestUtil.getScanResultCapsForNetwork(
                        WifiConfigurationTestUtil.createOpenNetwork());
            case SCAN_RESULT_TYPE_WPA_PSK:
                return WifiConfigurationTestUtil.getScanResultCapsForNetwork(
                        WifiConfigurationTestUtil.createPskNetwork());
            case SCAN_RESULT_TYPE_WPA_EAP:
                return WifiConfigurationTestUtil.getScanResultCapsForNetwork(
                        WifiConfigurationTestUtil.createEapNetwork());
        }
        fail("Invalid scan result type " + scanResultType);
        return "";
    }

    // Helper method to setup the scan data for verifying the matching algo.
    private void setupScanData(int scanResultType, String ssid1, String ssid2, String ssid3,
            String ssid4) {
        // 4 scan results,
        assertEquals(1, mTestScanDatas.length);
        ScanResult[] scanResults = mTestScanDatas[0].getResults();
        assertEquals(4, scanResults.length);

        String caps = getScanResultCapsForType(scanResultType);

        scanResults[0].SSID = ssid1;
        scanResults[0].BSSID = TEST_BSSID_1;
        scanResults[0].capabilities = caps;
        scanResults[1].SSID = ssid2;
        scanResults[1].BSSID = TEST_BSSID_2;
        scanResults[1].capabilities = caps;
        scanResults[2].SSID = ssid3;
        scanResults[2].BSSID = TEST_BSSID_3;
        scanResults[2].capabilities = caps;
        scanResults[3].SSID = ssid4;
        scanResults[3].BSSID = TEST_BSSID_4;
        scanResults[3].capabilities = caps;
    }
}
