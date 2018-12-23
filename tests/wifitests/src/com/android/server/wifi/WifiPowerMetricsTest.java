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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

import android.os.RemoteException;
import android.os.connectivity.WifiBatteryStats;

import androidx.test.filters.SmallTest;

import com.android.internal.app.IBatteryStats;
import com.android.server.wifi.nano.WifiMetricsProto.WifiRadioUsage;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

/**
 * Unit tests for {@link com.android.server.wifi.WifiPowerMetrics}.
 */
@SmallTest
public class WifiPowerMetricsTest {
    @Mock IBatteryStats mBatteryStats;
    WifiPowerMetrics mWifiPowerMetrics;

    private static final long DEFAULT_VALUE = 0;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mWifiPowerMetrics = new WifiPowerMetrics(mBatteryStats);
    }

    /**
     * Tests that WifiRadioUsage has its fields set according to the corresponding fields in
     * WifiBatteryStats
     * @throws Exception
     */
    @Test
    public void testBuildWifiRadioUsageProto() throws Exception {
        final long loggingDuration = 280;
        final long scanTime = 23;
        WifiBatteryStats wifiBatteryStats = new WifiBatteryStats();
        wifiBatteryStats.setLoggingDurationMs(loggingDuration);
        wifiBatteryStats.setScanTimeMs(scanTime);
        when(mBatteryStats.getWifiBatteryStats()).thenReturn(wifiBatteryStats);
        WifiRadioUsage wifiRadioUsage = mWifiPowerMetrics.buildWifiRadioUsageProto();
        verify(mBatteryStats).getWifiBatteryStats();
        assertEquals("loggingDurationMs must match with field from WifiBatteryStats",
                loggingDuration, wifiRadioUsage.loggingDurationMs);
        assertEquals("scanTimeMs must match with field from WifiBatteryStats",
                scanTime, wifiRadioUsage.scanTimeMs);
    }

    /**
     * Tests that WifiRadioUsage has its fields set to the |DEFAULT_VALUE| when IBatteryStats
     * returns null
     * @throws Exception
     */
    @Test
    public void testBuildWifiRadioUsageProtoNull() throws Exception {
        when(mBatteryStats.getWifiBatteryStats()).thenReturn(null);
        WifiRadioUsage wifiRadioUsage = mWifiPowerMetrics.buildWifiRadioUsageProto();
        verify(mBatteryStats).getWifiBatteryStats();
        assertEquals("loggingDurationMs must be default value when getWifiBatteryStats returns "
                        + "null", DEFAULT_VALUE, wifiRadioUsage.loggingDurationMs);
        assertEquals("scanTimeMs must be default value when getWifiBatteryStats returns null",
                DEFAULT_VALUE, wifiRadioUsage.scanTimeMs);
    }

    /**
     * Tests that WifiRadioUsage has its fields set to the |DEFAULT_VALUE| when IBatteryStats
     * throws a RemoteException
     * @throws Exception
     */
    @Test
    public void testBuildWifiRadioUsageProtoRemoteException() throws Exception {
        doThrow(new RemoteException()).when(mBatteryStats).getWifiBatteryStats();
        WifiRadioUsage wifiRadioUsage = mWifiPowerMetrics.buildWifiRadioUsageProto();
        verify(mBatteryStats).getWifiBatteryStats();
        assertEquals("loggingDurationMs must be default value when getWifiBatteryStats throws "
                        + "RemoteException", DEFAULT_VALUE, wifiRadioUsage.loggingDurationMs);
        assertEquals("scanTimeMs must be default value when getWifiBatteryStats throws "
                        + "RemoteException", DEFAULT_VALUE, wifiRadioUsage.scanTimeMs);
    }

    /**
     * Tests that dump() pulls data from IBatteryStats
     * @throws Exception
     */
    @Test
    public void testDumpCallsAppropriateMethods() throws Exception {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(stream);
        mWifiPowerMetrics.dump(writer);
        verify(mBatteryStats, atLeastOnce()).getWifiBatteryStats();
    }
}
