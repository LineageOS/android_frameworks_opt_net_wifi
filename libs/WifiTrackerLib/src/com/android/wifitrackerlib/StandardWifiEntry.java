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

package com.android.wifitrackerlib;

import static android.net.wifi.WifiInfo.removeDoubleQuotes;

import static androidx.core.util.Preconditions.checkNotNull;

import static com.android.wifitrackerlib.Utils.getBestScanResultByLevel;
import static com.android.wifitrackerlib.Utils.getSecurityFromScanResult;
import static com.android.wifitrackerlib.Utils.getSecurityFromWifiConfiguration;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.List;

/**
 * WifiEntry representation of a logical Wi-Fi network, uniquely identified by SSID and security.
 *
 * This type of WifiEntry can represent both open and saved networks.
 */
class StandardWifiEntry extends WifiEntry {
    static final String KEY_PREFIX = "StandardWifiEntry:";

    private final List<ScanResult> mCurrentScanResults = new ArrayList<>();

    @NonNull private final String mKey;
    @NonNull private final String mSsid;
    private final @Security int mSecurity;
    @Nullable private WifiConfiguration mWifiConfig;

    private int mLevel = WIFI_LEVEL_UNREACHABLE;

    StandardWifiEntry(@NonNull Handler callbackHandler, @NonNull List<ScanResult> scanResults)
            throws IllegalArgumentException {
        super(callbackHandler, false /* forSavedNetworksPage */);

        checkNotNull(scanResults, "Cannot construct with null ScanResult list!");
        if (scanResults.isEmpty()) {
            throw new IllegalArgumentException("Cannot construct with empty ScanResult list!");
        }
        final ScanResult firstScan = scanResults.get(0);
        mKey = scanResultToStandardWifiEntryKey(firstScan);
        mSsid = firstScan.SSID;
        mSecurity = getSecurityFromScanResult(firstScan);
        updateScanResultInfo(scanResults);
    }

    StandardWifiEntry(@NonNull Handler callbackHandler, @NonNull WifiConfiguration config)
            throws IllegalArgumentException {
        super(callbackHandler, true /* forSavedNetworksPage */);

        checkNotNull(config, "Cannot construct with null config!");
        checkNotNull(config.SSID, "Supplied config must have an SSID!");

        mKey = wifiConfigToStandardWifiEntryKey(config);
        mSsid = removeDoubleQuotes(config.SSID);
        mSecurity = getSecurityFromWifiConfiguration(config);
        mWifiConfig = config;
    }

    @Override
    public String getKey() {
        return mKey;
    }

    @Override
    @ConnectedState
    public int getConnectedState() {
        // TODO(b/70983952): Fill this method in
        return CONNECTED_STATE_DISCONNECTED;
    }

    @Override
    public String getTitle() {
        return mSsid;
    }

    @Override
    public String getSummary() {
        // TODO(b/70983952): Fill this method in
        if (mForSavedNetworksPage) return null;

        if (isSaved()) return "Saved"; // Placeholder for visual verification
        return null;
    }

    @Override
    public int getLevel() {
        return mLevel;
    }

    @Override
    @Security
    public int getSecurity() {
        // TODO(b/70983952): Fill this method in
        return mSecurity;
    }

    @Override
    public boolean isMetered() {
        // TODO(b/70983952): Fill this method in
        return false;
    }

    @Override
    public boolean isSaved() {
        return mWifiConfig != null;
    }

    @Override
    public ConnectedInfo getConnectedInfo() {
        // TODO(b/70983952): Fill this method in
        return null;
    }

    @Override
    public boolean canConnect() {
        // TODO(b/70983952): Fill this method in
        return false;
    }

    @Override
    public void connect() {
        // TODO(b/70983952): Fill this method in
    }

    @Override
    public boolean canDisconnect() {
        // TODO(b/70983952): Fill this method in
        return false;
    }

    @Override
    public void disconnect() {
        // TODO(b/70983952): Fill this method in
    }

    @Override
    public boolean canForget() {
        // TODO(b/70983952): Fill this method in
        return false;
    }

    @Override
    public void forget() {
        // TODO(b/70983952): Fill this method in
    }

    @Override
    public boolean canSetPassword() {
        // TODO(b/70983952): Fill this method in
        return false;
    }

    @Override
    public void setPassword(@NonNull String password) {
        // TODO(b/70983952): Fill this method in
    }

    @Override
    @MeteredChoice
    public int getMeteredChoice() {
        // TODO(b/70983952): Fill this method in
        return METERED_CHOICE_UNMETERED;
    }

    @Override
    public boolean canSetMeteredChoice() {
        // TODO(b/70983952): Fill this method in
        return false;
    }

