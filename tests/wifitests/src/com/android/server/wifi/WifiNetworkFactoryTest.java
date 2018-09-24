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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link com.android.server.wifi.WifiNetworkFactory}.
 */
@SmallTest
public class WifiNetworkFactoryTest {
    private static final int TEST_UID_1 = 10423;
    private static final String TEST_PACKAGE_NAME_1 = "com.test.networkrequest.1";
    private static final int TEST_UID_2 = 10424;
    private static final String TEST_PACKAGE_NAME_2 = "com.test.networkrequest.2";

    @Mock WifiConnectivityManager mWifiConnectivityManager;
    @Mock Context mContext;
    @Mock ActivityManager mActivityManager;
    @Mock PackageManager mPackageManager;
    NetworkCapabilities mNetworkCapabilities;
    TestLooper mLooper;
    NetworkRequest mNetworkRequest;

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

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getNameForUid(TEST_UID_1)).thenReturn(TEST_PACKAGE_NAME_1);
        when(mPackageManager.getNameForUid(TEST_UID_2)).thenReturn(TEST_PACKAGE_NAME_2);
        when(mActivityManager.getPackageImportance(TEST_PACKAGE_NAME_1))
                .thenReturn(IMPORTANCE_FOREGROUND_SERVICE);
        when(mActivityManager.getPackageImportance(TEST_PACKAGE_NAME_2))
                .thenReturn(IMPORTANCE_FOREGROUND_SERVICE);

        mWifiNetworkFactory = new WifiNetworkFactory(mLooper.getLooper(), mContext,
                mNetworkCapabilities, mActivityManager, mWifiConnectivityManager);

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

        WifiNetworkSpecifier specifier =
                new WifiNetworkSpecifier(TEST_UID_1);
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

        WifiNetworkSpecifier specifier =
                new WifiNetworkSpecifier(TEST_UID_1);
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
        WifiNetworkSpecifier specifier1 =
                new WifiNetworkSpecifier(TEST_UID_1);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier1);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        // Make request 2 which will be accepted because a fg app request can
        // override a fg service request.
        WifiNetworkSpecifier specifier2 =
                new WifiNetworkSpecifier(TEST_UID_2);
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
        WifiNetworkSpecifier specifier1 =
                new WifiNetworkSpecifier(TEST_UID_1);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier1);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        // Make request 2 which will be accepted because a fg service request can
        // override an existing fg service request.
        WifiNetworkSpecifier specifier2 =
                new WifiNetworkSpecifier(TEST_UID_2);
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
        WifiNetworkSpecifier specifier1 =
                new WifiNetworkSpecifier(TEST_UID_1);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier1);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        // Make request 2 which will be accepted because a fg app request can
        // override an existing fg app request.
        WifiNetworkSpecifier specifier2 =
                new WifiNetworkSpecifier(TEST_UID_2);
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
        WifiNetworkSpecifier specifier1 =
                new WifiNetworkSpecifier(TEST_UID_1);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier1);
        mWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        // Make request 2 which will be rejected because a fg service request cannot
        // override a fg app request.
        WifiNetworkSpecifier specifier2 =
                new WifiNetworkSpecifier(TEST_UID_2);
        mNetworkRequest.networkCapabilities.setNetworkSpecifier(specifier2);
        assertFalse(mWifiNetworkFactory.acceptRequest(mNetworkRequest, 0));
    }
}
