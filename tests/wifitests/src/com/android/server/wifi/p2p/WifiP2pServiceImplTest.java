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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.test.MockAnswerUtil.AnswerWithArguments;
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
import android.net.wifi.p2p.WifiP2pGroupList;
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
import android.os.UserManager;
import android.os.test.TestLooper;
import android.provider.Settings;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.server.wifi.FakeWifiLog;
import com.android.server.wifi.FrameworkFacade;
import com.android.server.wifi.HalDeviceManager;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.nano.WifiMetricsProto.P2pConnectionEvent;
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
    private static final String thisDeviceName = "thisDeviceName";

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
    private WifiP2pConfig mTestWifiP2pPeerConfig;
    private WifiP2pConfig mTestWifiP2pFastConnectionConfig;
    private WifiP2pGroup mTestWifiP2pNewPersistentGoGroup;
    private WifiP2pGroup mTestWifiP2pGroup;
    private WifiP2pDevice mTestWifiP2pDevice;
    private WifiP2pGroupList mGroups = new WifiP2pGroupList(null, null);

    @Mock Context mContext;
    @Mock FrameworkFacade mFrameworkFacade;
    @Mock HandlerThread mHandlerThread;
    @Mock INetworkManagementService mNwService;
    @Mock PackageManager mPackageManager;
    @Mock Resources mResources;
    @Mock WifiInjector mWifiInjector;
    @Mock WifiManager mMockWifiManager;
    @Mock WifiPermissionsUtil mWifiPermissionsUtil;
    @Mock WifiP2pNative mWifiNative;
    @Mock WifiP2pServiceInfo mTestWifiP2pServiceInfo;
    @Mock WifiP2pServiceRequest mTestWifiP2pServiceRequest;
    @Mock UserManager mUserManager;
    @Mock WifiP2pMetrics mWifiP2pMetrics;
    @Spy FakeWifiLog mLog;
    @Spy MockWifiP2pMonitor mWifiMonitor;


    private void generatorTestData() {
        mTestWifiP2pGroup = new WifiP2pGroup();
        mTestWifiP2pGroup.setNetworkName("TestGroupName");
        mTestWifiP2pDevice = spy(new WifiP2pDevice());
        mTestWifiP2pDevice.deviceName = "TestDeviceName";
        mTestWifiP2pDevice.deviceAddress = "aa:bb:cc:dd:ee:ff";

        // for general connect command
        mTestWifiP2pPeerConfig = new WifiP2pConfig();
        mTestWifiP2pPeerConfig.deviceAddress = mTestWifiP2pDevice.deviceAddress;

        // for fast-connection connect command
        mTestWifiP2pFastConnectionConfig = new WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-XY-HELLO")
                .setPassphrase("DEADBEEF")
                .build();

        // for general group started event
        mTestWifiP2pNewPersistentGoGroup = new WifiP2pGroup();
        mTestWifiP2pNewPersistentGoGroup.setNetworkId(WifiP2pGroup.PERSISTENT_NET_ID);
        mTestWifiP2pNewPersistentGoGroup.setNetworkName("DIRECT-xy-NEW");
        mTestWifiP2pNewPersistentGoGroup.setOwner(new WifiP2pDevice(thisDeviceMac));
        mTestWifiP2pNewPersistentGoGroup.setIsGroupOwner(true);

        mGroups.clear();
        WifiP2pGroup group1 = new WifiP2pGroup();
        group1.setNetworkId(0);
        group1.setNetworkName(mTestWifiP2pGroup.getNetworkName());
        group1.setOwner(mTestWifiP2pDevice);
        group1.setIsGroupOwner(false);
        mGroups.add(group1);

        WifiP2pGroup group2 = new WifiP2pGroup();
        group2.setNetworkId(1);
        group2.setNetworkName("DIRECT-ab-Hello");
        group2.setOwner(new WifiP2pDevice("12:34:56:78:90:ab"));
        group2.setIsGroupOwner(false);
        mGroups.add(group2);

        WifiP2pGroup group3 = new WifiP2pGroup();
        group3.setNetworkId(2);
        group3.setNetworkName("DIRECT-cd-OWNER");
        group3.setOwner(new WifiP2pDevice(thisDeviceMac));
        group3.setIsGroupOwner(true);
        mGroups.add(group3);
    }

    /**
     * Simulate Location Mode change: Changes the location manager return values and dispatches a
     * broadcast.
     *
     * @param isLocationModeEnabled whether the location mode is enabled.,
     */
    private void simulateLocationModeChange(boolean isLocationModeEnabled) {
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(isLocationModeEnabled);

        Intent intent = new Intent(LocationManager.MODE_CHANGED_ACTION);
        mLocationModeReceiver.onReceive(mContext, intent);
    }

    /**
     * Simulate Wi-Fi state change: broadcast state change and modify the API return value.
     *
     * @param isWifiOn whether the wifi mode is enabled.
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
     *
     * @param pkgName package name used for p2p channel init
     * @param binder client binder used for p2p channel init
     * @param replyMessenger for checking replied message.
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
     *
     * @param replyMessenger for checking replied message.
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
     *
     * @param replyMessenger for checking replied message.
     */
    private void sendConnectMsgWithConfigValidAsGroup(Messenger replyMessenger) throws Exception {
        sendConnectMsg(replyMessenger, mTestWifiP2pFastConnectionConfig);
    }

    /**
     * Mock send WifiP2pManager.CREATE_GROUP with ConfigValidAsGroup
     *
     * @param replyMessenger for checking replied message.
     */
    private void sendCreateGroupMsgWithConfigValidAsGroup(Messenger replyMessenger)
            throws Exception {
        sendCreateGroupMsg(replyMessenger, 0, mTestWifiP2pFastConnectionConfig);
    }

    /**
     * Mock send WifiP2pManager.DISCOVER_PEERS
     *
     * @param replyMessenger for checking replied message.
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
     *
     * @param replyMessenger for checking replied message.
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
     *
     * @param replyMessenger for checking replied message.
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
     *
     * @param replyMessenger for checking replied message.
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
     *
     * @param replyMessenger for checking replied message.
     */
    private void sendRequestGroupInfoMsg(Messenger replyMessenger) throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pManager.REQUEST_GROUP_INFO;
        msg.replyTo = replyMessenger;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Mock send WifiP2pManager.DELETE_PERSISTENT_GROUP.
     *
     * @param replyMessenger for checking replied message.
     * @param netId is the network id of the p2p group.
     */
    private void sendDeletePersistentGroupMsg(Messenger replyMessenger,
            int netId) throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pManager.DELETE_PERSISTENT_GROUP;
        msg.arg1 = netId;
        msg.replyTo = replyMessenger;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Send WifiP2pMonitor.P2P_GROUP_STARTED_EVENT.
     *
     * @param group the started group.
     */
    private void sendGroupStartedMsg(WifiP2pGroup group) throws Exception {
        if (group.getNetworkId() == WifiP2pGroup.PERSISTENT_NET_ID) {
            mGroups.add(group);
        }

        Message msg = Message.obtain();
        msg.what = WifiP2pMonitor.P2P_GROUP_STARTED_EVENT;
        msg.obj = group;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Send WifiP2pMonitor.P2P_GROUP_REMOVED_EVENT.
     */
    private void sendGroupRemovedMsg() throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pMonitor.P2P_GROUP_REMOVED_EVENT;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Send WifiP2pMonitor.P2P_DEVICE_FOUND_EVENT.
     *
     * @param device the found device.
     */
    private void sendDeviceFoundEventMsg(WifiP2pDevice device) throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pMonitor.P2P_DEVICE_FOUND_EVENT;
        msg.obj = device;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Send WifiP2pMonitor.P2P_INVITATION_RESULT_EVENT.
     *
     * @param status invitation result.
     */
    private void sendInvitationResultMsg(
            WifiP2pServiceImpl.P2pStatus status) throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pMonitor.P2P_INVITATION_RESULT_EVENT;
        msg.obj = status;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Send Connect API msg.
     *
     * @param replyMessenger for checking replied message.
     * @param config options as described in {@link WifiP2pConfig} class.
     */
    private void sendConnectMsg(Messenger replyMessenger,
            WifiP2pConfig config) throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pManager.CONNECT;
        msg.obj = config;
        msg.replyTo = replyMessenger;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Send CreateGroup API msg.
     *
     * @param replyMessenger for checking replied message.
     * @param config options as described in {@link WifiP2pConfig} class.
     */
    private void sendCreateGroupMsg(Messenger replyMessenger,
            int netId,
            WifiP2pConfig config) throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pManager.CREATE_GROUP;
        msg.arg1 = netId;
        msg.obj = config;
        msg.replyTo = replyMessenger;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Send simple API msg.
     *
     * Mock the API msg without arguments.
     *
     * @param replyMessenger for checking replied message.
     */
    private void sendSimpleMsg(Messenger replyMessenger,
            int what) throws Exception {
        Message msg = Message.obtain();
        msg.what = what;
        if (replyMessenger != null) {
            msg.replyTo = replyMessenger;
        }
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * force P2p State enter InactiveState to start others unit test
     *
     * @param clientBinder client binder to use for p2p channel init
     */
    private void forceP2pEnabled(Binder clientBinder) throws Exception {
        simulateWifiStateChange(true);
        simulateLocationModeChange(true);
        checkIsP2pInitWhenClientConnected(true, clientBinder);
    }

    /**
     * Check is P2p init as expected when client connected
     *
     * @param expectInit set true if p2p init should succeed as expected, set false when
     *        expected init should not happen
     * @param clientBinder client binder to use for p2p channel init
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
     * Check is P2p teardown as expected when client disconnected
     *
     * @param expectTearDown set true if p2p teardown should succeed as expected,
     *        set false when expected teardown should not happen
     * @param clientBinder client binder to use for p2p channel init
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

    /**
     * Set up the instance of WifiP2pServiceImpl for testing.
     *
     * @param supported defines the p2p is supported or not in this instance.
     */
    private void setUpWifiP2pServiceImpl(boolean supported) {
        reset(mContext, mFrameworkFacade, mHandlerThread, mPackageManager, mResources,
                mWifiInjector, mWifiNative);

        generatorTestData();
        mClientHanderLooper = new TestLooper();
        mClientHandler = spy(new Handler(mClientHanderLooper.getLooper()));
        mClientMessenger =  new Messenger(mClientHandler);
        mLooper = new TestLooper();

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getResources()).thenReturn(mResources);
        when(mFrameworkFacade.getStringSetting(any(),
                eq(Settings.Global.WIFI_P2P_DEVICE_NAME))).thenReturn(thisDeviceName);
        when(mFrameworkFacade.getIntegerSetting(any(),
                eq(Settings.Global.WIFI_P2P_PENDING_FACTORY_RESET), eq(0))).thenReturn(0);
        when(mHandlerThread.getLooper()).thenReturn(mLooper.getLooper());
        if (supported) {
            when(mPackageManager.hasSystemFeature(eq(PackageManager.FEATURE_WIFI_DIRECT)))
                    .thenReturn(true);
        } else {
            when(mPackageManager.hasSystemFeature(eq(PackageManager.FEATURE_WIFI_DIRECT)))
                    .thenReturn(false);
        }
        when(mResources.getString(R.string.config_wifi_p2p_device_type))
                .thenReturn("10-0050F204-5");
        when(mWifiInjector.getFrameworkFacade()).thenReturn(mFrameworkFacade);
        when(mWifiInjector.getUserManager()).thenReturn(mUserManager);
        when(mWifiInjector.getWifiP2pMetrics()).thenReturn(mWifiP2pMetrics);
        when(mWifiInjector.getWifiP2pMonitor()).thenReturn(mWifiMonitor);
        when(mWifiInjector.getWifiP2pNative()).thenReturn(mWifiNative);
        when(mWifiInjector.getWifiP2pServiceHandlerThread()).thenReturn(mHandlerThread);
        when(mWifiInjector.getWifiPermissionsUtil()).thenReturn(mWifiPermissionsUtil);
        when(mWifiNative.setupInterface(any(), any())).thenReturn(IFACE_NAME_P2P);
        when(mWifiNative.p2pGetDeviceAddress()).thenReturn(thisDeviceMac);
        doAnswer(new AnswerWithArguments() {
            public boolean answer(WifiP2pGroupList groups) {
                groups.clear();
                for (WifiP2pGroup group : mGroups.getGroupList()) {
                    groups.add(group);
                }
                return true;
            }
        }).when(mWifiNative).p2pListNetworks(any(WifiP2pGroupList.class));
        doAnswer(new AnswerWithArguments() {
            public boolean answer(int netId) {
                mGroups.remove(netId);
                return true;
            }
        }).when(mWifiNative).removeP2pNetwork(anyInt());

        mWifiP2pServiceImpl = new WifiP2pServiceImpl(mContext, mWifiInjector);
        if (supported) {
            verify(mContext, times(2)).registerReceiver(mBcastRxCaptor.capture(),
                    any(IntentFilter.class));
            mWifiStateChangedReceiver = mBcastRxCaptor.getAllValues().get(0);
            mLocationModeReceiver = mBcastRxCaptor.getAllValues().get(1);
            verify(mWifiNative).registerInterfaceAvailableListener(
                    mAvailListenerCaptor.capture(), any(Handler.class));
            mAvailListenerCaptor.getValue().onAvailabilityChanged(true);
        }

        mWifiP2pServiceImpl.mNwService = mNwService;
        mP2pStateMachineMessenger = mWifiP2pServiceImpl.getP2pStateMachineMessenger();
        mWifiP2pServiceImpl.setWifiHandlerLogForTest(mLog);
        mWifiP2pServiceImpl.setWifiLogForReplyChannel(mLog);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        setUpWifiP2pServiceImpl(true);
        mClient1 = new Binder();
        mClient2 = new Binder();
    }

    /**
     * Mock enter Disabled state.
     */
    private void mockEnterDisabledState() throws Exception {
        Message msg = Message.obtain();
        msg.what = WifiP2pMonitor.SUP_DISCONNECTION_EVENT;
        mP2pStateMachineMessenger.send(Message.obtain(msg));
        mLooper.dispatchAll();
    }

    /**
     * Mock enter GroupNegotiation state.
     */
    private void mockEnterGroupNegotiationState() throws Exception {
        sendCreateGroupMsg(mClientMessenger, WifiP2pGroup.TEMPORARY_NET_ID, null);
    }


    /**
     * Mock enter ProvisionDiscovery state.
     */
    private void mockEnterProvisionDiscoveryState() throws Exception {
        mockPeersList();
        sendConnectMsg(mClientMessenger, mTestWifiP2pPeerConfig);
    }

    /**
     * Mock WifiP2pServiceImpl.mPeers.
     */
    private void mockPeersList() throws Exception {
        sendDeviceFoundEventMsg(mTestWifiP2pDevice);
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
     * with wifi enabled and location disabled
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
     * with wifi disabled and location disabled
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
        mockEnterDisabledState();

        simulateWifiStateChange(true);
        mLooper.dispatchAll();
        verify(mWifiNative, times(2)).setupInterface(any(), any());
        verify(mNwService, times(2)).setInterfaceUp(anyString());
        verify(mWifiMonitor, atLeastOnce()).registerHandler(anyString(), anyInt(), any());

        simulateLocationModeChange(false);
        mLooper.dispatchAll();
        verify(mWifiNative, times(2)).teardownInterface();
        verify(mWifiMonitor, times(2)).stopMonitoring(anyString());
        mockEnterDisabledState();

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
     * Verify WifiP2pManager.ADD_LOCAL_SERVICE_FAILED is returned when a caller
     * uses abnormal way to send WifiP2pManager.ADD_LOCAL_SERVICE (i.e no channel info updated).
     */
    @Test
    public void testAddLocalServiceFailureWhenNoChannelUpdated() throws Exception {
        forceP2pEnabled(mClient1);
        sendAddLocalServiceMsg(mClientMessenger);
        verify(mWifiNative, never()).p2pServiceAdd(any());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.ADD_LOCAL_SERVICE_FAILED));
    }

    /**
     * Verify WifiP2pManager.ADD_LOCAL_SERVICE_FAILED is returned when a caller
     * uses wrong package name to initialize a channel.
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
     * Verify WifiP2pManager.ADD_LOCAL_SERVICE_FAILED is returned when a caller
     * without proper permission attmepts to send WifiP2pManager.ADD_LOCAL_SERVICE.
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
     * Verify the caller with proper permission sends WifiP2pManager.ADD_LOCAL_SERVICE.
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
     * Verify WifiP2pManager.ADD_LOCAL_SERVICE_FAILED is returned when native call failure.
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
     * Verify WifiP2pManager.CONNECT_FAILED is returned when a caller
     * uses abnormal way to send WifiP2pManager.CONNECT (i.e no channel info updated).
     */
    @Test
    public void testConnectWithConfigValidAsGroupFailureWhenNoChannelUpdated() throws Exception {
        forceP2pEnabled(mClient1);
        sendConnectMsgWithConfigValidAsGroup(mClientMessenger);
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.CONNECT_FAILED));
        verify(mWifiNative, never()).p2pGroupAdd(any(), anyBoolean());
    }

    /**
     * Verify WifiP2pManager.CONNECT_FAILED is returned when a caller
     * uses wrong package name to initialize a channel.
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
     * Verify WifiP2pManager.CONNECT_FAILED is returned when a caller
     * without proper permission attmepts to send WifiP2pManager.CONNECT.
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
     * Verify the caller with proper permission sends WifiP2pManager.CONNECT.
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
     * Verify WifiP2pManager.CONNECT_FAILED is returned when native call failure.
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
     * Verify WifiP2pManager.CREATE_GROUP_FAILED is returned when a caller
     * uses abnormal way to send WifiP2pManager.CREATE_GROUP (i.e no channel info updated).
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
     * Verify WifiP2pManager.CREATE_GROUP_FAILED is returned with null object when a caller
     * uses wrong package name to initialize a channel.
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
     * Verify WifiP2pManager.CREATE_GROUP_FAILED is returned when a caller
     * without proper permission attmepts to send WifiP2pManager.CREATE_GROUP.
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
     * Verify the caller with proper permission sends WifiP2pManager.CREATE_GROUP.
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
     * Verify WifiP2pManager.CREATE_GROUP_FAILED is returned when native call failure.
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
     * Verify WifiP2pManager.DISCOVER_PEERS_FAILED is returned when a caller
     * uses abnormal way to send WifiP2pManager.REQUEST_PEERS (i.e no channel info updated).
     */
    @Test
    public void testDiscoverPeersFailureWhenNoChannelUpdated() throws Exception {
        forceP2pEnabled(mClient1);
        sendDiscoverPeersMsg(mClientMessenger);
        verify(mWifiNative, never()).p2pFind(anyInt());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.DISCOVER_PEERS_FAILED));
    }

    /**
     * Verify WifiP2pManager.DISCOVER_PEERS_FAILED is returned when a caller
     * uses wrong package name to initialize a channel.
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
     * Verify WifiP2pManager.DISCOVER_PEERS_FAILED is returned with null object when a caller
     * without proper permission attmepts to send WifiP2pManager.DISCOVER_PEERS.
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
     * Verify the caller with proper permission sends WifiP2pManager.DISCOVER_PEERS.
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
     * Verify WifiP2pManager.DISCOVER_PEERS_FAILED is returned when native call failure.
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
     * Verify WifiP2pManager.DISCOVER_SERVICES_FAILED is returned when a caller
     * uses abnormal way to send WifiP2pManager.DISCOVER_SERVICES (i.e no channel info updated).
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
     * Verify WifiP2pManager.DISCOVER_SERVICES_FAILED is returned when a caller
     * uses wrong package name to initialize a channel.
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
     * Verify WifiP2pManager.DISCOVER_SERVICES_FAILED is returned when a caller
     * without proper permission attmepts to send WifiP2pManager.DISCOVER_SERVICES.
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
     * Verify the caller with proper permission sends WifiP2pManager.DISCOVER_SERVICES.
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
     * Verify WifiP2pManager.DISCOVER_SERVICES_FAILED is returned when add service failure.
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
     * Verify WifiP2pManager.DISCOVER_SERVICES_FAILED is returned when native call failure.
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
     * Verify WifiP2pManager.RESPONSE_PEERS is returned with null object when a caller
     * uses abnormal way to send WifiP2pManager.REQUEST_PEERS (i.e no channel info updated).
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
     * Verify WifiP2pManager.RESPONSE_PEERS is returned with null object when a caller
     * uses wrong package name to initialize a channel.
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
     * Verify WifiP2pManager.RESPONSE_PEERS is returned with null object when a caller
     * without proper permission attmepts to send WifiP2pManager.REQUEST_PEERS.
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
     * Verify WifiP2pManager.RESPONSE_PEERS is returned with expect object when a caller
     * with proper permission to send WifiP2pManager.REQUEST_PEERS.
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
     * Verify WifiP2pManager.RESPONSE_GROUP_INFO is returned with null object when a caller
     * uses abnormal way to send WifiP2pManager.REQUEST_GROUP_INFO (i.e no channel info updated).
     */
    @Test
    public void testRequestGroupInfoFailureWhenNoChannelUpdated() throws Exception {
        forceP2pEnabled(mClient1);
        sendGroupStartedMsg(mTestWifiP2pGroup);
        sendRequestGroupInfoMsg(mClientMessenger);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientHandler).sendMessage(messageCaptor.capture());
        assertEquals(WifiP2pManager.RESPONSE_GROUP_INFO, messageCaptor.getValue().what);
        assertEquals(null, messageCaptor.getValue().obj);
    }

    /**
     * Verify WifiP2pManager.RESPONSE_GROUP_INFO is returned with null object when a caller
     * uses wrong package name to initialize a channel.
     */
    @Test
    public void testRequestGroupInfoFailureWhenChannelUpdateWrongPkgName() throws Exception {
        forceP2pEnabled(mClient1);
        sendGroupStartedMsg(mTestWifiP2pGroup);
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
     * Verify WifiP2pManager.RESPONSE_GROUP_INFO is returned with null object when a caller
     * without proper permission attempts to send WifiP2pManager.REQUEST_GROUP_INFO.
     */
    @Test
    public void testRequestGroupInfoFailureWhenPermissionDenied() throws Exception {
        forceP2pEnabled(mClient1);
        sendGroupStartedMsg(mTestWifiP2pGroup);
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
     * Verify WifiP2pManager.RESPONSE_GROUP_INFO is returned with expect object when a caller
     * with proper permission.
     */
    @Test
    public void testRequestGroupInfoSuccess() throws Exception {
        forceP2pEnabled(mClient1);
        sendGroupStartedMsg(mTestWifiP2pGroup);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt())).thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendRequestGroupInfoMsg(mClientMessenger);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientHandler).sendMessage(messageCaptor.capture());
        assertEquals(WifiP2pManager.RESPONSE_GROUP_INFO, messageCaptor.getValue().what);
        WifiP2pGroup wifiP2pGroup = (WifiP2pGroup) messageCaptor.getValue().obj;
        assertEquals(mTestWifiP2pGroup.getNetworkName(), wifiP2pGroup.getNetworkName());
    }

    /**
     * Verify WifiP2pManager.START_LISTEN_FAILED is returned when a caller
     * without proper permission attempts to send WifiP2pManager.START_LISTEN.
     */
    @Test
    public void testStartListenFailureWhenPermissionDenied() throws Exception {
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(false);
        forceP2pEnabled(mClient1);
        sendSimpleMsg(mClientMessenger, WifiP2pManager.START_LISTEN);
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.START_LISTEN_FAILED));
        // p2pFlush should be invoked once in forceP2pEnabled.
        verify(mWifiNative).p2pFlush();
        verify(mWifiNative, never()).p2pExtListen(anyBoolean(), anyInt(), anyInt());
    }

    /**
     * Verify WifiP2pManager.START_LISTEN_FAILED is returned when native call failure.
     */
    @Test
    public void testStartListenFailureWhenNativeCallFailure() throws Exception {
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        when(mWifiNative.p2pExtListen(eq(true), anyInt(), anyInt())).thenReturn(false);
        forceP2pEnabled(mClient1);
        sendSimpleMsg(mClientMessenger, WifiP2pManager.START_LISTEN);
        // p2pFlush would be invoked in forceP2pEnabled and startListen both.
        verify(mWifiNative, times(2)).p2pFlush();
        verify(mWifiNative).p2pExtListen(eq(true), anyInt(), anyInt());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.START_LISTEN_FAILED));
    }

    /**
     * Verify the caller with proper permission sends WifiP2pManager.START_LISTEN.
     */
    @Test
    public void testStartListenSuccess() throws Exception {
        when(mWifiNative.p2pExtListen(eq(true), anyInt(), anyInt())).thenReturn(true);
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        forceP2pEnabled(mClient1);
        sendSimpleMsg(mClientMessenger, WifiP2pManager.START_LISTEN);
        // p2pFlush would be invoked in forceP2pEnabled and startListen both.
        verify(mWifiNative, times(2)).p2pFlush();
        verify(mWifiNative).p2pExtListen(eq(true), anyInt(), anyInt());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.START_LISTEN_SUCCEEDED));
    }

    /**
     * Verify WifiP2pManager.STOP_LISTEN_FAILED is returned when a caller
     * without proper permission attempts to sends WifiP2pManager.STOP_LISTEN.
     */
    @Test
    public void testStopListenFailureWhenPermissionDenied() throws Exception {
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(false);
        forceP2pEnabled(mClient1);
        sendSimpleMsg(mClientMessenger, WifiP2pManager.STOP_LISTEN);
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.STOP_LISTEN_FAILED));
        verify(mWifiNative, never()).p2pExtListen(anyBoolean(), anyInt(), anyInt());
    }

    /**
     * Verify WifiP2pManager.STOP_LISTEN_FAILED is returned when native call failure.
     */
    @Test
    public void testStopListenFailureWhenNativeCallFailure() throws Exception {
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        when(mWifiNative.p2pExtListen(eq(false), anyInt(), anyInt())).thenReturn(false);
        forceP2pEnabled(mClient1);
        sendSimpleMsg(mClientMessenger, WifiP2pManager.STOP_LISTEN);
        verify(mWifiNative).p2pExtListen(eq(false), anyInt(), anyInt());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.STOP_LISTEN_FAILED));
    }

    /**
     * Verify the caller with proper permission sends WifiP2pManager.STOP_LISTEN.
     */
    @Test
    public void testStopListenSuccess() throws Exception {
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        when(mWifiNative.p2pExtListen(eq(false), anyInt(), anyInt())).thenReturn(true);
        forceP2pEnabled(mClient1);
        sendSimpleMsg(mClientMessenger, WifiP2pManager.STOP_LISTEN);
        verify(mWifiNative).p2pExtListen(eq(false), anyInt(), anyInt());
        assertTrue(mClientHandler.hasMessages(WifiP2pManager.STOP_LISTEN_SUCCEEDED));
    }

    /** Verify the p2p randomized MAC feature is enabled if wlan driver supports it. */
    @Test
    public void testP2pRandomMacWithDriverSupport() throws Exception {
        when(mWifiNative.getSupportedFeatureSet(eq(IFACE_NAME_P2P)))
                .thenReturn(WifiManager.WIFI_FEATURE_P2P_RAND_MAC);
        forceP2pEnabled(mClient1);
        verify(mWifiNative).setMacRandomization(eq(true));
    }

    /** Verify the p2p randomized MAC feature is NOT enabled if wlan driver doesn't supports it. */
    @Test
    public void testP2pRandomMacWithoutDriverSupport() throws Exception {
        when(mWifiNative.getSupportedFeatureSet(eq(IFACE_NAME_P2P)))
                .thenReturn(0x0L);
        forceP2pEnabled(mClient1);
        verify(mWifiNative, never()).setMacRandomization(anyBoolean());
    }

    /**
     * Verify the caller sends WifiP2pManager.DELETE_PERSISTENT_GROUP.
     */
    @Test
    public void testDeletePersistentGroupSuccess() throws Exception {
        // Move to enabled state
        forceP2pEnabled(mClient1);

        sendDeletePersistentGroupMsg(mClientMessenger, WifiP2pGroup.PERSISTENT_NET_ID);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientHandler).sendMessage(messageCaptor.capture());
        Message message = messageCaptor.getValue();
        assertEquals(WifiP2pManager.DELETE_PERSISTENT_GROUP_SUCCEEDED, message.what);
    }

    /**
     * Verify WifiP2pManager.REMOVE_GROUP_FAILED is returned when p2p is disabled.
     */
    @Test
    public void testDeletePersistentGroupFailureWhenP2pDisabled() throws Exception {
        sendDeletePersistentGroupMsg(mClientMessenger, WifiP2pGroup.PERSISTENT_NET_ID);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientHandler).sendMessage(messageCaptor.capture());
        Message message = messageCaptor.getValue();
        assertEquals(WifiP2pManager.DELETE_PERSISTENT_GROUP_FAILED, message.what);
        assertEquals(WifiP2pManager.BUSY, message.arg1);
    }

    /**
     * Verify WifiP2pManager.REMOVE_GROUP_FAILED is returned when p2p is unsupported.
     */
    @Test
    public void testDeletePersistentGroupFailureWhenP2pUnsupported() throws Exception {
        setUpWifiP2pServiceImpl(false);
        sendDeletePersistentGroupMsg(mClientMessenger, WifiP2pGroup.PERSISTENT_NET_ID);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientHandler).sendMessage(messageCaptor.capture());
        Message message = messageCaptor.getValue();
        assertEquals(WifiP2pManager.DELETE_PERSISTENT_GROUP_FAILED, message.what);
        assertEquals(WifiP2pManager.P2P_UNSUPPORTED, message.arg1);
    }

    /**
     * Verify the peer scan counter is increased while receiving WifiP2pManager.DISCOVER_PEERS at
     * P2pEnabledState.
     */
    @Test
    public void testPeerScanMetricWhenSendDiscoverPeers() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        when(mWifiNative.p2pFind(anyInt())).thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendDiscoverPeersMsg(mClientMessenger);
        verify(mWifiP2pMetrics).incrementPeerScans();
    }

    /**
     * Verify the service scan counter is increased while receiving
     * WifiP2pManager.DISCOVER_SERVICES at P2pEnabledState.
     */
    @Test
    public void testServiceScanMetricWhenSendDiscoverServices() throws Exception {
        when(mWifiNative.p2pServDiscReq(anyString(), anyString()))
                .thenReturn("mServiceDiscReqId");
        when(mWifiNative.p2pFind(anyInt())).thenReturn(true);
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendAddServiceRequestMsg(mClientMessenger);
        sendDiscoverServiceMsg(mClientMessenger);
        verify(mWifiP2pMetrics).incrementServiceScans();
    }

    /**
     * Verify the persistent group counter is updated while receiving
     * WifiP2pManager.FACTORY_RESET.
     */
    @Test
    public void testPersistentGroupMetricWhenSendFactoryReset() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        // permissions for factory reset
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt()))
                .thenReturn(true);
        when(mUserManager.hasUserRestriction(eq(UserManager.DISALLOW_NETWORK_RESET)))
                .thenReturn(false);
        when(mUserManager.hasUserRestriction(eq(UserManager.DISALLOW_CONFIG_WIFI)))
                .thenReturn(false);

        ArgumentCaptor<WifiP2pGroupList> groupsCaptor =
                ArgumentCaptor.forClass(WifiP2pGroupList.class);
        verify(mWifiP2pMetrics).updatePersistentGroup(groupsCaptor.capture());
        assertEquals(3, groupsCaptor.getValue().getGroupList().size());

        sendSimpleMsg(mClientMessenger, WifiP2pManager.FACTORY_RESET);

        verify(mWifiP2pMetrics, times(2)).updatePersistentGroup(groupsCaptor.capture());
        // the captured object is the same object, just get the latest one is ok.
        assertEquals(0, groupsCaptor.getValue().getGroupList().size());
    }

    /**
     * Verify the persistent group counter is updated while receiving
     * WifiP2pMonitor.P2P_GROUP_STARTED_EVENT.
     */
    @Test
    public void testPersistentGroupMetricWhenSendP2pGroupStartedEvent() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        ArgumentCaptor<WifiP2pGroupList> groupsCaptor =
                ArgumentCaptor.forClass(WifiP2pGroupList.class);
        verify(mWifiP2pMetrics).updatePersistentGroup(groupsCaptor.capture());
        assertEquals(3, groupsCaptor.getValue().getGroupList().size());

        sendGroupStartedMsg(mTestWifiP2pNewPersistentGoGroup);

        verify(mWifiP2pMetrics, times(2)).updatePersistentGroup(groupsCaptor.capture());
        // the captured object is the same object, just get the latest one is ok.
        assertEquals(4, groupsCaptor.getValue().getGroupList().size());
    }

    /**
     * Verify the persistent group counter is updated while receiving
     * WifiP2pManager.DELETE_PERSISTENT_GROUP.
     */
    @Test
    public void testPersistentGroupMetricWhenSendDeletePersistentGroup() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        ArgumentCaptor<WifiP2pGroupList> groupsCaptor =
                ArgumentCaptor.forClass(WifiP2pGroupList.class);
        verify(mWifiP2pMetrics).updatePersistentGroup(groupsCaptor.capture());
        assertEquals(3, groupsCaptor.getValue().getGroupList().size());

        sendDeletePersistentGroupMsg(mClientMessenger, 0);

        verify(mWifiP2pMetrics, times(2)).updatePersistentGroup(groupsCaptor.capture());
        // the captured object is the same object, just get the latest one is ok.
        assertEquals(2, groupsCaptor.getValue().getGroupList().size());
    }

    /**
     * Verify the group event.
     */
    @Test
    public void testGroupEventMetric() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        sendGroupStartedMsg(mTestWifiP2pNewPersistentGoGroup);

        ArgumentCaptor<WifiP2pGroup> groupCaptor =
                ArgumentCaptor.forClass(WifiP2pGroup.class);
        verify(mWifiP2pMetrics).startGroupEvent(groupCaptor.capture());
        WifiP2pGroup groupCaptured = groupCaptor.getValue();
        assertEquals(mTestWifiP2pNewPersistentGoGroup.toString(), groupCaptured.toString());

        sendGroupRemovedMsg();
        verify(mWifiP2pMetrics).endGroupEvent();
    }

    /**
     * Verify the connection event for a fresh connection.
     */
    @Test
    public void testStartFreshConnectionEventWhenSendConnect() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        mockPeersList();
        sendConnectMsg(mClientMessenger, mTestWifiP2pPeerConfig);

        ArgumentCaptor<WifiP2pConfig> configCaptor =
                ArgumentCaptor.forClass(WifiP2pConfig.class);
        verify(mWifiP2pMetrics).startConnectionEvent(
                eq(P2pConnectionEvent.CONNECTION_FRESH),
                configCaptor.capture());
        assertEquals(mTestWifiP2pPeerConfig.toString(), configCaptor.getValue().toString());
    }

    /**
     * Verify the connection event for a reinvoked connection.
     */
    @Test
    public void testStartReinvokeConnectionEventWhenSendConnect() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        when(mWifiNative.p2pGroupAdd(anyInt()))
                .thenReturn(true);
        when(mTestWifiP2pDevice.isGroupOwner()).thenReturn(true);
        when(mWifiNative.p2pGetSsid(eq(mTestWifiP2pDevice.deviceAddress)))
                .thenReturn(mTestWifiP2pGroup.getNetworkName());
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        mockPeersList();
        sendConnectMsg(mClientMessenger, mTestWifiP2pPeerConfig);

        ArgumentCaptor<WifiP2pConfig> configCaptor =
                ArgumentCaptor.forClass(WifiP2pConfig.class);
        verify(mWifiP2pMetrics).startConnectionEvent(
                eq(P2pConnectionEvent.CONNECTION_REINVOKE),
                configCaptor.capture());
        assertEquals(mTestWifiP2pPeerConfig.toString(), configCaptor.getValue().toString());
    }

    /**
     * Verify the connection event for a reinvoked connection via
     * createGroup API.
     *
     * If there is a persistent group whose owner is this deivce, this would be
     * a reinvoked group.
     */
    @Test
    public void testStartReinvokeConnectionEventWhenCreateGroup()
            throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        sendCreateGroupMsg(mClientMessenger, WifiP2pGroup.PERSISTENT_NET_ID, null);

        verify(mWifiP2pMetrics).startConnectionEvent(
                eq(P2pConnectionEvent.CONNECTION_REINVOKE),
                eq(null));
    }

    /**
     * Verify the connection event for a local connection while setting
     * netId to {@link WifiP2pGroup#PERSISTENT_NET_ID}.
     */
    @Test
    public void testStartLocalConnectionWhenCreateGroup() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        // permissions for factory reset
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt()))
                .thenReturn(true);
        when(mUserManager.hasUserRestriction(eq(UserManager.DISALLOW_NETWORK_RESET)))
                .thenReturn(false);
        when(mUserManager.hasUserRestriction(eq(UserManager.DISALLOW_CONFIG_WIFI)))
                .thenReturn(false);

        // There is one group hosted by this device in mGroups.
        // clear all groups to avoid re-invoking a group.
        sendSimpleMsg(mClientMessenger, WifiP2pManager.FACTORY_RESET);

        sendCreateGroupMsg(mClientMessenger, WifiP2pGroup.PERSISTENT_NET_ID, null);

        verify(mWifiP2pMetrics).startConnectionEvent(
                eq(P2pConnectionEvent.CONNECTION_LOCAL),
                eq(null));
    }

    /**
     * Verify the connection event for a local connection while setting the
     * netId to {@link WifiP2pGroup#TEMPORARY_NET_ID}.
     */
    @Test
    public void testStartLocalConnectionEventWhenCreateTemporaryGroup() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        sendCreateGroupMsg(mClientMessenger, WifiP2pGroup.TEMPORARY_NET_ID, null);

        verify(mWifiP2pMetrics).startConnectionEvent(
                eq(P2pConnectionEvent.CONNECTION_LOCAL),
                eq(null));
    }

    /**
     * Verify the connection event for a fast connection via
     * connect with config.
     */
    @Test
    public void testStartFastConnectionEventWhenSendConnectWithConfig()
            throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        when(mWifiNative.p2pGroupAdd(any(), eq(true))).thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        sendConnectMsg(mClientMessenger, mTestWifiP2pFastConnectionConfig);

        ArgumentCaptor<WifiP2pConfig> configCaptor =
                ArgumentCaptor.forClass(WifiP2pConfig.class);
        verify(mWifiP2pMetrics).startConnectionEvent(
                eq(P2pConnectionEvent.CONNECTION_FAST),
                configCaptor.capture());
        assertEquals(mTestWifiP2pFastConnectionConfig.toString(),
                configCaptor.getValue().toString());
    }

    /**
     * Verify the connection event for a fast connection via
     * createGroup API with config.
     */
    @Test
    public void testStartFastConnectionEventWhenCreateGroupWithConfig()
            throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        sendCreateGroupMsg(mClientMessenger, 0, mTestWifiP2pFastConnectionConfig);

        ArgumentCaptor<WifiP2pConfig> configCaptor =
                ArgumentCaptor.forClass(WifiP2pConfig.class);
        verify(mWifiP2pMetrics).startConnectionEvent(
                eq(P2pConnectionEvent.CONNECTION_FAST),
                configCaptor.capture());
        assertEquals(mTestWifiP2pFastConnectionConfig.toString(),
                configCaptor.getValue().toString());
    }

    /**
     * Verify the connection event ends while the group is formed.
     */
    @Test
    public void testEndConnectionEventWhenGroupFormed() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        WifiP2pGroup group = new WifiP2pGroup();
        group.setNetworkId(WifiP2pGroup.PERSISTENT_NET_ID);
        group.setNetworkName("DIRECT-xy-NEW");
        group.setOwner(new WifiP2pDevice("thisDeviceMac"));
        group.setIsGroupOwner(true);
        sendGroupStartedMsg(group);
        verify(mWifiP2pMetrics).endConnectionEvent(
                eq(P2pConnectionEvent.CLF_NONE));
    }

    /**
     * Verify the connection event ends due to timeout.
     */
    @Test
    public void testEndConnectionEventWhenTimeout() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        when(mWifiNative.p2pGroupAdd(anyBoolean())).thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        mockEnterGroupNegotiationState();

        mLooper.moveTimeForward(120 * 1000 * 2);
        mLooper.dispatchAll();

        verify(mWifiP2pMetrics).endConnectionEvent(
                eq(P2pConnectionEvent.CLF_TIMEOUT));
    }

    /**
     * Verify the connection event ends due to the cancellation.
     */
    @Test
    public void testEndConnectionEventWhenCancel() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        when(mWifiNative.p2pGroupAdd(anyBoolean())).thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        mockEnterGroupNegotiationState();

        sendSimpleMsg(mClientMessenger, WifiP2pManager.CANCEL_CONNECT);

        verify(mWifiP2pMetrics).endConnectionEvent(
                eq(P2pConnectionEvent.CLF_CANCEL));
    }

    /**
     * Verify the connection event ends due to the provision discovery failure.
     */
    @Test
    public void testEndConnectionEventWhenProvDiscFailure() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        when(mWifiNative.p2pGroupAdd(anyBoolean())).thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        mockEnterProvisionDiscoveryState();

        sendSimpleMsg(null, WifiP2pMonitor.P2P_PROV_DISC_FAILURE_EVENT);

        verify(mWifiP2pMetrics).endConnectionEvent(
                eq(P2pConnectionEvent.CLF_PROV_DISC_FAIL));
    }

    /**
     * Verify the connection event ends due to the group removal.
     */
    @Test
    public void testEndConnectionEventWhenGroupRemoval() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        when(mWifiNative.p2pGroupAdd(anyBoolean())).thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        mockEnterGroupNegotiationState();

        sendSimpleMsg(null, WifiP2pMonitor.P2P_GROUP_REMOVED_EVENT);

        verify(mWifiP2pMetrics).endConnectionEvent(
                eq(P2pConnectionEvent.CLF_UNKNOWN));
    }

    /**
     * Verify the connection event ends due to the invitation failure.
     */
    @Test
    public void testEndConnectionEventWhenInvitationFailure() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(true);
        when(mWifiNative.p2pGroupAdd(anyBoolean())).thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);

        mockEnterGroupNegotiationState();

        sendInvitationResultMsg(WifiP2pServiceImpl.P2pStatus.UNKNOWN);

        verify(mWifiP2pMetrics).endConnectionEvent(
                eq(P2pConnectionEvent.CLF_INVITATION_FAIL));
    }

    /**
     * Verify WifiP2pManager.RESPONSE_DEVICE_INFO is returned with null object when a caller
     * without proper permission attempts.
     */
    @Test
    public void testRequestDeviceInfoFailureWhenPermissionDenied() throws Exception {
        forceP2pEnabled(mClient1);
        doNothing().when(mWifiPermissionsUtil).checkPackage(anyInt(), anyString());
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt()))
                .thenReturn(false);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendSimpleMsg(mClientMessenger, WifiP2pManager.REQUEST_DEVICE_INFO);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientHandler).sendMessage(messageCaptor.capture());
        assertEquals(WifiP2pManager.RESPONSE_DEVICE_INFO, messageCaptor.getValue().what);
        assertEquals(null, messageCaptor.getValue().obj);
    }

    /**
     * Verify WifiP2pManager.RESPONSE_DEVICE_INFO is returned with expect object when a caller
     * with proper permission attempts in p2p enabled state.
     */
    @Test
    public void testRequestDeviceInfoSuccessWhenP2pEnabled() throws Exception {
        forceP2pEnabled(mClient1);
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt())).thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendSimpleMsg(mClientMessenger, WifiP2pManager.REQUEST_DEVICE_INFO);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientHandler).sendMessage(messageCaptor.capture());
        assertEquals(WifiP2pManager.RESPONSE_DEVICE_INFO, messageCaptor.getValue().what);
        WifiP2pDevice wifiP2pDevice = (WifiP2pDevice) messageCaptor.getValue().obj;
        assertEquals(thisDeviceMac, wifiP2pDevice.deviceAddress);
        assertEquals(thisDeviceName, wifiP2pDevice.deviceName);
    }

    /**
     * Verify WifiP2pManager.RESPONSE_DEVICE_INFO is returned with empty object when a caller
     * with proper permission attempts in p2p disabled state.
     */
    @Test
    public void testRequestDeviceInfoReturnEmptyWifiP2pDeviceWhenP2pDisabled() throws Exception {
        when(mWifiPermissionsUtil.checkCanAccessWifiDirect(anyString(), anyInt())).thenReturn(true);
        sendChannelInfoUpdateMsg("testPkg1", mClient1, mClientMessenger);
        sendSimpleMsg(mClientMessenger, WifiP2pManager.REQUEST_DEVICE_INFO);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientHandler).sendMessage(messageCaptor.capture());
        assertEquals(WifiP2pManager.RESPONSE_DEVICE_INFO, messageCaptor.getValue().what);
        WifiP2pDevice wifiP2pDevice = (WifiP2pDevice) messageCaptor.getValue().obj;
        assertEquals("", wifiP2pDevice.deviceAddress);
        assertEquals("", wifiP2pDevice.deviceName);
    }
}
