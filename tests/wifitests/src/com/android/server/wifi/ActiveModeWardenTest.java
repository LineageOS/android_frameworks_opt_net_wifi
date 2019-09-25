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

import static com.google.common.truth.Truth.assertThat;

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
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Unit tests for {@link com.android.server.wifi.ActiveModeWarden}.
 */
@SmallTest
public class ActiveModeWardenTest extends WifiBaseTest {
    public static final String TAG = "WifiActiveModeWardenTest";

    private static final String STA_ENABLED_STATE_STRING = "StaEnabledState";
    private static final String STA_DISABLED_STATE_STRING = "StaDisabledState";

    private static final String WIFI_IFACE_NAME = "mockWlan";
    private static final int TEST_WIFI_RECOVERY_DELAY_MS = 2000;

    TestLooper mLooper;
    @Mock WifiInjector mWifiInjector;
    @Mock Context mContext;
    @Mock Resources mResources;
    @Mock WifiNative mWifiNative;
    @Mock WifiApConfigStore mWifiApConfigStore;
    @Mock ClientModeManager mClientModeManager;
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

    ActiveModeManager.Listener mClientListener;
    ActiveModeManager.Listener mSoftApListener;
    WifiManager.SoftApCallback mSoftApManagerCallback;
    SoftApModeConfiguration mSoftApConfig;
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
        when(mSoftApManager.getScanMode()).thenReturn(SCAN_NONE);

