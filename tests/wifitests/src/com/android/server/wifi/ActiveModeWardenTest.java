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

package com.android.server.wifi;

import static com.android.server.wifi.ActiveModeManager.SCAN_NONE;
import static com.android.server.wifi.ActiveModeManager.SCAN_WITHOUT_HIDDEN_NETWORKS;
import static com.android.server.wifi.ActiveModeManager.SCAN_WITH_HIDDEN_NETWORKS;
import static com.android.server.wifi.ActiveModeWarden.WifiController.CMD_AP_STOPPED;
import static com.android.server.wifi.ActiveModeWarden.WifiController.CMD_EMERGENCY_CALL_STATE_CHANGED;
import static com.android.server.wifi.ActiveModeWarden.WifiController.CMD_EMERGENCY_MODE_CHANGED;
import static com.android.server.wifi.ActiveModeWarden.WifiController.CMD_RECOVERY_DISABLE_WIFI;
import static com.android.server.wifi.ActiveModeWarden.WifiController.CMD_RECOVERY_RESTART_WIFI;
import static com.android.server.wifi.ActiveModeWarden.WifiController.CMD_SCANNING_STOPPED;
import static com.android.server.wifi.ActiveModeWarden.WifiController.CMD_SCAN_ALWAYS_MODE_CHANGED;
import static com.android.server.wifi.ActiveModeWarden.WifiController.CMD_SET_AP;
import static com.android.server.wifi.ActiveModeWarden.WifiController.CMD_STA_STOPPED;
import static com.android.server.wifi.ActiveModeWarden.WifiController.CMD_WIFI_TOGGLED;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.location.LocationManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.BatteryStats;
import android.os.test.TestLooper;
import android.util.Log;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
import com.android.server.wifi.util.WifiPermissionsUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

/**
 * Unit tests for {@link com.android.server.wifi.ActiveModeWarden}.
 */
@SmallTest
public class ActiveModeWardenTest {
    public static final String TAG = "WifiActiveModeWardenTest";

    private static final String CLIENT_MODE_STATE_STRING = "StaEnabledState";
    private static final String SCAN_ONLY_MODE_STATE_STRING = "StaDisabledWithScanState";
    private static final String STA_DISABLED_STATE_STRING = "StaDisabledState";
    private static final String ECM_STATE_STRING = "EcmState";

    private static final String WIFI_IFACE_NAME = "mockWlan";
    private static final int TEST_WIFI_RECOVERY_DELAY_MS = 2000;

    TestLooper mLooper;
    @Mock WifiInjector mWifiInjector;
    @Mock Context mContext;
    @Mock Resources mResources;
    @Mock WifiNative mWifiNative;
    @Mock WifiApConfigStore mWifiApConfigStore;
    @Mock ClientModeManager mClientModeManager;
    @Mock ScanOnlyModeManager mScanOnlyModeManager;
    @Mock SoftApManager mSoftApManager;
    @Mock DefaultModeManager mDefaultModeManager;
    @Mock IBatteryStats mBatteryStats;
    @Mock SelfRecovery mSelfRecovery;
    @Mock BaseWifiDiagnostics mWifiDiagnostics;
    @Mock ScanRequestProxy mScanRequestProxy;
    @Mock ClientModeImpl mClientModeImpl;
    @Mock FrameworkFacade mFacade;
    @Mock WifiSettingsStore mSettingsStore;
    @Mock WifiPermissionsUtil mWifiPermissionsUtil;

    ClientModeManager.Listener mClientListener;
    ScanOnlyModeManager.Listener mScanOnlyListener;
    WifiManager.SoftApCallback mSoftApManagerCallback;
    @Mock WifiManager.SoftApCallback mSoftApStateMachineCallback;
    @Mock WifiManager.SoftApCallback mLohsStateMachineCallback;
    WifiNative.StatusListener mWifiNativeStatusListener;
    ActiveModeWarden mActiveModeWarden;

    final ArgumentCaptor<WifiNative.StatusListener> mStatusListenerCaptor =
            ArgumentCaptor.forClass(WifiNative.StatusListener.class);

    /**
     * Set up the test environment.
     */
    @Before
    public void setUp() throws Exception {
        Log.d(TAG, "Setting up ...");

        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();

        when(mWifiInjector.getScanRequestProxy()).thenReturn(mScanRequestProxy);
        when(mClientModeManager.getScanMode()).thenReturn(SCAN_WITH_HIDDEN_NETWORKS);
        when(mContext.getResources()).thenReturn(mResources);
        when(mScanOnlyModeManager.getScanMode()).thenReturn(SCAN_WITHOUT_HIDDEN_NETWORKS);
        when(mSoftApManager.getScanMode()).thenReturn(SCAN_NONE);

        when(mResources.getString(R.string.wifi_localhotspot_configure_ssid_default))
                .thenReturn("AndroidShare");
        when(mResources.getInteger(R.integer.config_wifi_framework_recovery_timeout_delay))
                .thenReturn(TEST_WIFI_RECOVERY_DELAY_MS);

        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(false);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);

        mActiveModeWarden = createActiveModeWarden();
        mActiveModeWarden.start();
        mLooper.dispatchAll();

        verify(mWifiNative).registerStatusListener(mStatusListenerCaptor.capture());
        mWifiNativeStatusListener = mStatusListenerCaptor.getValue();

