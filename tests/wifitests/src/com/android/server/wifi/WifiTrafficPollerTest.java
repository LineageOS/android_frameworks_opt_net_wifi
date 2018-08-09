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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.ITrafficStateCallback;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.support.test.filters.SmallTest;

import com.android.server.wifi.util.ExternalCallbackTracker;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link com.android.server.wifi.WifiTrafficPoller}.
 */
@SmallTest
public class WifiTrafficPollerTest {
    public static final String TAG = "WifiTrafficPollerTest";

    private TestLooper mLooper;
    private WifiTrafficPoller mWifiTrafficPoller;
    private BroadcastReceiver mReceiver;
    private Intent mIntent;
    private final static String IFNAME = "wlan0";
    private final static long DEFAULT_PACKET_COUNT = 10;
    private final static long TX_PACKET_COUNT = 40;
    private final static long RX_PACKET_COUNT = 50;
    private static final int TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER = 14;

    final ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor =
            ArgumentCaptor.forClass(BroadcastReceiver.class);

    @Mock Context mContext;
    @Mock WifiNative mWifiNative;
    @Mock NetworkInfo mNetworkInfo;
    @Mock IBinder mAppBinder;
    @Mock ITrafficStateCallback mTrafficStateCallback;
    @Mock ExternalCallbackTracker<ITrafficStateCallback> mCallbackTracker;

    /**
     * Called before each test
     */
    @Before
    public void setUp() throws Exception {
        // Ensure looper exists
        mLooper = new TestLooper();
        MockitoAnnotations.initMocks(this);

        when(mWifiNative.getTxPackets(any(String.class))).thenReturn(DEFAULT_PACKET_COUNT,
                TX_PACKET_COUNT);
        when(mWifiNative.getRxPackets(any(String.class))).thenReturn(DEFAULT_PACKET_COUNT,
                RX_PACKET_COUNT);
        when(mWifiNative.getClientInterfaceName()).thenReturn(IFNAME);

        mWifiTrafficPoller = new WifiTrafficPoller(mContext, mLooper.getLooper(), mWifiNative);
        // Verify the constructor registers broadcast receiver with the collect intent filters.
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(), argThat(
                intentFilter -> intentFilter.hasAction(WifiManager.NETWORK_STATE_CHANGED_ACTION) &&
                        intentFilter.hasAction(Intent.ACTION_SCREEN_ON) &&
                        intentFilter.hasAction(Intent.ACTION_SCREEN_OFF)));
        mReceiver = mBroadcastReceiverCaptor.getValue();

