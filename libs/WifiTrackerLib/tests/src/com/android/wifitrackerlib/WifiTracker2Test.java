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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkScoreManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.test.TestLooper;

import androidx.lifecycle.Lifecycle;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Clock;

public class WifiTracker2Test {

    @Mock private Lifecycle mMockLifecycle;
    @Mock private Context mMockContext;
    @Mock private WifiManager mMockWifiManager;
    @Mock private ConnectivityManager mMockConnectivityManager;
    @Mock private NetworkScoreManager mMockNetworkScoreManager;
    @Mock private Clock mMockClock;
    @Mock private WifiTracker2.WifiTrackerCallback mMockWifiTrackerCallback;

    private TestLooper mTestLooper = new TestLooper();

    private final ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor =
            ArgumentCaptor.forClass(BroadcastReceiver.class);

    private WifiTracker2 createTestWifiTracker2() {
        final Handler testHandler = new Handler(mTestLooper.getLooper());

        return new WifiTracker2(mMockLifecycle, mMockContext,
                mMockWifiManager,
                mMockConnectivityManager,
                mMockNetworkScoreManager,
                testHandler,
                testHandler,
                mMockClock,
                15_000,
                10_000,
                mMockWifiTrackerCallback);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    /**
     * Tests that receiving a wifi state change broadcast updates getWifiState().
     */
    @Test
    public void testWifiStateChangeBroadcast_updatesWifiState() {
        WifiTracker2 wifiTracker2 = createTestWifiTracker2();
        wifiTracker2.onStart();
        verify(mMockContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(), any(), any());

        when(mMockWifiManager.getWifiState()).thenReturn(WifiManager.WIFI_STATE_DISABLED);
        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION));

        assertThat(wifiTracker2.getWifiState()).isEqualTo(WifiManager.WIFI_STATE_DISABLED);

        when(mMockWifiManager.getWifiState()).thenReturn(WifiManager.WIFI_STATE_ENABLED);
        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION));

        assertThat(wifiTracker2.getWifiState()).isEqualTo(WifiManager.WIFI_STATE_ENABLED);

    }

    /**
     * Tests that receiving a wifi state change broadcast notifies the listener.
     */
    @Test
    public void testWifiStateChangeBroadcast_NotifiesListener() {
        WifiTracker2 wifiTracker2 = createTestWifiTracker2();
        wifiTracker2.onStart();
        verify(mMockContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(), any(), any());

        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION));
        mTestLooper.dispatchAll();

        verify(mMockWifiTrackerCallback, atLeastOnce()).onWifiStateChanged();
    }

    /**
     * Tests that the wifi state is set correctly after onStart, even if no broadcast was received.
     */
    @Test
    public void testOnStart_setsWifiState() {
        WifiTracker2 wifiTracker2 = createTestWifiTracker2();
        when(mMockWifiManager.getWifiState()).thenReturn(WifiManager.WIFI_STATE_DISABLED);
        wifiTracker2.onStart();
        mTestLooper.dispatchAll();

        assertThat(wifiTracker2.getWifiState()).isEqualTo(WifiManager.WIFI_STATE_DISABLED);

        wifiTracker2.onStop();
        when(mMockWifiManager.getWifiState()).thenReturn(WifiManager.WIFI_STATE_ENABLED);
        wifiTracker2.onStart();
        mTestLooper.dispatchAll();

        assertThat(wifiTracker2.getWifiState()).isEqualTo(WifiManager.WIFI_STATE_ENABLED);
    }
}
