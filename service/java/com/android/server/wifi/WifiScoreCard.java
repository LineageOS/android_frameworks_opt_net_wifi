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
import static android.net.wifi.WifiInfo.INVALID_RSSI;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.MacAddress;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.WifiScoreCardProto.AccessPoint;
import com.android.server.wifi.WifiScoreCardProto.Event;
import com.android.server.wifi.WifiScoreCardProto.Signal;
import com.android.server.wifi.WifiScoreCardProto.UnivariateStatistic;

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

    /**
     * Timestamp of the start of the most recent connection attempt.
     *
     * Based on mClock.getElapsedSinceBootMillis().
     *
     * This is for calculating the time to connect and the duration of the connection.
     * Any negative value means we are not currently connected.
     */
    private long mTsConnectionAttemptStart = TS_NONE;
    private static final long TS_NONE = -1;

    /**
     * Becomes true the first time we see a poll with a valid RSSI in a connection
     */
    private boolean mPolled = false;

    /**
     * @param clock is the time source
     */
    public WifiScoreCard(Clock clock) {
        mClock = clock;
    }

    private void resetConnectionState() {
        mTsConnectionAttemptStart = TS_NONE;
        mPolled = false;
    }

    /**
     * Updates the score card using relevant parts of WifiInfo
     *
     * @param wifiInfo object holding relevant values.
     */
    private void update(WifiScoreCardProto.Event event, ExtendedWifiInfo wifiInfo) {
        PerBssid perBssid = lookupBssid(wifiInfo.getSSID(), wifiInfo.getBSSID());
        perBssid.updateEventStats(event,
                wifiInfo.getFrequency(),
                wifiInfo.getRssi(),
                wifiInfo.getLinkSpeed());
    }

    /**
     * Updates the score card after a signal poll
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteSignalPoll(ExtendedWifiInfo wifiInfo) {
        update(Event.SIGNAL_POLL, wifiInfo);
        if (!mPolled && wifiInfo.getRssi() != INVALID_RSSI) {
            update(Event.FIRST_POLL_AFTER_CONNECTION, wifiInfo);
            mPolled = true;
        }
        // TODO(b/112196799) capture state for LAST_POLL_BEFORE_ROAM
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
        // We may or may not be currently connected. If not, simply record the start.
        // But if we are connected, wrap up the old one first TODO(b/112196799)
        mTsConnectionAttemptStart = mClock.getElapsedSinceBootMillis();
        mPolled = false;
    }

    /**
     * Updates the score card after a failed connection attempt
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteConnectionFailure(ExtendedWifiInfo wifiInfo) {
        update(Event.CONNECTION_FAILURE, wifiInfo);
        resetConnectionState();
    }

    /**
     * Updates the score card after network reachability failure
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteIpReachabilityLost(ExtendedWifiInfo wifiInfo) {
        update(Event.IP_REACHABILITY_LOST, wifiInfo);
        // TODO(b/112196799) Check for roam failure here
        resetConnectionState();
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
        resetConnectionState();
    }

    private int mNextId = 0;
    final class PerBssid {
        public int id;
        public final String ssid;
        public final MacAddress bssid;
        private final Map<Pair<Event, Integer>, PerSignal>
                mSignalForEventAndFrequency = new ArrayMap<>();
        PerBssid(String ssid, MacAddress bssid) {
            this.id = mNextId++;
            this.ssid = ssid;
            this.bssid = bssid;
        }
        PerBssid(String ssid, MacAddress bssid, AccessPoint ap) {
            if (ap.hasId()) {
                this.id = ap.getId();
                if (this.id >= mNextId) {
                    // Caller must be prepared to deal with duplicate ids,
                    // but try here to minimize the possibility
                    mNextId = this.id + 1;
                }
            }
            this.ssid = ssid;
            this.bssid = bssid;
            for (Signal signal: ap.getEventStatsList()) {
                PerSignal perSignal = new PerSignal(signal);
                Pair<Event, Integer> key = new Pair<>(perSignal.event, perSignal.frequency);
                mSignalForEventAndFrequency.put(key, perSignal);
            }
        }
        void updateEventStats(Event event, int frequency, int rssi, int linkspeed) {
            PerSignal perSignal = lookupSignal(event, frequency);
            if (rssi != INVALID_RSSI) {
                perSignal.rssi.update(rssi);
            }
            if (linkspeed > 0) {
                perSignal.linkspeed.update(linkspeed);
            }
            if (perSignal.elapsedMs != null && mTsConnectionAttemptStart > TS_NONE) {
                long millis = mClock.getElapsedSinceBootMillis() - mTsConnectionAttemptStart;
                if (millis >= 0) {
                    perSignal.elapsedMs.update(millis);
                }
            }
        }
        PerSignal lookupSignal(Event event, int frequency) {
            Pair<Event, Integer> key = new Pair<>(event, frequency);
            PerSignal ans = mSignalForEventAndFrequency.get(key);
            if (ans == null) {
                ans = new PerSignal(event, frequency);
                mSignalForEventAndFrequency.put(key, ans);
            }
            return ans;
        }
        AccessPoint toAccessPoint() {
            AccessPoint.Builder builder = AccessPoint.newBuilder();
            builder.setId(id).setBssid(ByteString.copyFrom(bssid.toByteArray()));
            for (PerSignal sig: mSignalForEventAndFrequency.values()) {
                builder.addEventStats(sig.toSignal());
            }
            return builder.build();
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
                Log.i(TAG, "Discarding stats for score card (ssid changed) ID: " + old.id);
            }
        }
        return ans;
    }

    @VisibleForTesting
    PerBssid fetchByBssid(MacAddress mac) {
        return mApForBssid.get(mac);
    }

    @VisibleForTesting
    PerBssid perBssidFromAccessPoint(String ssid, AccessPoint ap) {
        MacAddress bssid = MacAddress.fromBytes(ap.getBssid().toByteArray());
        return new PerBssid(ssid, bssid, ap);
    }

    final class PerSignal {
        public final Event event;
        public final int frequency;
        public final PerUnivariateStatistic rssi;
        public final PerUnivariateStatistic linkspeed;
        @Nullable public final PerUnivariateStatistic elapsedMs;
        PerSignal(Event event, int frequency) {
            this.event = event;
            this.frequency = frequency;
            this.rssi = new PerUnivariateStatistic();
            this.linkspeed = new PerUnivariateStatistic();
            switch (event) {
                case FIRST_POLL_AFTER_CONNECTION:
                case IP_CONFIGURATION_SUCCESS:
                case CONNECTION_FAILURE:
                case WIFI_DISABLED:
                    this.elapsedMs = new PerUnivariateStatistic();
                    break;
                default:
                    this.elapsedMs = null;
                    break;
            }
        }
        PerSignal(Signal signal) {
            this.event = signal.getEvent();
            this.frequency = signal.getFrequency();
            this.rssi = new PerUnivariateStatistic(signal.getRssi());
            this.linkspeed = new PerUnivariateStatistic(signal.getLinkspeed());
            if (signal.hasElapsedMs()) {
                this.elapsedMs = new PerUnivariateStatistic(signal.getElapsedMs());
            } else {
                this.elapsedMs = null;
            }
        }
        Signal toSignal() {
            Signal.Builder builder = Signal.newBuilder();
            builder.setEvent(event)
                    .setFrequency(frequency)
                    .setRssi(rssi.toUnivariateStatistic())
                    .setLinkspeed(linkspeed.toUnivariateStatistic());
            if (elapsedMs != null) {
                builder.setElapsedMs(elapsedMs.toUnivariateStatistic());
            }
            return builder.build();
        }
    }

    final class PerUnivariateStatistic {
        public long count = 0;
        public double sum = 0.0;
        public double sumOfSquares = 0.0;
        public double minValue = Double.POSITIVE_INFINITY;
        public double maxValue = Double.NEGATIVE_INFINITY;
        public double historicalMean = 0.0;
        public double historicalVariance = Double.POSITIVE_INFINITY;
        PerUnivariateStatistic() {}
        PerUnivariateStatistic(UnivariateStatistic stats) {
            if (stats.hasCount()) {
                this.count = stats.getCount();
                this.sum = stats.getSum();
                this.sumOfSquares = stats.getSumOfSquares();
            }
            if (stats.hasMinValue()) {
                this.minValue = stats.getMinValue();
            }
            if (stats.hasMaxValue()) {
                this.maxValue = stats.getMaxValue();
            }
            if (stats.hasHistoricalMean()) {
                this.historicalMean = stats.getHistoricalMean();
            }
            if (stats.hasHistoricalVariance()) {
                this.historicalVariance = stats.getHistoricalVariance();
            }
        }
        void update(double value) {
            count++;
            sum += value;
            sumOfSquares += value * value;
            minValue = Math.min(minValue, value);
            maxValue = Math.max(maxValue, value);
        }
        void age() {
            //TODO  Fold the current stats into the historical stats
        }
        UnivariateStatistic toUnivariateStatistic() {
            UnivariateStatistic.Builder builder = UnivariateStatistic.newBuilder();
            if (count != 0) {
                builder.setCount(count)
                        .setSum(sum)
                        .setSumOfSquares(sumOfSquares)
                        .setMinValue(minValue)
                        .setMaxValue(maxValue);
            }
            if (historicalVariance < Double.POSITIVE_INFINITY) {
                builder.setHistoricalMean(historicalMean)
                        .setHistoricalVariance(historicalVariance);
            }
            return builder.build();
        }
    }
}