    @Override
    public void setMeteredChoice(int meteredChoice) {
        // TODO(b/70983952): Fill this method in
    }

    @Override
    public boolean canSetPrivacy() {
        // TODO(b/70983952): Fill this method in
        return false;
    }

    @Override
    @Privacy
    public int getPrivacy() {
        // TODO(b/70983952): Fill this method in
        return PRIVACY_RANDOMIZED_MAC;
    }

    @Override
    public void setPrivacy(int privacy) {
        // TODO(b/70983952): Fill this method in
    }

    @Override
    public boolean isAutoJoinEnabled() {
        // TODO(b/70983952): Fill this method in
        return true;
    }

    @Override
    public boolean canSetAutoJoinEnabled() {
        // TODO(b/70983952): Fill this method in
        return false;
    }

    @Override
    public void setAutoJoinEnabled(boolean enabled) {
        // TODO(b/70983952): Fill this method in
    }

    @Override
    public ProxySettings getProxySettings() {
        // TODO(b/70983952): Fill this method in
        return null;
    }

    @Override
    public boolean canSetProxySettings() {
        // TODO(b/70983952): Fill this method in
        return false;
    }

    @Override
    public void setProxySettings(@NonNull ProxySettings proxySettings) {
        // TODO(b/70983952): Fill this method in
    }

    @Override
    public IpSettings getIpSettings() {
        // TODO(b/70983952): Fill this method in
        return null;
    }

    @Override
    public boolean canSetIpSettings() {
        // TODO(b/70983952): Fill this method in
        return false;
    }

    @Override
    public void setIpSettings(@NonNull IpSettings ipSettings) {
        // TODO(b/70983952): Fill this method in
    }

    @WorkerThread
    void updateScanResultInfo(@Nullable List<ScanResult> scanResults)
            throws IllegalArgumentException {
        if (scanResults == null) scanResults = new ArrayList<>();

        for (ScanResult result : scanResults) {
            if (!TextUtils.equals(result.SSID, mSsid)) {
                throw new IllegalArgumentException(
                        "Attempted to update with wrong SSID! Expected: "
                                + mSsid + ", Actual: " + result.SSID + ", ScanResult: " + result);
            }
            int security = getSecurityFromScanResult(result);
            if (security != mSecurity) {
                throw new IllegalArgumentException(
                        "Attempted to update with wrong security type! Expected: "
                        + mSecurity + ", Actual: " + security + ", ScanResult: " + result);
            }
        }

        mCurrentScanResults.clear();
        mCurrentScanResults.addAll(scanResults);

        final ScanResult bestScanResult = getBestScanResultByLevel(mCurrentScanResults);
        if (bestScanResult == null) {
            mLevel = WIFI_LEVEL_UNREACHABLE;
        } else {
            mLevel = WifiManager.calculateSignalLevel(
                    bestScanResult.level, WifiManager.RSSI_LEVELS);
        }

        notifyOnUpdated();
    }

    @WorkerThread
    void updateConfig(@Nullable WifiConfiguration wifiConfig) throws IllegalArgumentException {
        if (wifiConfig != null) {
            if (!TextUtils.equals(mSsid, removeDoubleQuotes(wifiConfig.SSID))) {
                throw new IllegalArgumentException(
                        "Attempted to update with wrong SSID!"
                                + " Expected: " + mSsid
                                + ", Actual: " + removeDoubleQuotes(wifiConfig.SSID)
                                + ", Config: " + wifiConfig);
            }
            if (mSecurity != getSecurityFromWifiConfiguration(wifiConfig)) {
                throw new IllegalArgumentException(
                        "Attempted to update with wrong security!"
                                + " Expected: " + mSsid
                                + ", Actual: " + getSecurityFromWifiConfiguration(wifiConfig)
                                + ", Config: " + wifiConfig);
            }
        }

        mWifiConfig = wifiConfig;
        notifyOnUpdated();
    }

    @NonNull
    static String scanResultToStandardWifiEntryKey(@NonNull ScanResult scan) {
        checkNotNull(scan, "Cannot create key with null scan result!");
        return KEY_PREFIX + scan.SSID + "," + getSecurityFromScanResult(scan);
    }

    @NonNull
    static String wifiConfigToStandardWifiEntryKey(@NonNull WifiConfiguration config) {
        checkNotNull(config, "Cannot create key with null config!");
        checkNotNull(config.SSID, "Cannot create key with null SSID in config!");
        return KEY_PREFIX + removeDoubleQuotes(config.SSID) + ","
                + getSecurityFromWifiConfiguration(config);
    }
}
