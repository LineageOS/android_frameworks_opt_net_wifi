/*
 * Copyright 2018 The Android Open Source Project
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

import static android.net.wifi.WifiInfo.DEFAULT_MAC_ADDRESS;

import android.annotation.NonNull;
import android.net.MacAddress;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.WifiScoreCardProto.AccessPoint;
import com.android.server.wifi.WifiScoreCardProto.AccessPointOrBuilder;
import com.android.server.wifi.WifiScoreCardProto.Event;

import com.google.protobuf.ByteString;

import java.util.Map;

/**
 * Retains statistical information about the performance of various
 * access points, as experienced by this device.
 *
 * The purpose is to better inform future network selection and switching
 * by this device.
 */
public class WifiScoreCard {

    private static final String TAG = "WifiScoreCard";

    private final Clock mClock;

    private long mConnectionAttemptStartTimeMills = 0;

    /**
     * @param clock is the time source
     */
    public WifiScoreCard(Clock clock) {
        mClock = clock;
    }

    /**
     * Updates the score card using relevant parts of WifiInfo
     *
     * @param wifiInfo object holding relevant values.
     */
    private void update(WifiScoreCardProto.Event event, ExtendedWifiInfo wifiInfo) {
        long now = mClock.getWallClockMillis();
        // TODO(b/112196799) update the score card
        AccessPointOrBuilder ap = lookupBssid(wifiInfo.getSSID(), wifiInfo.getBSSID()).ap;
    }

    /**
     * Updates the score card after a signal poll
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteSignalPoll(ExtendedWifiInfo wifiInfo) {
        update(Event.SIGNAL_POLL, wifiInfo);
        // TODO(b/112196799) capture state for LAST_POLL_BEFORE_ROAM
        // TODO(b/112196799) check for FIRST_POLL_AFTER_CONNECTION
    }

    /**
     * Updates the score card after IP configuration
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteIpConfiguration(ExtendedWifiInfo wifiInfo) {
        update(Event.IP_CONFIGURATION_SUCCESS, wifiInfo);
    }

    /**
     * Records the start of a connection attempt
     *
     * @param wifiInfo may have state about an existing connection
     */
    public void noteConnectionAttempt(ExtendedWifiInfo wifiInfo) {
        mConnectionAttemptStartTimeMills = mClock.getWallClockMillis();
        // TODO(b/112196799) If currently connected, record any needed state
    }

    /**
     * Updates the score card after a failed connection attempt
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteConnectionFailure(ExtendedWifiInfo wifiInfo) {
        update(Event.CONNECTION_FAILURE, wifiInfo);
    }

    /**
     * Updates the score card after network reachability failure
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteIpReachabilityLost(ExtendedWifiInfo wifiInfo) {
        update(Event.IP_REACHABILITY_LOST, wifiInfo);
        // TODO(b/112196799) Check for roam failure here
    }

    /**
     * Updates the score card after a roam
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteRoam(ExtendedWifiInfo wifiInfo) {
        // TODO(b/112196799) Defer recording success until we believe it works
        update(Event.ROAM_SUCCESS, wifiInfo);
    }

    /**
     * Updates the score card after wifi is disabled
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteWifiDisabled(ExtendedWifiInfo wifiInfo) {
        update(Event.WIFI_DISABLED, wifiInfo);
    }

    private int mNextId = 0;
    final class PerBssid {
        public final String ssid;
        public final MacAddress bssid;
        public final AccessPointOrBuilder ap;
        PerBssid(String ssid, MacAddress bssid) {
            this.ssid = ssid;
            this.bssid = bssid;
            this.ap = AccessPoint.newBuilder()
                    .setId(mNextId++)
                    .setBssid(ByteString.copyFrom(bssid.toByteArray()));
        }
    }

    // Create mDummyPerBssid here so it gets an id of 0. This is returned when the
    // BSSID is not available, for instance when we are not associated.
    private final PerBssid mDummyPerBssid = new PerBssid("",
            MacAddress.fromString(DEFAULT_MAC_ADDRESS));

    private final Map<MacAddress, PerBssid> mApForBssid = new ArrayMap<>();

    private @NonNull PerBssid lookupBssid(String ssid, String bssid) {
        MacAddress mac;
        if (ssid == null || bssid == null) {
            return mDummyPerBssid;
        }
        try {
            mac = MacAddress.fromString(bssid);
        } catch (IllegalArgumentException e) {
            return mDummyPerBssid;
        }
        PerBssid ans = mApForBssid.get(mac);
        if (ans == null || !ans.ssid.equals(ssid)) {
            ans = new PerBssid(ssid, mac);
            PerBssid old = mApForBssid.put(mac, ans);
            if (old != null) {
                Log.i(TAG, "Discarding stats for score card (ssid changed) ID: " + old.ap.getId());
            }
        }
        return ans;
    }

    @VisibleForTesting
    PerBssid fetchByBssid(MacAddress mac) {
        return mApForBssid.get(mac);
    }

}
