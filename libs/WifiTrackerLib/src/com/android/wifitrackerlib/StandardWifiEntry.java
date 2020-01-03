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

import static android.net.wifi.WifiInfo.INVALID_RSSI;
import static android.net.wifi.WifiInfo.removeDoubleQuotes;

import static androidx.core.util.Preconditions.checkNotNull;

import static com.android.wifitrackerlib.Utils.getBestScanResultByLevel;
import static com.android.wifitrackerlib.Utils.getSecurityFromScanResult;
import static com.android.wifitrackerlib.Utils.getSecurityFromWifiConfiguration;

import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

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
    @Nullable private NetworkInfo mNetworkInfo;
    @Nullable private WifiInfo mWifiInfo;
    @Nullable private ConnectCallback mConnectCallback;
    @Nullable private DisconnectCallback mDisconnectCallback;
    @Nullable private ForgetCallback mForgetCallback;
    private boolean mCalledConnect = false;
    private boolean mCalledDisconnect = false;

    private int mLevel = WIFI_LEVEL_UNREACHABLE;

    StandardWifiEntry(@NonNull Handler callbackHandler, @NonNull List<ScanResult> scanResults,
            @NonNull WifiManager wifiManager) throws IllegalArgumentException {
        super(callbackHandler, false /* forSavedNetworksPage */, wifiManager);

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

    StandardWifiEntry(@NonNull Handler callbackHandler, @NonNull WifiConfiguration config,
            @NonNull WifiManager wifiManager) throws IllegalArgumentException {
        super(callbackHandler, true /* forSavedNetworksPage */, wifiManager);

        checkNotNull(config, "Cannot construct with null config!");
        checkNotNull(config.SSID, "Supplied config must have an SSID!");

        mKey = wifiConfigToStandardWifiEntryKey(config);
        mSsid = removeDoubleQuotes(config.SSID);
        mSecurity = getSecurityFromWifiConfiguration(config);
        mWifiConfig = config;
    }

    StandardWifiEntry(@NonNull Handler callbackHandler, @NonNull String key,
            @NonNull WifiManager wifiManager) {
        // TODO: second argument (isSaved = false) is bogus in this context
        super(callbackHandler, false, wifiManager);

        if (!key.startsWith(KEY_PREFIX)) {
            throw new IllegalArgumentException("Key does not start with correct prefix!");
        }
        mKey = key;
        try {
            final int securityDelimiter = key.lastIndexOf(",");
            mSsid = key.substring(KEY_PREFIX.length(), securityDelimiter);
            mSecurity = Integer.valueOf(key.substring(securityDelimiter + 1));
        } catch (StringIndexOutOfBoundsException | NumberFormatException e) {
            throw new IllegalArgumentException("Malformed key: " + key);
        }
    }

    @Override
    public String getKey() {
        return mKey;
    }

    @Override
    @ConnectedState
    public int getConnectedState() {
        if (mNetworkInfo == null) {
            return CONNECTED_STATE_DISCONNECTED;
        }

        switch (mNetworkInfo.getDetailedState()) {
            case SCANNING:
            case CONNECTING:
            case AUTHENTICATING:
            case OBTAINING_IPADDR:
            case VERIFYING_POOR_LINK:
            case CAPTIVE_PORTAL_CHECK:
                return CONNECTED_STATE_CONNECTING;
            case CONNECTED:
                return CONNECTED_STATE_CONNECTED;
            default:
                return CONNECTED_STATE_DISCONNECTED;
        }
    }

    @Override
    public String getTitle() {
        return mSsid;
    }

    @Override
    public String getSummary() {
        // TODO(b/70983952): Fill this method in and replace placeholders with resource strings
        StringJoiner sj = new StringJoiner(" / ");
        // Placeholder text
        if (getConnectedState() == CONNECTED_STATE_CONNECTING) sj.add("Connecting...");
        if (getConnectedState() == CONNECTED_STATE_CONNECTED) sj.add("Connected");
        if (isSaved() && !mForSavedNetworksPage) sj.add("Saved");
        return sj.toString();
    }

    @Override
    public int getLevel() {
        return mLevel;
    }

    @Override
    public String getSsid() {
        return mSsid;
    }

    @Override
    @Security
    public int getSecurity() {
        // TODO(b/70983952): Fill this method in
        return mSecurity;
    }

    @Override
    public String getMacAddress() {
        if (mWifiConfig == null || getPrivacy() != PRIVACY_RANDOMIZED_MAC) {
            final String[] factoryMacs = mWifiManager.getFactoryMacAddresses();
            if (factoryMacs.length > 0) {
                return factoryMacs[0];
            } else {
                return null;
            }
        } else {
            return mWifiConfig.getRandomizedMacAddress().toString();
        }
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
    public boolean isSubscription() {
        return false;
    }

    @Override
    public WifiConfiguration getWifiConfiguration() {
        return mWifiConfig;
    }

    @Override
    public ConnectedInfo getConnectedInfo() {
        // TODO(b/70983952): Fill this method in
        return null;
    }

    @Override
    public boolean canConnect() {
        return mLevel != WIFI_LEVEL_UNREACHABLE
                && getConnectedState() == CONNECTED_STATE_DISCONNECTED;
    }

    @Override
    public void connect(@Nullable ConnectCallback callback) {
        mConnectCallback = callback;
        if (mWifiConfig == null) {
            // Unsaved network
            if (mSecurity == SECURITY_NONE
                    || mSecurity == SECURITY_OWE
                    || mSecurity == SECURITY_OWE_TRANSITION) {
                // Open network
                final WifiConfiguration connectConfig = new WifiConfiguration();
                connectConfig.SSID = "\"" + mSsid + "\"";

                if (mSecurity == SECURITY_OWE
                        || (mSecurity == SECURITY_OWE_TRANSITION
                        && mWifiManager.isEnhancedOpenSupported())) {
                    // Use OWE if possible
                    connectConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.OWE);
                    connectConfig.requirePMF = true;
                } else {
                    connectConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                }
                mWifiManager.connect(connectConfig, new ConnectActionListener());
            } else {
                // Secure network
                if (callback != null) {
                    mCallbackHandler.post(() ->
                            callback.onConnectResult(
                                    ConnectCallback.CONNECT_STATUS_FAILURE_NO_CONFIG));
                }
            }
        } else {
            // Saved network
            mWifiManager.connect(mWifiConfig.networkId, new ConnectActionListener());
        }
    }

    @Override
    public boolean canDisconnect() {
        return getConnectedState() == CONNECTED_STATE_CONNECTED;
    }

    @Override
    public void disconnect(@Nullable DisconnectCallback callback) {
        if (canDisconnect()) {
            mCalledDisconnect = true;
            mDisconnectCallback = callback;
            mCallbackHandler.postDelayed(() -> {
                if (callback != null && mCalledDisconnect) {
                    callback.onDisconnectResult(
                            DisconnectCallback.DISCONNECT_STATUS_FAILURE_UNKNOWN);
                }
            }, 10_000 /* delayMillis */);
            mWifiManager.disconnect();
        }
    }

    @Override
    public boolean canForget() {
        return isSaved();
    }

    @Override
    public void forget(@Nullable ForgetCallback callback) {
        if (mWifiConfig != null) {
            mForgetCallback = callback;
            mWifiManager.forget(mWifiConfig.networkId, new ForgetActionListener());
        }
    }

    public boolean canSignIn() {
        // TODO(b/70983952): Fill this method in
        return false;
    }

    @Override
    public void signIn(@Nullable SignInCallback callback) {
        // TODO(b/70983952): Fill this method in
    }

    /**
     * Returns whether the network can be shared via QR code.
     * See https://github.com/zxing/zxing/wiki/Barcode-Contents#wi-fi-network-config-android-ios-11
     */
    @Override
    public boolean canShare() {
        if (!isSaved()) {
            return false;
        }

        switch (mSecurity) {
            case SECURITY_PSK:
            case SECURITY_WEP:
            case SECURITY_NONE:
            case SECURITY_SAE:
            case SECURITY_OWE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns whether the user can use Easy Connect to onboard a device to the network.
     * See https://www.wi-fi.org/discover-wi-fi/wi-fi-easy-connect
     */
    @Override
    public boolean canEasyConnect() {
        if (!isSaved()) {
            return false;
        }

        if (!mWifiManager.isEasyConnectSupported()) {
            return false;
        }

        // DPP 1.0 only supports SAE and PSK.
        switch (mSecurity) {
            case SECURITY_SAE:
            case SECURITY_PSK:
                return true;
            default:
                return false;
        }
    }

    @Override
    public String getQrCodeString() {
        // TODO(b/70983952): Fill this method in
        return null;
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
        if (mWifiConfig != null) {
            final int meteredOverride = mWifiConfig.meteredOverride;
            if (meteredOverride == WifiConfiguration.METERED_OVERRIDE_NONE) {
                return METERED_CHOICE_AUTO;
            } else if (meteredOverride == WifiConfiguration.METERED_OVERRIDE_METERED) {
                return METERED_CHOICE_METERED;
            } else if (meteredOverride == WifiConfiguration.METERED_OVERRIDE_NOT_METERED) {
                return METERED_CHOICE_UNMETERED;
            }
        }
        return METERED_CHOICE_UNKNOWN;
    }

    @Override
    public boolean canSetMeteredChoice() {
        return isSaved();
    }

    @Override
    public void setMeteredChoice(int meteredChoice) {
        if (mWifiConfig == null) {
            return;
        }

        final WifiConfiguration saveConfig = new WifiConfiguration(mWifiConfig);
        if (meteredChoice == METERED_CHOICE_AUTO) {
            saveConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_NONE;
        } else if (meteredChoice == METERED_CHOICE_METERED) {
            saveConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_METERED;
        } else if (meteredChoice == METERED_CHOICE_UNMETERED) {
            saveConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_NOT_METERED;
        }
        mWifiManager.save(saveConfig, null /* listener */);
    }

    @Override
    public boolean canSetPrivacy() {
        // TODO(b/70983952): Fill this method in
        return false;
    }

    @Override
    @Privacy
    public int getPrivacy() {
        if (mWifiConfig == null) {
            return PRIVACY_UNKNOWN;
        }

        if (mWifiConfig.macRandomizationSetting == WifiConfiguration.RANDOMIZATION_NONE) {
            return PRIVACY_DEVICE_MAC;
        } else {
            return PRIVACY_RANDOMIZED_MAC;
        }
    }

    @Override
    public void setPrivacy(int privacy) {
        // TODO(b/70983952): Fill this method in
    }

    @Override
    public boolean isAutoJoinEnabled() {
        if (mWifiConfig == null) {
            return false;
        }

        return mWifiConfig.allowAutojoin;
    }

    @Override
    public boolean canSetAutoJoinEnabled() {
        return isSaved();
    }

    @Override
    public void setAutoJoinEnabled(boolean enabled) {
        mWifiManager.allowAutojoin(mWifiConfig.networkId, enabled);
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
            mLevel = mWifiManager.calculateSignalLevel(bestScanResult.level);
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

    /**
     * Updates information regarding the current network connection. If the supplied WifiInfo and
     * NetworkInfo do not represent this WifiEntry, then the WifiEntry will update to be
     * unconnected.
     */
    @WorkerThread
    void updateConnectionInfo(@Nullable WifiInfo wifiInfo, @Nullable NetworkInfo networkInfo) {
        if (mWifiConfig != null && wifiInfo != null
                && mWifiConfig.networkId == wifiInfo.getNetworkId()) {
            mNetworkInfo = networkInfo;
            mWifiInfo = wifiInfo;
            final int wifiInfoRssi = wifiInfo.getRssi();
            if (wifiInfoRssi != INVALID_RSSI) {
                mLevel = mWifiManager.calculateSignalLevel(wifiInfoRssi);
            }
            if (mCalledConnect && getConnectedState() == CONNECTED_STATE_CONNECTED) {
                mCalledConnect = false;
                mCallbackHandler.post(() -> {
                    if (mConnectCallback != null) {
                        mConnectCallback.onConnectResult(ConnectCallback.CONNECT_STATUS_SUCCESS);
                    }
                });
            }
        } else {
            mNetworkInfo = null;
        }
        if (mCalledDisconnect && getConnectedState() == CONNECTED_STATE_DISCONNECTED) {
            mCalledDisconnect = false;
            mCallbackHandler.post(() -> {
                if (mDisconnectCallback != null) {
                    mDisconnectCallback.onDisconnectResult(
                            DisconnectCallback.DISCONNECT_STATUS_SUCCESS);
                }
            });
        }
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

    private class ConnectActionListener implements WifiManager.ActionListener {
        @Override
        public void onSuccess() {
            mCalledConnect = true;
            // If we aren't connected to the network after 10 seconds, trigger the failure callback
            mCallbackHandler.postDelayed(() -> {
                if (mConnectCallback != null && mCalledConnect
                        && getConnectedState() == CONNECTED_STATE_DISCONNECTED) {
                    mConnectCallback.onConnectResult(
                            ConnectCallback.CONNECT_STATUS_FAILURE_UNKNOWN);
                    mCalledConnect = false;
                }
            }, 10_000 /* delayMillis */);
        }

        @Override
        public void onFailure(int i) {
            mCallbackHandler.post(() -> {
                if (mConnectCallback != null) {
                    mConnectCallback.onConnectResult(
                            mConnectCallback.CONNECT_STATUS_FAILURE_UNKNOWN);
                }
            });
        }
    }

    class ForgetActionListener implements WifiManager.ActionListener {
        @Override
        public void onSuccess() {
            mCallbackHandler.post(() -> {
                if (mForgetCallback != null) {
                    mForgetCallback.onForgetResult(ForgetCallback.FORGET_STATUS_SUCCESS);
                }
            });
        }

        @Override
        public void onFailure(int i) {
            mCallbackHandler.post(() -> {
                if (mForgetCallback != null) {
                    mForgetCallback.onForgetResult(ForgetCallback.FORGET_STATUS_FAILURE_UNKNOWN);
                }
            });
        }
    }
}
