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

package com.android.server.wifi.p2p;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.Messenger;
import android.os.test.TestLooper;
import android.provider.Settings;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.server.wifi.FakeWifiLog;
import com.android.server.wifi.FrameworkFacade;
import com.android.server.wifi.HalDeviceManager;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.util.WifiPermissionsUtil;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

/**
 * Unit test harness for WifiP2pServiceImpl.
 */
@SmallTest
public class WifiP2pServiceImplTest {
    private static final String TAG = "WifiP2pServiceImplTest";
    private static final String IFACE_NAME_P2P = "mockP2p0";
    private static final long STATE_CHANGE_WAITING_TIME = 1000;

    private ArgumentCaptor<HalDeviceManager.InterfaceAvailableForRequestListener>
            mAvailListenerCaptor = ArgumentCaptor.forClass(
            HalDeviceManager.InterfaceAvailableForRequestListener.class);
    private ArgumentCaptor<BroadcastReceiver> mBcastRxCaptor = ArgumentCaptor.forClass(
            BroadcastReceiver.class);
    private Binder mClient1;
    private Binder mClient2;
    private BroadcastReceiver mLocationModeReceiver;
    private BroadcastReceiver mWifiStateChangedReceiver;
    private Messenger mP2pStateMachineMessenger;
    private WifiP2pServiceImpl mWifiP2pServiceImpl;
    private TestLooper mLooper;

    @Mock Context mContext;
    @Mock FrameworkFacade mFrameworkFacade;
    @Mock HandlerThread mHandlerThread;
    @Mock INetworkManagementService mNwService;
    @Mock LocationManager mLocationManagerMock;
    @Mock PackageManager mPackageManager;
    @Mock Resources mResources;
    @Mock WifiInjector mWifiInjector;
    @Mock WifiManager mMockWifiManager;
    @Mock WifiPermissionsUtil mWifiPermissionsUtil;
    @Mock WifiP2pNative mWifiNative;
    @Spy FakeWifiLog mLog;
    @Spy MockWifiP2pMonitor mWifiMonitor;

    /**
     * Simulate Location Mode change: Changes the location manager return values and dispatches a
     * broadcast.
     */
    private void simulateLocationModeChange(boolean isLocationModeEnabled) {
        when(mLocationManagerMock.isLocationEnabled()).thenReturn(isLocationModeEnabled);

        Intent intent = new Intent(LocationManager.MODE_CHANGED_ACTION);
        mLocationModeReceiver.onReceive(mContext, intent);
    }

    /**
     * Simulate Wi-Fi state change: broadcast state change and modify the API return value.
     */
    private void simulateWifiStateChange(boolean isWifiOn) {
        when(mMockWifiManager.getWifiState()).thenReturn(
                isWifiOn ? WifiManager.WIFI_STATE_ENABLED : WifiManager.WIFI_STATE_DISABLED);

        Intent intent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        intent.putExtra(WifiManager.EXTRA_WIFI_STATE,
                isWifiOn ? WifiManager.WIFI_STATE_ENABLED : WifiManager.WIFI_STATE_DISABLED);
        mWifiStateChangedReceiver.onReceive(mContext, intent);
    }

    /**
     * force P2p State enter InactiveState to start others unit test
     */
    private void forceP2pEnabled(Binder clientBinder) throws Exception {
        simulateWifiStateChange(true);
        simulateLocationModeChange(true);
        checkIsP2pInitWhenClientConnected(true, clientBinder);
    }

    /**
     * Check is P2p init as expect when client connected
     *
     * @param expectInit boolean, set true if p2p init should succeed as expect, set fail when
     *        expect init should not happen
     * @param clientBinder binder, client binder to use for p2p channel init
     */
    private void checkIsP2pInitWhenClientConnected(boolean expectInit, Binder clientBinder)
            throws Exception {
        mWifiP2pServiceImpl.getMessenger(clientBinder);
        mLooper.dispatchAll();
        if (expectInit) {
            verify(mWifiNative).setupInterface(any(), any());
            verify(mNwService).setInterfaceUp(anyString());
            verify(mWifiMonitor, atLeastOnce()).registerHandler(anyString(), anyInt(), any());
        } else {
            verify(mWifiNative, never()).setupInterface(any(), any());
            verify(mNwService, never()).setInterfaceUp(anyString());
            verify(mWifiMonitor, never()).registerHandler(anyString(), anyInt(), any());
        }
    }

