/*
 * Copyright 2018 The Android Open Source Project
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

import static android.net.wifi.WifiManager.EXTRA_PREVIOUS_WIFI_STATE;
import static android.net.wifi.WifiManager.EXTRA_WIFI_STATE;
import static android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

/**
 * Unit tests for {@link ClientModeManager}.
 */
@SmallTest
public class ClientModeManagerTest extends WifiBaseTest {
    private static final String TAG = "ClientModeManagerTest";
    private static final String TEST_INTERFACE_NAME = "testif0";
    private static final String OTHER_INTERFACE_NAME = "notTestIf";

    TestLooper mLooper;

    ClientModeManager mClientModeManager;

    @Mock Context mContext;
    @Mock WifiMetrics mWifiMetrics;
    @Mock WifiNative mWifiNative;
    @Mock ClientModeManager.Listener mListener;
    @Mock SarManager mSarManager;
    @Mock WakeupController mWakeupController;
    @Mock ClientModeImpl mClientModeImpl;

    final ArgumentCaptor<WifiNative.InterfaceCallback> mInterfaceCallbackCaptor =
            ArgumentCaptor.forClass(WifiNative.InterfaceCallback.class);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();

        mClientModeManager = createClientModeManager();
        mLooper.dispatchAll();
    }

    private ClientModeManager createClientModeManager() {
        return new ClientModeManager(mContext, mLooper.getLooper(), mWifiNative, mListener,
                mWifiMetrics, mSarManager, mWakeupController, mClientModeImpl);
    }

    private void startClientInScanOnlyModeAndVerifyEnabled() throws Exception {
        when(mWifiNative.setupInterfaceForClientInScanMode(any()))
                .thenReturn(TEST_INTERFACE_NAME);
        mClientModeManager.start();
        mLooper.dispatchAll();

        verify(mWifiNative).setupInterfaceForClientInScanMode(
                mInterfaceCallbackCaptor.capture());
        verify(mClientModeImpl).setOperationalMode(
                ClientModeImpl.SCAN_ONLY_MODE, TEST_INTERFACE_NAME);
        verify(mSarManager).setScanOnlyWifiState(WIFI_STATE_ENABLED);

        // now mark the interface as up
        mInterfaceCallbackCaptor.getValue().onUp(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();

        // Ensure that no public broadcasts were sent.
        verifyNoMoreInteractions(mContext);
        verify(mListener).onStarted();
    }

    private void startClientInConnectModeAndVerifyEnabled() throws Exception {
        when(mWifiNative.setupInterfaceForClientInScanMode(any()))
                .thenReturn(TEST_INTERFACE_NAME);
        when(mWifiNative.switchClientInterfaceToConnectivityMode(any()))
                .thenReturn(true);
        mClientModeManager.start();
        mLooper.dispatchAll();

        verify(mWifiNative).setupInterfaceForClientInScanMode(
                mInterfaceCallbackCaptor.capture());
        verify(mClientModeImpl).setOperationalMode(
                ClientModeImpl.SCAN_ONLY_MODE, TEST_INTERFACE_NAME);
        mLooper.dispatchAll();

        mClientModeManager.setRole(ActiveModeManager.ROLE_CLIENT_PRIMARY);
        mLooper.dispatchAll();

        verify(mWifiNative).switchClientInterfaceToConnectivityMode(TEST_INTERFACE_NAME);
        verify(mClientModeImpl).setOperationalMode(
                ClientModeImpl.CONNECT_MODE, TEST_INTERFACE_NAME);
        verify(mSarManager).setClientWifiState(WIFI_STATE_ENABLED);

        // now mark the interface as up
        mInterfaceCallbackCaptor.getValue().onUp(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce()).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        List<Intent> intents = intentCaptor.getAllValues();
        assertEquals(2, intents.size());
        Log.d(TAG, "captured intents: " + intents);
        checkWifiConnectModeStateChangedBroadcast(intents.get(0), WIFI_STATE_ENABLING,
                WIFI_STATE_DISABLED);
        checkWifiConnectModeStateChangedBroadcast(intents.get(1), WIFI_STATE_ENABLED,
                WIFI_STATE_ENABLING);

        verify(mListener, times(2)).onStarted();
    }

    private void checkWifiConnectModeStateChangedBroadcast(
            Intent intent, int expectedCurrentState, int expectedPrevState) {
        String action = intent.getAction();
        assertEquals(WIFI_STATE_CHANGED_ACTION, action);
        int currentState = intent.getIntExtra(EXTRA_WIFI_STATE, WIFI_STATE_UNKNOWN);
        assertEquals(expectedCurrentState, currentState);
        int prevState = intent.getIntExtra(EXTRA_PREVIOUS_WIFI_STATE, WIFI_STATE_UNKNOWN);
        assertEquals(expectedPrevState, prevState);

        verify(mClientModeImpl, atLeastOnce()).setWifiStateForApiCalls(expectedCurrentState);
    }

    private void verifyConnectModeNotificationsForCleanShutdown(int fromState) {
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce())
                .sendStickyBroadcastAsUser(intentCaptor.capture(), eq(UserHandle.ALL));

        List<Intent> intents = intentCaptor.getAllValues();
        assertTrue(intents.size() >= 2);
        checkWifiConnectModeStateChangedBroadcast(intents.get(intents.size() - 2),
                WIFI_STATE_DISABLING, fromState);
        checkWifiConnectModeStateChangedBroadcast(intents.get(intents.size() - 1),
                WIFI_STATE_DISABLED, WIFI_STATE_DISABLING);
    }

    private void verifyConnectModeNotificationsForFailure() {
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce())
                .sendStickyBroadcastAsUser(intentCaptor.capture(), eq(UserHandle.ALL));

        List<Intent> intents = intentCaptor.getAllValues();
        assertEquals(2, intents.size());
        checkWifiConnectModeStateChangedBroadcast(intents.get(0), WIFI_STATE_DISABLING,
                WIFI_STATE_UNKNOWN);
        checkWifiConnectModeStateChangedBroadcast(intents.get(1), WIFI_STATE_DISABLED,
                WIFI_STATE_DISABLING);
    }

    /**
     * ClientMode start sets up an interface in ClientMode.
     */
    @Test
    public void clientInConnectModeStartCreatesClientInterface() throws Exception {
        startClientInConnectModeAndVerifyEnabled();
    }

    /**
     * ClientMode start sets up an interface in ClientMode.
     */
    @Test
    public void clientInScanOnlyModeStartCreatesClientInterface() throws Exception {
        startClientInScanOnlyModeAndVerifyEnabled();
    }

    /**
     * Switch ClientModeManager from ScanOnly mode To Connect mode.
     */
    @Test
    public void switchFromScanOnlyModeToConnectMode() throws Exception {
        startClientInScanOnlyModeAndVerifyEnabled();

        when(mWifiNative.switchClientInterfaceToConnectivityMode(any()))
                .thenReturn(true);
        mClientModeManager.setRole(ActiveModeManager.ROLE_CLIENT_PRIMARY);
        mLooper.dispatchAll();

        verify(mSarManager).setScanOnlyWifiState(WIFI_STATE_DISABLED);
        verify(mClientModeImpl).setOperationalMode(
                ClientModeImpl.SCAN_ONLY_MODE, TEST_INTERFACE_NAME);
        verify(mClientModeImpl).setOperationalMode(
                ClientModeImpl.CONNECT_MODE, TEST_INTERFACE_NAME);
        verify(mSarManager).setClientWifiState(WIFI_STATE_ENABLED);

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce()).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        List<Intent> intents = intentCaptor.getAllValues();
        assertEquals(2, intents.size());
        Log.d(TAG, "captured intents: " + intents);
        checkWifiConnectModeStateChangedBroadcast(intents.get(0), WIFI_STATE_ENABLING,
                WIFI_STATE_DISABLED);
        checkWifiConnectModeStateChangedBroadcast(intents.get(1), WIFI_STATE_ENABLED,
                WIFI_STATE_ENABLING);

        verify(mListener, times(2)).onStarted();
    }

    /**
     * Switch ClientModeManager from Connect mode to ScanOnly mode.
     */
    @Test
    public void switchFromConnectModeToScanOnlyMode() throws Exception {
        startClientInConnectModeAndVerifyEnabled();

        when(mWifiNative.switchClientInterfaceToScanMode(any()))
                .thenReturn(true);
        mClientModeManager.setRole(ActiveModeManager.ROLE_CLIENT_SCAN_ONLY);
        mLooper.dispatchAll();

        verifyConnectModeNotificationsForCleanShutdown(WIFI_STATE_ENABLED);

        verify(mSarManager).setClientWifiState(WIFI_STATE_DISABLED);
        verify(mWifiNative).setupInterfaceForClientInScanMode(
                mInterfaceCallbackCaptor.capture());
        verify(mWifiNative).switchClientInterfaceToScanMode(TEST_INTERFACE_NAME);
        verify(mClientModeImpl, times(2)).setOperationalMode(
                ClientModeImpl.SCAN_ONLY_MODE, TEST_INTERFACE_NAME);
        verify(mSarManager, times(2)).setScanOnlyWifiState(WIFI_STATE_ENABLED);

        // Ensure that no public broadcasts were sent.
        verifyNoMoreInteractions(mContext);
        verify(mListener, times(3)).onStarted();
    }

    /**
     * ClientMode increments failure metrics when failing to setup client mode in connectivity mode.
     */
    @Test
    public void detectAndReportErrorWhenSetupForClientInConnectivityModeWifiNativeFailure()
            throws Exception {
        when(mWifiNative.setupInterfaceForClientInScanMode(any()))
                .thenReturn(TEST_INTERFACE_NAME);
        when(mWifiNative.switchClientInterfaceToConnectivityMode(any())).thenReturn(false);

        mClientModeManager.start();
        mLooper.dispatchAll();

        mClientModeManager.setRole(ActiveModeManager.ROLE_CLIENT_PRIMARY);
        mLooper.dispatchAll();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce()).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));
        List<Intent> intents = intentCaptor.getAllValues();
        assertEquals(2, intents.size());
        checkWifiConnectModeStateChangedBroadcast(intents.get(0), WIFI_STATE_ENABLING,
                WIFI_STATE_DISABLED);
        checkWifiConnectModeStateChangedBroadcast(intents.get(1), WIFI_STATE_DISABLED,
                WIFI_STATE_UNKNOWN);
        verify(mListener).onStartFailure();
    }

    /**
     * Calling ClientModeManager.start twice does not crash or restart client mode.
     */
    @Test
    public void clientModeStartCalledTwice() throws Exception {
        startClientInConnectModeAndVerifyEnabled();
        reset(mWifiNative, mContext);
        mClientModeManager.start();
        mLooper.dispatchAll();
        verifyNoMoreInteractions(mWifiNative, mContext);
    }

    /**
     * ClientMode stop properly cleans up state
     */
    @Test
    public void clientModeStopCleansUpState() throws Exception {
        startClientInConnectModeAndVerifyEnabled();
        reset(mContext, mListener);
        mClientModeManager.stop();
        mLooper.dispatchAll();
        verify(mListener).onStopped();

        verifyConnectModeNotificationsForCleanShutdown(WIFI_STATE_ENABLED);

        // on an explicit stop, we should not trigger the callback
        verifyNoMoreInteractions(mListener);
    }

    /**
     * Calling stop when ClientMode is not started should not send scan state updates
     */
    @Test
    public void clientModeStopWhenNotStartedDoesNotUpdateScanStateUpdates() throws Exception {
        startClientInConnectModeAndVerifyEnabled();
        reset(mContext);
        mClientModeManager.stop();
        mLooper.dispatchAll();
        verifyConnectModeNotificationsForCleanShutdown(WIFI_STATE_ENABLED);

        reset(mContext, mListener);
        // now call stop again
        mClientModeManager.stop();
        mLooper.dispatchAll();
        verify(mContext, never()).sendStickyBroadcastAsUser(any(), any());
        verifyNoMoreInteractions(mListener);
    }

    /**
     * Triggering interface down when ClientMode is active properly exits the active state.
     */
    @Test
    public void clientModeStartedStopsWhenInterfaceDown() throws Exception {
        startClientInConnectModeAndVerifyEnabled();
        reset(mContext);
        when(mClientModeImpl.isConnectedMacRandomizationEnabled()).thenReturn(false);
        mInterfaceCallbackCaptor.getValue().onDown(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();
        verify(mClientModeImpl).failureDetected(eq(SelfRecovery.REASON_STA_IFACE_DOWN));
        verifyConnectModeNotificationsForFailure();
        verify(mListener).onStopped();
    }

    /**
     * Triggering interface down when ClientMode is active and Connected MacRandomization is enabled
     * does not exit the active state.
     */
    @Test
    public void clientModeStartedWithConnectedMacRandDoesNotStopWhenInterfaceDown()
            throws Exception {
        startClientInConnectModeAndVerifyEnabled();
        reset(mContext);
        when(mClientModeImpl.isConnectedMacRandomizationEnabled()).thenReturn(true);
        mInterfaceCallbackCaptor.getValue().onDown(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();
        verify(mClientModeImpl, never()).failureDetected(eq(SelfRecovery.REASON_STA_IFACE_DOWN));
        verify(mContext, never()).sendStickyBroadcastAsUser(any(), any());
    }

    /**
     * Testing the handling of an interface destroyed notification.
     */
    @Test
    public void clientModeStartedStopsOnInterfaceDestroyed() throws Exception {
        startClientInConnectModeAndVerifyEnabled();
        reset(mContext);
        mInterfaceCallbackCaptor.getValue().onDestroyed(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();
        verifyConnectModeNotificationsForCleanShutdown(WIFI_STATE_ENABLED);
        verify(mClientModeImpl).handleIfaceDestroyed();
        verify(mListener).onStopped();
    }

    /**
     * Verify that onDestroyed after client mode is stopped doesn't trigger a callback.
     */
    @Test
    public void noCallbackOnInterfaceDestroyedWhenAlreadyStopped() throws Exception {
        startClientInConnectModeAndVerifyEnabled();

        reset(mListener);

        mClientModeManager.stop();
        mLooper.dispatchAll();

        // now trigger interface destroyed and make sure callback doesn't get called
        mInterfaceCallbackCaptor.getValue().onDestroyed(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();
        verify(mListener).onStopped();

        verifyNoMoreInteractions(mListener);
        verify(mClientModeImpl, never()).handleIfaceDestroyed();
    }

    /**
     * Entering ScanOnly state starts the WakeupController.
     */
    @Test
    public void scanModeEnterStartsWakeupController() throws Exception {
        startClientInScanOnlyModeAndVerifyEnabled();

        verify(mWakeupController).start();
    }

    /**
     * Exiting ScanOnly state stops the WakeupController.
     */
    @Test
    public void scanModeExitStopsWakeupController() throws Exception {
        startClientInScanOnlyModeAndVerifyEnabled();

        mClientModeManager.stop();
        mLooper.dispatchAll();

        InOrder inOrder = inOrder(mWakeupController, mWifiNative, mListener);

        inOrder.verify(mListener).onStarted();
        inOrder.verify(mWakeupController).start();
        inOrder.verify(mWakeupController).stop();
        inOrder.verify(mWifiNative).teardownInterface(eq(TEST_INTERFACE_NAME));
    }
}
