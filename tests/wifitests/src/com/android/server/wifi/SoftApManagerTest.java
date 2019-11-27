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

import static android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK;
import static android.net.wifi.WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_FAILURE_REASON;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_INTERFACE_NAME;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_MODE;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_STATE;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_LOCAL_ONLY;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;

import static com.android.server.wifi.LocalOnlyHotspotRequestInfo.HOTSPOT_NO_ERROR;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.test.TestAlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.MacAddress;
import android.net.Uri;
import android.net.wifi.IApInterfaceEventCallback;
import android.net.wifi.SoftApInfo;
import android.net.wifi.WifiClient;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.provider.Settings;

import androidx.test.filters.SmallTest;

import com.android.internal.util.WakeupMessage;
import com.android.server.wifi.wificond.NativeWifiClient;
import com.android.wifi.resources.R;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Unit tests for {@link SoftApManager}. */
@SmallTest
public class SoftApManagerTest extends WifiBaseTest {

    private static final String TAG = "SoftApManagerTest";

    private static final String DEFAULT_SSID = "DefaultTestSSID";
    private static final String TEST_SSID = "TestSSID";
    private static final String TEST_PASSWORD = "TestPassword";
    private static final String TEST_COUNTRY_CODE = "TestCountry";
    private static final String TEST_INTERFACE_NAME = "testif0";
    private static final String OTHER_INTERFACE_NAME = "otherif";
    private static final int TEST_NUM_CONNECTED_CLIENTS = 4;
    private static final MacAddress TEST_MAC_ADDRESS = MacAddress.fromString("22:33:44:55:66:77");
    private static final WifiClient TEST_CONNECTED_CLIENT = new WifiClient(TEST_MAC_ADDRESS);
    private static final List<WifiClient> TEST_CONNECTED_CLIENTS =
            new ArrayList(Arrays.asList(TEST_CONNECTED_CLIENT));
    private static final NativeWifiClient TEST_NATIVE_CLIENT = new NativeWifiClient() {{
            macAddress = TEST_MAC_ADDRESS.toByteArray();
        }
    };
    private static final List<NativeWifiClient> TEST_CONNECTED_NATIVECLIENTS =
            new ArrayList(Arrays.asList(TEST_NATIVE_CLIENT));
    private static final int TEST_AP_FREQUENCY = 2412;
    private static final int TEST_AP_BANDWIDTH_FROM_IFACE_CALLBACK =
            IApInterfaceEventCallback.BANDWIDTH_20;
    private static final int TEST_AP_BANDWIDTH_IN_SOFTAPINFO = SoftApInfo.CHANNEL_WIDTH_20MHZ;

    private final WifiConfiguration mDefaultApConfig = createDefaultApConfig();

    private ContentObserver mContentObserver;
    private TestLooper mLooper;
    private TestAlarmManager mAlarmManager;
    private SoftApInfo mTestSoftApInfo;

    @Mock Context mContext;
    @Mock Resources mResources;
    @Mock WifiNative mWifiNative;
    @Mock WifiManager.SoftApCallback mCallback;
    @Mock ActiveModeManager.Listener mListener;
    @Mock FrameworkFacade mFrameworkFacade;
    @Mock WifiApConfigStore mWifiApConfigStore;
    @Mock WifiMetrics mWifiMetrics;
    @Mock SarManager mSarManager;
    @Mock BaseWifiDiagnostics mWifiDiagnostics;
    final ArgumentCaptor<WifiNative.InterfaceCallback> mWifiNativeInterfaceCallbackCaptor =
            ArgumentCaptor.forClass(WifiNative.InterfaceCallback.class);
    final ArgumentCaptor<WifiNative.SoftApListener> mSoftApListenerCaptor =
            ArgumentCaptor.forClass(WifiNative.SoftApListener.class);

    SoftApManager mSoftApManager;

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();

        when(mWifiNative.startSoftAp(eq(TEST_INTERFACE_NAME), any(), any())).thenReturn(true);

