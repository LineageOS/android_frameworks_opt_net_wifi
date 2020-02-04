/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.wifi.scanner;

import static com.android.server.wifi.ScanTestUtil.setupMockChannels;

import androidx.test.filters.SmallTest;

import org.junit.Before;

import java.util.Arrays;

/**
 * Unit tests for {@link com.android.server.wifi.scanner.HalWifiScannerImpl}.
 */
@SmallTest
public class HalWifiScannerTest extends BaseWifiScannerImplTest {

    @Before
    public void setUp() throws Exception {
        setupMockChannels(mWifiNative,
                Arrays.asList(2400, 2450),
                Arrays.asList(5150, 5175),
                Arrays.asList(5600, 5650),
                Arrays.asList(5945, 5985));
        mScanner = new HalWifiScannerImpl(mContext, BaseWifiScannerImplTest.IFACE_NAME,
                mWifiNative, mWifiMonitor, mLooper.getLooper(), mClock);
    }

    // Subtle: tests are inherited from base class.
}