        // For the fist call, this is required to set the DEFAULT_PACKET_COUNT to mTxPkts and
        // mRxPkts in WifiTrafficPoll Object.
        triggerForUpdatedInformationOfData(Intent.ACTION_SCREEN_ON,
                NetworkInfo.DetailedState.CONNECTED);
    }

    private void triggerForUpdatedInformationOfData(String actionScreen,
            NetworkInfo.DetailedState networkState) {
        when(mNetworkInfo.getDetailedState()).thenReturn(NetworkInfo.DetailedState.DISCONNECTED);
        mIntent = new Intent(actionScreen);
        mReceiver.onReceive(mContext, mIntent);
        mLooper.dispatchAll();

        when(mNetworkInfo.getDetailedState()).thenReturn(networkState);
        mIntent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mIntent.putExtra(WifiManager.EXTRA_NETWORK_INFO, mNetworkInfo);
        mReceiver.onReceive(mContext, mIntent);
        mLooper.dispatchAll();
    }

    /**
     * Verify that StartTrafficStatsPolling should not happen in case a network is not connected
     */
    @Test
    public void testNotStartTrafficStatsPollingWithDisconnected() throws RemoteException {
        // Register Client to verify that Tx/RX packet message is properly received.
        mWifiTrafficPoller.addCallback(
                mAppBinder, mTrafficStateCallback, TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
        triggerForUpdatedInformationOfData(Intent.ACTION_SCREEN_ON,
                NetworkInfo.DetailedState.DISCONNECTED);

        // Client should not get any message when the network is disconnected
        verify(mTrafficStateCallback, never()).onStateChanged(anyInt());
    }

    /**
     * Verify that StartTrafficStatsPolling should happen in case screen is on and rx/tx packets are
     * available.
     */
    @Test
    public void testStartTrafficStatsPollingWithScreenOn() throws RemoteException {
        // Register Client to verify that Tx/RX packet message is properly received.
        mWifiTrafficPoller.addCallback(
                mAppBinder, mTrafficStateCallback, TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
        triggerForUpdatedInformationOfData(Intent.ACTION_SCREEN_ON,
                NetworkInfo.DetailedState.CONNECTED);

        // Client should get the DATA_ACTIVITY_NOTIFICATION
        verify(mTrafficStateCallback).onStateChanged(
                WifiManager.TrafficStateCallback.DATA_ACTIVITY_INOUT);
    }

    /**
     * Verify that StartTrafficStatsPolling should not happen in case screen is off.
     */
    @Test
    public void testNotStartTrafficStatsPollingWithScreenOff() throws RemoteException {
        // Register Client to verify that Tx/RX packet message is properly received.
        mWifiTrafficPoller.addCallback(
                mAppBinder, mTrafficStateCallback, TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
        triggerForUpdatedInformationOfData(Intent.ACTION_SCREEN_OFF,
                NetworkInfo.DetailedState.CONNECTED);

        verify(mNetworkInfo, atLeastOnce()).getDetailedState();
        mLooper.dispatchAll();

        // Client should not get any message when the screen is off
        verify(mTrafficStateCallback, never()).onStateChanged(anyInt());
    }

    /**
     * Verify that remove client should be handled
     */
    @Test
    public void testRemoveClient() throws RemoteException {
        // Register Client to verify that Tx/RX packet message is properly received.
        mWifiTrafficPoller.addCallback(
                mAppBinder, mTrafficStateCallback, TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
        mWifiTrafficPoller.removeCallback(TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
        verify(mAppBinder).unlinkToDeath(any(), anyInt());

        triggerForUpdatedInformationOfData(Intent.ACTION_SCREEN_ON,
                NetworkInfo.DetailedState.CONNECTED);

        // Client should not get any message after the client is removed.
        verify(mTrafficStateCallback, never()).onStateChanged(anyInt());
    }

    /**
     * Verify that remove client ignores when callback identifier is wrong.
     */
    @Test
    public void testRemoveClientWithWrongIdentifier() throws RemoteException {
        // Register Client to verify that Tx/RX packet message is properly received.
        mWifiTrafficPoller.addCallback(
                mAppBinder, mTrafficStateCallback, TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
        mWifiTrafficPoller.removeCallback(TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER + 5);
        mLooper.dispatchAll();

        triggerForUpdatedInformationOfData(Intent.ACTION_SCREEN_ON,
                NetworkInfo.DetailedState.CONNECTED);

        // Client should get the DATA_ACTIVITY_NOTIFICATION
        verify(mTrafficStateCallback).onStateChanged(
                WifiManager.TrafficStateCallback.DATA_ACTIVITY_INOUT);
    }

    /**
     *
     * Verify that traffic poller registers for death notification on adding client.
     */
    @Test
    public void registersForBinderDeathOnAddClient() throws Exception {
        mWifiTrafficPoller.addCallback(
                mAppBinder, mTrafficStateCallback, TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
        verify(mAppBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());
    }

    /**
     *
     * Verify that traffic poller registers for death notification on adding client.
     */
    @Test
    public void addCallbackFailureOnLinkToDeath() throws Exception {
        doThrow(new RemoteException())
                .when(mAppBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());
        mWifiTrafficPoller.addCallback(
                mAppBinder, mTrafficStateCallback, TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
        verify(mAppBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        triggerForUpdatedInformationOfData(Intent.ACTION_SCREEN_ON,
                NetworkInfo.DetailedState.CONNECTED);

        // Client should not get any message callback add failed.
        verify(mTrafficStateCallback, never()).onStateChanged(anyInt());
    }
}