        mActiveModeWarden.registerSoftApCallback(mSoftApStateMachineCallback);
        mActiveModeWarden.registerLohsCallback(mLohsStateMachineCallback);
    }

    private ActiveModeWarden createActiveModeWarden() {
        ActiveModeWarden warden = new ActiveModeWarden(
                mWifiInjector,
                mLooper.getLooper(),
                mWifiNative,
                mDefaultModeManager,
                mBatteryStats,
                mWifiDiagnostics,
                mContext,
                mClientModeImpl,
                mSettingsStore,
                mFacade,
                mWifiPermissionsUtil);
        // SelfRecovery is created in WifiInjector after ActiveModeWarden, so getSelfRecovery()
        // returns null when constructing ActiveModeWarden.
        when(mWifiInjector.getSelfRecovery()).thenReturn(mSelfRecovery);
        return warden;
    }

    /**
     * Clean up after tests - explicitly set tested object to null.
     */
    @After
    public void cleanUp() throws Exception {
        mActiveModeWarden = null;
        mLooper.dispatchAll();
    }

    /**
     * Helper method to enter the ClientModeActiveState for ActiveModeWarden.
     */
    private void enterClientModeActiveState() throws Exception {
        String fromState = mActiveModeWarden.getCurrentMode();
        doAnswer(new Answer<ClientModeManager>() {
                public ClientModeManager answer(InvocationOnMock invocation) {
                    Object[] args = invocation.getArguments();
                    mClientListener = (ClientModeManager.Listener) args[0];
                    return mClientModeManager;
                }
        }).when(mWifiInjector).makeClientModeManager(any(ClientModeManager.Listener.class));
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        mActiveModeWarden.wifiToggled();
        mLooper.dispatchAll();
        mClientListener.onStateChanged(WifiManager.WIFI_STATE_ENABLED);
        mLooper.dispatchAll();

        assertEquals(CLIENT_MODE_STATE_STRING, mActiveModeWarden.getCurrentMode());
        verify(mClientModeManager).start();
        if (fromState.equals(SCAN_ONLY_MODE_STATE_STRING)) {
            verify(mScanRequestProxy).enableScanning(false, false);
        }
        verify(mScanRequestProxy).enableScanning(true, true);
        verify(mBatteryStats).noteWifiOn();
    }

    /**
     * Helper method to enter the ScanOnlyModeActiveState for ActiveModeWarden.
     */
    private void enterScanOnlyModeActiveState() throws Exception {
        String fromState = mActiveModeWarden.getCurrentMode();
        doAnswer(new Answer<ScanOnlyModeManager>() {
                public ScanOnlyModeManager answer(InvocationOnMock invocation) {
                    Object[] args = invocation.getArguments();
                    mScanOnlyListener = (ScanOnlyModeManager.Listener) args[0];
                    return mScanOnlyModeManager;
                }
        }).when(mWifiInjector).makeScanOnlyModeManager(any(ScanOnlyModeManager.Listener.class));
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        mActiveModeWarden.wifiToggled();
        mLooper.dispatchAll();
        mScanOnlyListener.onStateChanged(WifiManager.WIFI_STATE_ENABLED);
        mLooper.dispatchAll();

        assertEquals(SCAN_ONLY_MODE_STATE_STRING, mActiveModeWarden.getCurrentMode());
        verify(mScanOnlyModeManager).start();
        if (fromState.equals(CLIENT_MODE_STATE_STRING)) {
            verify(mScanRequestProxy).enableScanning(false, false);
        }
        verify(mScanRequestProxy).enableScanning(true, false);
        verify(mBatteryStats).noteWifiOn();
        verify(mBatteryStats).noteWifiState(eq(BatteryStats.WIFI_STATE_OFF_SCANNING), eq(null));
    }

    private void enterSoftApActiveMode() throws Exception {
        enterSoftApActiveMode(
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null));
    }

    /**
     * Helper method to enter the SoftApActiveMode for ActiveModeWarden.
     *
     * This method puts the test object into the correct state and verifies steps along the way.
     */
    private void enterSoftApActiveMode(SoftApModeConfiguration softApConfig) throws Exception {
        String fromState = mActiveModeWarden.getCurrentMode();
        doAnswer(new Answer<SoftApManager>() {
                public SoftApManager answer(InvocationOnMock invocation) {
                    Object[] args = invocation.getArguments();
                    mSoftApManagerCallback = (WifiManager.SoftApCallback) args[0];
                    assertEquals(softApConfig, (SoftApModeConfiguration) args[1]);
                    return mSoftApManager;
                }
        }).when(mWifiInjector).makeSoftApManager(any(WifiManager.SoftApCallback.class), any());
        mActiveModeWarden.startSoftAp(softApConfig);
        mLooper.dispatchAll();
        verify(mSoftApManager).start();
        if (fromState.equals(STA_DISABLED_STATE_STRING)) {
            verify(mBatteryStats).noteWifiOn();
        } else if (!fromState.equals(SCAN_ONLY_MODE_STATE_STRING)
                && !fromState.equals(CLIENT_MODE_STATE_STRING)) {
            verify(mScanRequestProxy, atLeastOnce()).enableScanning(false, false);
        }
    }

    private void enterStaDisabledState() {
        String fromState = mActiveModeWarden.getCurrentMode();
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(false);
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(false);
        mActiveModeWarden.wifiToggled();
        mLooper.dispatchAll();

        assertEquals(CLIENT_MODE_STATE_STRING, mActiveModeWarden.getCurrentMode());
        if (fromState.equals(SCAN_ONLY_MODE_STATE_STRING)
                || fromState.equals(CLIENT_MODE_STATE_STRING)) {
            verify(mScanRequestProxy).enableScanning(false, false);
        }
    }

    private void shutdownWifi() {
        mActiveModeWarden.recoveryDisableWifi();
        mLooper.dispatchAll();
    }

    private void assertInScanOnlyMode() {
        assertThat(mActiveModeWarden.getCurrentMode()).isEqualTo(SCAN_ONLY_MODE_STATE_STRING);
    }

    private void assertInClientMode() {
        assertThat(mActiveModeWarden.getCurrentMode()).isEqualTo(CLIENT_MODE_STATE_STRING);
    }

    private void assertInStaDisabledMode() {
        assertThat(mActiveModeWarden.getCurrentMode()).isEqualTo(STA_DISABLED_STATE_STRING);
    }

    /** Test that after starting up, ActiveModeWarden is in the Disabled State. */
    @Test
    public void testWifiDisabledAtStartup() {
        assertInStaDisabledMode();
    }

    /**
     * Test that ActiveModeWarden properly enters the ScanOnlyModeActiveState from the
     * WifiDisabled state.
     */
    @Test
    public void testEnterScanOnlyModeFromDisabled() throws Exception {
        enterScanOnlyModeActiveState();
    }

    /**
     * Test that ActiveModeWarden properly enters the SoftApModeActiveState from the
     * WifiDisabled state.
     */
    @Test
    public void testEnterSoftApModeFromDisabled() throws Exception {
        enterSoftApActiveMode();
    }

    /**
     * Test that ActiveModeWarden properly enters the SoftApModeActiveState from another state.
     */
    @Test
    public void testEnterSoftApModeFromDifferentState() throws Exception {
        enterClientModeActiveState();
        mLooper.dispatchAll();
        assertEquals(CLIENT_MODE_STATE_STRING, mActiveModeWarden.getCurrentMode());
        reset(mBatteryStats, mScanRequestProxy);
        enterSoftApActiveMode();
    }

    /**
     * Test that we can disable wifi fully from the ScanOnlyModeActiveState.
     */
    @Test
    public void testDisableWifiFromScanOnlyModeActiveState() throws Exception {
        enterScanOnlyModeActiveState();

        enterStaDisabledState();
        verify(mScanOnlyModeManager).stop();
        verify(mBatteryStats).noteWifiOff();
        assertInStaDisabledMode();
    }

    /**
     * Test that we can disable wifi from the SoftApModeActiveState and not impact softap.
     */
    @Test
    public void testDisableWifiFromSoftApModeActiveStateDoesNotStopSoftAp() throws Exception {
        enterSoftApActiveMode();

        reset(mDefaultModeManager);
        enterStaDisabledState();
        verify(mSoftApManager, never()).stop();
        verify(mBatteryStats, never()).noteWifiOff();
        assertEquals(STA_DISABLED_STATE_STRING, mActiveModeWarden.getCurrentMode());
    }

    /**
     * Test that we can switch from ScanOnlyActiveMode to another mode.
     * Expectation: When switching out of ScanOnlyModeActivState we stop the ScanOnlyModeManager.
     */
    @Test
    public void testSwitchModeWhenScanOnlyModeActiveState() throws Exception {
        enterScanOnlyModeActiveState();

        reset(mBatteryStats, mScanRequestProxy);
        enterClientModeActiveState();
        mLooper.dispatchAll();
        verify(mScanOnlyModeManager).stop();
        assertEquals(CLIENT_MODE_STATE_STRING, mActiveModeWarden.getCurrentMode());
    }

    /**
     * Reentering ClientModeActiveState should be a NOP.
     */
    @Test
    public void testReenterClientModeActiveStateIsNop() throws Exception {
        enterClientModeActiveState();
        reset(mClientModeManager);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        mActiveModeWarden.wifiToggled();
        mLooper.dispatchAll();
        verify(mClientModeManager, never()).start();
    }

    /**
     * Test that we can switch from SoftApActiveMode to another mode.
     * Expectation: When switching out of SoftApModeActiveState we do not impact softap operation
     */
    @Test
    public void testSwitchModeWhenSoftApActiveMode() throws Exception {
        enterSoftApActiveMode();

        reset(mWifiNative);

        enterClientModeActiveState();
        mLooper.dispatchAll();
        verify(mSoftApManager, never()).stop();
        assertEquals(CLIENT_MODE_STATE_STRING, mActiveModeWarden.getCurrentMode());
        verify(mWifiNative, never()).teardownAllInterfaces();
    }

    /**
     * Test that we do enter the SoftApModeActiveState if we are already in WifiDisabledState due to
     * a failure.
     * Expectations: We should exit the current WifiDisabledState and re-enter before successfully
     * entering the SoftApModeActiveState.
     */
    @Test
    public void testEnterSoftApModeActiveWhenAlreadyInSoftApMode() throws Exception {
        enterSoftApActiveMode();
        // now inject failure through the SoftApManager.Listener
        mSoftApManagerCallback.onStateChanged(WifiManager.WIFI_AP_STATE_FAILED, 0);
        mLooper.dispatchAll();
        assertEquals(STA_DISABLED_STATE_STRING, mActiveModeWarden.getCurrentMode());
        // clear the first call to start SoftApManager
        reset(mSoftApManager, mBatteryStats);

        enterSoftApActiveMode();
    }

    /**
     * Test that we return to the WifiDisabledState after a failure is reported when in the
     * ScanOnlyModeActiveState.
     * Expectations: we should exit the ScanOnlyModeActiveState and stop the ScanOnlyModeManager.
     */
    @Test
    public void testScanOnlyModeFailureWhenActive() throws Exception {
        enterScanOnlyModeActiveState();
        // now inject a failure through the ScanOnlyModeManager.Listener
        mScanOnlyListener.onStateChanged(WifiManager.WIFI_STATE_UNKNOWN);
        mLooper.dispatchAll();
        assertEquals(STA_DISABLED_STATE_STRING, mActiveModeWarden.getCurrentMode());
        verify(mScanOnlyModeManager).stop();
        verify(mBatteryStats).noteWifiOff();
    }

    /**
     * Test that we return to the WifiDisabledState after a failure is reported when in the
     * SoftApModeActiveState.
     * Expectations: We should exit the SoftApModeActiveState and stop the SoftApManager.
     */
    @Test
    public void testSoftApFailureWhenActive() throws Exception {
        enterSoftApActiveMode();
        // now inject failure through the SoftApManager.Listener
        mSoftApManagerCallback.onStateChanged(WifiManager.WIFI_AP_STATE_FAILED, 0);
        mLooper.dispatchAll();
        verify(mBatteryStats).noteWifiOff();
    }

    /**
     * Test that we return to the WifiDisabledState after the ScanOnlyModeManager is stopping in the
     * ScanOnlyModeActiveState.
     * Expectations: We should exit the ScanOnlyModeActiveState and stop the ScanOnlyModeManager.
     */
    @Test
    public void testScanOnlyModeDisabledWhenActive() throws Exception {
        enterScanOnlyModeActiveState();
        // now inject the stop message through the ScanOnlyModeManager.Listener
        mScanOnlyListener.onStateChanged(WifiManager.WIFI_STATE_DISABLED);
        mLooper.dispatchAll();
        assertEquals(STA_DISABLED_STATE_STRING, mActiveModeWarden.getCurrentMode());
        verify(mScanOnlyModeManager).stop();
        verify(mBatteryStats).noteWifiOff();
    }

    /**
     * Test that we return to the WifiDisabledState after the SoftApManager is stopped in the
     * SoftApModeActiveState.
     * Expectations: We should exit the SoftApModeActiveState and stop the SoftApManager.
     */
    @Test
    public void testSoftApDisabledWhenActive() throws Exception {
        enterSoftApActiveMode();
        reset(mWifiNative);
        // now inject failure through the SoftApManager.Listener
        mSoftApManagerCallback.onStateChanged(WifiManager.WIFI_AP_STATE_FAILED, 0);
        mLooper.dispatchAll();
        verify(mBatteryStats).noteWifiOff();
        verifyNoMoreInteractions(mWifiNative);
    }

    /**
     * Verifies that SoftApStateChanged event is being passed from SoftApManager to WifiServiceImpl
     */
    @Test
    public void callsWifiServiceCallbackOnSoftApStateChanged() throws Exception {
        enterSoftApActiveMode();

        mSoftApManagerCallback.onStateChanged(WifiManager.WIFI_AP_STATE_ENABLED, 0);
        mLooper.dispatchAll();

        verify(mSoftApStateMachineCallback).onStateChanged(WifiManager.WIFI_AP_STATE_ENABLED, 0);
    }

    /**
     * Verifies that SoftApStateChanged event isn't passed to WifiServiceImpl for LOHS,
     * so the state change for LOHS doesn't affect Wifi Tethering indication.
     */
    @Test
    public void doesntCallWifiServiceCallbackOnLOHSStateChanged() throws Exception {
        enterSoftApActiveMode(new SoftApModeConfiguration(
                WifiManager.IFACE_IP_MODE_LOCAL_ONLY, null));

        mSoftApManagerCallback.onStateChanged(WifiManager.WIFI_AP_STATE_ENABLED, 0);
        mLooper.dispatchAll();

        verify(mSoftApStateMachineCallback, never()).onStateChanged(anyInt(), anyInt());
        verify(mSoftApStateMachineCallback, never()).onNumClientsChanged(anyInt());
    }

    /**
     * Verifies that triggering a state change update will not crash if the callback to
     * WifiServiceImpl is null.
     */
    @Test
    public void testNullCallbackToWifiServiceImplForStateChange() throws Exception {
        //set the callback to null
        mActiveModeWarden.registerSoftApCallback(null);

        enterSoftApActiveMode();

        mSoftApManagerCallback.onStateChanged(WifiManager.WIFI_AP_STATE_DISABLING, 0);
        mLooper.dispatchAll();

        verify(mSoftApStateMachineCallback, never()).onStateChanged(anyInt(), anyInt());
    }

    /**
     * Verifies that NumClientsChanged event is being passed from SoftApManager to WifiServiceImpl
     */
    @Test
    public void callsWifiServiceCallbackOnSoftApNumClientsChanged() throws Exception {
        final int testNumClients = 3;
        enterSoftApActiveMode();
        mSoftApManagerCallback.onNumClientsChanged(testNumClients);
        mLooper.dispatchAll();

        verify(mSoftApStateMachineCallback).onNumClientsChanged(testNumClients);
    }

    /**
     * Verifies that triggering a number of clients changed update will not crash if the callback to
     * WifiServiceImpl is null.
     */
    @Test
    public void testNullCallbackToWifiServiceImplForNumClientsChanged() throws Exception {

        final int testNumClients = 3;

        //set the callback to null
        mActiveModeWarden.registerSoftApCallback(null);

        enterSoftApActiveMode();
        mSoftApManagerCallback.onNumClientsChanged(testNumClients);

        verify(mSoftApStateMachineCallback, never()).onNumClientsChanged(anyInt());
    }

    /**
     * Test that we remain in the active state when we get a state change update that scan mode is
     * active.
     * Expectations: We should remain in the ScanOnlyModeActive state.
     */
    @Test
    public void testScanOnlyModeStaysActiveOnEnabledUpdate() throws Exception {
        enterScanOnlyModeActiveState();
        // now inject failure through the SoftApManager.Listener
        mScanOnlyListener.onStateChanged(WifiManager.WIFI_STATE_ENABLED);
        mLooper.dispatchAll();
        assertEquals(SCAN_ONLY_MODE_STATE_STRING, mActiveModeWarden.getCurrentMode());
        verify(mScanOnlyModeManager, never()).stop();
    }

    /**
     * Test that we do not act on unepected state string messages and remain in the active state.
     * Expectations: We should remain in the ScanOnlyModeActive state.
     */
    @Test
    public void testScanOnlyModeStaysActiveOnUnexpectedStateUpdate() throws Exception {
        enterScanOnlyModeActiveState();
        // now inject failure through the SoftApManager.Listener
        mScanOnlyListener.onStateChanged(WifiManager.WIFI_AP_STATE_DISABLING);
        mLooper.dispatchAll();
        assertEquals(SCAN_ONLY_MODE_STATE_STRING, mActiveModeWarden.getCurrentMode());
        verify(mScanOnlyModeManager, never()).stop();
    }

    /**
     * Test that a config passed in to the call to enterSoftApMode is used to create the new
     * SoftApManager.
     * Expectations: We should create a SoftApManager in WifiInjector with the config passed in to
     * ActiveModeWarden to switch to SoftApMode.
     */
    @Test
    public void testConfigIsPassedToWifiInjector() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "ThisIsAConfig";
        SoftApModeConfiguration softApConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);
        enterSoftApActiveMode(softApConfig);
    }

    /**
     * Test that when enterSoftAPMode is called with a null config, we pass a null config to
     * WifiInjector.makeSoftApManager.
     *
     * Passing a null config to SoftApManager indicates that the default config should be used.
     *
     * Expectations: WifiInjector should be called with a null config.
     */
    @Test
    public void testNullConfigIsPassedToWifiInjector() throws Exception {
        enterSoftApActiveMode();
    }

    /**
     * Test that two calls to switch to SoftAPMode in succession ends up with the correct config.
     *
     * Expectation: we should end up in SoftAPMode state configured with the second config.
     */
    @Test
    public void testStartSoftApModeTwiceWithTwoConfigs() throws Exception {
        when(mWifiInjector.getWifiApConfigStore()).thenReturn(mWifiApConfigStore);
        WifiConfiguration config1 = new WifiConfiguration();
        config1.SSID = "ThisIsAConfig";
        SoftApModeConfiguration softApConfig1 =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config1);
        WifiConfiguration config2 = new WifiConfiguration();
        config2.SSID = "ThisIsASecondConfig";
        SoftApModeConfiguration softApConfig2 =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config2);

        when(mWifiInjector.makeSoftApManager(any(WifiManager.SoftApCallback.class),
                                             eq(softApConfig1)))
                .thenReturn(mSoftApManager);
        // make a second softap manager
        SoftApManager softapManager = mock(SoftApManager.class);
        when(mWifiInjector.makeSoftApManager(any(WifiManager.SoftApCallback.class),
                                             eq(softApConfig2)))
                .thenReturn(softapManager);

        mActiveModeWarden.startSoftAp(softApConfig1);
        mActiveModeWarden.startSoftAp(softApConfig2);
        mLooper.dispatchAll();
        verify(mSoftApManager).start();
        verify(softapManager).start();
        verify(mBatteryStats).noteWifiOn();
    }

    /**
     * Test that we safely disable wifi if it is already disabled.
     * Expectations: We should not interact with WifiNative since we should have already cleaned up
     * everything.
     */
    @Test
    public void disableWifiWhenAlreadyOff() throws Exception {
        enterStaDisabledState();
        verifyZeroInteractions(mWifiNative);
    }

    /**
     * Trigger recovery and a bug report if we see a native failure.
     */
    @Test
    public void handleWifiNativeFailure() throws Exception {
        mWifiNativeStatusListener.onStatusChanged(false);
        mLooper.dispatchAll();
        verify(mWifiDiagnostics).captureBugReportData(
                WifiDiagnostics.REPORT_REASON_WIFINATIVE_FAILURE);
        verify(mSelfRecovery).trigger(eq(SelfRecovery.REASON_WIFINATIVE_FAILURE));
    }

    /**
     * Verify an onStatusChanged callback with "true" does not trigger recovery.
     */
    @Test
    public void handleWifiNativeStatusReady() throws Exception {
        mWifiNativeStatusListener.onStatusChanged(true);
        mLooper.dispatchAll();
        verify(mWifiDiagnostics, never()).captureBugReportData(
                WifiDiagnostics.REPORT_REASON_WIFINATIVE_FAILURE);
        verify(mSelfRecovery, never()).trigger(eq(SelfRecovery.REASON_WIFINATIVE_FAILURE));
    }

    /**
     * Verify that mode stop is safe even if the underlying Client mode exited already.
     */
    @Test
    public void shutdownWifiDoesNotCrashWhenClientModeExitsOnDestroyed() throws Exception {
        enterClientModeActiveState();

        mClientListener.onStateChanged(WifiManager.WIFI_STATE_DISABLED);
        mLooper.dispatchAll();

        shutdownWifi();

        assertEquals(STA_DISABLED_STATE_STRING, mActiveModeWarden.getCurrentMode());
    }

    /**
     * Verify that an interface destruction callback is safe after already having been stopped.
     */
    @Test
    public void onDestroyedCallbackDoesNotCrashWhenClientModeAlreadyStopped() throws Exception {
        enterClientModeActiveState();

        shutdownWifi();

        mClientListener.onStateChanged(WifiManager.WIFI_STATE_DISABLED);
        mLooper.dispatchAll();

        assertEquals(STA_DISABLED_STATE_STRING, mActiveModeWarden.getCurrentMode());
    }

    /**
     * Verify that mode stop is safe even if the underlying softap mode exited already.
     */
    @Test
    public void shutdownWifiDoesNotCrashWhenSoftApExitsOnDestroyed() throws Exception {
        enterSoftApActiveMode();

        mSoftApManagerCallback.onStateChanged(WifiManager.WIFI_AP_STATE_DISABLED, 0);
        mLooper.dispatchAll();

        shutdownWifi();

        verify(mSoftApStateMachineCallback).onStateChanged(WifiManager.WIFI_AP_STATE_DISABLED, 0);
    }

    /**
     * Verify that an interface destruction callback is safe after already having been stopped.
     */
    @Test
    public void onDestroyedCallbackDoesNotCrashWhenSoftApModeAlreadyStopped() throws Exception {
        enterSoftApActiveMode();

        shutdownWifi();

        mSoftApManagerCallback.onStateChanged(WifiManager.WIFI_AP_STATE_DISABLED, 0);
        mLooper.dispatchAll();

        verify(mSoftApStateMachineCallback).onStateChanged(WifiManager.WIFI_AP_STATE_DISABLED, 0);
    }

    /**
     * Verify that we do not crash when calling dump and wifi is fully disabled.
     */
    @Test
    public void dumpWhenWifiFullyOffDoesNotCrash() throws Exception {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(stream);
        mActiveModeWarden.dump(null, writer, null);
    }

    /**
     * Verify that we trigger dump on active mode managers.
     */
    @Test
    public void dumpCallsActiveModeManagers() throws Exception {
        enterSoftApActiveMode();
        enterClientModeActiveState();
        reset(mScanRequestProxy);
        enterScanOnlyModeActiveState();

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(stream);
        mActiveModeWarden.dump(null, writer, null);

        verify(mSoftApManager).dump(eq(null), eq(writer), eq(null));
        // can only be in scan or client, so we should not have a client mode active
        verify(mClientModeManager, never()).dump(eq(null), eq(writer), eq(null));
        verify(mScanOnlyModeManager).dump(eq(null), eq(writer), eq(null));
    }

    /**
     * Verify that stopping tethering doesn't stop LOHS.
     */
    @Test
    public void testStopTetheringButNotLOHS() throws Exception {
        // prepare WiFi configurations
        when(mWifiInjector.getWifiApConfigStore()).thenReturn(mWifiApConfigStore);
        SoftApModeConfiguration tetherConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        WifiConfiguration lohsConfigWC = WifiApConfigStore.generateLocalOnlyHotspotConfig(mContext,
                WifiConfiguration.AP_BAND_2GHZ);
        SoftApModeConfiguration lohsConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_LOCAL_ONLY, lohsConfigWC);

        // mock SoftAPManagers
        when(mSoftApManager.getIpMode()).thenReturn(WifiManager.IFACE_IP_MODE_TETHERED);
        when(mWifiInjector.makeSoftApManager(any(WifiManager.SoftApCallback.class),
                                             eq(tetherConfig)))
                .thenReturn(mSoftApManager);
        SoftApManager lohsSoftapManager = mock(SoftApManager.class);
        when(lohsSoftapManager.getIpMode()).thenReturn(WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
        when(mWifiInjector.makeSoftApManager(any(WifiManager.SoftApCallback.class),
                                             eq(lohsConfig)))
                .thenReturn(lohsSoftapManager);

        // enable tethering and LOHS
        mActiveModeWarden.startSoftAp(tetherConfig);
        mActiveModeWarden.startSoftAp(lohsConfig);
        mLooper.dispatchAll();
        verify(mSoftApManager).start();
        verify(lohsSoftapManager).start();
        verify(mBatteryStats).noteWifiOn();

        // disable tethering
        mActiveModeWarden.stopSoftAp(WifiManager.IFACE_IP_MODE_TETHERED);
        mLooper.dispatchAll();
        verify(mSoftApManager).stop();
        verify(lohsSoftapManager, never()).stop();
    }

    /**
     * Verify that toggling wifi from disabled starts client mode.
     */
    @Test
    public void enableWifi() throws Exception {
        assertEquals("StaDisabledState", getCurrentState().getName());

        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        mWifiController.sendMessage(CMD_WIFI_TOGGLED);
        mLooper.dispatchAll();
        assertEquals("StaEnabledState", getCurrentState().getName());
    }

    /**
     * Test verifying that we can enter scan mode when the scan mode changes
     */
    @Test
    public void enableScanMode() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        mWifiController.sendMessage(CMD_SCAN_ALWAYS_MODE_CHANGED);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).enterScanOnlyMode();
    }

    /**
     * Verify that if scanning is enabled at startup, we enter scan mode
     */
    @Test
    public void testEnterScanModeAtStartWhenSet() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);

        // reset to avoid the default behavior
        reset(mActiveModeWarden);

        ActiveModeWarden.WifiController wifiController = new ActiveModeWarden.WifiController(mContext, mClientModeImpl,
                mLooper.getLooper(), mSettingsStore, mFacade, mActiveModeWarden,
                mWifiPermissionsUtil);

        wifiController.start();
        mLooper.dispatchAll();

        verify(mActiveModeWarden, never()).disableWifi();
        verify(mActiveModeWarden).enterScanOnlyMode();
    }

    /**
     * Do not enter scan mode if location mode disabled.
     */
    @Test
    public void testDoesNotEnterScanModeWhenLocationModeDisabled() throws Exception {
        // Start a new WifiController with wifi disabled
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(false);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(false);

        mWifiController = new ActiveModeWarden.WifiController(mContext, mClientModeImpl, mLooper.getLooper(),
                mSettingsStore, mFacade, mActiveModeWarden, mWifiPermissionsUtil);

        reset(mActiveModeWarden);
        mWifiController.start();
        mLooper.dispatchAll();

        verify(mActiveModeWarden).disableWifi();

        // toggling scan always available is not sufficient for scan mode
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        mWifiController.sendMessage(CMD_SCAN_ALWAYS_MODE_CHANGED);
        mLooper.dispatchAll();

        verify(mActiveModeWarden, never()).enterScanOnlyMode();

    }

    /**
     * Only enter scan mode if location mode enabled
     */
    @Test
    public void testEnterScanModeWhenLocationModeEnabled() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(false);

        reset(mContext, mActiveModeWarden);
        when(mContext.getResources()).thenReturn(mResources);
        mWifiController = new ActiveModeWarden.WifiController(mContext, mClientModeImpl, mLooper.getLooper(),
                mSettingsStore, mFacade, mActiveModeWarden, mWifiPermissionsUtil);

        mWifiController.start();
        mLooper.dispatchAll();

        ArgumentCaptor<BroadcastReceiver> bcastRxCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContext).registerReceiver(bcastRxCaptor.capture(), any(IntentFilter.class));
        BroadcastReceiver broadcastReceiver = bcastRxCaptor.getValue();

        verify(mActiveModeWarden).disableWifi();

        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);
        Intent intent = new Intent(LocationManager.MODE_CHANGED_ACTION);

        broadcastReceiver.onReceive(mContext, intent);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).enterScanOnlyMode();
    }



    /**
     * Disabling location mode when in scan mode will disable wifi
     */
    @Test
    public void testExitScanModeWhenLocationModeDisabled() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);

        reset(mContext, mActiveModeWarden);
        when(mContext.getResources()).thenReturn(mResources);
        mWifiController = new ActiveModeWarden.WifiController(mContext, mClientModeImpl, mLooper.getLooper(),
                mSettingsStore, mFacade, mActiveModeWarden, mWifiPermissionsUtil);
        mWifiController.start();
        mLooper.dispatchAll();

        ArgumentCaptor<BroadcastReceiver> bcastRxCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContext).registerReceiver(bcastRxCaptor.capture(), any(IntentFilter.class));
        BroadcastReceiver broadcastReceiver = bcastRxCaptor.getValue();

        verify(mActiveModeWarden).enterScanOnlyMode();

        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(false);
        Intent intent = new Intent(LocationManager.MODE_CHANGED_ACTION);

        broadcastReceiver.onReceive(mContext, intent);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).disableWifi();
    }

    /**
     * When in Client mode, make sure ECM triggers wifi shutdown.
     */
    @Test
    public void testEcmOnFromClientMode() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(false);
        enableWifi();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();

        verify(mActiveModeWarden).shutdownWifi();
    }

    /**
     * ECM disabling messages, when in client mode (not expected) do not trigger state changes.
     */
    @Test
    public void testEcmOffInClientMode() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(false);
        enableWifi();

        // Test with WifiDisableInECBM turned off
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(false);

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();

        verify(mActiveModeWarden, never()).shutdownWifi();
        verify(mActiveModeWarden).stopSoftAPMode(WifiManager.IFACE_IP_MODE_UNSPECIFIED);
    }

    /**
     * When ECM activates and we are in client mode, disabling ECM should return us to client mode.
     */
    @Test
    public void testEcmDisabledReturnsToClientMode() throws Exception {
        enableWifi();
        verify(mActiveModeWarden).enterClientMode();

        reset(mActiveModeWarden);

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();

        verify(mActiveModeWarden).shutdownWifi();

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 0);
        mLooper.dispatchAll();

        verify(mActiveModeWarden).enterClientMode();
    }

    /**
     * When Ecm mode is enabled, we should shut down wifi when we get an emergency mode changed
     * update.
     */
    @Test
    public void testEcmOnFromScanMode() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        mWifiController.sendMessage(CMD_SCAN_ALWAYS_MODE_CHANGED);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).enterScanOnlyMode();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);
        verify(mActiveModeWarden).enterScanOnlyMode();

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();

        verify(mActiveModeWarden).shutdownWifi();
    }

    /**
     * When Ecm mode is disabled, we should not shut down scan mode if we get an emergency mode
     * changed update, but we should turn off soft AP
     */
    @Test
    public void testEcmOffInScanMode() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        mWifiController.sendMessage(CMD_SCAN_ALWAYS_MODE_CHANGED);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).enterScanOnlyMode();

        // Test with WifiDisableInECBM turned off:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(false);
        verify(mActiveModeWarden).enterScanOnlyMode();

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();

        verify(mActiveModeWarden, never()).shutdownWifi();
        verify(mActiveModeWarden).stopSoftAPMode(WifiManager.IFACE_IP_MODE_UNSPECIFIED);
    }

    /**
     * When ECM is disabled, we should return to scan mode
     */
    @Test
    public void testEcmDisabledReturnsToScanMode() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        mWifiController.sendMessage(CMD_SCAN_ALWAYS_MODE_CHANGED);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).enterScanOnlyMode();

        reset(mActiveModeWarden);

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();

        verify(mActiveModeWarden).shutdownWifi();

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 0);
        mLooper.dispatchAll();

        verify(mActiveModeWarden).enterScanOnlyMode();
    }

    /**
     * When Ecm mode is enabled, we should shut down wifi when we get an emergency mode changed
     * update.
     */
    @Test
    public void testEcmOnFromSoftApMode() throws Exception {
        mWifiController.obtainMessage(CMD_SET_AP, 1, 0).sendToTarget();
        mLooper.dispatchAll();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);
        verify(mActiveModeWarden).enterSoftAPMode(any());

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();

        verify(mActiveModeWarden).shutdownWifi();
    }

    /**
     * When Ecm mode is disabled, we should shut down softap mode if we get an emergency mode
     * changed update
     */
    @Test
    public void testEcmOffInSoftApMode() throws Exception {
        mWifiController.obtainMessage(CMD_SET_AP, 1, 0).sendToTarget();
        mLooper.dispatchAll();
        verify(mActiveModeWarden).enterSoftAPMode(any());

        // Test with WifiDisableInECBM turned off:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(false);

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();

        verify(mActiveModeWarden).stopSoftAPMode(WifiManager.IFACE_IP_MODE_UNSPECIFIED);
    }

    /**
     * When ECM is activated and we were in softap mode, we should just return to wifi off when ECM
     * ends
     */
    @Test
    public void testEcmDisabledRemainsDisabledWhenSoftApHadBeenOn() throws Exception {
        verify(mActiveModeWarden).disableWifi();

        mWifiController.obtainMessage(CMD_SET_AP, 1, 0).sendToTarget();
        mLooper.dispatchAll();
        verify(mActiveModeWarden).enterSoftAPMode(any());

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).shutdownWifi();

        reset(mActiveModeWarden);

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 0);
        mLooper.dispatchAll();

        verify(mActiveModeWarden).disableWifi();
        // no additional calls to enable softap
        verify(mActiveModeWarden, never()).enterSoftAPMode(any());
    }

    /**
     * Wifi should remain off when already disabled and we enter ECM.
     */
    @Test
    public void testEcmOnFromDisabledMode() throws Exception {
        verify(mActiveModeWarden, never()).enterSoftAPMode(any());
        verify(mActiveModeWarden, never()).enterClientMode();
        verify(mActiveModeWarden, never()).enterScanOnlyMode();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();
        assertEquals("EcmState", getCurrentState().getName());

        verify(mActiveModeWarden).shutdownWifi();
    }


    /**
     * Updates about call state change also trigger entry of ECM mode.
     */
    @Test
    public void testEnterEcmOnEmergencyCallStateChange() throws Exception {
        verify(mActiveModeWarden).disableWifi();

        enableWifi();
        verify(mActiveModeWarden).enterClientMode();

        reset(mActiveModeWarden);

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        // test call state changed
        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 1);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).shutdownWifi();

        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 0);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).enterClientMode();
    }

    /**
     * Verify when both ECM and call state changes arrive, we enter ECM mode
     */
    @Test
    public void testEnterEcmWithBothSignals() throws Exception {
        verify(mActiveModeWarden).disableWifi();

        enableWifi();
        verify(mActiveModeWarden).enterClientMode();

        reset(mActiveModeWarden);

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 1);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).shutdownWifi();

        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();
        // still only 1 shutdown
        verify(mActiveModeWarden).shutdownWifi();

        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 0);
        mLooper.dispatchAll();
        // stay in ecm, do not send an additional client mode trigger
        verify(mActiveModeWarden, never()).enterClientMode();

        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 0);
        mLooper.dispatchAll();
        // now we can re-enable wifi
        verify(mActiveModeWarden).enterClientMode();
    }

    /**
     * Verify when both ECM and call state changes arrive but out of order, we enter ECM mode
     */
    @Test
    public void testEnterEcmWithBothSignalsOutOfOrder() throws Exception {
        verify(mActiveModeWarden).disableWifi();

        enableWifi();
        verify(mActiveModeWarden).enterClientMode();

        reset(mActiveModeWarden);

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).shutdownWifi();

        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 1);
        mLooper.dispatchAll();
        // still only 1 shutdown
        verify(mActiveModeWarden).shutdownWifi();

        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 0);
        mLooper.dispatchAll();
        // stay in ecm, do not send an additional client mode trigger
        verify(mActiveModeWarden, never()).enterClientMode();

        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 0);
        mLooper.dispatchAll();
        // now we can re-enable wifi
        verify(mActiveModeWarden).enterClientMode();
    }

    /**
     * Verify when both ECM and call state changes arrive but completely out of order,
     * we still enter and properly exit ECM mode
     */
    @Test
    public void testEnterEcmWithBothSignalsOppositeOrder() throws Exception {
        verify(mActiveModeWarden).disableWifi();

        enableWifi();
        verify(mActiveModeWarden).enterClientMode();

        reset(mActiveModeWarden);

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 1);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).shutdownWifi();

        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();
        // still only 1 shutdown
        verify(mActiveModeWarden).shutdownWifi();

        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 0);
        mLooper.dispatchAll();
        // stay in ecm, do not send an additional client mode trigger
        verify(mActiveModeWarden, never()).enterClientMode();

        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 0);
        mLooper.dispatchAll();
        // now we can re-enable wifi
        verify(mActiveModeWarden).enterClientMode();
    }


    /**
     * When ECM is active, we might get addition signals of ECM mode, we must not exit until they
     * are all cleared.
     */
    @Test
    public void testProperExitFromEcmModeWithMultipleMessages() throws Exception {
        verify(mActiveModeWarden).disableWifi();

        enableWifi();
        verify(mActiveModeWarden).enterClientMode();

        reset(mActiveModeWarden);

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 1);
        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 1);
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).shutdownWifi();

        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 0);
        mLooper.dispatchAll();
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 0);
        mLooper.dispatchAll();
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 0);
        mLooper.dispatchAll();
        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 0);
        mLooper.dispatchAll();
        verify(mActiveModeWarden, never()).enterClientMode();

        // now we will exit ECM
        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 0);
        mLooper.dispatchAll();

        // now we can re-enable wifi
        verify(mActiveModeWarden).enterClientMode();
    }

    /**
     * Toggling wifi when in ECM does not exit ecm mode and enable wifi
     */
    @Test
    public void testWifiDoesNotToggleOnWhenInEcm() throws Exception {

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();
        assertEquals("EcmState", getCurrentState().getName());

        verify(mActiveModeWarden).shutdownWifi();

        // now toggle wifi and verify we do not start wifi
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        mWifiController.sendMessage(CMD_WIFI_TOGGLED);
        mLooper.dispatchAll();

        verify(mActiveModeWarden, never()).enterClientMode();
    }

    /**
     * Toggling scan mode when in ECM does not exit ecm mode and enable scan mode
     */
    @Test
    public void testScanModeDoesNotToggleOnWhenInEcm() throws Exception {

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();
        assertEquals("EcmState", getCurrentState().getName());

        verify(mActiveModeWarden).shutdownWifi();

        // now enable scanning and verify we do not start wifi
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);

        mWifiController.sendMessage(CMD_SCAN_ALWAYS_MODE_CHANGED);
        mLooper.dispatchAll();

        verify(mActiveModeWarden, never()).enterScanOnlyMode();
    }

    /**
     * Toggling softap mode when in ECM does not exit ecm mode and enable softap
     */
    @Test
    public void testSoftApModeDoesNotToggleOnWhenInEcm() throws Exception {

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();
        assertEquals("EcmState", getCurrentState().getName());

        verify(mActiveModeWarden).shutdownWifi();

        mWifiController.sendMessage(CMD_SET_AP);
        mLooper.dispatchAll();

        verify(mActiveModeWarden, never()).enterSoftAPMode(any());
    }

    /**
     * Toggling off softap mode when in ECM does not induce a mode change
     */
    @Test
    public void testSoftApStoppedDoesNotSwitchModesWhenInEcm() throws Exception {

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();
        assertEquals("EcmState", getCurrentState().getName());

        verify(mActiveModeWarden).shutdownWifi();

        reset(mActiveModeWarden);
        mWifiController.sendMessage(CMD_AP_STOPPED);
        mLooper.dispatchAll();

        verifyNoMoreInteractions(mActiveModeWarden);
    }

    /**
     * Toggling softap mode when in airplane mode needs to enable softap
     */
    @Test
    public void testSoftApModeToggleWhenInAirplaneMode() throws Exception {
        // Test with airplane mode turned on:
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(true);

        // Turn on SoftAp.
        mWifiController.sendMessage(CMD_SET_AP, 1);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).enterSoftAPMode(any());

        // Turn off SoftAp.
        mWifiController.sendMessage(CMD_SET_AP, 0, WifiManager.IFACE_IP_MODE_UNSPECIFIED);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).stopSoftAPMode(WifiManager.IFACE_IP_MODE_UNSPECIFIED);
    }

    /**
     * Toggling off scan mode when in ECM does not induce a mode change
     */
    @Test
    public void testScanModeStoppedDoesNotSwitchModesWhenInEcm() throws Exception {

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();
        assertEquals("EcmState", getCurrentState().getName());

        verify(mActiveModeWarden).shutdownWifi();

        reset(mActiveModeWarden);
        mWifiController.sendMessage(CMD_SCANNING_STOPPED);
        mLooper.dispatchAll();

        verifyNoMoreInteractions(mActiveModeWarden);
    }

    /**
     * Toggling off client mode when in ECM does not induce a mode change
     */
    @Test
    public void testClientModeStoppedDoesNotSwitchModesWhenInEcm() throws Exception {

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();
        assertEquals("EcmState", getCurrentState().getName());

        verify(mActiveModeWarden).shutdownWifi();

        reset(mActiveModeWarden);
        mWifiController.sendMessage(CMD_STA_STOPPED);
        mLooper.dispatchAll();

        verifyNoMoreInteractions(mActiveModeWarden);
    }


    /**
     * When AP mode is enabled and wifi was previously in AP mode, we should return to
     * StaEnabledState after the AP is disabled.
     * Enter StaEnabledState, activate AP mode, disable AP mode.
     * <p>
     * Expected: AP should successfully start and exit, then return to StaEnabledState.
     */
    @Test
    public void testReturnToStaEnabledStateAfterAPModeShutdown() throws Exception {
        enableWifi();
        assertEquals("StaEnabledState", getCurrentState().getName());

        mWifiController.obtainMessage(CMD_SET_AP, 1, 0).sendToTarget();
        // add an "unexpected" sta mode stop to simulate a single interface device
        mClientModeCallback.onStateChanged(WifiManager.WIFI_STATE_DISABLED);
        mLooper.dispatchAll();

        when(mSettingsStore.getWifiSavedState()).thenReturn(1);
        mWifiController.obtainMessage(CMD_AP_STOPPED).sendToTarget();
        mLooper.dispatchAll();

        InOrder inOrder = inOrder(mActiveModeWarden);
        inOrder.verify(mActiveModeWarden).enterClientMode();
        assertEquals("StaEnabledState", getCurrentState().getName());
    }

    /**
     * When AP mode is enabled and wifi is toggled on, we should transition to
     * StaEnabledState after the AP is disabled.
     * Enter StaEnabledState, activate AP mode, toggle WiFi.
     * <p>
     * Expected: AP should successfully start and exit, then return to StaEnabledState.
     */
    @Test
    public void testReturnToStaEnabledStateAfterWifiEnabledShutdown() throws Exception {
        enableWifi();
        assertEquals("StaEnabledState", getCurrentState().getName());

        mWifiController.obtainMessage(CMD_SET_AP, 1, 0).sendToTarget();
        mLooper.dispatchAll();

        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        mWifiController.obtainMessage(CMD_WIFI_TOGGLED).sendToTarget();
        mWifiController.obtainMessage(CMD_AP_STOPPED).sendToTarget();
        mLooper.dispatchAll();

        InOrder inOrder = inOrder(mActiveModeWarden);
        inOrder.verify(mActiveModeWarden).enterClientMode();
        assertEquals("StaEnabledState", getCurrentState().getName());
    }

    @Test
    public void testRestartWifiStackInStaEnabledStateTriggersBugReport() throws Exception {
        enableWifi();
        mWifiController.sendMessage(CMD_RECOVERY_RESTART_WIFI,
                SelfRecovery.REASON_WIFINATIVE_FAILURE);
        mLooper.dispatchAll();
        verify(mClientModeImpl).takeBugReport(anyString(), anyString());
    }

    @Test
    public void testRestartWifiWatchdogDoesNotTriggerBugReport() throws Exception {
        enableWifi();
        mWifiController.sendMessage(CMD_RECOVERY_RESTART_WIFI,
                SelfRecovery.REASON_LAST_RESORT_WATCHDOG);
        mLooper.dispatchAll();
        verify(mClientModeImpl, never()).takeBugReport(anyString(), anyString());
    }

    /**
     * When in sta mode, CMD_RECOVERY_DISABLE_WIFI messages should trigger wifi to disable.
     */
    @Test
    public void testRecoveryDisabledTurnsWifiOff() throws Exception {
        enableWifi();
        reset(mActiveModeWarden);
        mWifiController.sendMessage(CMD_RECOVERY_DISABLE_WIFI);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).disableWifi();
    }

    /**
     * When wifi is disabled, CMD_RECOVERY_DISABLE_WIFI should not trigger a state change.
     */
    @Test
    public void testRecoveryDisabledWhenWifiAlreadyOff() throws Exception {
        assertEquals("StaDisabledState", getCurrentState().getName());
        mWifiController.sendMessage(CMD_RECOVERY_DISABLE_WIFI);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).shutdownWifi();
    }

    /**
     * The command to trigger a WiFi reset should not trigger any action by WifiController if we
     * are not in STA mode.
     * WiFi is not in connect mode, so any calls to reset the wifi stack due to connection failures
     * should be ignored.
     * Create and start WifiController in StaDisabledState, send command to restart WiFi
     * <p>
     * Expected: WiFiController should not call ActiveModeWarden.disableWifi()
     */
    @Test
    public void testRestartWifiStackInStaDisabledState() throws Exception {
        // Start a new WifiController with wifi disabled
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(false);

        mWifiController = new ActiveModeWarden.WifiController(mContext, mClientModeImpl, mLooper.getLooper(),
                mSettingsStore, mFacade, mActiveModeWarden, mWifiPermissionsUtil);

        mWifiController.start();
        mLooper.dispatchAll();

        reset(mClientModeImpl);
        assertEquals("StaDisabledState", getCurrentState().getName());

        reset(mActiveModeWarden);
        mWifiController.sendMessage(CMD_RECOVERY_RESTART_WIFI);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).disableWifi();
    }

    /**
     * The command to trigger a WiFi reset should not trigger any action by WifiController if we
     * are not in STA mode, even if scans are allowed.
     * WiFi is not in connect mode, so any calls to reset the wifi stack due to connection failures
     * should be ignored.
     * Create and start WifiController in StaDisabledState, send command to restart WiFi
     * <p>
     * Expected: WiFiController should not call ActiveModeWarden.disableWifi() or
     * ActiveModeWarden.shutdownWifi().
     */
    @Test
    public void testRestartWifiStackInStaDisabledWithScanState() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        mWifiController.sendMessage(CMD_SCAN_ALWAYS_MODE_CHANGED);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).enterScanOnlyMode();

        reset(mActiveModeWarden);
        mWifiController.sendMessage(CMD_RECOVERY_RESTART_WIFI);
        mLooper.dispatchAll();
        mLooper.moveTimeForward(TEST_WIFI_RECOVERY_DELAY_MS);
        mLooper.dispatchAll();
        InOrder inOrder = inOrder(mActiveModeWarden);
        inOrder.verify(mActiveModeWarden).disableWifi();
        inOrder.verify(mActiveModeWarden).enterScanOnlyMode();
    }

    /**
     * The command to trigger a WiFi reset should trigger a wifi reset in ClientModeImpl through
     * the ActiveModeWarden.shutdownWifi() call when in STA mode.
     * WiFi is in connect mode, calls to reset the wifi stack due to connection failures
     * should trigger a supplicant stop, and subsequently, a driver reload.
     * Create and start WifiController in StaEnabledState, send command to restart WiFi
     * <p>
     * Expected: WiFiController should call ActiveModeWarden.shutdownWifi() and
     * ActiveModeWarden should enter CONNECT_MODE and the wifi driver should be started.
     */
    @Test
    public void testRestartWifiStackInStaEnabledState() throws Exception {
        enableWifi();
        assertEquals("StaEnabledState", getCurrentState().getName());

        mWifiController.sendMessage(CMD_RECOVERY_RESTART_WIFI);
        mLooper.dispatchAll();
        mLooper.moveTimeForward(TEST_WIFI_RECOVERY_DELAY_MS);
        mLooper.dispatchAll();

        InOrder inOrder = inOrder(mActiveModeWarden);
        inOrder.verify(mActiveModeWarden).shutdownWifi();
        inOrder.verify(mActiveModeWarden).enterClientMode();
        assertEquals("StaEnabledState", getCurrentState().getName());
    }

    /**
     * The command to trigger a WiFi reset should not trigger a reset when in ECM mode.
     * Enable wifi and enter ECM state, send command to restart wifi.
     * <p>
     * Expected: The command to trigger a wifi reset should be ignored and we should remain in ECM
     * mode.
     */
    @Test
    public void testRestartWifiStackDoesNotExitECMMode() throws Exception {
        enableWifi();
        assertEquals("StaEnabledState", getCurrentState().getName());
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 1);
        mLooper.dispatchAll();
        assertEquals("EcmState", getCurrentState().getName());
        verify(mActiveModeWarden).shutdownWifi();

        reset(mActiveModeWarden);
        mWifiController.sendMessage(CMD_RECOVERY_RESTART_WIFI);
        mLooper.dispatchAll();
        assertEquals("EcmState", getCurrentState().getName());

        verifyZeroInteractions(mActiveModeWarden);
    }

    /**
     * The command to trigger a WiFi reset should trigger a reset when in AP mode.
     * Enter AP mode, send command to restart wifi.
     * <p>
     * Expected: The command to trigger a wifi reset should trigger wifi shutdown.
     */
    @Test
    public void testRestartWifiStackFullyStopsWifi() throws Exception {
        mWifiController.obtainMessage(CMD_SET_AP, 1).sendToTarget();
        mLooper.dispatchAll();
        verify(mActiveModeWarden).enterSoftAPMode(any());

        reset(mActiveModeWarden);
        mWifiController.sendMessage(CMD_RECOVERY_RESTART_WIFI);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).shutdownWifi();
    }
}
