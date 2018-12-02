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

import com.android.server.wifi.WifiScoreCardProto.Event;

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
        mWifiInfo.setBSSID(TEST_BSSID_1.toString());
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

    /**
     * Test rssi poll updates
     */
    @Test
    public void testRssiPollUpdates() throws Exception {
        // Start out on one frequency
        mWifiInfo.setFrequency(5805);
        mWifiInfo.setRssi(-77);
        mWifiInfo.setLinkSpeed(12);
        mWifiScoreCard.noteSignalPoll(mWifiInfo);
        // Switch channels for a bit
        mWifiInfo.setFrequency(5290);
        mWifiInfo.setRssi(-66);
        mWifiInfo.setLinkSpeed(666);
        mWifiScoreCard.noteSignalPoll(mWifiInfo);
        // Back to the first channel
        mWifiInfo.setFrequency(5805);
        mWifiInfo.setRssi(-55);
        mWifiInfo.setLinkSpeed(86);
        mWifiScoreCard.noteSignalPoll(mWifiInfo);

        double expectSum = -77 + -55;
        double expectSumSq = 77 * 77 + 55 * 55;
        final double tol = 1e-6;

        // Now verify
        WifiScoreCard.PerBssid perBssid = mWifiScoreCard.fetchByBssid(TEST_BSSID_1);
        // Looking up the same thing twice should yield the same object.
        assertTrue(perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                == perBssid.lookupSignal(Event.SIGNAL_POLL, 5805));
        // Check the rssi statistics for the first channel
        assertEquals(2, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805).rssi.count);
        assertEquals(expectSum, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                .rssi.sum, tol);
        assertEquals(expectSumSq, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                .rssi.sumOfSquares, tol);
        assertEquals(-77.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                .rssi.minValue, tol);
        assertEquals(-55.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                .rssi.maxValue, tol);
        // Check the rssi statistics for the second channel
        assertEquals(1, perBssid.lookupSignal(Event.SIGNAL_POLL, 5290).rssi.count);
        // Check that the linkspeed was updated
        assertEquals(666.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 5290).linkspeed.sum, tol);
    }
}