        when(mFrameworkFacade.getIntegerSetting(
                mContext, Settings.Global.SOFT_AP_TIMEOUT_ENABLED, 1)).thenReturn(1);
        mAlarmManager = new TestAlarmManager();
        when(mContext.getSystemService(Context.ALARM_SERVICE))
                .thenReturn(mAlarmManager.getAlarmManager());
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getInteger(R.integer.config_wifi_framework_soft_ap_timeout_delay))
                .thenReturn(600000);
        when(mWifiNative.setCountryCodeHal(
                TEST_INTERFACE_NAME, TEST_COUNTRY_CODE.toUpperCase(Locale.ROOT)))
                .thenReturn(true);
        when(mWifiNative.getFactoryMacAddress(any())).thenReturn(TEST_MAC_ADDRESS);
        when(mWifiApConfigStore.randomizeBssidIfUnset(any(), any())).thenAnswer(
                (invocation) -> invocation.getArgument(1));
        mTestSoftApInfo = new SoftApInfo();
        mTestSoftApInfo.setFrequency(TEST_AP_FREQUENCY);
        mTestSoftApInfo.setBandwidth(TEST_AP_BANDWIDTH_IN_SOFTAPINFO);
    }

    private WifiConfiguration createDefaultApConfig() {
        WifiConfiguration defaultConfig = new WifiConfiguration();
        defaultConfig.SSID = DEFAULT_SSID;
        return defaultConfig;
    }

    private SoftApManager createSoftApManager(SoftApModeConfiguration config, String countryCode) {
        if (config.getWifiConfiguration() == null) {
            when(mWifiApConfigStore.getApConfiguration()).thenReturn(mDefaultApConfig);
        }
        SoftApManager newSoftApManager = new SoftApManager(mContext,
                                                           mLooper.getLooper(),
                                                           mFrameworkFacade,
                                                           mWifiNative,
                                                           countryCode,
                                                           mListener,
                                                           mCallback,
                                                           mWifiApConfigStore,
                                                           config,
                                                           mWifiMetrics,
                                                           mSarManager,
                                                           mWifiDiagnostics);
        mLooper.dispatchAll();

        return newSoftApManager;
    }

    /** Verifies startSoftAp will use default config if AP configuration is not provided. */
    @Test
    public void startSoftApWithoutConfig() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);
    }

    /** Verifies startSoftAp will use provided config and start AP. */
    @Test
    public void startSoftApWithConfig() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = WifiConfiguration.AP_BAND_2GHZ;
        config.SSID = TEST_SSID;
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);
        startSoftApAndVerifyEnabled(apConfig);
    }

    /**
     * Verifies startSoftAp will start with the hiddenSSID param set when it is set to true in the
     * supplied config.
     */
    @Test
    public void startSoftApWithHiddenSsidTrueInConfig() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = WifiConfiguration.AP_BAND_2GHZ;
        config.SSID = TEST_SSID;
        config.hiddenSSID = true;
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);
        startSoftApAndVerifyEnabled(apConfig);
    }

    /**
     * Verifies startSoftAp will start with the password param set in the
     * supplied config.
     */
    @Test
    public void startSoftApWithPassphraseInConfig() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = WifiConfiguration.AP_BAND_5GHZ;
        config.SSID = TEST_SSID;
        config.allowedKeyManagement.set(WPA_PSK);
        config.preSharedKey = TEST_PASSWORD;
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);
        startSoftApAndVerifyEnabled(apConfig);
    }

    /** Tests softap startup if default config fails to load. **/
    @Test
    public void startSoftApDefaultConfigFailedToLoad() throws Exception {
        when(mWifiNative.setupInterfaceForSoftApMode(any())).thenReturn(TEST_INTERFACE_NAME);

        when(mWifiApConfigStore.getApConfiguration()).thenReturn(null);
        SoftApModeConfiguration nullApConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        SoftApManager newSoftApManager = new SoftApManager(mContext,
                                                           mLooper.getLooper(),
                                                           mFrameworkFacade,
                                                           mWifiNative,
                                                           TEST_COUNTRY_CODE,
                                                           mListener,
                                                           mCallback,
                                                           mWifiApConfigStore,
                                                           nullApConfig,
                                                           mWifiMetrics,
                                                           mSarManager,
                                                           mWifiDiagnostics);
        mLooper.dispatchAll();
        newSoftApManager.start();
        mLooper.dispatchAll();
        verify(mCallback).onStateChanged(WifiManager.WIFI_AP_STATE_FAILED,
                WifiManager.SAP_START_FAILURE_GENERAL);
        verify(mListener).onStartFailure();
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(2)).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        List<Intent> capturedIntents = intentCaptor.getAllValues();
        checkApStateChangedBroadcast(capturedIntents.get(0), WIFI_AP_STATE_ENABLING,
                WIFI_AP_STATE_DISABLED, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                nullApConfig.getTargetMode());
        checkApStateChangedBroadcast(capturedIntents.get(1), WIFI_AP_STATE_FAILED,
                WIFI_AP_STATE_ENABLING, WifiManager.SAP_START_FAILURE_GENERAL, TEST_INTERFACE_NAME,
                nullApConfig.getTargetMode());
    }

    /**
     * Test that failure to retrieve the SoftApInterface name increments the corresponding metrics
     * and proper state updates are sent out.
     */
    @Test
    public void testSetupForSoftApModeNullApInterfaceNameFailureIncrementsMetrics()
            throws Exception {
        when(mWifiNative.setupInterfaceForSoftApMode(any())).thenReturn(null);

        SoftApModeConfiguration config = new SoftApModeConfiguration(
                WifiManager.IFACE_IP_MODE_TETHERED, new WifiConfiguration());

        when(mWifiApConfigStore.getApConfiguration()).thenReturn(null);
        SoftApModeConfiguration nullApConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        SoftApManager newSoftApManager = new SoftApManager(mContext,
                                                           mLooper.getLooper(),
                                                           mFrameworkFacade,
                                                           mWifiNative,
                                                           TEST_COUNTRY_CODE,
                                                           mListener,
                                                           mCallback,
                                                           mWifiApConfigStore,
                                                           nullApConfig,
                                                           mWifiMetrics,
                                                           mSarManager,
                                                           mWifiDiagnostics);
        mLooper.dispatchAll();
        newSoftApManager.start();
        mLooper.dispatchAll();
        verify(mCallback).onStateChanged(WifiManager.WIFI_AP_STATE_FAILED,
                WifiManager.SAP_START_FAILURE_GENERAL);
        verify(mListener).onStartFailure();
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        checkApStateChangedBroadcast(intentCaptor.getValue(), WIFI_AP_STATE_FAILED,
                WIFI_AP_STATE_DISABLED, WifiManager.SAP_START_FAILURE_GENERAL, null,
                nullApConfig.getTargetMode());

        verify(mWifiMetrics).incrementSoftApStartResult(false,
                WifiManager.SAP_START_FAILURE_GENERAL);
    }

    /**
     * Test that an empty SoftApInterface name is detected as a failure and increments the
     * corresponding metrics and proper state updates are sent out.
     */
    @Test
    public void testSetupForSoftApModeEmptyInterfaceNameFailureIncrementsMetrics()
            throws Exception {
        when(mWifiNative.setupInterfaceForSoftApMode(any())).thenReturn("");

        SoftApModeConfiguration config = new SoftApModeConfiguration(
                WifiManager.IFACE_IP_MODE_TETHERED, new WifiConfiguration());

        when(mWifiApConfigStore.getApConfiguration()).thenReturn(null);
        SoftApModeConfiguration nullApConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        SoftApManager newSoftApManager = new SoftApManager(mContext,
                                                           mLooper.getLooper(),
                                                           mFrameworkFacade,
                                                           mWifiNative,
                                                           TEST_COUNTRY_CODE,
                                                           mListener,
                                                           mCallback,
                                                           mWifiApConfigStore,
                                                           nullApConfig,
                                                           mWifiMetrics,
                                                           mSarManager,
                                                           mWifiDiagnostics);
        mLooper.dispatchAll();
        newSoftApManager.start();
        mLooper.dispatchAll();
        verify(mCallback).onStateChanged(WifiManager.WIFI_AP_STATE_FAILED,
                WifiManager.SAP_START_FAILURE_GENERAL);
        verify(mListener).onStartFailure();
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        checkApStateChangedBroadcast(intentCaptor.getValue(), WIFI_AP_STATE_FAILED,
                WIFI_AP_STATE_DISABLED, WifiManager.SAP_START_FAILURE_GENERAL, "",
                nullApConfig.getTargetMode());

        verify(mWifiMetrics).incrementSoftApStartResult(false,
                WifiManager.SAP_START_FAILURE_GENERAL);
    }

    /**
     * Tests that the generic error is propagated and properly reported when starting softap and no
     * country code is provided.
     */
    @Test
    public void startSoftApOn5GhzFailGeneralErrorForNoCountryCode() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = WifiConfiguration.AP_BAND_5GHZ;
        config.SSID = TEST_SSID;
        SoftApModeConfiguration softApConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);

        when(mWifiNative.setupInterfaceForSoftApMode(any())).thenReturn(TEST_INTERFACE_NAME);

        SoftApManager newSoftApManager = new SoftApManager(mContext,
                mLooper.getLooper(),
                mFrameworkFacade,
                mWifiNative,
                null,
                mListener,
                mCallback,
                mWifiApConfigStore,
                softApConfig,
                mWifiMetrics,
                mSarManager,
                mWifiDiagnostics);
        mLooper.dispatchAll();
        newSoftApManager.start();
        mLooper.dispatchAll();

        verify(mWifiNative, never()).setCountryCodeHal(eq(TEST_INTERFACE_NAME), any());

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(2)).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        List<Intent> capturedIntents = intentCaptor.getAllValues();
        checkApStateChangedBroadcast(capturedIntents.get(0), WIFI_AP_STATE_ENABLING,
                WIFI_AP_STATE_DISABLED, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApConfig.getTargetMode());
        checkApStateChangedBroadcast(capturedIntents.get(1), WIFI_AP_STATE_FAILED,
                WIFI_AP_STATE_ENABLING, WifiManager.SAP_START_FAILURE_GENERAL, TEST_INTERFACE_NAME,
                softApConfig.getTargetMode());
    }

    /**
     * Tests that the generic error is propagated and properly reported when starting softap and the
     * country code cannot be set.
     */
    @Test
    public void startSoftApOn5GhzFailGeneralErrorForCountryCodeSetFailure() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = WifiConfiguration.AP_BAND_5GHZ;
        config.SSID = TEST_SSID;
        SoftApModeConfiguration softApConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);

        when(mWifiNative.setupInterfaceForSoftApMode(any())).thenReturn(TEST_INTERFACE_NAME);
        when(mWifiNative.setCountryCodeHal(
                TEST_INTERFACE_NAME, TEST_COUNTRY_CODE.toUpperCase(Locale.ROOT)))
                .thenReturn(false);

        SoftApManager newSoftApManager = new SoftApManager(mContext,
                                                           mLooper.getLooper(),
                                                           mFrameworkFacade,
                                                           mWifiNative,
                                                           TEST_COUNTRY_CODE,
                                                           mListener,
                                                           mCallback,
                                                           mWifiApConfigStore,
                                                           softApConfig,
                                                           mWifiMetrics,
                                                           mSarManager,
                                                           mWifiDiagnostics);
        mLooper.dispatchAll();
        newSoftApManager.start();
        mLooper.dispatchAll();

        verify(mWifiNative).setCountryCodeHal(
                TEST_INTERFACE_NAME, TEST_COUNTRY_CODE.toUpperCase(Locale.ROOT));

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(2)).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        List<Intent> capturedIntents = intentCaptor.getAllValues();
        checkApStateChangedBroadcast(capturedIntents.get(0), WIFI_AP_STATE_ENABLING,
                WIFI_AP_STATE_DISABLED, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApConfig.getTargetMode());
        checkApStateChangedBroadcast(capturedIntents.get(1), WIFI_AP_STATE_FAILED,
                WIFI_AP_STATE_ENABLING, WifiManager.SAP_START_FAILURE_GENERAL, TEST_INTERFACE_NAME,
                softApConfig.getTargetMode());
    }

    /**
     * Tests that there is no failure in starting softap in 2Ghz band when no country code is
     * provided.
     */
    @Test
    public void startSoftApOn24GhzNoFailForNoCountryCode() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = WifiConfiguration.AP_BAND_2GHZ;
        config.SSID = TEST_SSID;
        SoftApModeConfiguration softApConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);

        startSoftApAndVerifyEnabled(softApConfig, null);
        verify(mWifiNative, never()).setCountryCodeHal(eq(TEST_INTERFACE_NAME), any());
    }

    /**
     * Tests that there is no failure in starting softap in ANY band when no country code is
     * provided.
     */
    @Test
    public void startSoftApOnAnyGhzNoFailForNoCountryCode() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = WifiConfiguration.AP_BAND_ANY;
        config.SSID = TEST_SSID;
        SoftApModeConfiguration softApConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);

        startSoftApAndVerifyEnabled(softApConfig, null);
        verify(mWifiNative, never()).setCountryCodeHal(eq(TEST_INTERFACE_NAME), any());
    }

    /**
     * Tests that there is no failure in starting softap in 2Ghz band when country code cannot be
     * set.
     */
    @Test
    public void startSoftApOn2GhzNoFailForCountryCodeSetFailure() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = WifiConfiguration.AP_BAND_2GHZ;
        config.SSID = TEST_SSID;
        SoftApModeConfiguration softApConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);

        when(mWifiNative.setCountryCodeHal(eq(TEST_INTERFACE_NAME), any())).thenReturn(false);

        startSoftApAndVerifyEnabled(softApConfig, TEST_COUNTRY_CODE);
        verify(mWifiNative).setCountryCodeHal(
                TEST_INTERFACE_NAME, TEST_COUNTRY_CODE.toUpperCase(Locale.ROOT));
    }

    /**
     * Tests that there is no failure in starting softap in ANY band when country code cannot be
     * set.
     */
    @Test
    public void startSoftApOnAnyNoFailForCountryCodeSetFailure() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = WifiConfiguration.AP_BAND_ANY;
        config.SSID = TEST_SSID;
        SoftApModeConfiguration softApConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);

        when(mWifiNative.setCountryCodeHal(eq(TEST_INTERFACE_NAME), any())).thenReturn(false);

        startSoftApAndVerifyEnabled(softApConfig, TEST_COUNTRY_CODE);
        verify(mWifiNative).setCountryCodeHal(
                TEST_INTERFACE_NAME, TEST_COUNTRY_CODE.toUpperCase(Locale.ROOT));
    }

    /**
     * Tests that the NO_CHANNEL error is propagated and properly reported when starting softap and
     * a valid channel cannot be determined.
     */
    @Test
    public void startSoftApFailNoChannel() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = -2;
        config.apChannel = 0;
        config.SSID = TEST_SSID;
        SoftApModeConfiguration softApConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);

        when(mWifiNative.setupInterfaceForSoftApMode(any())).thenReturn(TEST_INTERFACE_NAME);
        when(mWifiNative.isHalStarted()).thenReturn(true);

        SoftApManager newSoftApManager = new SoftApManager(mContext,
                                                           mLooper.getLooper(),
                                                           mFrameworkFacade,
                                                           mWifiNative,
                                                           TEST_COUNTRY_CODE,
                                                           mListener,
                                                           mCallback,
                                                           mWifiApConfigStore,
                                                           softApConfig,
                                                           mWifiMetrics,
                                                           mSarManager,
                                                           mWifiDiagnostics);
        mLooper.dispatchAll();
        newSoftApManager.start();
        mLooper.dispatchAll();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(2)).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        List<Intent> capturedIntents = intentCaptor.getAllValues();
        checkApStateChangedBroadcast(capturedIntents.get(0), WIFI_AP_STATE_ENABLING,
                WIFI_AP_STATE_DISABLED, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApConfig.getTargetMode());
        checkApStateChangedBroadcast(capturedIntents.get(1), WIFI_AP_STATE_FAILED,
                WIFI_AP_STATE_ENABLING, WifiManager.SAP_START_FAILURE_NO_CHANNEL,
                TEST_INTERFACE_NAME, softApConfig.getTargetMode());
    }

    /**
     * Tests startup when Ap Interface fails to start successfully.
     */
    @Test
    public void startSoftApApInterfaceFailedToStart() throws Exception {
        when(mWifiNative.setupInterfaceForSoftApMode(any())).thenReturn(TEST_INTERFACE_NAME);
        when(mWifiNative.startSoftAp(eq(TEST_INTERFACE_NAME), any(), any())).thenReturn(false);

        SoftApModeConfiguration softApModeConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, mDefaultApConfig);

        SoftApManager newSoftApManager = new SoftApManager(mContext,
                                                           mLooper.getLooper(),
                                                           mFrameworkFacade,
                                                           mWifiNative,
                                                           TEST_COUNTRY_CODE,
                                                           mListener,
                                                           mCallback,
                                                           mWifiApConfigStore,
                                                           softApModeConfig,
                                                           mWifiMetrics,
                                                           mSarManager,
                                                           mWifiDiagnostics);

        mLooper.dispatchAll();
        newSoftApManager.start();
        mLooper.dispatchAll();
        verify(mCallback).onStateChanged(WifiManager.WIFI_AP_STATE_FAILED,
                WifiManager.SAP_START_FAILURE_GENERAL);
        verify(mListener).onStartFailure();
        verify(mWifiNative).teardownInterface(TEST_INTERFACE_NAME);
    }

    /**
     * Tests the handling of stop command when soft AP is not started.
     */
    @Test
    public void stopWhenNotStarted() throws Exception {
        mSoftApManager = createSoftApManager(
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null),
                TEST_COUNTRY_CODE);
        mSoftApManager.stop();
        mLooper.dispatchAll();
        /* Verify no state changes. */
        verify(mCallback, never()).onStateChanged(anyInt(), anyInt());
        verifyNoMoreInteractions(mListener);
        verify(mSarManager, never()).setSapWifiState(anyInt());
        verify(mContext, never()).sendStickyBroadcastAsUser(any(), any());
        verify(mWifiNative, never()).teardownInterface(anyString());
    }

    /**
     * Tests the handling of stop command when soft AP is started.
     */
    @Test
    public void stopWhenStarted() throws Exception {
        SoftApModeConfiguration softApModeConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(softApModeConfig);

        // reset to clear verified Intents for ap state change updates
        reset(mContext);

        InOrder order = inOrder(mCallback, mListener, mContext);

        mSoftApManager.stop();
        mLooper.dispatchAll();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        order.verify(mCallback).onStateChanged(WifiManager.WIFI_AP_STATE_DISABLING, 0);
        order.verify(mContext).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));
        checkApStateChangedBroadcast(intentCaptor.getValue(), WIFI_AP_STATE_DISABLING,
                WIFI_AP_STATE_ENABLED, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApModeConfig.getTargetMode());

        order.verify(mCallback).onStateChanged(WifiManager.WIFI_AP_STATE_DISABLED, 0);
        verify(mSarManager).setSapWifiState(WifiManager.WIFI_AP_STATE_DISABLED);
        verify(mWifiDiagnostics).stopLogging(TEST_INTERFACE_NAME);
        order.verify(mContext).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));
        checkApStateChangedBroadcast(intentCaptor.getValue(), WIFI_AP_STATE_DISABLED,
                WIFI_AP_STATE_DISABLING, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApModeConfig.getTargetMode());
    }

    /**
     * Verify that onDestroyed properly reports softap stop.
     */
    @Test
    public void cleanStopOnInterfaceDestroyed() throws Exception {
        SoftApModeConfiguration softApModeConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(softApModeConfig);

        // reset to clear verified Intents for ap state change updates
        reset(mContext);

        InOrder order = inOrder(mCallback, mListener, mContext);

        mWifiNativeInterfaceCallbackCaptor.getValue().onDestroyed(TEST_INTERFACE_NAME);

        mLooper.dispatchAll();
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        order.verify(mCallback).onStateChanged(WifiManager.WIFI_AP_STATE_DISABLING, 0);
        order.verify(mContext).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));
        checkApStateChangedBroadcast(intentCaptor.getValue(), WIFI_AP_STATE_DISABLING,
                WIFI_AP_STATE_ENABLED, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApModeConfig.getTargetMode());

        order.verify(mCallback).onStateChanged(WifiManager.WIFI_AP_STATE_DISABLED, 0);
        order.verify(mContext).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));
        checkApStateChangedBroadcast(intentCaptor.getValue(), WIFI_AP_STATE_DISABLED,
                WIFI_AP_STATE_DISABLING, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApModeConfig.getTargetMode());
        order.verify(mListener).onStopped();
    }

    /**
     * Verify that onDestroyed after softap is stopped doesn't trigger a callback.
     */
    @Test
    public void noCallbackOnInterfaceDestroyedWhenAlreadyStopped() throws Exception {
        SoftApModeConfiguration softApModeConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(softApModeConfig);

        mSoftApManager.stop();
        mLooper.dispatchAll();
        verify(mListener).onStopped();

        verify(mCallback).onStateChanged(WifiManager.WIFI_AP_STATE_DISABLING, 0);
        verify(mCallback).onStateChanged(WifiManager.WIFI_AP_STATE_DISABLED, 0);

        reset(mCallback);

        // now trigger interface destroyed and make sure callback doesn't get called
        mWifiNativeInterfaceCallbackCaptor.getValue().onDestroyed(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();

        verifyNoMoreInteractions(mCallback, mListener);
    }

    /**
     * Verify that onDown is handled by SoftApManager.
     */
    @Test
    public void testInterfaceOnDownHandled() throws Exception {
        SoftApModeConfiguration softApModeConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(softApModeConfig);

        // reset to clear verified Intents for ap state change updates
        reset(mContext, mCallback, mWifiNative);

        InOrder order = inOrder(mCallback, mListener, mContext);

        mWifiNativeInterfaceCallbackCaptor.getValue().onDown(TEST_INTERFACE_NAME);

        mLooper.dispatchAll();

        order.verify(mCallback).onStateChanged(WifiManager.WIFI_AP_STATE_FAILED,
                WifiManager.SAP_START_FAILURE_GENERAL);
        order.verify(mListener).onStopped();
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(3)).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        List<Intent> capturedIntents = intentCaptor.getAllValues();
        checkApStateChangedBroadcast(capturedIntents.get(0), WIFI_AP_STATE_FAILED,
                WIFI_AP_STATE_ENABLED, WifiManager.SAP_START_FAILURE_GENERAL, TEST_INTERFACE_NAME,
                softApModeConfig.getTargetMode());
        checkApStateChangedBroadcast(capturedIntents.get(1), WIFI_AP_STATE_DISABLING,
                WIFI_AP_STATE_FAILED, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApModeConfig.getTargetMode());
        checkApStateChangedBroadcast(capturedIntents.get(2), WIFI_AP_STATE_DISABLED,
                WIFI_AP_STATE_DISABLING, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApModeConfig.getTargetMode());
    }

    /**
     * Verify that onDown for a different interface name does not stop SoftApManager.
     */
    @Test
    public void testInterfaceOnDownForDifferentInterfaceDoesNotTriggerStop() throws Exception {
        SoftApModeConfiguration softApModeConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(softApModeConfig);

        // reset to clear verified Intents for ap state change updates
        reset(mContext, mCallback, mWifiNative);

        InOrder order = inOrder(mCallback, mContext);

        mWifiNativeInterfaceCallbackCaptor.getValue().onDown(OTHER_INTERFACE_NAME);

        mLooper.dispatchAll();

        verifyNoMoreInteractions(mContext, mCallback, mListener, mWifiNative);
    }

    /**
     * Verify that onFailure from hostapd is handled by SoftApManager.
     */
    @Test
    public void testHostapdOnFailureHandled() throws Exception {
        SoftApModeConfiguration softApModeConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(softApModeConfig);

        // reset to clear verified Intents for ap state change updates
        reset(mContext, mCallback, mWifiNative);

        InOrder order = inOrder(mCallback, mListener, mContext);

        mSoftApListenerCaptor.getValue().onFailure();
        mLooper.dispatchAll();

        order.verify(mCallback).onStateChanged(WifiManager.WIFI_AP_STATE_FAILED,
                WifiManager.SAP_START_FAILURE_GENERAL);
        order.verify(mListener).onStopped();
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(3)).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        List<Intent> capturedIntents = intentCaptor.getAllValues();
        checkApStateChangedBroadcast(capturedIntents.get(0), WIFI_AP_STATE_FAILED,
                WIFI_AP_STATE_ENABLED, WifiManager.SAP_START_FAILURE_GENERAL, TEST_INTERFACE_NAME,
                softApModeConfig.getTargetMode());
        checkApStateChangedBroadcast(capturedIntents.get(1), WIFI_AP_STATE_DISABLING,
                WIFI_AP_STATE_FAILED, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApModeConfig.getTargetMode());
        checkApStateChangedBroadcast(capturedIntents.get(2), WIFI_AP_STATE_DISABLED,
                WIFI_AP_STATE_DISABLING, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApModeConfig.getTargetMode());
    }

    @Test
    public void updatesMetricsOnChannelSwitchedEvent() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        final int channelFrequency = 2437;
        final int channelBandwidth = IApInterfaceEventCallback.BANDWIDTH_20;
        mSoftApListenerCaptor.getValue().onSoftApChannelSwitched(channelFrequency,
                channelBandwidth);
        mLooper.dispatchAll();

        verify(mWifiMetrics).addSoftApChannelSwitchedEvent(channelFrequency, channelBandwidth,
                apConfig.getTargetMode());
    }

    @Test
    public void updatesMetricsOnChannelSwitchedEventDetectsBandUnsatisfiedOnBand2Ghz()
            throws Exception {
        WifiConfiguration config = createDefaultApConfig();
        config.apBand = WifiConfiguration.AP_BAND_2GHZ;

        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);
        startSoftApAndVerifyEnabled(apConfig);

        final int channelFrequency = 5180;
        final int channelBandwidth = IApInterfaceEventCallback.BANDWIDTH_20;
        mSoftApListenerCaptor.getValue().onSoftApChannelSwitched(channelFrequency,
                channelBandwidth);
        mLooper.dispatchAll();

        verify(mWifiMetrics).addSoftApChannelSwitchedEvent(channelFrequency, channelBandwidth,
                apConfig.getTargetMode());
        verify(mWifiMetrics).incrementNumSoftApUserBandPreferenceUnsatisfied();
    }

    @Test
    public void updatesMetricsOnChannelSwitchedEventDetectsBandUnsatisfiedOnBand5Ghz()
            throws Exception {
        WifiConfiguration config = createDefaultApConfig();
        config.apBand = WifiConfiguration.AP_BAND_5GHZ;

        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);
        startSoftApAndVerifyEnabled(apConfig);

        final int channelFrequency = 2437;
        final int channelBandwidth = IApInterfaceEventCallback.BANDWIDTH_20;
        mSoftApListenerCaptor.getValue().onSoftApChannelSwitched(channelFrequency,
                channelBandwidth);
        mLooper.dispatchAll();

        verify(mWifiMetrics).addSoftApChannelSwitchedEvent(channelFrequency, channelBandwidth,
                apConfig.getTargetMode());
        verify(mWifiMetrics).incrementNumSoftApUserBandPreferenceUnsatisfied();
    }

    @Test
    public void updatesMetricsOnChannelSwitchedEventDoesNotDetectBandUnsatisfiedOnBandAny()
            throws Exception {
        WifiConfiguration config = createDefaultApConfig();
        config.apBand = WifiConfiguration.AP_BAND_ANY;

        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);
        startSoftApAndVerifyEnabled(apConfig);

        final int channelFrequency = 5220;
        final int channelBandwidth = IApInterfaceEventCallback.BANDWIDTH_20;
        mSoftApListenerCaptor.getValue().onSoftApChannelSwitched(channelFrequency,
                channelBandwidth);
        mLooper.dispatchAll();

        verify(mWifiMetrics).addSoftApChannelSwitchedEvent(channelFrequency, channelBandwidth,
                apConfig.getTargetMode());
        verify(mWifiMetrics, never()).incrementNumSoftApUserBandPreferenceUnsatisfied();
    }

    /**
     * If SoftApManager gets an update for the ap channal and the frequency, it will trigger
     * callbacks to update softap information.
     */
    @Test
    public void testOnSoftApChannelSwitchedEventTriggerSoftApInfoUpdate() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        mSoftApListenerCaptor.getValue().onSoftApChannelSwitched(
                TEST_AP_FREQUENCY, TEST_AP_BANDWIDTH_FROM_IFACE_CALLBACK);
        mLooper.dispatchAll();

        verify(mCallback).onInfoChanged(mTestSoftApInfo);
        verify(mWifiMetrics).addSoftApChannelSwitchedEvent(TEST_AP_FREQUENCY,
                TEST_AP_BANDWIDTH_IN_SOFTAPINFO, apConfig.getTargetMode());
    }

    /**
     * If SoftApManager gets an update for the ap channal and the frequency those are the same,
     * do not trigger callbacks a second time.
     */
    @Test
    public void testDoesNotTriggerCallbackForSameChannelInfoUpdate() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        mSoftApListenerCaptor.getValue().onSoftApChannelSwitched(
                TEST_AP_FREQUENCY, TEST_AP_BANDWIDTH_FROM_IFACE_CALLBACK);
        mLooper.dispatchAll();

        // now trigger callback again, but we should have each method only called once
        mSoftApListenerCaptor.getValue().onSoftApChannelSwitched(
                TEST_AP_FREQUENCY, TEST_AP_BANDWIDTH_FROM_IFACE_CALLBACK);
        mLooper.dispatchAll();

        verify(mCallback).onInfoChanged(mTestSoftApInfo);
        verify(mWifiMetrics).addSoftApChannelSwitchedEvent(TEST_AP_FREQUENCY,
                TEST_AP_BANDWIDTH_IN_SOFTAPINFO, apConfig.getTargetMode());
    }

    /**
     * If SoftApManager gets an update for the invalid ap frequency, it will not
     * trigger callbacks
     */
    @Test
    public void testHandlesInvalidChannelFrequency() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        mSoftApListenerCaptor.getValue().onSoftApChannelSwitched(
                -1, TEST_AP_BANDWIDTH_FROM_IFACE_CALLBACK);
        mLooper.dispatchAll();

        verify(mCallback, never()).onInfoChanged(any());
        verify(mWifiMetrics, never()).addSoftApChannelSwitchedEvent(anyInt(), anyInt(),
                anyInt());
    }

    /**
     * If softap leave started state, it should update softap inforation which frequency is 0 via
     * trigger callbacks.
     */
    @Test
    public void testCallbackForChannelUpdateToZeroWhenLeaveSoftapStarted() throws Exception {
        InOrder order = inOrder(mCallback, mWifiMetrics);
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        mSoftApListenerCaptor.getValue().onSoftApChannelSwitched(
                TEST_AP_FREQUENCY, TEST_AP_BANDWIDTH_FROM_IFACE_CALLBACK);
        mLooper.dispatchAll();

        order.verify(mCallback).onInfoChanged(mTestSoftApInfo);
        order.verify(mWifiMetrics).addSoftApChannelSwitchedEvent(TEST_AP_FREQUENCY,
                TEST_AP_BANDWIDTH_IN_SOFTAPINFO, apConfig.getTargetMode());

        mSoftApManager.stop();
        mLooper.dispatchAll();

        mTestSoftApInfo.setFrequency(0);
        mTestSoftApInfo.setBandwidth(SoftApInfo.CHANNEL_WIDTH_INVALID);

        order.verify(mCallback).onInfoChanged(mTestSoftApInfo);
        order.verify(mWifiMetrics, never()).addSoftApChannelSwitchedEvent(0,
                SoftApInfo.CHANNEL_WIDTH_INVALID, apConfig.getTargetMode());
    }

    @Test
    public void updatesConnectedClients() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        mSoftApListenerCaptor.getValue().onConnectedClientsChanged(
                TEST_CONNECTED_NATIVECLIENTS);
        mLooper.dispatchAll();

        verify(mCallback).onConnectedClientsChanged(
                Mockito.argThat((List<WifiClient> clients) ->
                        clients.contains(TEST_CONNECTED_CLIENT))
        );
        verify(mWifiMetrics).addSoftApNumAssociatedStationsChangedEvent(
                TEST_CONNECTED_CLIENTS.size(),
                apConfig.getTargetMode());
    }

    /**
     * If SoftApManager gets an update for the number of connected clients that is the same, do not
     * trigger callbacks a second time.
     */
    @Test
    public void testDoesNotTriggerCallbackForSameClients() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        mSoftApListenerCaptor.getValue().onConnectedClientsChanged(
                TEST_CONNECTED_NATIVECLIENTS);
        mLooper.dispatchAll();

        // now trigger callback again, but we should have each method only called once
        mSoftApListenerCaptor.getValue().onConnectedClientsChanged(
                TEST_CONNECTED_NATIVECLIENTS);
        mLooper.dispatchAll();

        verify(mCallback).onConnectedClientsChanged(
                Mockito.argThat((List<WifiClient> clients) ->
                        clients.contains(TEST_CONNECTED_CLIENT))
        );
        verify(mWifiMetrics)
                .addSoftApNumAssociatedStationsChangedEvent(
                TEST_CONNECTED_CLIENTS.size(),
                apConfig.getTargetMode());
    }

    @Test
    public void handlesInvalidConnectedClients() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        /* Invalid values should be ignored */
        final List<NativeWifiClient> mInvalidClients = null;
        mSoftApListenerCaptor.getValue().onConnectedClientsChanged(mInvalidClients);
        mLooper.dispatchAll();
        verify(mCallback, never()).onConnectedClientsChanged(null);
        verify(mWifiMetrics, never()).addSoftApNumAssociatedStationsChangedEvent(anyInt(),
                anyInt());
    }

    @Test
    public void testCallbackForClientUpdateToZeroWhenLeaveSoftapStarted() throws Exception {
        InOrder order = inOrder(mCallback, mWifiMetrics);
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        mSoftApListenerCaptor.getValue().onConnectedClientsChanged(
                TEST_CONNECTED_NATIVECLIENTS);
        mLooper.dispatchAll();

        verify(mCallback).onConnectedClientsChanged(
                Mockito.argThat((List<WifiClient> clients) ->
                        clients.contains(TEST_CONNECTED_CLIENT))
        );
        order.verify(mWifiMetrics).addSoftApNumAssociatedStationsChangedEvent(
                TEST_CONNECTED_CLIENTS.size(), apConfig.getTargetMode());
        // Verify timer is canceled at this point
        verify(mAlarmManager.getAlarmManager()).cancel(any(WakeupMessage.class));

        mSoftApManager.stop();
        mLooper.dispatchAll();

        order.verify(mCallback).onConnectedClientsChanged(new ArrayList<>());
        order.verify(mWifiMetrics).addSoftApNumAssociatedStationsChangedEvent(0,
                apConfig.getTargetMode());
        // Verify timer is canceled after stop softap
        verify(mAlarmManager.getAlarmManager(), times(2)).cancel(any(WakeupMessage.class));
    }

    @Test
    public void schedulesTimeoutTimerOnStart() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        // Verify timer is scheduled
        verify(mAlarmManager.getAlarmManager()).setExact(anyInt(), anyLong(),
                eq(mSoftApManager.SOFT_AP_SEND_MESSAGE_TIMEOUT_TAG), any(), any());
    }

    @Test
    public void cancelsTimeoutTimerOnStop() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);
        mSoftApManager.stop();
        mLooper.dispatchAll();

        // Verify timer is canceled
        verify(mAlarmManager.getAlarmManager()).cancel(any(WakeupMessage.class));
    }

    @Test
    public void cancelsTimeoutTimerOnNewClientsConnect() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);
        mSoftApListenerCaptor.getValue().onConnectedClientsChanged(
                TEST_CONNECTED_NATIVECLIENTS);
        mLooper.dispatchAll();

        // Verify timer is canceled
        verify(mAlarmManager.getAlarmManager()).cancel(any(WakeupMessage.class));
    }

    @Test
    public void schedulesTimeoutTimerWhenAllClientsDisconnect() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        mSoftApListenerCaptor.getValue().onConnectedClientsChanged(
                TEST_CONNECTED_NATIVECLIENTS);
        mLooper.dispatchAll();
        verify(mCallback).onConnectedClientsChanged(
                Mockito.argThat((List<WifiClient> clients) ->
                        clients.contains(TEST_CONNECTED_CLIENT))
        );
        // Verify timer is canceled at this point
        verify(mAlarmManager.getAlarmManager()).cancel(any(WakeupMessage.class));

        List<NativeWifiClient> testClients = new ArrayList();
        mSoftApListenerCaptor.getValue().onConnectedClientsChanged(testClients);
        mLooper.dispatchAll();
        verify(mCallback).onConnectedClientsChanged(
                Mockito.argThat((List<WifiClient> clients) ->
                        clients.contains(TEST_CONNECTED_CLIENT))
        );
        // Verify timer is scheduled again
        verify(mAlarmManager.getAlarmManager(), times(2)).setExact(anyInt(), anyLong(),
                eq(mSoftApManager.SOFT_AP_SEND_MESSAGE_TIMEOUT_TAG), any(), any());
    }

    @Test
    public void stopsSoftApOnTimeoutMessage() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        mAlarmManager.dispatch(mSoftApManager.SOFT_AP_SEND_MESSAGE_TIMEOUT_TAG);
        mLooper.dispatchAll();

        verify(mWifiNative).teardownInterface(TEST_INTERFACE_NAME);
    }

    @Test
    public void cancelsTimeoutTimerOnTimeoutToggleChangeWhenNoClients() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        when(mFrameworkFacade.getIntegerSetting(
                mContext, Settings.Global.SOFT_AP_TIMEOUT_ENABLED, 1)).thenReturn(0);
        mContentObserver.onChange(false);
        mLooper.dispatchAll();

        // Verify timer is canceled
        verify(mAlarmManager.getAlarmManager()).cancel(any(WakeupMessage.class));
    }

    @Test
    public void schedulesTimeoutTimerOnTimeoutToggleChangeWhenNoClients() throws Exception {
        // start with timeout toggle disabled
        when(mFrameworkFacade.getIntegerSetting(
                mContext, Settings.Global.SOFT_AP_TIMEOUT_ENABLED, 1)).thenReturn(0);
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        when(mFrameworkFacade.getIntegerSetting(
                mContext, Settings.Global.SOFT_AP_TIMEOUT_ENABLED, 1)).thenReturn(1);
        mContentObserver.onChange(false);
        mLooper.dispatchAll();

        // Verify timer is scheduled
        verify(mAlarmManager.getAlarmManager()).setExact(anyInt(), anyLong(),
                eq(mSoftApManager.SOFT_AP_SEND_MESSAGE_TIMEOUT_TAG), any(), any());
    }

    @Test
    public void doesNotScheduleTimeoutTimerOnStartWhenTimeoutIsDisabled() throws Exception {
        // start with timeout toggle disabled
        when(mFrameworkFacade.getIntegerSetting(
                mContext, Settings.Global.SOFT_AP_TIMEOUT_ENABLED, 1)).thenReturn(0);
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        // Verify timer is not scheduled
        verify(mAlarmManager.getAlarmManager(), never()).setExact(anyInt(), anyLong(),
                eq(mSoftApManager.SOFT_AP_SEND_MESSAGE_TIMEOUT_TAG), any(), any());
    }

    @Test
    public void doesNotScheduleTimeoutTimerWhenAllClientsDisconnectButTimeoutIsDisabled()
            throws Exception {
        // start with timeout toggle disabled
        when(mFrameworkFacade.getIntegerSetting(
                mContext, Settings.Global.SOFT_AP_TIMEOUT_ENABLED, 1)).thenReturn(0);
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);
        // add some clients
        mSoftApListenerCaptor.getValue().onConnectedClientsChanged(
                TEST_CONNECTED_NATIVECLIENTS);
        mLooper.dispatchAll();
        // remove all clients
        mSoftApListenerCaptor.getValue()
                .onConnectedClientsChanged(new ArrayList<NativeWifiClient>());
        mLooper.dispatchAll();
        // Verify timer is not scheduled
        verify(mAlarmManager.getAlarmManager(), never()).setExact(anyInt(), anyLong(),
                eq(mSoftApManager.SOFT_AP_SEND_MESSAGE_TIMEOUT_TAG), any(), any());
    }

    @Test
    public void unregistersSettingsObserverOnStop() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);
        mSoftApManager.stop();
        mLooper.dispatchAll();

        verify(mFrameworkFacade).unregisterContentObserver(eq(mContext), eq(mContentObserver));
    }

    @Test
    public void resetsFactoryMacWhenRandomizationOff() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = WifiConfiguration.AP_BAND_2GHZ;
        config.SSID = TEST_SSID;
        config.BSSID = null;
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);
        ArgumentCaptor<MacAddress> mac = ArgumentCaptor.forClass(MacAddress.class);
        when(mWifiNative.getFactoryMacAddress(TEST_INTERFACE_NAME)).thenReturn(TEST_MAC_ADDRESS);
        when(mWifiNative.setMacAddress(eq(TEST_INTERFACE_NAME), mac.capture())).thenReturn(true);

        startSoftApAndVerifyEnabled(apConfig);

        assertThat(mac.getValue()).isEqualTo(TEST_MAC_ADDRESS);
    }

    @Test
    public void setsCustomMac() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = WifiConfiguration.AP_BAND_2GHZ;
        config.SSID = TEST_SSID;
        config.BSSID = "23:34:45:56:67:78";
        SoftApModeConfiguration apConfig = new SoftApModeConfiguration(
                IFACE_IP_MODE_LOCAL_ONLY, config);
        ArgumentCaptor<MacAddress> mac = ArgumentCaptor.forClass(MacAddress.class);
        when(mWifiNative.setMacAddress(eq(TEST_INTERFACE_NAME), mac.capture())).thenReturn(true);

        startSoftApAndVerifyEnabled(apConfig);

        assertThat(mac.getValue()).isEqualTo(MacAddress.fromString("23:34:45:56:67:78"));
    }

    @Test
    public void setMacFailureAllowedWhenRandomizationOff() throws Exception {
        when(mResources.getBoolean(R.bool.config_wifi_ap_mac_randomization_supported))
                .thenReturn(false);
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        ArgumentCaptor<MacAddress> mac = ArgumentCaptor.forClass(MacAddress.class);

        when(mWifiNative.setMacAddress(any(), any())).thenReturn(false);

        startSoftApAndVerifyEnabled(apConfig);
    }

    /** Starts soft AP and verifies that it is enabled successfully. */
    protected void startSoftApAndVerifyEnabled(
            SoftApModeConfiguration softApConfig) throws Exception {
        startSoftApAndVerifyEnabled(softApConfig, TEST_COUNTRY_CODE);
    }

    /** Starts soft AP and verifies that it is enabled successfully. */
    protected void startSoftApAndVerifyEnabled(
            SoftApModeConfiguration softApConfig, String countryCode) throws Exception {
        WifiConfiguration expectedConfig;
        InOrder order = inOrder(mCallback, mWifiNative);

        mSoftApManager = createSoftApManager(softApConfig, countryCode);
        WifiConfiguration config = softApConfig.getWifiConfiguration();
        if (config == null) {
            when(mWifiApConfigStore.getApConfiguration()).thenReturn(mDefaultApConfig);
            expectedConfig = new WifiConfiguration(mDefaultApConfig);
        } else {
            expectedConfig = new WifiConfiguration(config);
        }

        ArgumentCaptor<ContentObserver> observerCaptor = ArgumentCaptor.forClass(
                ContentObserver.class);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);

        when(mWifiNative.setupInterfaceForSoftApMode(any()))
                .thenReturn(TEST_INTERFACE_NAME);

        mSoftApManager.start();
        mLooper.dispatchAll();
        order.verify(mWifiNative).setupInterfaceForSoftApMode(
                mWifiNativeInterfaceCallbackCaptor.capture());
        ArgumentCaptor<WifiConfiguration> configCaptor =
                ArgumentCaptor.forClass(WifiConfiguration.class);
        order.verify(mCallback).onStateChanged(WifiManager.WIFI_AP_STATE_ENABLING, 0);
        order.verify(mWifiNative).startSoftAp(eq(TEST_INTERFACE_NAME),
                configCaptor.capture(), mSoftApListenerCaptor.capture());
        WifiConfigurationTestUtil.assertConfigurationEqual(expectedConfig, configCaptor.getValue());
        mWifiNativeInterfaceCallbackCaptor.getValue().onUp(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();
        order.verify(mCallback).onStateChanged(WifiManager.WIFI_AP_STATE_ENABLED, 0);
        order.verify(mCallback).onConnectedClientsChanged(new ArrayList<>());
        verify(mSarManager).setSapWifiState(WifiManager.WIFI_AP_STATE_ENABLED);
        verify(mWifiDiagnostics).startLogging(TEST_INTERFACE_NAME);
        verify(mContext, times(2)).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));
        List<Intent> capturedIntents = intentCaptor.getAllValues();
        checkApStateChangedBroadcast(capturedIntents.get(0), WIFI_AP_STATE_ENABLING,
                WIFI_AP_STATE_DISABLED, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApConfig.getTargetMode());
        checkApStateChangedBroadcast(capturedIntents.get(1), WIFI_AP_STATE_ENABLED,
                WIFI_AP_STATE_ENABLING, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApConfig.getTargetMode());
        verify(mListener).onStarted();
        verify(mWifiMetrics).addSoftApUpChangedEvent(true, softApConfig.getTargetMode());
        verify(mFrameworkFacade).registerContentObserver(eq(mContext), any(Uri.class), eq(true),
                observerCaptor.capture());
        mContentObserver = observerCaptor.getValue();
    }

    private void checkApStateChangedBroadcast(Intent intent, int expectedCurrentState,
            int expectedPrevState, int expectedErrorCode,
            String expectedIfaceName, int expectedMode) {
        int currentState = intent.getIntExtra(EXTRA_WIFI_AP_STATE, WIFI_AP_STATE_DISABLED);
        int prevState = intent.getIntExtra(EXTRA_PREVIOUS_WIFI_AP_STATE, WIFI_AP_STATE_DISABLED);
        int errorCode = intent.getIntExtra(EXTRA_WIFI_AP_FAILURE_REASON, HOTSPOT_NO_ERROR);
        String ifaceName = intent.getStringExtra(EXTRA_WIFI_AP_INTERFACE_NAME);
        int mode = intent.getIntExtra(EXTRA_WIFI_AP_MODE, WifiManager.IFACE_IP_MODE_UNSPECIFIED);
        assertEquals(expectedCurrentState, currentState);
        assertEquals(expectedPrevState, prevState);
        assertEquals(expectedErrorCode, errorCode);
        assertEquals(expectedIfaceName, ifaceName);
        assertEquals(expectedMode, mode);
    }
}
