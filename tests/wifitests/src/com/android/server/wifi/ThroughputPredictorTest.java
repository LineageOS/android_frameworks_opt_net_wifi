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

import static com.android.server.wifi.util.InformationElementUtil.BssLoad.INVALID;
import static com.android.server.wifi.util.InformationElementUtil.BssLoad.MAX_CHANNEL_UTILIZATION;
import static com.android.server.wifi.util.InformationElementUtil.BssLoad.MIN_CHANNEL_UTILIZATION;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.wifi.ScanResult;

import androidx.test.filters.SmallTest;

import com.android.wifi.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

/**
 * Unit tests for {@link com.android.server.wifi.ThroughputPredictor}.
 */
@SmallTest
public class ThroughputPredictorTest extends WifiBaseTest {
    @Mock private Context mContext;
    // For simulating the resources, we use a Spy on a MockResource
    // (which is really more of a stub than a mock, in spite if its name).
    // This is so that we get errors on any calls that we have not explicitly set up.
    @Spy
    private MockResources mResource = new MockResources();
    ThroughputPredictor mThroughputPredictor;

    /**
     * Sets up for unit test
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        doReturn(false).when(mResource).getBoolean(R.bool.config_wifi_11ax_supported);
        doReturn(false).when(mResource).getBoolean(
                R.bool.config_wifi_contiguous_160mhz_supported);
        doReturn(2).when(mResource).getInteger(
                R.integer.config_wifi_max_num_spatial_stream_supported);
        when(mContext.getResources()).thenReturn(mResource);
        mThroughputPredictor = new ThroughputPredictor(mContext);
    }

    /** Cleans up test. */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    @Test
    public void verifyVeryLowRssi() {
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_11AC, ScanResult.CHANNEL_WIDTH_20MHZ, -200, 2412, 1,
                0, 0, false);

        assertEquals(0, predictedThroughputMbps);
    }

    @Test
    public void verifyMaxChannelUtilizationBssLoad() {
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_11AC, ScanResult.CHANNEL_WIDTH_20MHZ, 0, 2412, 1,
                MAX_CHANNEL_UTILIZATION, 0, false);

        assertEquals(0, predictedThroughputMbps);
    }

    @Test
    public void verifyMaxChannelUtilizationLinkLayerStats() {
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_11AC, ScanResult.CHANNEL_WIDTH_20MHZ, 0, 5210, 1,
                INVALID, MAX_CHANNEL_UTILIZATION, false);

        assertEquals(0, predictedThroughputMbps);
    }

    @Test
    public void verifyHighRssiMinChannelUtilizationAc5g80Mhz2ss() {
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_11AC, ScanResult.CHANNEL_WIDTH_80MHZ, 0, 5180, 2,
                MIN_CHANNEL_UTILIZATION, 50, false);

        assertEquals(866, predictedThroughputMbps);
    }

    @Test
    public void verifyHighRssiMinChannelUtilizationAx5g160Mhz4ss() {
        doReturn(true).when(mResource).getBoolean(R.bool.config_wifi_11ax_supported);
        doReturn(true).when(mResource).getBoolean(
                R.bool.config_wifi_contiguous_160mhz_supported);
        doReturn(4).when(mResource).getInteger(
                R.integer.config_wifi_max_num_spatial_stream_supported);
        when(mContext.getResources()).thenReturn(mResource);
        mThroughputPredictor = new ThroughputPredictor(mContext);
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_11AX, ScanResult.CHANNEL_WIDTH_160MHZ, 0, 5180, 4,
                MIN_CHANNEL_UTILIZATION, INVALID, false);

        assertEquals(4803, predictedThroughputMbps);
    }

    @Test
    public void verifyMidRssiMinChannelUtilizationAc5g80Mhz2ss() {
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_11AC, ScanResult.CHANNEL_WIDTH_80MHZ, -50, 5180, 2,
                MIN_CHANNEL_UTILIZATION, INVALID, false);

        assertEquals(866, predictedThroughputMbps);
    }

    @Test
    public void verifyLowRssiMinChannelUtilizationAc5g80Mhz2ss() {
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_11AC, ScanResult.CHANNEL_WIDTH_80MHZ, -80, 5180, 2,
                MIN_CHANNEL_UTILIZATION, INVALID, false);

        assertEquals(41, predictedThroughputMbps);
    }

    @Test
    public void verifyLowRssiDefaultChannelUtilizationAc5g80Mhz2ss() {
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_11AC, ScanResult.CHANNEL_WIDTH_80MHZ, -80, 5180, 2,
                INVALID, INVALID, false);

        assertEquals(31, predictedThroughputMbps);
    }

    @Test
    public void verifyHighRssiMinChannelUtilizationAc2g20Mhz2ss() {
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_11AC, ScanResult.CHANNEL_WIDTH_20MHZ, -20, 2437, 2,
                MIN_CHANNEL_UTILIZATION, INVALID, false);

        assertEquals(192, predictedThroughputMbps);
    }

    @Test
    public void verifyHighRssiMinChannelUtilizationAc2g20Mhz2ssBluetoothConnected() {
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_11AC, ScanResult.CHANNEL_WIDTH_20MHZ, -20, 2437, 2,
                MIN_CHANNEL_UTILIZATION, INVALID, true);

        assertEquals(144, predictedThroughputMbps);
    }

    @Test
    public void verifyHighRssiMinChannelUtilizationLegacy5g20Mhz() {
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_LEGACY, ScanResult.CHANNEL_WIDTH_20MHZ, -50, 5180,
                1, MIN_CHANNEL_UTILIZATION, INVALID, false);

        assertEquals(54, predictedThroughputMbps);
    }

    @Test
    public void verifyLowRssiDefaultChannelUtilizationLegacy5g20Mhz() {
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_LEGACY, ScanResult.CHANNEL_WIDTH_20MHZ, -80, 5180,
                2, INVALID, INVALID, false);

        assertEquals(11, predictedThroughputMbps);
    }

    @Test
    public void verifyHighRssiMinChannelUtilizationHt2g20Mhz2ss() {
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_11N, ScanResult.CHANNEL_WIDTH_20MHZ, -50, 2437, 2,
                MIN_CHANNEL_UTILIZATION, INVALID, false);

        assertEquals(144, predictedThroughputMbps);
    }

    @Test
    public void verifyLowRssiDefaultChannelUtilizationHt2g20Mhz1ss() {
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_11N, ScanResult.CHANNEL_WIDTH_20MHZ, -80, 2437, 1,
                INVALID, INVALID, true);

        assertEquals(5, predictedThroughputMbps);
    }

    @Test
    public void verifyHighRssiHighChannelUtilizationAx2g20Mhz2ss() {
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_11AC, ScanResult.CHANNEL_WIDTH_20MHZ, -50, 2437, 2,
                INVALID, 80, true);

        assertEquals(84, predictedThroughputMbps);
    }

    @Test
    public void verifyRssiBoundaryHighChannelUtilizationAc2g20Mhz2ss() {
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_11AC, ScanResult.CHANNEL_WIDTH_20MHZ, -69, 2437, 2,
                INVALID, 80, true);

        assertEquals(46, predictedThroughputMbps);
    }

    @Test
    public void verifyRssiBoundaryHighChannelUtilizationAc5g40Mhz2ss() {
        int predictedThroughputMbps = mThroughputPredictor.predictThroughput(
                ScanResult.WIFI_STANDARD_11AC, ScanResult.CHANNEL_WIDTH_40MHZ, -66, 5180, 2,
                INVALID, 80, false);

        assertEquals(103, predictedThroughputMbps);
    }
}
