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
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiSsid;
import android.util.Base64;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.WifiScoreCardProto.AccessPoint;
import com.android.server.wifi.WifiScoreCardProto.Event;
import com.android.server.wifi.WifiScoreCardProto.Network;
import com.android.server.wifi.WifiScoreCardProto.NetworkList;
import com.android.server.wifi.WifiScoreCardProto.Signal;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

/**
 * Unit tests for {@link com.android.server.wifi.WifiScoreCard}.
 */
@SmallTest
public class WifiScoreCardTest {

    static final WifiSsid TEST_SSID_1 = WifiSsid.createFromAsciiEncoded("Joe's Place");
    static final WifiSsid TEST_SSID_2 = WifiSsid.createFromAsciiEncoded("Poe's Raven");

    static final MacAddress TEST_BSSID_1 = MacAddress.fromString("aa:bb:cc:dd:ee:ff");
    static final MacAddress TEST_BSSID_2 = MacAddress.fromString("1:2:3:4:5:6");

    static final double TOL = 1e-6; // for assertEquals(double, double, tolerance)

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
        mMilliSecondsSinceBoot = 0;
        mWifiInfo = new ExtendedWifiInfo();
        mWifiInfo.setSSID(TEST_SSID_1);
        mWifiInfo.setBSSID(TEST_BSSID_1.toString());
        millisecondsPass(0);
        mWifiScoreCard = new WifiScoreCard(mClock, "some seed");
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
        assertTrue(perBssid.id > 0);

        mWifiInfo.setBSSID(TEST_BSSID_2.toString());

        mWifiScoreCard.noteIpConfiguration(mWifiInfo);

        assertEquals(perBssid, mWifiScoreCard.fetchByBssid(TEST_BSSID_1));
        assertNotEquals(perBssid.id, mWifiScoreCard.fetchByBssid(TEST_BSSID_2).id);
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

