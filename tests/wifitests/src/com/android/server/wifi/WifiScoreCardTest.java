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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.net.MacAddress;
import android.net.wifi.WifiSsid;
import android.support.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link com.android.server.wifi.WifiScoreCard}.
 */
@SmallTest
public class WifiScoreCardTest {

    static final WifiSsid TEST_SSID_1 = WifiSsid.createFromAsciiEncoded("Joe's Place");
    static final WifiSsid TEST_SSID_2 = WifiSsid.createFromAsciiEncoded("Poe's Raven");

    static final MacAddress TEST_BSSID_1 = MacAddress.fromString("aa:bb:cc:dd:ee:ff");
    static final MacAddress TEST_BSSID_2 = MacAddress.fromString("1:2:3:4:5:6");

    WifiScoreCard mWifiScoreCard;

    @Mock Clock mClock;

    long mMilliSecondsSinceBoot;
    ExtendedWifiInfo mWifiInfo;

    void millisecondsPass(long ms) {
        mMilliSecondsSinceBoot += ms;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(mMilliSecondsSinceBoot);
        when(mClock.getWallClockMillis()).thenReturn(mMilliSecondsSinceBoot + 1_500_000_000_000L);
    }

    void secondsPass(long s) {
        millisecondsPass(s * 1000);
    }

    /**
     * Sets up for unit test
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mWifiInfo = new ExtendedWifiInfo();
        mWifiInfo.setSSID(TEST_SSID_1);
        millisecondsPass(0);
        mWifiScoreCard = new WifiScoreCard(mClock);
    }

    /**
     * Test generic update
     */
    @Test
    public void testUpdate() throws Exception {
        mWifiInfo.setSSID(TEST_SSID_1);
        mWifiInfo.setBSSID(TEST_BSSID_1.toString());

        mWifiScoreCard.noteIpConfiguration(mWifiInfo);

        WifiScoreCard.PerBssid perBssid = mWifiScoreCard.fetchByBssid(TEST_BSSID_1);
        assertTrue(perBssid.ap.getId() > 0);

        mWifiInfo.setBSSID(TEST_BSSID_2.toString());

        mWifiScoreCard.noteIpConfiguration(mWifiInfo);

        assertEquals(perBssid, mWifiScoreCard.fetchByBssid(TEST_BSSID_1));
        assertNotEquals(perBssid.ap.getId(), mWifiScoreCard.fetchByBssid(TEST_BSSID_2).ap.getId());
    }
}
