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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest;
import android.os.Binder;
import android.os.Bundle;
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
    private static final String thisDeviceMac = "11:22:33:44:55:66";

    private ArgumentCaptor<HalDeviceManager.InterfaceAvailableForRequestListener>
            mAvailListenerCaptor = ArgumentCaptor.forClass(
            HalDeviceManager.InterfaceAvailableForRequestListener.class);
    private ArgumentCaptor<BroadcastReceiver> mBcastRxCaptor = ArgumentCaptor.forClass(
            BroadcastReceiver.class);
    private Binder mClient1;
    private Binder mClient2;

    private BroadcastReceiver mLocationModeReceiver;
    private BroadcastReceiver mWifiStateChangedReceiver;
    private Handler mClientHandler;
    private Messenger mP2pStateMachineMessenger;
    private Messenger mClientMessenger;
    private WifiP2pServiceImpl mWifiP2pServiceImpl;
    private TestLooper mClientHanderLooper;
    private TestLooper mLooper;
    private WifiP2pGroup mTestWifiP2pGroup;
    private WifiP2pDevice mTestWifiP2pDevice;

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
    @Mock WifiP2pServiceInfo mTestWifiP2pServiceInfo;
    @Mock WifiP2pServiceRequest mTestWifiP2pServiceRequest;
    @Spy FakeWifiLog mLog;
    @Spy MockWifiP2pMonitor mWifiMonitor;


    private void generatorTestData() {
        mTestWifiP2pGroup = new WifiP2pGroup();
        mTestWifiP2pGroup.setNetworkName("TestGroupName");
        mTestWifiP2pDevice = new WifiP2pDevice();
        mTestWifiP2pDevice.deviceName = "TestDeviceName";
        mTestWifiP2pDevice.deviceAddress = "aa:bb:cc:dd:ee:ff";
    }

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
     * Mock send WifiP2pManager.UPDATE_CHANNEL_INFO
     */
    private void sendChannelInfoUpdateMsg(String pkgName, Binder binder,
            Messenger replyMessenger) throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pManager.UPDATE_CHANNEL_INFO;
        Bundle bundle = new Bundle();
        bundle.putString(WifiP2pManager.CALLING_PACKAGE, pkgName);
        bundle.putBinder(WifiP2pManager.CALLING_BINDER, binder);
        msg.obj = bundle;
        msg.replyTo = replyMessenger;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Mock send WifiP2pManager.ADD_LOCAL_SERVICE with mTestWifiP2pServiceInfo
     */
    private void sendAddLocalServiceMsg(Messenger replyMessenger) throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pManager.ADD_LOCAL_SERVICE;
        msg.obj = mTestWifiP2pServiceInfo;
        msg.replyTo = replyMessenger;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Mock send WifiP2pManager.CONNECT with ConfigValidAsGroup
     */
    private void sendConnectMsgWithConfigValidAsGroup(Messenger replyMessenger) throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pManager.CONNECT;
        msg.obj = new WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-XY-HELLO")
                .setPassphrase("DEADBEEF")
                .build();
        msg.replyTo = replyMessenger;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Mock send WifiP2pManager.CREATE_GROUP with ConfigValidAsGroup
     */
    private void sendCreateGroupMsgWithConfigValidAsGroup(Messenger replyMessenger)
            throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pManager.CREATE_GROUP;
        msg.obj = new WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-XY-HELLO")
                .setPassphrase("DEADBEEF")
                .build();
        msg.replyTo = replyMessenger;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Mock send WifiP2pManager.DISCOVER_PEERS
     */
    private void sendDiscoverPeersMsg(Messenger replyMessenger) throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pManager.DISCOVER_PEERS;
        msg.replyTo = replyMessenger;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Mock send WifiP2pManager.ADD_SERVICE_REQUEST with mocked mTestWifiP2pServiceRequest
     */
    private void sendAddServiceRequestMsg(Messenger replyMessenger) throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pManager.ADD_SERVICE_REQUEST;
        msg.replyTo = replyMessenger;
        msg.obj = mTestWifiP2pServiceRequest;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Mock send WifiP2pManager.DISCOVER_SERVICES
     */
    private void sendDiscoverServiceMsg(Messenger replyMessenger) throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pManager.DISCOVER_SERVICES;
        msg.replyTo = replyMessenger;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Mock send WifiP2pManager.REQUEST_PEERS
     */
    private void sendRequestPeersMsg(Messenger replyMessenger) throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pManager.REQUEST_PEERS;
        msg.replyTo = replyMessenger;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Mock send WifiP2pManager.REQUEST_GROUP_INFO
     */
    private void sendRequestGroupInfoMsg(Messenger replyMessenger) throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pManager.REQUEST_GROUP_INFO;
        msg.replyTo = replyMessenger;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Mock mPeers via WifiP2pMonitor.P2P_DEVICE_FOUND_EVENT
     */
    private void mockPeersList() throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pMonitor.P2P_DEVICE_FOUND_EVENT;
        msg.obj = mTestWifiP2pDevice;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Mock mGroup via WifiP2pMonitor.P2P_GROUP_STARTED_EVENT
     */
    private void mockGroupCreated() throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pMonitor.P2P_GROUP_STARTED_EVENT;
        msg.obj = mTestWifiP2pGroup;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
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
        mClientHanderLooper = new TestLooper();
        mClientHandler = spy(new Handler(mClientHanderLooper.getLooper()));
        mClientMessenger =  new Messenger(mClientHandler);
        mLooper = new TestLooper();
        generatorTestData();
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
        mWifiP2pServiceImpl.setWifiLogForReplyChannel(mLog);
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
        when(mWifiNative.p2pGetDeviceAddress()).thenReturn(thisDeviceMac);
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

    /**
     * Verify receive WifiP2pManager.ADD_LOCAL_SERVICE msg,
     * but no channel infomation update before.
     */
    @Test
    public void testAddLocalServiceFailureWhenNoChannelUpdated() throws Exception {
        forceP2pEnabled(mClient1);
        sendAddLocalServiceMsg(mClientMessenger);
        verify(mWifiNative, never()).p2pServiceAdd(any());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.ADD_LOCAL_SERVICE_FAILED));
    }

    /**
     * Verify receive WifiP2pManager.ADD_LOCAL_SERVICE msg,
     * but channel info update is not correct.
     */
    @Test
    public void testAddLocalServiceFailureWhenChannelUpdateWrongPkgName() throws Exception {
        forceP2pEnabled(mClient1);
        doThrow(new SecurityException("P2p unit test"))
                .when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendAddLocalServiceMsg(mClientMessenger);
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.ADD_LOCAL_SERVICE_FAILED));
        verify(mWifiNative, never()).p2pServiceAdd(any());
    }

    /**
     * Verify receive WifiP2pManager.ADD_LOCAL_SERVICE msg,
     * and channel info updated correctly, but caller permission deny.
     */
    @Test
    public void testAddLocalServiceFailureWhenCallerPermissionDenied() throws Exception {
        forceP2pEnabled(mClient1);
        doNothing().when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(
                anyString(), anyInt())).thenReturn(false);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendAddLocalServiceMsg(mClientMessenger);
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.ADD_LOCAL_SERVICE_FAILED));
        verify(mWifiNative, never()).p2pServiceAdd(any());
    }

    /**
     * Verify receive WifiP2pManager.ADD_LOCAL_SERVICE msg,
     * and channel info updated correctly with passed permission.
     */
    @Test
    public void testAddLocalServiceSuccess() throws Exception {
        forceP2pEnabled(mClient1);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        doNothing().when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        when(mWifiNative.p2pServiceAdd(any())).thenReturn(true);
        sendAddLocalServiceMsg(mClientMessenger);
        verify(mWifiNative).p2pServiceAdd(any());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.ADD_LOCAL_SERVICE_SUCCEEDED));
    }

    /**
     * Verify receive WifiP2pManager.ADD_LOCAL_SERVICE msg,
     * and channel info updated correctly with passed permission, but native add failed.
     */
    @Test
    public void testAddLocalServiceFailureWhenNativeCallFailure() throws Exception {
        forceP2pEnabled(mClient1);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        doNothing().when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        when(mWifiNative.p2pServiceAdd(any())).thenReturn(false);
        sendAddLocalServiceMsg(mClientMessenger);
        verify(mWifiNative).p2pServiceAdd(any());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.ADD_LOCAL_SERVICE_FAILED));
    }

    /**
     * Verify receive WifiP2pManager.CONNECT msg with ConfigValidAsGroup,
     * but no channel infomation update before.
     */
    @Test
    public void testConnectWithConfigValidAsGroupFailureWhenNoChannelUpdated() throws Exception {
        forceP2pEnabled(mClient1);
        sendConnectMsgWithConfigValidAsGroup(mClientMessenger);
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.CONNECT_FAILED));
        verify(mWifiNative, never()).p2pGroupAdd(any(), anyBoolean());
    }

    /**
     * Verify receive WifiP2pManager.CONNECT msg with ConfigValidAsGroup,
     * but channel info update is not correct.
     */
    @Test
    public void testConnectWithConfigValidAsGroupFailureWhenChannelUpdateWrongPkgName()
            throws Exception {
        forceP2pEnabled(mClient1);
        doThrow(new SecurityException("P2p unit test"))
                .when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendConnectMsgWithConfigValidAsGroup(mClientMessenger);
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.CONNECT_FAILED));
        verify(mWifiNative, never()).p2pGroupAdd(any(), anyBoolean());
    }

    /**
     * Verify receive WifiP2pManager.CONNECT msg with ConfigValidAsGroup,
     * and channel info updated correctly, but caller permission deny.
     */
    @Test
    public void testConnectWithConfigValidAsGroupFailureWhenPermissionDenied() throws Exception {
        forceP2pEnabled(mClient1);
        doNothing().when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(false);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendConnectMsgWithConfigValidAsGroup(mClientMessenger);
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.CONNECT_FAILED));
        verify(mWifiNative, never()).p2pGroupAdd(any(), anyBoolean());
    }

    /**
     * Verify receive WifiP2pManager.CONNECT msg with ConfigValidAsGroup,
     * and channel info updated correctly with passed permission.
     */
    @Test
    public void testConnectWithConfigValidAsGroupSuccess() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        when(mWifiNative.p2pGroupAdd(any(), eq(true))).thenReturn(true);
        sendConnectMsgWithConfigValidAsGroup(mClientMessenger);
        verify(mWifiNative).p2pGroupAdd(any(), eq(true));
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.CONNECT_SUCCEEDED));
    }

    /**
     * Verify receive WifiP2pManager.CONNECT msg with ConfigValidAsGroup,
     * and channel info updated correctly with passed permission but failed in native call.
     */
    @Test
    public void testConnectWithConfigValidAsGroupFailureWhenNativeCallFailure() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        when(mWifiNative.p2pGroupAdd(any(), eq(true))).thenReturn(false);
        sendConnectMsgWithConfigValidAsGroup(mClientMessenger);
        verify(mWifiNative).p2pGroupAdd(any(), eq(true));
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.CONNECT_FAILED));
    }

    /**
     * Verify receive WifiP2pManager.CREATE_GROUP msg with ConfigValidAsGroup,
     * but no channel infomation update before.
     */
    @Test
    public void testCreateGroupWithConfigValidAsGroupFailureWhenNoChannelUpdated()
            throws Exception {
        forceP2pEnabled(mClient1);
        sendCreateGroupMsgWithConfigValidAsGroup(mClientMessenger);
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.CREATE_GROUP_FAILED));
        verify(mWifiNative, never()).p2pGroupAdd(anyBoolean());
        verify(mWifiNative, never()).p2pGroupAdd(any(), anyBoolean());
    }

    /**
     * Verify receive WifiP2pManager.CREATE_GROUP msg with ConfigValidAsGroup,
     * but channel info update is not correct.
     */
    @Test
    public void testCreateGroupWithConfigValidAsGroupFailureWhenChannelUpdateWrongPkgName()
            throws Exception {
        forceP2pEnabled(mClient1);
        doThrow(new SecurityException("P2p unit test"))
                .when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendCreateGroupMsgWithConfigValidAsGroup(mClientMessenger);
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.CREATE_GROUP_FAILED));
        verify(mWifiNative, never()).p2pGroupAdd(anyBoolean());
        verify(mWifiNative, never()).p2pGroupAdd(any(), anyBoolean());
    }

    /**
     * Verify receive WifiP2pManager.CREATE_GROUP msg with ConfigValidAsGroup,
     * and channel info updated correctly, but caller permission deny.
     */
    @Test
    public void testCreateGroupWithConfigValidAsGroupFailureWhenPermissionDenied()
            throws Exception {
        forceP2pEnabled(mClient1);
        doNothing().when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(false);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendCreateGroupMsgWithConfigValidAsGroup(mClientMessenger);
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.CREATE_GROUP_FAILED));
        verify(mWifiNative, never()).p2pGroupAdd(anyBoolean());
        verify(mWifiNative, never()).p2pGroupAdd(any(), anyBoolean());
    }

    /**
     * Verify receive WifiP2pManager.CREATE_GROUP msg with ConfigValidAsGroup,
     * and channel info updated correctly with passed permission.
     */
    @Test
    public void testCreateGroupWithConfigValidAsGroupSuccess() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        when(mWifiNative.p2pGroupAdd(any(), eq(false))).thenReturn(true);
        sendCreateGroupMsgWithConfigValidAsGroup(mClientMessenger);
        verify(mWifiNative).p2pGroupAdd(any(), eq(false));
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.CREATE_GROUP_SUCCEEDED));
    }

    /**
     * Verify receive WifiP2pManager.CREATE_GROUP msg with ConfigValidAsGroup,
     * and channel info updated correctly with passed permission but failed in native call.
     */
    @Test
    public void testCreateGroupWithConfigValidAsGroupFailureWhenNativeCallFailure()
            throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        when(mWifiNative.p2pGroupAdd(any(), eq(false))).thenReturn(false);
        sendCreateGroupMsgWithConfigValidAsGroup(mClientMessenger);
        verify(mWifiNative).p2pGroupAdd(any(), eq(false));
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.CREATE_GROUP_FAILED));
    }

    /**
     * Verify receive WifiP2pManager.DISCOVER_PEERS msg,
     * but no channel infomation update before.
     */
    @Test
    public void testDiscoverPeersFailureWhenNoChannelUpdated() throws Exception {
        forceP2pEnabled(mClient1);
        sendDiscoverPeersMsg(mClientMessenger);
        verify(mWifiNative, never()).p2pFind(anyInt());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.DISCOVER_PEERS_FAILED));
    }

    /**
     * Verify receive WifiP2pManager.DISCOVER_PEERS msg,
     * but channel info update is not correct.
     */
    @Test
    public void testDiscoverPeersFailureWhenChannelUpdateWrongPkgName() throws Exception {
        forceP2pEnabled(mClient1);
        doThrow(new SecurityException("P2p unit test"))
                .when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendDiscoverPeersMsg(mClientMessenger);
        verify(mWifiNative, never()).p2pFind(anyInt());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.DISCOVER_PEERS_FAILED));
    }

    /**
     * Verify receive WifiP2pManager.DISCOVER_PEERS msg,
     * and channel info updated correctly, but caller permission deny.
     */
    @Test
    public void testDiscoverPeersFailureWhenPermissionDenied() throws Exception {
        forceP2pEnabled(mClient1);
        doNothing().when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(false);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendDiscoverPeersMsg(mClientMessenger);
        verify(mWifiNative, never()).p2pFind(anyInt());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.DISCOVER_PEERS_FAILED));
    }

    /**
     * Verify receive WifiP2pManager.DISCOVER_PEERS msg,
     * and channel info updated correctly with passed permission.
     */
    @Test
    public void testDiscoverPeersSuccess() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        when(mWifiNative.p2pFind(anyInt())).thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendDiscoverPeersMsg(mClientMessenger);
        verify(mWifiNative).p2pFind(anyInt());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.DISCOVER_PEERS_SUCCEEDED));
    }

    /**
     * Verify receive WifiP2pManager.DISCOVER_PEERS msg,
     * and channel info updated correctly with passed permission but failed in native call.
     */
    @Test
    public void testDiscoverPeersFailureWhenNativeCallFailure() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt())).thenReturn(true);
        when(mWifiNative.p2pFind(anyInt())).thenReturn(false);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendDiscoverPeersMsg(mClientMessenger);
        verify(mWifiNative).p2pFind(anyInt());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.DISCOVER_PEERS_FAILED));
    }


    /**
     * Verify receive WifiP2pManager.DISCOVER_SERVICES msg,
     * but no channel infomation update before.
     */
    @Test
    public void testDiscoverServicesFailureWhenNoChannelUpdated() throws Exception {
        when(mWifiNative.p2pServDiscReq(anyString(), anyString())).thenReturn("mServiceDiscReqId");
        forceP2pEnabled(mClient1);
        sendAddServiceRequestMsg(mClientMessenger);
        sendDiscoverServiceMsg(mClientMessenger);
        verify(mWifiNative, never()).p2pServDiscReq(anyString(), anyString());
        verify(mWifiNative, never()).p2pFind(anyInt());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.DISCOVER_SERVICES_FAILED));
    }

    /**
     * Verify receive WifiP2pManager.DISCOVER_SERVICES msg,
     * but channel info update is not correct.
     */
    @Test
    public void testDiscoverServicesFailureWhenChannelUpdateWrongPkgName() throws Exception {
        when(mWifiNative.p2pServDiscReq(anyString(), anyString())).thenReturn("mServiceDiscReqId");
        forceP2pEnabled(mClient1);
        doThrow(new SecurityException("P2p unit test"))
                .when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendAddServiceRequestMsg(mClientMessenger);
        sendDiscoverServiceMsg(mClientMessenger);
        verify(mWifiNative, never()).p2pServDiscReq(anyString(), anyString());
        verify(mWifiNative, never()).p2pFind(anyInt());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.DISCOVER_SERVICES_FAILED));
    }

    /**
     * Verify receive WifiP2pManager.DISCOVER_SERVICES msg,
     * and channel info updated correctly, but caller permission deny.
     */
    @Test
    public void testDiscoverServicesFailureWhenPermissionDenied() throws Exception {
        when(mWifiNative.p2pServDiscReq(anyString(), anyString()))
                .thenReturn("mServiceDiscReqId");
        forceP2pEnabled(mClient1);
        doNothing().when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(false);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendAddServiceRequestMsg(mClientMessenger);
        sendDiscoverServiceMsg(mClientMessenger);
        verify(mWifiNative, never()).p2pServDiscReq(anyString(), anyString());
        verify(mWifiNative, never()).p2pFind(anyInt());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.DISCOVER_SERVICES_FAILED));
    }

    /**
     * Verify receive WifiP2pManager.DISCOVER_SERVICES msg,
     * and channel info updated correctly with passed permission.
     */
    @Test
    public void testDiscoverServicesSuccess() throws Exception {
        when(mWifiNative.p2pServDiscReq(anyString(), anyString()))
                .thenReturn("mServiceDiscReqId");
        when(mWifiNative.p2pFind(anyInt())).thenReturn(true);
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendAddServiceRequestMsg(mClientMessenger);
        sendDiscoverServiceMsg(mClientMessenger);
        verify(mWifiNative).p2pServDiscReq(anyString(), anyString());
        verify(mWifiNative).p2pFind(anyInt());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.DISCOVER_SERVICES_SUCCEEDED));
    }

    /**
     * Verify receive WifiP2pManager.DISCOVER_SERVICES msg,
     * and channel info updated correctly with passed permission,
     * but fail in add service request.
     */
    @Test
    public void testDiscoverServicesFailureWhenAddServiceRequestFailure() throws Exception {
        when(mWifiNative.p2pServDiscReq(anyString(), anyString())).thenReturn(null);
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendAddServiceRequestMsg(mClientMessenger);
        sendDiscoverServiceMsg(mClientMessenger);
        verify(mWifiNative).p2pServDiscReq(anyString(), anyString());
        verify(mWifiNative, never()).p2pFind(anyInt());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.DISCOVER_SERVICES_FAILED));
    }

    /**
     * Verify receive WifiP2pManager.DISCOVER_SERVICES msg,
     * and channel info updated correctly with passed permission.
     */
    @Test
    public void testDiscoverServicesFailureWhenNativeCallFailure() throws Exception {
        when(mWifiNative.p2pServDiscReq(anyString(), anyString()))
                .thenReturn("mServiceDiscReqId");
        when(mWifiNative.p2pFind(anyInt())).thenReturn(false);
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendAddServiceRequestMsg(mClientMessenger);
        sendDiscoverServiceMsg(mClientMessenger);
        verify(mWifiNative).p2pServDiscReq(anyString(), anyString());
        verify(mWifiNative).p2pFind(anyInt());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.DISCOVER_SERVICES_FAILED));
    }

    /**
     * Verify receive WifiP2pManager.REQUEST_PEERS msg,
     * but no channel infomation update before.
     */
    @Test
    public void testRequestPeersFailureWhenNoChannelUpdated() throws Exception {
        forceP2pEnabled(mClient1);
        mockPeersList();
        sendRequestPeersMsg(mClientMessenger);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientHandler).sendMessage(messageCaptor.capture());
        WifiP2pDeviceList peers = (WifiP2pDeviceList) messageCaptor.getValue().obj;
        assertEquals(WifiP2pManager.RESPONSE_PEERS, messageCaptor.getValue().what);
        assertEquals(null, peers.get(mTestWifiP2pDevice.deviceAddress));

    }

    /**
     * Verify receive WifiP2pManager.REQUEST_PEERS msg,
     * but channel info update is not correct.
     */
    @Test
    public void testRequestPeersFailureWhenChannelUpdateWrongPkgName() throws Exception {
        forceP2pEnabled(mClient1);
        mockPeersList();
        doThrow(new SecurityException("P2p unit test"))
                .when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendRequestPeersMsg(mClientMessenger);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientHandler).sendMessage(messageCaptor.capture());
        WifiP2pDeviceList peers = (WifiP2pDeviceList) messageCaptor.getValue().obj;
        assertEquals(WifiP2pManager.RESPONSE_PEERS, messageCaptor.getValue().what);
        assertEquals(null, peers.get(mTestWifiP2pDevice.deviceAddress));
    }

    /**
     * Verify receive WifiP2pManager.REQUEST_PEERS msg,
     * and channel info updated correctly, but caller permission deny.
     */
    @Test
    public void testRequestPeersFailureWhenPermissionDenied() throws Exception {
        forceP2pEnabled(mClient1);
        mockPeersList();
        doNothing().when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(false);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendRequestPeersMsg(mClientMessenger);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientHandler).sendMessage(messageCaptor.capture());
        WifiP2pDeviceList peers = (WifiP2pDeviceList) messageCaptor.getValue().obj;
        assertEquals(WifiP2pManager.RESPONSE_PEERS, messageCaptor.getValue().what);
        assertEquals(null, peers.get(mTestWifiP2pDevice.deviceAddress));

    }

    /**
     * Verify receive WifiP2pManager.REQUEST_PEERS msg,
     * and channel info updated correctly with passed permission.
     */
    @Test
    public void testRequestPeersSuccess() throws Exception {
        forceP2pEnabled(mClient1);
        mockPeersList();
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt())).thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendRequestPeersMsg(mClientMessenger);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientHandler).sendMessage(messageCaptor.capture());
        WifiP2pDeviceList peers = (WifiP2pDeviceList) messageCaptor.getValue().obj;
        assertEquals(WifiP2pManager.RESPONSE_PEERS, messageCaptor.getValue().what);
        assertNotEquals(null, peers.get(mTestWifiP2pDevice.deviceAddress));
    }

    /**
     * Verify receive WifiP2pManager.REQUEST_GROUP_INFO msg,
     * but no channel infomation update before.
     */
    @Test
    public void testRequestGroupInfoFailureWhenNoChannelUpdated() throws Exception {
        forceP2pEnabled(mClient1);
        mockGroupCreated();
        sendRequestGroupInfoMsg(mClientMessenger);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientHandler).sendMessage(messageCaptor.capture());
        assertEquals(WifiP2pManager.RESPONSE_GROUP_INFO, messageCaptor.getValue().what);
        assertEquals(null, messageCaptor.getValue().obj);
    }

    /**
     * Verify receive WifiP2pManager.REQUEST_GROUP_INFO msg,
     * but channel info update is not correct.
     */
    @Test
    public void testRequestGroupInfoFailureWhenChannelUpdateWrongPkgName() throws Exception {
        forceP2pEnabled(mClient1);
        mockGroupCreated();
        doThrow(new SecurityException("P2p unit test"))
                .when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendRequestGroupInfoMsg(mClientMessenger);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientHandler).sendMessage(messageCaptor.capture());
        assertEquals(WifiP2pManager.RESPONSE_GROUP_INFO, messageCaptor.getValue().what);
        assertEquals(null, messageCaptor.getValue().obj);
    }

    /**
     * Verify receive WifiP2pManager.REQUEST_GROUP_INFO msg,
     * and channel info updated correctly, but caller permission deny.
     */
    @Test
    public void testRequestGroupInfoFailureWhenPermissionDenied() throws Exception {
        forceP2pEnabled(mClient1);
        mockGroupCreated();
        doNothing().when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(false);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendRequestGroupInfoMsg(mClientMessenger);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientHandler).sendMessage(messageCaptor.capture());
        assertEquals(WifiP2pManager.RESPONSE_GROUP_INFO, messageCaptor.getValue().what);
        assertEquals(null, messageCaptor.getValue().obj);
    }

    /**
     * Verify receive WifiP2pManager.REQUEST_GROUP_INFO msg,
     * and channel info updated correctly with passed permission.
     */
    @Test
    public void testRequestGroupInfoSuccess() throws Exception {
        forceP2pEnabled(mClient1);
        mockGroupCreated();
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt())).thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendRequestGroupInfoMsg(mClientMessenger);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientHandler).sendMessage(messageCaptor.capture());
        assertEquals(WifiP2pManager.RESPONSE_GROUP_INFO, messageCaptor.getValue().what);
        WifiP2pGroup wifiP2pGroup = (WifiP2pGroup) messageCaptor.getValue().obj;
        assertEquals(mTestWifiP2pGroup.getNetworkName(), wifiP2pGroup.getNetworkName());
    }
}