    /**
     * Check is P2p teardown as expect when client disconnected
     *
     * @param expectTearDown, boolean, set true if p2p teardown should succeed as expect,
     *        set fail when expect teardown should not happen
     * @param clientBinder binder, client binder to use for p2p channel init
     */
    private void checkIsP2pTearDownWhenClientDisconnected(
            boolean expectTearDown, Binder clientBinder) throws Exception {
        mWifiP2pServiceImpl.close(clientBinder);
        mLooper.dispatchAll();
        if (expectTearDown) {
            verify(mWifiNative).teardownInterface();
            verify(mWifiMonitor).stopMonitoring(anyString());
        } else {
            verify(mWifiNative, never()).teardownInterface();
            verify(mWifiMonitor, never()).stopMonitoring(anyString());
        }
    }


    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getString(R.string.config_wifi_p2p_device_type))
                .thenReturn("10-0050F204-5");

        mLooper = new TestLooper();
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)).thenReturn(true);
        when(mContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mMockWifiManager);
        when(mMockWifiManager.getWifiState()).thenReturn(WifiManager.WIFI_STATE_ENABLED);
        when(mContext.getSystemService(Context.LOCATION_SERVICE)).thenReturn(
                mLocationManagerMock);

        when(mWifiInjector.getWifiPermissionsUtil()).thenReturn(mWifiPermissionsUtil);
        when(mWifiInjector.getWifiP2pNative()).thenReturn(mWifiNative);
        when(mWifiInjector.getWifiP2pMonitor()).thenReturn(mWifiMonitor);
        when(mWifiInjector.getFrameworkFacade()).thenReturn(mFrameworkFacade);
        when(mWifiInjector.makeLog(anyString())).thenReturn(mLog);
        when(mWifiInjector.getWifiP2pServiceHandlerThread()).thenReturn(mHandlerThread);
        when(mHandlerThread.getLooper()).thenReturn(mLooper.getLooper());

        mWifiP2pServiceImpl = new WifiP2pServiceImpl(mContext, mWifiInjector);
        mWifiP2pServiceImpl.mNwService = mNwService;

        mP2pStateMachineMessenger = mWifiP2pServiceImpl.getP2pStateMachineMessenger();
        mWifiP2pServiceImpl.setWifiHandlerLogForTest(mLog);
        verify(mContext, times(2)).registerReceiver(mBcastRxCaptor.capture(),
                any(IntentFilter.class));
        mWifiStateChangedReceiver = mBcastRxCaptor.getAllValues().get(0);
        mLocationModeReceiver = mBcastRxCaptor.getAllValues().get(1);

        verify(mWifiNative).registerInterfaceAvailableListener(
                mAvailListenerCaptor.capture(), any(Handler.class));
        mAvailListenerCaptor.getValue().onAvailabilityChanged(true);
        mClient1 = new Binder();
        mClient2 = new Binder();
        when(mWifiNative.setupInterface(any(), any())).thenReturn(IFACE_NAME_P2P);
        when(mFrameworkFacade.getStringSetting(any(),
                eq(Settings.Global.WIFI_P2P_DEVICE_NAME))).thenReturn("p2p_device");
        when(mFrameworkFacade.getIntegerSetting(any(),
                eq(Settings.Global.WIFI_P2P_PENDING_FACTORY_RESET), eq(0))).thenReturn(0);
    }

    /**
     * mock WifiP2pMonitor disconnect to enter disabled state
     */
    private void mockSendWifiP2pMonitorDisconnectEvent() throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pMonitor.SUP_DISCONNECTION_EVENT;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Verify that p2p init / teardown whn a client connects / disconnects
     * with wifi enabled and location enabled
     */
    @Test
    public void testP2pInitWhenClientConnectWithWifiAndLocationEnabled() throws Exception {
        simulateWifiStateChange(true);
        simulateLocationModeChange(true);
        checkIsP2pInitWhenClientConnected(true, mClient1);
        checkIsP2pTearDownWhenClientDisconnected(true, mClient1);
    }

    /**
     * Verify that p2p doesn't init when  a client connects / disconnects
     * with wifi disabled and location enabled
     */
    @Test
    public void testP2pDoesntInitWhenClientConnectWithWifiDisabledAndLocationEnabled()
            throws Exception {
        simulateWifiStateChange(false);
        simulateLocationModeChange(true);
        checkIsP2pInitWhenClientConnected(false, mClient1);
        checkIsP2pTearDownWhenClientDisconnected(false, mClient1);
    }

    /**
     * Verify that p2p doesn't init whe a client connects / disconnects
     * with wifi enabled and location disbaled
     */
    @Test
    public void testP2pDoesntInitWhenClientConnectWithWifiEnabledAndLocationDisabled()
            throws Exception {
        simulateWifiStateChange(true);
        simulateLocationModeChange(false);
        checkIsP2pInitWhenClientConnected(false, mClient1);
        checkIsP2pTearDownWhenClientDisconnected(false, mClient1);
    }

    /**
     * Verify that p2p doesn't init when a client connects / disconnects
     * with wifi disabled and location disbaled
     */
    @Test
    public void testP2pDoesntInitWhenClientConnectWithWifiAndLocationDisabled()
            throws Exception {
        simulateWifiStateChange(false);
        simulateLocationModeChange(false);
        checkIsP2pInitWhenClientConnected(false, mClient1);
        checkIsP2pTearDownWhenClientDisconnected(false, mClient1);
    }

    /**
     * Verify that p2p init / teardown when wifi off / on or location off / on
     * with a client connected
     */
    @Test
    public void checkIsP2pInitForWifiAndLocationModeChanges() throws Exception {
        forceP2pEnabled(mClient1);

        simulateWifiStateChange(false);
        mLooper.dispatchAll();
        verify(mWifiNative).teardownInterface();
        verify(mWifiMonitor).stopMonitoring(anyString());
        // Force to back disable state for next test
        mockSendWifiP2pMonitorDisconnectEvent();

        simulateWifiStateChange(true);
        mLooper.dispatchAll();
        verify(mWifiNative, times(2)).setupInterface(any(), any());
        verify(mNwService, times(2)).setInterfaceUp(anyString());
        verify(mWifiMonitor, atLeastOnce()).registerHandler(anyString(), anyInt(), any());

        simulateLocationModeChange(false);
        mLooper.dispatchAll();
        verify(mWifiNative, times(2)).teardownInterface();
        verify(mWifiMonitor, times(2)).stopMonitoring(anyString());
        mockSendWifiP2pMonitorDisconnectEvent();

        simulateLocationModeChange(true);
        mLooper.dispatchAll();
        verify(mWifiNative, times(3)).setupInterface(any(), any());
        verify(mNwService, times(3)).setInterfaceUp(anyString());
        verify(mWifiMonitor, atLeastOnce()).registerHandler(anyString(), anyInt(), any());
    }

    /**
     * Verify p2p init / teardown when two clients connect / disconnect
     */
    @Test
    public void checkIsP2pInitForTwoClientsConnection() throws Exception {
        forceP2pEnabled(mClient1);
        // P2pInit check count should keep in once, same as one client connected case.
        checkIsP2pInitWhenClientConnected(true, mClient2);
        checkIsP2pTearDownWhenClientDisconnected(false, mClient2);
        checkIsP2pTearDownWhenClientDisconnected(true, mClient1);
    }
}