        // Now verify
        WifiScoreCard.PerBssid perBssid = mWifiScoreCard.fetchByBssid(TEST_BSSID_1);
        // Looking up the same thing twice should yield the same object.
        assertTrue(perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                == perBssid.lookupSignal(Event.SIGNAL_POLL, 5805));
        // Check the rssi statistics for the first channel
        assertEquals(2, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805).rssi.count);
        assertEquals(expectSum, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                .rssi.sum, TOL);
        assertEquals(expectSumSq, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                .rssi.sumOfSquares, TOL);
        assertEquals(-77.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                .rssi.minValue, TOL);
        assertEquals(-55.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                .rssi.maxValue, TOL);
        // Check the rssi statistics for the second channel
        assertEquals(1, perBssid.lookupSignal(Event.SIGNAL_POLL, 5290).rssi.count);
        // Check that the linkspeed was updated
        assertEquals(666.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 5290).linkspeed.sum, TOL);
    }

    /**
     * Statistics on time-to-connect, connection duration
     */
    @Test
    public void testDurationStatistics() throws Exception {
        // Start out disconnected; start connecting
        mWifiInfo.setBSSID(android.net.wifi.WifiInfo.DEFAULT_MAC_ADDRESS);
        mWifiScoreCard.noteConnectionAttempt(mWifiInfo);
        // First poll has a bad RSSI
        millisecondsPass(111);
        mWifiInfo.setBSSID(TEST_BSSID_1.toString());
        mWifiInfo.setFrequency(5805);
        mWifiInfo.setRssi(WifiInfo.INVALID_RSSI);
        // A bit later, connection is complete (up through DHCP)
        millisecondsPass(222);
        mWifiInfo.setRssi(-55);
        mWifiScoreCard.noteIpConfiguration(mWifiInfo);
        millisecondsPass(666);
        // Rssi polls for 99 seconds
        for (int i = 0; i < 99; i += 3) {
            mWifiScoreCard.noteSignalPoll(mWifiInfo);
            secondsPass(3);
        }
        // Make sure our simulated time adds up
        assertEquals(mMilliSecondsSinceBoot, 99999);
        // A long while later, wifi is toggled off
        secondsPass(9900);
        mWifiScoreCard.noteWifiDisabled(mWifiInfo);


        // Now verify
        WifiScoreCard.PerBssid perBssid = mWifiScoreCard.fetchByBssid(TEST_BSSID_1);
        assertEquals(1, perBssid.lookupSignal(Event.IP_CONFIGURATION_SUCCESS, 5805)
                .elapsedMs.count);
        assertEquals(333.0, perBssid.lookupSignal(Event.IP_CONFIGURATION_SUCCESS, 5805)
                .elapsedMs.sum, TOL);
        assertEquals(9999999.0, perBssid.lookupSignal(Event.WIFI_DISABLED, 5805)
                .elapsedMs.maxValue, TOL);
        assertEquals(999.0,  perBssid.lookupSignal(Event.FIRST_POLL_AFTER_CONNECTION, 5805)
                .elapsedMs.minValue, TOL);
        assertNull(perBssid.lookupSignal(Event.SIGNAL_POLL, 5805).elapsedMs);
    }

    /**
     * Constructs a protobuf form of an example.
     */
    private byte[] makeSerializedAccessPointExample() {
        mWifiScoreCard.noteConnectionAttempt(mWifiInfo);
        millisecondsPass(111);
        mWifiInfo.setRssi(-55);
        mWifiInfo.setFrequency(5805);
        mWifiInfo.setLinkSpeed(384);
        mWifiScoreCard.noteIpConfiguration(mWifiInfo);
        millisecondsPass(888);
        mWifiScoreCard.noteSignalPoll(mWifiInfo);
        millisecondsPass(1000);
        mWifiInfo.setRssi(-44);
        mWifiScoreCard.noteSignalPoll(mWifiInfo);
        WifiScoreCard.PerBssid perBssid = mWifiScoreCard.fetchByBssid(TEST_BSSID_1);
        checkSerializationExample("before serialization", perBssid);
        // Now convert to protobuf form
        byte[] serialized = perBssid.toAccessPoint().toByteArray();
        return serialized;
    }

    /**
     * Checks that the fields of the serialization example are as expected
     */
    private void checkSerializationExample(String diag, WifiScoreCard.PerBssid perBssid) {
        assertEquals(diag, 2, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805).rssi.count);
        assertEquals(diag, -55.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                .rssi.minValue, TOL);
        assertEquals(diag, -44.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                .rssi.maxValue, TOL);
        assertEquals(diag, 384.0, perBssid.lookupSignal(Event.FIRST_POLL_AFTER_CONNECTION, 5805)
                .linkspeed.sum, TOL);
        assertEquals(diag, 111.0, perBssid.lookupSignal(Event.IP_CONFIGURATION_SUCCESS, 5805)
                .elapsedMs.minValue, TOL);
    }

    /**
     * AccessPoint serialization
     */
    @Test
    public void testAccessPointSerialization() throws Exception {
        byte[] serialized = makeSerializedAccessPointExample();

        // Verify by parsing it and checking that we see the expected results
        AccessPoint ap = AccessPoint.parseFrom(serialized);
        assertEquals(3, ap.getEventStatsCount());
        for (Signal signal: ap.getEventStatsList()) {
            assertEquals(5805, signal.getFrequency());
            switch (signal.getEvent()) {
                case IP_CONFIGURATION_SUCCESS:
                    assertEquals(384.0, signal.getLinkspeed().getMaxValue(), TOL);
                    assertEquals(111.0, signal.getElapsedMs().getMinValue(), TOL);
                    break;
                case SIGNAL_POLL:
                    assertEquals(2, signal.getRssi().getCount());
                    break;
                case FIRST_POLL_AFTER_CONNECTION:
                    assertEquals(-55.0, signal.getRssi().getSum(), TOL);
                    break;
                default:
                    fail(signal.getEvent().toString());
            }
        }
    }

    /**
     * Serialization should be reproducable
     */
    @Test
    public void testReproducableSerialization() throws Exception {
        byte[] serialized = makeSerializedAccessPointExample();
        setUp();
        assertArrayEquals(serialized, makeSerializedAccessPointExample());
    }

    /**
     * Deserialization
     */
    @Test
    public void testDeserialization() throws Exception {
        byte[] serialized = makeSerializedAccessPointExample();
        setUp(); // Get back to the initial state

        WifiScoreCard.PerBssid perBssid = mWifiScoreCard.perBssidFromAccessPoint(
                mWifiInfo.getSSID(),
                AccessPoint.parseFrom(serialized));

        // Now verify
        String diag = com.android.server.wifi.util.NativeUtil.hexStringFromByteArray(serialized);
        checkSerializationExample(diag, perBssid);
    }

    /**
     * Serialization of all internally represented networks
     */
    @Test
    public void testNetworksSerialization() throws Exception {
        makeSerializedAccessPointExample();

        byte[] serialized = mWifiScoreCard.getNetworkListByteArray(false);
        byte[] cleaned = mWifiScoreCard.getNetworkListByteArray(true);
        String base64Encoded = mWifiScoreCard.getNetworkListBase64(true);

        setUp(); // Get back to the initial state
        String diag = com.android.server.wifi.util.NativeUtil.hexStringFromByteArray(serialized);
        NetworkList networkList = NetworkList.parseFrom(serialized);
        assertEquals(diag, 1, networkList.getNetworksCount());
        Network network = networkList.getNetworks(0);
        assertEquals(diag, 1, network.getAccessPointsCount());
        AccessPoint accessPoint = network.getAccessPoints(0);
        WifiScoreCard.PerBssid perBssid = mWifiScoreCard.perBssidFromAccessPoint(network.getSsid(),
                accessPoint);
        checkSerializationExample(diag, perBssid);
        // Leaving out the bssids should make the cleaned version shorter.
        assertTrue(cleaned.length < serialized.length);
        // Check the Base64 version
        assertTrue(Arrays.equals(cleaned, Base64.decode(base64Encoded, Base64.DEFAULT)));
    }

}
