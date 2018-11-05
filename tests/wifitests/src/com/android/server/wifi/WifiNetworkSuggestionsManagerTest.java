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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.Intent;
import android.net.MacAddress;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.wifi.util.WifiPermissionsUtil;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for {@link com.android.server.wifi.WifiNetworkSuggestionsManager}.
 */
@SmallTest
public class WifiNetworkSuggestionsManagerTest {
    private static final String TEST_PACKAGE_1 = "com.test12345";
    private static final String TEST_PACKAGE_2 = "com.test54321";
    private static final int TEST_UID_1 = 5667;
    private static final int TEST_UID_2 = 4537;

    private @Mock Context mContext;
    private @Mock WifiPermissionsUtil mWifiPermissionsUtil;
    private InOrder mInorder;

    private WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    private List<WifiNetworkSuggestion> mWifiNetworkSuggestionsList1;
    private List<WifiNetworkSuggestion> mWifiNetworkSuggestionsList2;

    /**
     * Setup the mocks.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mInorder = inOrder(mContext, mWifiPermissionsUtil);
        mWifiNetworkSuggestionsManager =
                new WifiNetworkSuggestionsManager(mContext, mWifiPermissionsUtil);
    }

    /**
     * Verify successful addition of network suggestions.
     */
    @Test
    public void testAddNetworkSuggestionsSuccess() {
        WifiNetworkSuggestion networkSuggestion1 = new WifiNetworkSuggestion(
                WifiConfigurationTestUtil.createOpenNetwork(), false, false, TEST_UID_1);
        WifiNetworkSuggestion networkSuggestion2 = new WifiNetworkSuggestion(
                WifiConfigurationTestUtil.createOpenNetwork(), false, false, TEST_UID_2);

        List<WifiNetworkSuggestion> networkSuggestionList1 =
                new ArrayList<WifiNetworkSuggestion>() {{
                add(networkSuggestion1);
            }};
        List<WifiNetworkSuggestion> networkSuggestionList2 =
                new ArrayList<WifiNetworkSuggestion>() {{
                add(networkSuggestion2);
            }};

        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList1, TEST_PACKAGE_1));
        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList2, TEST_PACKAGE_2));

        Set<WifiNetworkSuggestion> allNetworkSuggestions =
                mWifiNetworkSuggestionsManager.getAllNetworkSuggestions();
        Set<WifiNetworkSuggestion> expectedAllNetworkSuggestions =
                new HashSet<WifiNetworkSuggestion>() {{
                    add(networkSuggestion1);
                    add(networkSuggestion2);
            }};
        assertEquals(expectedAllNetworkSuggestions, allNetworkSuggestions);
    }

    /**
     * Verify successful removal of network suggestions.
     */
    @Test
    public void testRemoveNetworkSuggestionsSuccess() {
        WifiNetworkSuggestion networkSuggestion1 = new WifiNetworkSuggestion(
                WifiConfigurationTestUtil.createOpenNetwork(), false, false, TEST_UID_1);
        WifiNetworkSuggestion networkSuggestion2 = new WifiNetworkSuggestion(
                WifiConfigurationTestUtil.createOpenNetwork(), false, false, TEST_UID_2);

        List<WifiNetworkSuggestion> networkSuggestionList1 =
                new ArrayList<WifiNetworkSuggestion>() {{
                add(networkSuggestion1);
            }};
        List<WifiNetworkSuggestion> networkSuggestionList2 =
                new ArrayList<WifiNetworkSuggestion>() {{
                add(networkSuggestion2);
            }};

        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList1, TEST_PACKAGE_1));
        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList2, TEST_PACKAGE_2));

        // Now remove all of them.
        assertTrue(mWifiNetworkSuggestionsManager.remove(networkSuggestionList1, TEST_PACKAGE_1));
        assertTrue(mWifiNetworkSuggestionsManager.remove(networkSuggestionList2, TEST_PACKAGE_2));

        assertTrue(mWifiNetworkSuggestionsManager.getAllNetworkSuggestions().isEmpty());
    }

    /**
     * Verify successful removal of all network suggestions.
     */
    @Test
    public void testRemoveAllNetworkSuggestionsSuccess() {
        WifiNetworkSuggestion networkSuggestion1 = new WifiNetworkSuggestion(
                WifiConfigurationTestUtil.createOpenNetwork(), false, false, TEST_UID_1);
        WifiNetworkSuggestion networkSuggestion2 = new WifiNetworkSuggestion(
                WifiConfigurationTestUtil.createOpenNetwork(), false, false, TEST_UID_2);

        List<WifiNetworkSuggestion> networkSuggestionList1 =
                new ArrayList<WifiNetworkSuggestion>() {{
                    add(networkSuggestion1);
                }};
        List<WifiNetworkSuggestion> networkSuggestionList2 =
                new ArrayList<WifiNetworkSuggestion>() {{
                    add(networkSuggestion2);
                }};

        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList1, TEST_PACKAGE_1));
        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList2, TEST_PACKAGE_2));

        // Now remove all of them by sending an empty list.
        assertTrue(mWifiNetworkSuggestionsManager.remove(new ArrayList<>(), TEST_PACKAGE_1));
        assertTrue(mWifiNetworkSuggestionsManager.remove(new ArrayList<>(), TEST_PACKAGE_2));

        assertTrue(mWifiNetworkSuggestionsManager.getAllNetworkSuggestions().isEmpty());
    }

    /**
     * Verify successful replace (add,remove, add) of network suggestions.
     */
    @Test
    public void testReplaceNetworkSuggestionsSuccess() {
        WifiNetworkSuggestion networkSuggestion = new WifiNetworkSuggestion(
                WifiConfigurationTestUtil.createOpenNetwork(), false, false, TEST_UID_1);

        List<WifiNetworkSuggestion> networkSuggestionList1 =
                new ArrayList<WifiNetworkSuggestion>() {{
                add(networkSuggestion);
            }};

        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList1, TEST_PACKAGE_1));
        assertTrue(mWifiNetworkSuggestionsManager.remove(networkSuggestionList1, TEST_PACKAGE_1));
        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList1, TEST_PACKAGE_1));

        Set<WifiNetworkSuggestion> allNetworkSuggestions =
                mWifiNetworkSuggestionsManager.getAllNetworkSuggestions();
        Set<WifiNetworkSuggestion> expectedAllNetworkSuggestions =
                new HashSet<WifiNetworkSuggestion>() {{
                add(networkSuggestion);
            }};
        assertEquals(expectedAllNetworkSuggestions, allNetworkSuggestions);
    }

    /**
     * Verify that an attempt to modify networks that are already active is rejected.
     */
    @Test
    public void testAddNetworkSuggestionsFailureOnInPlaceModification() {
        WifiNetworkSuggestion networkSuggestion = new WifiNetworkSuggestion(
                WifiConfigurationTestUtil.createOpenNetwork(), false, false, TEST_UID_1);
        List<WifiNetworkSuggestion> networkSuggestionList1 =
                new ArrayList<WifiNetworkSuggestion>() {{
                add(networkSuggestion);
            }};

        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList1, TEST_PACKAGE_1));

        // Modify the original suggestion.
        networkSuggestion.wifiConfiguration.meteredOverride =
                WifiConfiguration.METERED_OVERRIDE_METERED;

        // Replace attempt should fail.
        assertFalse(mWifiNetworkSuggestionsManager.add(networkSuggestionList1, TEST_PACKAGE_1));
    }

    /**
     * Verify that an attempt to remove an invalid set of network suggestions is rejected.
     */
    @Test
    public void testRemoveNetworkSuggestionsFailureOnInvalid() {
        WifiNetworkSuggestion networkSuggestion1 = new WifiNetworkSuggestion(
                WifiConfigurationTestUtil.createOpenNetwork(), false, false, TEST_UID_1);
        WifiNetworkSuggestion networkSuggestion2 = new WifiNetworkSuggestion(
                WifiConfigurationTestUtil.createOpenNetwork(), false, false, TEST_UID_1);

        List<WifiNetworkSuggestion> networkSuggestionList1 =
                new ArrayList<WifiNetworkSuggestion>() {{
                add(networkSuggestion1);
            }};
        List<WifiNetworkSuggestion> networkSuggestionList2 =
                new ArrayList<WifiNetworkSuggestion>() {{
                add(networkSuggestion2);
            }};
        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList1, TEST_PACKAGE_1));
        // Remove should fail because the network list is different.
        assertFalse(mWifiNetworkSuggestionsManager.remove(networkSuggestionList2, TEST_PACKAGE_1));
    }

    /**
     * Verify a successful lookup of a single network suggestion matching the provided scan detail.
     */
    @Test
    public void testGetNetworkSuggestionsForScanDetailSuccessWithOneMatch() {
        WifiNetworkSuggestion networkSuggestion = new WifiNetworkSuggestion(
                WifiConfigurationTestUtil.createOpenNetwork(), false, false, TEST_UID_1);
        List<WifiNetworkSuggestion> networkSuggestionList1 =
                new ArrayList<WifiNetworkSuggestion>() {{
                add(networkSuggestion);
            }};
        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList1, TEST_PACKAGE_1));

        ScanDetail scanDetail = createScanDetailForNetwork(networkSuggestion.wifiConfiguration);

        Set<WifiNetworkSuggestion> matchingNetworkSuggestions =
                mWifiNetworkSuggestionsManager.getNetworkSuggestionsForScanDetail(scanDetail);
        Set<WifiNetworkSuggestion> expectedMatchingNetworkSuggestions =
                new HashSet<WifiNetworkSuggestion>() {{
                add(networkSuggestion);
            }};
        assertEquals(expectedMatchingNetworkSuggestions, matchingNetworkSuggestions);
    }

    /**
     * Verify a successful lookup of multiple network suggestions matching the provided scan detail.
     */
    @Test
    public void testGetNetworkSuggestionsForScanDetailSuccessWithMultipleMatch() {
        WifiConfiguration wifiConfiguration = WifiConfigurationTestUtil.createOpenNetwork();
        WifiNetworkSuggestion networkSuggestion1 = new WifiNetworkSuggestion(
                wifiConfiguration, false, false, TEST_UID_1);
        // Reuse the same network credentials to ensure they both match.
        WifiNetworkSuggestion networkSuggestion2 = new WifiNetworkSuggestion(
                wifiConfiguration, false, false, TEST_UID_2);

        List<WifiNetworkSuggestion> networkSuggestionList1 =
                new ArrayList<WifiNetworkSuggestion>() {{
                add(networkSuggestion1);
            }};
        List<WifiNetworkSuggestion> networkSuggestionList2 =
                new ArrayList<WifiNetworkSuggestion>() {{
                add(networkSuggestion2);
            }};

        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList1, TEST_PACKAGE_1));
        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList2, TEST_PACKAGE_2));

        ScanDetail scanDetail = createScanDetailForNetwork(networkSuggestion1.wifiConfiguration);

        Set<WifiNetworkSuggestion> matchingNetworkSuggestions =
                mWifiNetworkSuggestionsManager.getNetworkSuggestionsForScanDetail(scanDetail);
        Set<WifiNetworkSuggestion> expectedMatchingNetworkSuggestions =
                new HashSet<WifiNetworkSuggestion>() {{
                add(networkSuggestion1);
                add(networkSuggestion2);
            }};
        assertEquals(expectedMatchingNetworkSuggestions, matchingNetworkSuggestions);
    }

    /**
     * Verify failure to lookup any network suggestion matching the provided scan detail.
     */
    @Test
    public void testGetNetworkSuggestionsForScanDetailFailure() {
        WifiNetworkSuggestion networkSuggestion = new WifiNetworkSuggestion(
                WifiConfigurationTestUtil.createOpenNetwork(), false, false, TEST_UID_1);
        List<WifiNetworkSuggestion> networkSuggestionList1 =
                new ArrayList<WifiNetworkSuggestion>() {{
                add(networkSuggestion);
            }};
        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList1, TEST_PACKAGE_1));

        // Create a scan result corresponding to a different network.
        ScanDetail scanDetail = createScanDetailForNetwork(
                WifiConfigurationTestUtil.createPskNetwork());

        assertNull(mWifiNetworkSuggestionsManager.getNetworkSuggestionsForScanDetail(scanDetail));
    }

    /**
     * Verify a successful lookup of a single network suggestion matching the connected network.
     * a) The corresponding network suggestion has the
     * {@link WifiNetworkSuggestion#isAppInteractionRequired} flag set.
     * b) The app holds location permission.
     * This should trigger a broadcast to the app.
     */
    @Test
    public void testOnNetworkConnectionSuccessWithOneMatch() {
        WifiNetworkSuggestion networkSuggestion = new WifiNetworkSuggestion(
                WifiConfigurationTestUtil.createOpenNetwork(), true, false, TEST_UID_1);
        List<WifiNetworkSuggestion> networkSuggestionList =
                new ArrayList<WifiNetworkSuggestion>() {{
                    add(networkSuggestion);
                }};
        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList, TEST_PACKAGE_1));

        // Simulate connecting to the network.
        mWifiNetworkSuggestionsManager.handleNetworkConnection(networkSuggestion.wifiConfiguration);

        // Verify that the correct broadcast was sent out.
        mInorder.verify(mWifiPermissionsUtil)
                .enforceCanAccessScanResults(TEST_PACKAGE_1, TEST_UID_1);
        validatePostConnectionBroadcastSent(TEST_PACKAGE_1, networkSuggestion);

        // Verify no more broadcast were sent out.
        verifyNoMoreInteractions(mContext);
    }

    /**
     * Verify a successful lookup of multiple network suggestion matching the connected network.
     * a) The corresponding network suggestion has the
     * {@link WifiNetworkSuggestion#isAppInteractionRequired} flag set.
     * b) The app holds location permission.
     * This should trigger a broadcast to all the apps.
     */
    @Test
    public void testOnNetworkConnectionSuccessWithMulitpleMatch() {
        WifiConfiguration wifiConfiguration = WifiConfigurationTestUtil.createOpenNetwork();
        WifiNetworkSuggestion networkSuggestion1 = new WifiNetworkSuggestion(
                wifiConfiguration, true, false, TEST_UID_1);
        List<WifiNetworkSuggestion> networkSuggestionList1 =
                new ArrayList<WifiNetworkSuggestion>() {{
                    add(networkSuggestion1);
                }};
        WifiNetworkSuggestion networkSuggestion2 = new WifiNetworkSuggestion(
                wifiConfiguration, true, false, TEST_UID_2);
        List<WifiNetworkSuggestion> networkSuggestionList2 =
                new ArrayList<WifiNetworkSuggestion>() {{
                    add(networkSuggestion2);
                }};

        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList1, TEST_PACKAGE_1));
        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList2, TEST_PACKAGE_2));

        // Simulate connecting to the network.
        mWifiNetworkSuggestionsManager.handleNetworkConnection(wifiConfiguration);

        // Verify that the correct broadcasts were sent out.
        mInorder.verify(mWifiPermissionsUtil)
                .enforceCanAccessScanResults(TEST_PACKAGE_1, TEST_UID_1);
        validatePostConnectionBroadcastSent(TEST_PACKAGE_1, networkSuggestion1);
        mInorder.verify(mWifiPermissionsUtil)
                .enforceCanAccessScanResults(TEST_PACKAGE_2, TEST_UID_2);
        validatePostConnectionBroadcastSent(TEST_PACKAGE_2, networkSuggestion2);

        // Verify no more broadcast were sent out.
        verifyNoMoreInteractions(mContext);
    }

    /**
     * Verify a successful lookup of a single network suggestion matching the connected network.
     * a) The corresponding network suggestion does not have the
     * {@link WifiNetworkSuggestion#isAppInteractionRequired} flag set.
     * b) The app holds location permission.
     * This should not trigger a broadcast to the app.
     */
    @Test
    public void testOnNetworkConnectionWhenIsAppInteractionRequiredNotSet() {
        WifiNetworkSuggestion networkSuggestion = new WifiNetworkSuggestion(
                WifiConfigurationTestUtil.createOpenNetwork(), false, false, TEST_UID_1);
        List<WifiNetworkSuggestion> networkSuggestionList =
                new ArrayList<WifiNetworkSuggestion>() {{
                    add(networkSuggestion);
                }};
        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList, TEST_PACKAGE_1));

        // Simulate connecting to the network.
        mWifiNetworkSuggestionsManager.handleNetworkConnection(networkSuggestion.wifiConfiguration);

        // Verify no broadcast was sent out.
        verifyNoMoreInteractions(mContext, mWifiPermissionsUtil);
    }

    /**
     * Verify a successful lookup of a single network suggestion matching the connected network.
     * a) The corresponding network suggestion has the
     * {@link WifiNetworkSuggestion#isAppInteractionRequired} flag set.
     * b) The app does not hold location permission.
     * This should not trigger a broadcast to the app.
     */
    @Test
    public void testOnNetworkConnectionWhenAppDoesNotHoldLocationPermission() {
        WifiNetworkSuggestion networkSuggestion = new WifiNetworkSuggestion(
                WifiConfigurationTestUtil.createOpenNetwork(), true, false, TEST_UID_1);
        List<WifiNetworkSuggestion> networkSuggestionList =
                new ArrayList<WifiNetworkSuggestion>() {{
                    add(networkSuggestion);
                }};
        assertTrue(mWifiNetworkSuggestionsManager.add(networkSuggestionList, TEST_PACKAGE_1));

        doThrow(new SecurityException())
                .when(mWifiPermissionsUtil).enforceCanAccessScanResults(TEST_PACKAGE_1, TEST_UID_1);

        // Simulate connecting to the network.
        mWifiNetworkSuggestionsManager.handleNetworkConnection(networkSuggestion.wifiConfiguration);

        mInorder.verify(mWifiPermissionsUtil)
                .enforceCanAccessScanResults(TEST_PACKAGE_1, TEST_UID_1);

        // Verify no broadcast was sent out.
        verifyNoMoreInteractions(mContext, mWifiPermissionsUtil);
    }

    /**
     * Creates a scan detail corresponding to the provided network values.
     */
    private ScanDetail createScanDetailForNetwork(WifiConfiguration configuration) {
        return WifiConfigurationTestUtil.createScanDetailForNetwork(configuration,
                MacAddress.createRandomUnicastAddress().toString(), -45, 0, 0, 0);
    }

    private void validatePostConnectionBroadcastSent(
            String expectedPackageName, WifiNetworkSuggestion expectedNetworkSuggestion) {
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        ArgumentCaptor<UserHandle> userHandleCaptor = ArgumentCaptor.forClass(UserHandle.class);
        mInorder.verify(mContext,  calls(1)).sendBroadcastAsUser(
                intentCaptor.capture(), userHandleCaptor.capture());

        assertEquals(userHandleCaptor.getValue(), UserHandle.SYSTEM);

        Intent intent = intentCaptor.getValue();
        assertEquals(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION,
                intent.getAction());
        String packageName = intent.getPackage();
        WifiNetworkSuggestion networkSuggestion =
                intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_SUGGESTION);
        assertEquals(expectedPackageName, packageName);
        assertEquals(expectedNetworkSuggestion, networkSuggestion);
    }
}
