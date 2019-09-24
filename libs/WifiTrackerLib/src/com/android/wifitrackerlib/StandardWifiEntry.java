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

import static com.android.wifitrackerlib.Utils.getBestScanResultByLevel;
import static com.android.wifitrackerlib.Utils.getSecurityFromScanResult;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.text.TextUtils;

import androidx.annotation.NonNull;
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

    private final String mKey;
    private final String mSsid;
    private final @Security int mSecurity;

    private int mLevel = WIFI_LEVEL_UNREACHABLE;

    StandardWifiEntry(@NonNull Handler callbackHandler, @NonNull List<ScanResult> scanResults)
            throws IllegalArgumentException {
        super(callbackHandler);

        if (scanResults.isEmpty()) {
            throw new IllegalArgumentException("Cannot construct with empty ScanResult list!");
        }
        final ScanResult firstScan = scanResults.get(0);
        mKey = createStandardWifiEntryKey(firstScan);
        mSsid = firstScan.SSID;
        mSecurity = getSecurityFromScanResult(firstScan);
        updateScanResultInfo(scanResults);
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
    void updateScanResultInfo(@NonNull List<ScanResult> scanResults)
            throws IllegalArgumentException {
        if (scanResults.isEmpty()) {
            throw new IllegalArgumentException("Cannot update with empty ScanResult list!");
        }

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
        mLevel = WifiManager.calculateSignalLevel(bestScanResult.level, WifiManager.RSSI_LEVELS);

        notifyOnUpdated();
    }

    static String createStandardWifiEntryKey(ScanResult scan) {
        return KEY_PREFIX + scan.SSID + "," + getSecurityFromScanResult(scan);
    }
}
