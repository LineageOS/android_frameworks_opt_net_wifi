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

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;


/**
 * Unit tests for {@link com.android.server.wifi.DeviceConfigFacade}.
 */
@SmallTest
public class DeviceConfigFacadeTest {
    @Mock Context mContext;

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

        mDeviceConfigFacade = new DeviceConfigFacade(mContext, new Handler(mLooper.getLooper()));
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

        // Simulate updating the fields
        when(DeviceConfig.getBoolean(anyString(), eq("abnormal_connection_bugreport_enabled"),
                anyBoolean())).thenReturn(true);
        when(DeviceConfig.getInt(anyString(), eq("abnormal_connection_duration_ms"),
                anyInt())).thenReturn(100);
        when(DeviceConfig.getBoolean(anyString(),
                eq("aggressive_randomization_ssid_whitelist_enabled"),
                anyBoolean())).thenReturn(true);
        mOnPropertiesChangedListenerCaptor.getValue().onPropertiesChanged(null);

        // Verifying fields are updated to the new values
        assertEquals(true, mDeviceConfigFacade.isAbnormalConnectionBugreportEnabled());
        assertEquals(100, mDeviceConfigFacade.getAbnormalConnectionDurationMs());
        assertEquals(true, mDeviceConfigFacade.isAggressiveMacRandomizationSsidWhitelistEnabled());
    }
}
