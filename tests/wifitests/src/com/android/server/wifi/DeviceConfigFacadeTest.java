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

package com.android.server.wifi;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.*;

import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.content.Context;
import android.os.Handler;
import android.os.test.TestLooper;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.OnPropertiesChangedListener;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.wifi.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.util.Collections;
import java.util.Set;


/**
 * Unit tests for {@link com.android.server.wifi.DeviceConfigFacade}.
 */
@SmallTest
public class DeviceConfigFacadeTest extends WifiBaseTest {
    @Mock Context mContext;
    @Mock WifiMetrics mWifiMetrics;

    final ArgumentCaptor<OnPropertiesChangedListener> mOnPropertiesChangedListenerCaptor =
            ArgumentCaptor.forClass(OnPropertiesChangedListener.class);

    private DeviceConfigFacade mDeviceConfigFacade;
    private TestLooper mLooper = new TestLooper();
    private MockResources mResources;
    private MockitoSession mSession;

    /**
     * Setup the mocks and an instance of WifiConfigManager before each test.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mResources = new MockResources();
        mResources.setBoolean(
                R.bool.config_wifi_aggressive_randomization_ssid_whitelist_enabled, false);
        when(mContext.getResources()).thenReturn(mResources);

        // static mocking
        mSession = ExtendedMockito.mockitoSession()
                .mockStatic(DeviceConfig.class, withSettings().lenient())
                .startMocking();
        // Have DeviceConfig return the default value passed in.
        when(DeviceConfig.getBoolean(anyString(), anyString(), anyBoolean()))
                .then(new AnswerWithArguments() {
                    public boolean answer(String namespace, String field, boolean def) {
                        return def;
                    }
                });
        when(DeviceConfig.getInt(anyString(), anyString(), anyInt()))
                .then(new AnswerWithArguments() {
                    public int answer(String namespace, String field, int def) {
                        return def;
                    }
                });
        when(DeviceConfig.getString(anyString(), anyString(), anyString()))
                .then(new AnswerWithArguments() {
                    public String answer(String namespace, String field, String def) {
                        return def;
                    }
                });

        mDeviceConfigFacade = new DeviceConfigFacade(mContext, new Handler(mLooper.getLooper()),
                mWifiMetrics);
        verify(() -> DeviceConfig.addOnPropertiesChangedListener(anyString(), any(),
                mOnPropertiesChangedListenerCaptor.capture()));
    }

    /**
     * Called after each test
     */
    @After
    public void cleanup() {
        validateMockitoUsage();
        mSession.finishMocking();
    }

    /**
     * Verifies that all fields are updated properly.
     */
    @Test
    public void testFieldUpdates() throws Exception {
        // First verify fields are set to their default values.
        assertEquals(false, mDeviceConfigFacade.isAbnormalConnectionBugreportEnabled());
        assertEquals(DeviceConfigFacade.DEFAULT_ABNORMAL_CONNECTION_DURATION_MS,
                mDeviceConfigFacade.getAbnormalConnectionDurationMs());
        assertEquals(false,
                mDeviceConfigFacade.isAggressiveMacRandomizationSsidWhitelistEnabled());
        assertEquals(DeviceConfigFacade.DEFAULT_DATA_STALL_DURATION_MS,
                mDeviceConfigFacade.getDataStallDurationMs());
        assertEquals(DeviceConfigFacade.DEFAULT_DATA_STALL_TX_TPUT_THR_KBPS,
                mDeviceConfigFacade.getDataStallTxTputThrKbps());
        assertEquals(DeviceConfigFacade.DEFAULT_DATA_STALL_RX_TPUT_THR_KBPS,
                mDeviceConfigFacade.getDataStallRxTputThrKbps());
        assertEquals(DeviceConfigFacade.DEFAULT_DATA_STALL_TX_PER_THR,
                mDeviceConfigFacade.getDataStallTxPerThr());
        assertEquals(DeviceConfigFacade.DEFAULT_DATA_STALL_CCA_LEVEL_THR,
                mDeviceConfigFacade.getDataStallCcaLevelThr());
        assertEquals(Collections.emptySet(),
                mDeviceConfigFacade.getRandomizationFlakySsidHotlist());

        // Simulate updating the fields
        when(DeviceConfig.getBoolean(anyString(), eq("abnormal_connection_bugreport_enabled"),
                anyBoolean())).thenReturn(true);
        when(DeviceConfig.getInt(anyString(), eq("abnormal_connection_duration_ms"),
                anyInt())).thenReturn(100);
        when(DeviceConfig.getBoolean(anyString(),
                eq("aggressive_randomization_ssid_whitelist_enabled"),
                anyBoolean())).thenReturn(true);
        when(DeviceConfig.getInt(anyString(), eq("data_stall_duration_ms"),
                anyInt())).thenReturn(0);
        when(DeviceConfig.getInt(anyString(), eq("data_stall_tx_tput_thr_kbps"),
                anyInt())).thenReturn(1000);
        when(DeviceConfig.getInt(anyString(), eq("data_stall_rx_tput_thr_kbps"),
                anyInt())).thenReturn(1500);
        when(DeviceConfig.getInt(anyString(), eq("data_stall_tx_per_thr"),
                anyInt())).thenReturn(95);
        when(DeviceConfig.getInt(anyString(), eq("data_stall_cca_level_thr"),
                anyInt())).thenReturn(80);
        when(DeviceConfig.getString(anyString(), eq("randomization_flaky_ssid_hotlist"),
                anyString())).thenReturn("ssid_1,ssid_2");
        mOnPropertiesChangedListenerCaptor.getValue().onPropertiesChanged(null);

        // Verifying fields are updated to the new values
        Set<String> randomizationFlakySsidSet = new ArraySet<>();
        randomizationFlakySsidSet.add("\"ssid_1\"");
        randomizationFlakySsidSet.add("\"ssid_2\"");
        assertEquals(true, mDeviceConfigFacade.isAbnormalConnectionBugreportEnabled());
        assertEquals(100, mDeviceConfigFacade.getAbnormalConnectionDurationMs());
        assertEquals(true, mDeviceConfigFacade.isAggressiveMacRandomizationSsidWhitelistEnabled());
        assertEquals(0, mDeviceConfigFacade.getDataStallDurationMs());
        assertEquals(1000, mDeviceConfigFacade.getDataStallTxTputThrKbps());
        assertEquals(1500, mDeviceConfigFacade.getDataStallRxTputThrKbps());
        assertEquals(95, mDeviceConfigFacade.getDataStallTxPerThr());
        assertEquals(80, mDeviceConfigFacade.getDataStallCcaLevelThr());
        assertEquals(randomizationFlakySsidSet,
                mDeviceConfigFacade.getRandomizationFlakySsidHotlist());
    }
}