        when(mResources.getString(R.string.wifi_localhotspot_configure_ssid_default))
                .thenReturn("AndroidShare");
        when(mResources.getInteger(R.integer.config_wifi_framework_recovery_timeout_delay))
                .thenReturn(TEST_WIFI_RECOVERY_DELAY_MS);

        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(false);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);

        doAnswer(new Answer<ClientModeManager>() {
            public ClientModeManager answer(InvocationOnMock invocation) {
                Object[] args = invocation.getArguments();
                mClientListener = (ClientModeManager.Listener) args[0];
                return mClientModeManager;
            }
        }).when(mWifiInjector).makeClientModeManager(any(ActiveModeManager.Listener.class));
        doAnswer(new Answer<SoftApManager>() {
            public SoftApManager answer(InvocationOnMock invocation) {
                Object[] args = invocation.getArguments();
                mSoftApListener = (ActiveModeManager.Listener) args[0];
                mSoftApManagerCallback = (WifiManager.SoftApCallback) args[1];
                mSoftApConfig = (SoftApModeConfiguration) args[2];
                return mSoftApManager;
            }
        }).when(mWifiInjector).makeSoftApManager(any(ActiveModeManager.Listener.class),
                any(WifiManager.SoftApCallback.class), any());

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
     * Helper method to enter the StaEnabledState and set ClientModeManager in ConnectMode.
     */
    private void enterClientModeActiveState() throws Exception {
        when(mClientModeManager.getScanMode()).thenReturn(SCAN_WITH_HIDDEN_NETWORKS);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        mActiveModeWarden.wifiToggled();
        mLooper.dispatchAll();
        mClientListener.onStarted();
        mLooper.dispatchAll();

        assertInStaEnabledState();
        verify(mClientModeManager).start();
        verify(mClientModeManager).switchToConnectMode();
        verify(mScanRequestProxy).enableScanning(true, true);
        verify(mBatteryStats).noteWifiOn();
    }

    /**
     * Helper method to enter the StaEnabledState and set ClientModeManager in ScanOnlyMode.
     */
    private void enterScanOnlyModeActiveState() throws Exception {
        when(mClientModeManager.getScanMode()).thenReturn(SCAN_WITHOUT_HIDDEN_NETWORKS);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mActiveModeWarden.wifiToggled();
        mLooper.dispatchAll();
        mClientListener.onStarted();
        mLooper.dispatchAll();

        assertInStaEnabledState();
        verify(mClientModeManager).start();
        verify(mClientModeManager).switchToScanOnlyMode();
        verify(mScanRequestProxy).enableScanning(true, false);
        verify(mBatteryStats).noteWifiOn();
        verify(mBatteryStats).noteWifiState(BatteryStats.WIFI_STATE_OFF_SCANNING, null);
    }

    private void enterSoftApActiveMode() throws Exception {
        enterSoftApActiveMode(
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null));
    }

    /**
     * Helper method to activate SoftApManager.
     *
     * This method puts the test object into the correct state and verifies steps along the way.
     */
    private void enterSoftApActiveMode(SoftApModeConfiguration softApConfig) throws Exception {
        String fromState = mActiveModeWarden.getCurrentMode();
        mActiveModeWarden.startSoftAp(softApConfig);
        mLooper.dispatchAll();
        assertThat(softApConfig).isEqualTo(mSoftApConfig);
        verify(mSoftApManager).start();
        if (fromState.equals(STA_DISABLED_STATE_STRING)) {
            verify(mBatteryStats).noteWifiOn();
        } else if (!fromState.equals(STA_ENABLED_STATE_STRING)) {
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

        assertInStaDisabledState();
        if (fromState.equals(STA_ENABLED_STATE_STRING)) {
            verify(mScanRequestProxy).enableScanning(false, false);
        }
    }

    private void shutdownWifi() {
        mActiveModeWarden.recoveryDisableWifi();
        mLooper.dispatchAll();
    }

    private void assertInStaEnabledState() {
        assertThat(mActiveModeWarden.getCurrentMode()).isEqualTo(STA_ENABLED_STATE_STRING);
    }

    private void assertInStaDisabledState() {
        assertThat(mActiveModeWarden.getCurrentMode()).isEqualTo(STA_DISABLED_STATE_STRING);
    }

    /**
     * Emergency mode is a sub-mode within each main state (ScanOnly, Client, StaDisabled).
     */
    private void assertInEmergencyMode() {
        assertThat(mActiveModeWarden.isInEmergencyMode()).isTrue();
    }

    /**
     * Counts the number of times a void method was called on a mock.
     *
     * Void methods cannot be passed to Mockito.mockingDetails(). Thus we have to use method name
     * matching instead.
     */
    private static int getMethodInvocationCount(Object mock, String methodName) {
        long count = mockingDetails(mock).getInvocations()
                .stream()
                .filter(invocation -> methodName.equals(invocation.getMethod().getName()))
                .count();
        return (int) count;
    }

    /**
     * Counts the number of times a non-void method was called on a mock.
     *
     * For non-void methods, can pass the method call literal directly:
     * e.g. getMethodInvocationCount(mock.method());
     */
    private static int getMethodInvocationCount(Object mockMethod) {
        return mockingDetails(mockMethod).getInvocations().size();
    }

    private void assertWifiShutDown(Runnable r) {
        assertWifiShutDown(r, 1);
    }

    /**
     * Asserts that the runnable r has shut down wifi properly.
     * @param r runnable that will shut down wifi
     * @param times expected number of times that <code>r</code> shut down wifi
     */
    private void assertWifiShutDown(Runnable r, int times) {
        // take snapshot of ActiveModeManagers
        Collection<ActiveModeManager> activeModeManagers =
                mActiveModeWarden.getActiveModeManagers();

        List<Integer> expectedStopInvocationCounts = activeModeManagers
                .stream()
                .map(manager -> getMethodInvocationCount(manager, "stop") + times)
                .collect(Collectors.toList());

        r.run();

        List<Integer> actualStopInvocationCounts = activeModeManagers
                .stream()
                .map(manager -> getMethodInvocationCount(manager, "stop"))
                .collect(Collectors.toList());

        String managerNames = activeModeManagers.stream()
                .map(manager -> manager.getClass().getCanonicalName())
                .collect(Collectors.joining(", ", "[", "]"));

        assertThat(actualStopInvocationCounts)
                .named(managerNames)
                .isEqualTo(expectedStopInvocationCounts);
    }

    private void assertEnteredEcmMode(Runnable r) {
        assertEnteredEcmMode(r, 1);
    }

    /**
     * Asserts that the runnable r has entered ECM state properly.
     * @param r runnable that will enter ECM
     * @param times expected number of times that <code>r</code> shut down wifi
     */
    private void assertEnteredEcmMode(Runnable r, int times) {
        // take snapshot of ActiveModeManagers
        Collection<ActiveModeManager> activeModeManagers =
                mActiveModeWarden.getActiveModeManagers();

        boolean disableWifiInEcm = mFacade.getConfigWiFiDisableInECBM(mContext);

        List<Integer> expectedStopInvocationCounts = activeModeManagers.stream()
                .map(manager -> {
                    int initialCount = getMethodInvocationCount(manager, "stop");
                    // carrier config enabled, all mode managers should have been shut down once
                    int count = disableWifiInEcm ? initialCount + times : initialCount;
                    if (manager instanceof SoftApManager) {
                        // expect SoftApManager.close() to be called
                        return count + times;
                    } else {
                        // don't expect other Managers close() to be called
                        return count;
                    }
                })
                .collect(Collectors.toList());

        r.run();

        assertInEmergencyMode();

        List<Integer> actualStopInvocationCounts = activeModeManagers.stream()
                .map(manager -> getMethodInvocationCount(manager, "stop"))
                .collect(Collectors.toList());

        String managerNames = activeModeManagers.stream()
                .map(manager -> manager.getClass().getCanonicalName())
                .collect(Collectors.joining(", ", "[", "]"));

        assertThat(actualStopInvocationCounts)
                .named(managerNames)
                .isEqualTo(expectedStopInvocationCounts);
    }

    /** Test that after starting up, ActiveModeWarden is in the StaDisabled State. */
    @Test
    public void testStaDisabledAtStartup() {
        assertInStaDisabledState();
    }

    /**
     * Test that ActiveModeWarden properly enters the StaEnabledState (in ScanOnlyMode) from the
     * StaDisabled state.
     */
    @Test
    public void testEnterScanOnlyModeFromDisabled() throws Exception {
        enterScanOnlyModeActiveState();
    }

    /**
     * Test that ActiveModeWarden properly starts the SoftApManager from the
     * StaDisabled state.
     */
    @Test
    public void testEnterSoftApModeFromDisabled() throws Exception {
        enterSoftApActiveMode();
    }

    /**
     * Test that ActiveModeWarden properly starts the SoftApManager from another state.
     */
    @Test
    public void testEnterSoftApModeFromDifferentState() throws Exception {
        enterClientModeActiveState();
        assertInStaEnabledState();
        reset(mBatteryStats, mScanRequestProxy);
        enterSoftApActiveMode();
    }

    /**
     * Test that we can disable wifi fully from the StaEnabledState (in ScanOnlyMode).
     */
    @Test
    public void testDisableWifiFromScanOnlyModeActiveState() throws Exception {
        enterScanOnlyModeActiveState();

        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(false);
        mActiveModeWarden.scanAlwaysModeChanged();
        mLooper.dispatchAll();

        verify(mClientModeManager).stop();
        verify(mBatteryStats).noteWifiOff();
        assertInStaDisabledState();
    }

    /**
     * Test that we can disable wifi when SoftApManager is active and not impact softap.
     */
    @Test
    public void testDisableWifiFromSoftApModeActiveStateDoesNotStopSoftAp() throws Exception {
        enterSoftApActiveMode();

        reset(mDefaultModeManager);
        enterStaDisabledState();
        verify(mSoftApManager, never()).stop();
        verify(mBatteryStats, never()).noteWifiOff();
        assertInStaDisabledState();
    }

    /**
     * Test that we can switch from the StaEnabledState (in ScanOnlyMode) to another mode.
     */
    @Test
    public void testSwitchModeWhenScanOnlyModeActiveState() throws Exception {
        enterScanOnlyModeActiveState();

        reset(mBatteryStats, mScanRequestProxy);
        enterClientModeActiveState();
        mLooper.dispatchAll();
        verify(mClientModeManager).switchToConnectMode();
        assertInStaEnabledState();
    }

    /**
     * Reentering StaEnabledState should be a NOP.
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
     * Test that we can switch mode when SoftApManager is active to another mode.
     */
    @Test
    public void testSwitchModeWhenSoftApActiveMode() throws Exception {
        enterSoftApActiveMode();

        reset(mWifiNative);

        enterClientModeActiveState();
        mLooper.dispatchAll();
        verify(mSoftApManager, never()).stop();
        assertInStaEnabledState();
        verify(mWifiNative, never()).teardownAllInterfaces();
    }

    /**
     * Test that we activate SoftApModeManager if we are already in StaDisabledState due to
     * a failure.
     */
    @Test
    public void testEnterSoftApModeActiveWhenAlreadyInSoftApMode() throws Exception {
        enterSoftApActiveMode();
        // now inject failure through the SoftApManager.Listener
        mSoftApListener.onStartFailure();
        mLooper.dispatchAll();
        assertInStaDisabledState();
        // clear the first call to start SoftApManager
        reset(mSoftApManager, mBatteryStats);

        enterSoftApActiveMode();
    }

    /**
     * Test that we return to the StaDisabledState after a failure is reported when in the
     * StaEnabledState.
     */
    @Test
    public void testScanOnlyModeFailureWhenActive() throws Exception {
        enterScanOnlyModeActiveState();
        // now inject a failure through the ScanOnlyModeManager.Listener
        mClientListener.onStartFailure();
        mLooper.dispatchAll();
        assertInStaDisabledState();
        verify(mClientModeManager).stop();
        verify(mBatteryStats).noteWifiOff();
    }

    /**
     * Test that we return to the StaDisabledState after a failure is reported when
     * SoftApManager is active.
     */
    @Test
    public void testSoftApFailureWhenActive() throws Exception {
        enterSoftApActiveMode();
        // now inject failure through the SoftApManager.Listener
        mSoftApListener.onStartFailure();
        mLooper.dispatchAll();
        verify(mBatteryStats).noteWifiOff();
    }

    /**
     * Test that we return to the DisabledState after the ClientModeManager running in ScanOnlyMode
     * is stopped.
     */
    @Test
    public void testScanOnlyModeDisabledWhenActive() throws Exception {
        enterScanOnlyModeActiveState();

        // now inject the stop message through the ScanOnlyModeManager.Listener
        mClientListener.onStopped();
        mLooper.dispatchAll();

        assertInStaDisabledState();
        verify(mClientModeManager).stop();
        verify(mBatteryStats).noteWifiOff();
    }

    /**
     * Test that we return to the StaDisabledState after the SoftApManager is stopped.
     */
    @Test
    public void testSoftApDisabledWhenActive() throws Exception {
        enterSoftApActiveMode();
        reset(mWifiNative);
        // now inject failure through the SoftApManager.Listener
        mSoftApListener.onStartFailure();
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

        mSoftApListener.onStarted();
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

        mSoftApListener.onStarted();
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
     */
    @Test
    public void testScanOnlyModeStaysActiveOnEnabledUpdate() throws Exception {
        enterScanOnlyModeActiveState();
        // now inject success through the Listener
        mClientListener.onStarted();
        mLooper.dispatchAll();
        assertInStaEnabledState();
        verify(mClientModeManager, never()).stop();
    }

    /**
     * Test that a config passed in to the call to enterSoftApMode is used to create the new
     * SoftApManager.
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

        when(mWifiInjector.makeSoftApManager(any(ActiveModeManager.Listener.class),
                                             any(WifiManager.SoftApCallback.class),
                                             eq(softApConfig1)))
                .thenReturn(mSoftApManager);
        // make a second softap manager
        SoftApManager softapManager = mock(SoftApManager.class);
        when(mWifiInjector.makeSoftApManager(any(ActiveModeManager.Listener.class),
                                             any(WifiManager.SoftApCallback.class),
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

        mClientListener.onStopped();
        mLooper.dispatchAll();

        shutdownWifi();

        assertInStaDisabledState();
    }

    /**
     * Verify that an interface destruction callback is safe after already having been stopped.
     */
    @Test
    public void onDestroyedCallbackDoesNotCrashWhenClientModeAlreadyStopped() throws Exception {
        enterClientModeActiveState();

        shutdownWifi();

        mClientListener.onStopped();
        mLooper.dispatchAll();

        assertInStaDisabledState();
    }

    /**
     * Verify that mode stop is safe even if the underlying softap mode exited already.
     */
    @Test
    public void shutdownWifiDoesNotCrashWhenSoftApExitsOnDestroyed() throws Exception {
        enterSoftApActiveMode();

        mSoftApListener.onStopped();
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

        mSoftApListener.onStopped();
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

        verify(mSoftApManager).dump(null, writer, null);
        verify(mClientModeManager).dump(null, writer, null);
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
        when(mWifiInjector.makeSoftApManager(any(ActiveModeManager.Listener.class),
                                             any(WifiManager.SoftApCallback.class),
                                             eq(tetherConfig)))
                .thenReturn(mSoftApManager);
        SoftApManager lohsSoftapManager = mock(SoftApManager.class);
        when(lohsSoftapManager.getIpMode()).thenReturn(WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
        when(mWifiInjector.makeSoftApManager(any(ActiveModeManager.Listener.class),
                                             any(WifiManager.SoftApCallback.class),
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
        assertInStaDisabledState();

        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        mActiveModeWarden.wifiToggled();
        mLooper.dispatchAll();

        assertInStaEnabledState();
    }

    /**
     * Test verifying that we can enter scan mode when the scan mode changes
     */
    @Test
    public void enableScanMode() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        mActiveModeWarden.scanAlwaysModeChanged();
        mLooper.dispatchAll();
        verify(mClientModeManager).start();
        verify(mClientModeManager).switchToScanOnlyMode();
        assertInStaEnabledState();
        verify(mClientModeManager, never()).stop();
    }

    /**
     * Verify that if scanning is enabled at startup, we enter scan mode
     */
    @Test
    public void testEnterScanModeAtStartWhenSet() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);

        mActiveModeWarden = createActiveModeWarden();
        mActiveModeWarden.start();
        mLooper.dispatchAll();

        assertInStaEnabledState();
    }

    /**
     * Verify that if Wifi is enabled at startup, we enter client mode
     */
    @Test
    public void testEnterClientModeAtStartWhenSet() throws Exception {
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);

        mActiveModeWarden = createActiveModeWarden();
        mActiveModeWarden.start();
        mLooper.dispatchAll();

        assertInStaEnabledState();

        verify(mClientModeManager).start();
        verify(mClientModeManager).switchToConnectMode();
    }

    /**
     * Do not enter scan mode if location mode disabled.
     */
    @Test
    public void testDoesNotEnterScanModeWhenLocationModeDisabled() throws Exception {
        // Start a new WifiController with wifi disabled
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(false);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(false);

        mActiveModeWarden = createActiveModeWarden();
        mActiveModeWarden.start();
        mLooper.dispatchAll();

        assertInStaDisabledState();

        // toggling scan always available is not sufficient for scan mode
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        mActiveModeWarden.scanAlwaysModeChanged();
        mLooper.dispatchAll();

        assertInStaDisabledState();
    }

    /**
     * Only enter scan mode if location mode enabled
     */
    @Test
    public void testEnterScanModeWhenLocationModeEnabled() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(false);

        reset(mContext);
        when(mContext.getResources()).thenReturn(mResources);
        mActiveModeWarden = createActiveModeWarden();
        mActiveModeWarden.start();
        mLooper.dispatchAll();

        ArgumentCaptor<BroadcastReceiver> bcastRxCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mContext).registerReceiver(bcastRxCaptor.capture(), any(IntentFilter.class));
        BroadcastReceiver broadcastReceiver = bcastRxCaptor.getValue();

        assertInStaDisabledState();

        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);
        Intent intent = new Intent(LocationManager.MODE_CHANGED_ACTION);
        broadcastReceiver.onReceive(mContext, intent);
        mLooper.dispatchAll();

        assertInStaEnabledState();
    }


    /**
     * Disabling location mode when in scan mode will disable wifi
     */
    @Test
    public void testExitScanModeWhenLocationModeDisabled() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);

        reset(mContext);
        when(mContext.getResources()).thenReturn(mResources);
        mActiveModeWarden = createActiveModeWarden();
        mActiveModeWarden.start();
        mLooper.dispatchAll();

        ArgumentCaptor<BroadcastReceiver> bcastRxCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mContext).registerReceiver(bcastRxCaptor.capture(), any(IntentFilter.class));
        BroadcastReceiver broadcastReceiver = bcastRxCaptor.getValue();

        assertInStaEnabledState();

        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(false);
        Intent intent = new Intent(LocationManager.MODE_CHANGED_ACTION);
        broadcastReceiver.onReceive(mContext, intent);
        mLooper.dispatchAll();

        assertInStaDisabledState();
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

        assertWifiShutDown(() -> {
            // test ecm changed
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });
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

        assertEnteredEcmMode(() -> {
            // test ecm changed
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });
    }

    /**
     * When ECM activates and we are in client mode, disabling ECM should return us to client mode.
     */
    @Test
    public void testEcmDisabledReturnsToClientMode() throws Exception {
        enableWifi();
        assertInStaEnabledState();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        assertWifiShutDown(() -> {
            // test ecm changed
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });

        // test ecm changed
        mActiveModeWarden.emergencyCallbackModeChanged(false);
        mLooper.dispatchAll();

        assertInStaEnabledState();
    }

    /**
     * When Ecm mode is enabled, we should shut down wifi when we get an emergency mode changed
     * update.
     */
    @Test
    public void testEcmOnFromScanMode() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        mActiveModeWarden.scanAlwaysModeChanged();
        mLooper.dispatchAll();

        assertInStaEnabledState();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        assertWifiShutDown(() -> {
            // test ecm changed
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });
    }

    /**
     * When Ecm mode is disabled, we should not shut down scan mode if we get an emergency mode
     * changed update, but we should turn off soft AP
     */
    @Test
    public void testEcmOffInScanMode() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        mActiveModeWarden.scanAlwaysModeChanged();
        mLooper.dispatchAll();

        assertInStaEnabledState();

        // Test with WifiDisableInECBM turned off:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(false);

        assertEnteredEcmMode(() -> {
            // test ecm changed
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });
    }

    /**
     * When ECM is disabled, we should return to scan mode
     */
    @Test
    public void testEcmDisabledReturnsToScanMode() throws Exception {
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        mActiveModeWarden.scanAlwaysModeChanged();
        mLooper.dispatchAll();

        assertInStaEnabledState();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        assertWifiShutDown(() -> {
            // test ecm changed
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });

        // test ecm changed
        mActiveModeWarden.emergencyCallbackModeChanged(false);
        mLooper.dispatchAll();

        assertInStaEnabledState();
    }

    /**
     * When Ecm mode is enabled, we should shut down wifi when we get an emergency mode changed
     * update.
     */
    @Test
    public void testEcmOnFromSoftApMode() throws Exception {
        enterSoftApActiveMode();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        assertEnteredEcmMode(() -> {
            // test ecm changed
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });
    }

    /**
     * When Ecm mode is disabled, we should shut down softap mode if we get an emergency mode
     * changed update
     */
    @Test
    public void testEcmOffInSoftApMode() throws Exception {
        enterSoftApActiveMode();

        // Test with WifiDisableInECBM turned off:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(false);

        // test ecm changed
        mActiveModeWarden.emergencyCallbackModeChanged(true);
        mLooper.dispatchAll();

        verify(mSoftApManager).stop();
    }

    /**
     * When ECM is activated and we were in softap mode, we should just return to wifi off when ECM
     * ends
     */
    @Test
    public void testEcmDisabledRemainsDisabledWhenSoftApHadBeenOn() throws Exception {
        assertInStaDisabledState();

        enterSoftApActiveMode();

        // verify Soft AP Manager started
        verify(mSoftApManager).start();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        assertEnteredEcmMode(() -> {
            // test ecm changed
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });

        // test ecm changed
        mActiveModeWarden.emergencyCallbackModeChanged(false);
        mLooper.dispatchAll();

        assertInStaDisabledState();

        // verify no additional calls to enable softap
        verify(mSoftApManager).start();
    }

    /**
     * Wifi should remain off when already disabled and we enter ECM.
     */
    @Test
    public void testEcmOnFromDisabledMode() throws Exception {
        assertInStaDisabledState();
        verify(mSoftApManager, never()).start();
        verify(mClientModeManager, never()).start();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        assertEnteredEcmMode(() -> {
            // test ecm changed
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });
    }


    /**
     * Updates about call state change also trigger entry of ECM mode.
     */
    @Test
    public void testEnterEcmOnEmergencyCallStateChange() throws Exception {
        assertInStaDisabledState();

        enableWifi();
        assertInStaEnabledState();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        assertEnteredEcmMode(() -> {
            // test call state changed
            mActiveModeWarden.emergencyCallStateChanged(true);
            mLooper.dispatchAll();
        });

        mActiveModeWarden.emergencyCallStateChanged(false);
        mLooper.dispatchAll();

        assertInStaEnabledState();
    }

    /**
     * Verify when both ECM and call state changes arrive, we enter ECM mode
     */
    @Test
    public void testEnterEcmWithBothSignals() throws Exception {
        assertInStaDisabledState();

        enableWifi();
        assertInStaEnabledState();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        assertWifiShutDown(() -> {
            mActiveModeWarden.emergencyCallStateChanged(true);
            mLooper.dispatchAll();
        });

        assertWifiShutDown(() -> {
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        }, 0); // does not cause another shutdown

        // client mode only started once so far
        verify(mClientModeManager).start();

        mActiveModeWarden.emergencyCallStateChanged(false);
        mLooper.dispatchAll();

        // stay in ecm, do not send an additional client mode trigger
        assertInEmergencyMode();
        // assert that the underlying state is still client state
        assertInStaEnabledState();
        // client mode still only started once
        verify(mClientModeManager).start();

        mActiveModeWarden.emergencyCallbackModeChanged(false);
        mLooper.dispatchAll();

        // now we can re-enable wifi
        verify(mClientModeManager, times(2)).start();
        assertInStaEnabledState();
    }

    /**
     * Verify when both ECM and call state changes arrive but out of order, we enter ECM mode
     */
    @Test
    public void testEnterEcmWithBothSignalsOutOfOrder() throws Exception {
        assertInStaDisabledState();

        enableWifi();

        assertInStaEnabledState();
        verify(mClientModeManager).start();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        assertEnteredEcmMode(() -> {
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });
        assertInStaEnabledState();

        assertEnteredEcmMode(() -> {
            mActiveModeWarden.emergencyCallStateChanged(true);
            mLooper.dispatchAll();
        }, 0); // does not enter ECM state again

        mActiveModeWarden.emergencyCallStateChanged(false);
        mLooper.dispatchAll();

        // stay in ecm, do not send an additional client mode trigger
        verify(mClientModeManager).start();
        assertInStaEnabledState();

        mActiveModeWarden.emergencyCallbackModeChanged(false);
        mLooper.dispatchAll();

        // now we can re-enable wifi
        verify(mClientModeManager, times(2)).start();
        assertInStaEnabledState();
    }

    /**
     * Verify when both ECM and call state changes arrive but completely out of order,
     * we still enter and properly exit ECM mode
     */
    @Test
    public void testEnterEcmWithBothSignalsOppositeOrder() throws Exception {
        assertInStaDisabledState();

        enableWifi();

        assertInStaEnabledState();
        verify(mClientModeManager).start();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        assertEnteredEcmMode(() -> {
            mActiveModeWarden.emergencyCallStateChanged(true);
            mLooper.dispatchAll();
        });
        assertInStaEnabledState();

        assertEnteredEcmMode(() -> {
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        }, 0); // still only 1 shutdown

        mActiveModeWarden.emergencyCallbackModeChanged(false);
        mLooper.dispatchAll();

        // stay in ecm, do not send an additional client mode trigger
        verify(mClientModeManager).start();
        assertInStaEnabledState();

        mActiveModeWarden.emergencyCallStateChanged(false);
        mLooper.dispatchAll();

        // now we can re-enable wifi
        verify(mClientModeManager, times(2)).start();
        assertInStaEnabledState();
    }

    /**
     * When ECM is active, we might get addition signals of ECM mode, drop those additional signals,
     * we must exit when one of each signal is received.
     *
     * In any case, duplicate signals indicate a bug from Telephony. Each signal should be turned
     * off before it is turned on again.
     */
    @Test
    public void testProperExitFromEcmModeWithMultipleMessages() throws Exception {
        assertInStaDisabledState();

        enableWifi();

        verify(mClientModeManager).start();
        assertInStaEnabledState();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);

        assertEnteredEcmMode(() -> {
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mActiveModeWarden.emergencyCallStateChanged(true);
            mActiveModeWarden.emergencyCallStateChanged(true);
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });
        assertInStaEnabledState();

        assertEnteredEcmMode(() -> {
            mActiveModeWarden.emergencyCallbackModeChanged(false);
            mLooper.dispatchAll();
            mActiveModeWarden.emergencyCallbackModeChanged(false);
            mLooper.dispatchAll();
            mActiveModeWarden.emergencyCallbackModeChanged(false);
            mLooper.dispatchAll();
            mActiveModeWarden.emergencyCallbackModeChanged(false);
            mLooper.dispatchAll();
        }, 0);

        // didn't enter client mode again
        verify(mClientModeManager).start();
        assertInStaEnabledState();

        // now we will exit ECM
        mActiveModeWarden.emergencyCallStateChanged(false);
        mLooper.dispatchAll();

        // now we can re-enable wifi
        verify(mClientModeManager, times(2)).start();
        assertInStaEnabledState();
    }

    /**
     * Toggling wifi when in ECM does not exit ecm mode and enable wifi
     */
    @Test
    public void testWifiDoesNotToggleOnWhenInEcm() throws Exception {
        assertInStaDisabledState();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);
        // test ecm changed
        assertEnteredEcmMode(() -> {
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });

        // now toggle wifi and verify we do not start wifi
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        mActiveModeWarden.wifiToggled();
        mLooper.dispatchAll();

        verify(mClientModeManager, never()).start();
        assertInStaDisabledState();
    }

    @Test
    public void testAirplaneModeDoesNotToggleOnWhenInEcm() throws Exception {
        // TODO(b/139829963): investigate the expected behavior is when toggling airplane mode in
        //  ECM
    }

    /**
     * Toggling scan mode when in ECM does not exit ecm mode and enable scan mode
     */
    @Test
    public void testScanModeDoesNotToggleOnWhenInEcm() throws Exception {
        assertInStaDisabledState();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);
        assertEnteredEcmMode(() -> {
            // test ecm changed
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });

        // now enable scanning and verify we do not start wifi
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        mActiveModeWarden.scanAlwaysModeChanged();
        mLooper.dispatchAll();

        verify(mClientModeManager, never()).start();
        assertInStaDisabledState();
    }


    /**
     * Toggling softap mode when in ECM does not exit ecm mode and enable softap
     */
    @Test
    public void testSoftApModeDoesNotToggleOnWhenInEcm() throws Exception {
        assertInStaDisabledState();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);
        assertEnteredEcmMode(() -> {
            // test ecm changed
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });

        mActiveModeWarden.startSoftAp(
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null));
        mLooper.dispatchAll();

        verify(mSoftApManager, never()).start();
        assertInStaDisabledState();
    }

    /**
     * Toggling off softap mode when in ECM does not induce a mode change
     */
    @Test
    public void testSoftApStoppedDoesNotSwitchModesWhenInEcm() throws Exception {
        assertInStaDisabledState();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);
        assertEnteredEcmMode(() -> {
            // test ecm changed
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });

        mActiveModeWarden.stopSoftAp(WifiManager.IFACE_IP_MODE_UNSPECIFIED);
        mLooper.dispatchAll();

        assertInStaDisabledState();
        verifyNoMoreInteractions(mSoftApManager, mClientModeManager);
    }

    /**
     * Toggling softap mode when in airplane mode needs to enable softap
     */
    @Test
    public void testSoftApModeToggleWhenInAirplaneMode() throws Exception {
        // Test with airplane mode turned on:
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(true);

        // Turn on SoftAp.
        mActiveModeWarden.startSoftAp(
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null));
        mLooper.dispatchAll();
        verify(mSoftApManager).start();

        // Turn off SoftAp.
        mActiveModeWarden.stopSoftAp(WifiManager.IFACE_IP_MODE_UNSPECIFIED);
        mLooper.dispatchAll();

        verify(mSoftApManager).stop();
    }

    /**
     * Toggling off scan mode when in ECM does not induce a mode change
     */
    @Test
    public void testScanModeStoppedDoesNotSwitchModesWhenInEcm() throws Exception {
        enterScanOnlyModeActiveState();
        assertInStaEnabledState();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);
        assertEnteredEcmMode(() -> {
            // test ecm changed
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });

        mClientListener.onStopped();
        mLooper.dispatchAll();

        assertInStaEnabledState();
    }

    /**
     * Toggling off client mode when in ECM does not induce a mode change
     */
    @Test
    public void testClientModeStoppedDoesNotSwitchModesWhenInEcm() throws Exception {
        enterClientModeActiveState();
        assertInStaEnabledState();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);
        assertEnteredEcmMode(() -> {
            // test ecm changed
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });

        mClientListener.onStopped();
        mLooper.dispatchAll();

        assertInStaEnabledState();
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
        assertInStaEnabledState();
        verify(mClientModeManager).start();

        mActiveModeWarden.startSoftAp(
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null));
        // add an "unexpected" sta mode stop to simulate a single interface device
        mClientListener.onStopped();
        mLooper.dispatchAll();

        mActiveModeWarden.softApStopped();
        mLooper.dispatchAll();

        verify(mClientModeManager, times(2)).start();
        assertInStaEnabledState();
    }

    /**
     * When in STA mode and SoftAP is enabled and the device supports STA+AP (i.e. the STA wasn't
     * shut down when the AP started), both modes will be running concurrently.
     *
     * Then when the AP is disabled, we should remain in STA mode.
     *
     * Enter StaEnabledState, activate AP mode, toggle WiFi off.
     * <p>
     * Expected: AP should successfully start and exit, then return to StaEnabledState.
     */
    @Test
    public void testReturnToStaEnabledStateAfterWifiEnabledShutdown() throws Exception {
        enableWifi();
        assertInStaEnabledState();
        verify(mClientModeManager).start();

        mActiveModeWarden.startSoftAp(
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null));
        mLooper.dispatchAll();

        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        mActiveModeWarden.wifiToggled();
        mActiveModeWarden.softApStopped();
        mLooper.dispatchAll();

        // wasn't called again
        verify(mClientModeManager).start();
        assertInStaEnabledState();
    }

    @Test
    public void testRestartWifiStackInStaEnabledStateTriggersBugReport() throws Exception {
        enableWifi();
        mActiveModeWarden.recoveryRestartWifi(SelfRecovery.REASON_WIFINATIVE_FAILURE);
        mLooper.dispatchAll();
        verify(mClientModeImpl).takeBugReport(anyString(), anyString());
    }

    @Test
    public void testRestartWifiWatchdogDoesNotTriggerBugReport() throws Exception {
        enableWifi();
        mActiveModeWarden.recoveryRestartWifi(SelfRecovery.REASON_LAST_RESORT_WATCHDOG);
        mLooper.dispatchAll();
        verify(mClientModeImpl, never()).takeBugReport(anyString(), anyString());
    }

    /**
     * When in sta mode, CMD_RECOVERY_DISABLE_WIFI messages should trigger wifi to disable.
     */
    @Test
    public void testRecoveryDisabledTurnsWifiOff() throws Exception {
        enableWifi();
        assertInStaEnabledState();
        mActiveModeWarden.recoveryDisableWifi();
        mLooper.dispatchAll();
        verify(mClientModeManager).stop();
        assertInStaDisabledState();
    }

    /**
     * When wifi is disabled, CMD_RECOVERY_DISABLE_WIFI should not trigger a state change.
     */
    @Test
    public void testRecoveryDisabledWhenWifiAlreadyOff() throws Exception {
        assertInStaDisabledState();
        assertWifiShutDown(() -> {
            mActiveModeWarden.recoveryDisableWifi();
            mLooper.dispatchAll();
        });
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
        assertInStaDisabledState();

        mActiveModeWarden.recoveryRestartWifi(SelfRecovery.REASON_WIFINATIVE_FAILURE);
        mLooper.dispatchAll();

        assertInStaDisabledState();
        verifyNoMoreInteractions(mClientModeManager, mSoftApManager);
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
        assertInStaDisabledState();

        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        mActiveModeWarden.scanAlwaysModeChanged();
        mLooper.dispatchAll();

        assertInStaEnabledState();
        verify(mClientModeManager).start();
        verify(mClientModeManager).switchToScanOnlyMode();

        mActiveModeWarden.recoveryRestartWifi(SelfRecovery.REASON_WIFINATIVE_FAILURE);
        mLooper.dispatchAll();

        verify(mClientModeManager).stop();
        assertInStaDisabledState();

        mLooper.moveTimeForward(TEST_WIFI_RECOVERY_DELAY_MS);
        mLooper.dispatchAll();

        verify(mClientModeManager, times(2)).start();
        verify(mClientModeManager, times(2)).switchToScanOnlyMode();
        assertInStaEnabledState();
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
        assertInStaEnabledState();
        verify(mClientModeManager).start();

        assertWifiShutDown(() -> {
            mActiveModeWarden.recoveryRestartWifi(SelfRecovery.REASON_WIFINATIVE_FAILURE);
            mLooper.dispatchAll();
        });

        // still only started once
        verify(mClientModeManager).start();

        mLooper.moveTimeForward(TEST_WIFI_RECOVERY_DELAY_MS);
        mLooper.dispatchAll();

        // started again
        verify(mClientModeManager, times(2)).start();
        assertInStaEnabledState();
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
        assertInStaEnabledState();
        verify(mClientModeManager).start();
        verify(mClientModeManager).switchToConnectMode();

        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);
        assertEnteredEcmMode(() -> {
            mActiveModeWarden.emergencyCallStateChanged(true);
            mLooper.dispatchAll();
        });
        assertInStaEnabledState();
        verify(mClientModeManager).stop();

        mActiveModeWarden.recoveryRestartWifi(SelfRecovery.REASON_LAST_RESORT_WATCHDOG);
        mLooper.dispatchAll();

        verify(mClientModeManager).start(); // wasn't called again
        assertInEmergencyMode();
        assertInStaEnabledState();
        verifyNoMoreInteractions(mClientModeManager, mSoftApManager);
    }

    /**
     * The command to trigger a WiFi reset should trigger a reset when in AP mode.
     * Enter AP mode, send command to restart wifi.
     * <p>
     * Expected: The command to trigger a wifi reset should trigger wifi shutdown.
     */
    @Test
    public void testRestartWifiStackFullyStopsWifi() throws Exception {
        mActiveModeWarden.startSoftAp(new SoftApModeConfiguration(
                WifiManager.IFACE_IP_MODE_LOCAL_ONLY, null));
        mLooper.dispatchAll();
        verify(mSoftApManager).start();

        assertWifiShutDown(() -> {
            mActiveModeWarden.recoveryRestartWifi(SelfRecovery.REASON_STA_IFACE_DOWN);
            mLooper.dispatchAll();
        });
    }

    /**
     * Tests that when Wifi is already disabled and another Wifi toggle command arrives, but we're
     * in airplane mode, don't enter scan mode.
     */
    @Test
    public void staDisabled_toggleWifiOff_scanAvailable_airplaneModeOn_dontGoToScanMode() {
        assertInStaDisabledState();

        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(true);

        mActiveModeWarden.wifiToggled();
        mLooper.dispatchAll();

        assertInStaDisabledState();
        verify(mClientModeManager, never()).start();
    }

    /**
     * Tests that when Wifi is already disabled and another Wifi toggle command arrives, but we're
     * not in airplane mode, enter scan mode.
     */
    @Test
    public void staDisabled_toggleWifiOff_scanAvailable_airplaneModeOff_goToScanMode() {
        assertInStaDisabledState();

        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);

        mActiveModeWarden.wifiToggled();
        mLooper.dispatchAll();

        assertInStaEnabledState();
        verify(mClientModeManager).start();
        verify(mClientModeManager).switchToScanOnlyMode();
    }

    /**
     * Tests that if the carrier config to disable Wifi is enabled during ECM, Wifi is shut down
     * when entering ECM and turned back on when exiting ECM.
     */
    @Test
    public void ecmDisablesWifi_exitEcm_restartWifi() throws Exception {
        enterClientModeActiveState();

        verify(mClientModeManager).start();

        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);
        assertEnteredEcmMode(() -> {
            mActiveModeWarden.emergencyCallbackModeChanged(true);
            mLooper.dispatchAll();
        });
        assertInStaEnabledState();
        verify(mClientModeManager).stop();

        mActiveModeWarden.emergencyCallbackModeChanged(false);
        mLooper.dispatchAll();

        assertThat(mActiveModeWarden.isInEmergencyMode()).isFalse();
        // client mode restarted
        verify(mClientModeManager, times(2)).start();
        assertInStaEnabledState();
    }
}
