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

import static com.android.wifitrackerlib.Utils.getAppLabel;
import static com.android.wifitrackerlib.Utils.getAppLabelForSavedNetwork;
import static com.android.wifitrackerlib.Utils.getAutoConnectDescription;
import static com.android.wifitrackerlib.Utils.getBestScanResultByLevel;
import static com.android.wifitrackerlib.Utils.getMeteredDescription;
import static com.android.wifitrackerlib.Utils.getSecurityFromScanResult;
import static com.android.wifitrackerlib.Utils.getSecurityFromWifiConfiguration;
import static com.android.wifitrackerlib.Utils.getSpeedDescription;
import static com.android.wifitrackerlib.Utils.getVerboseLoggingDescription;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkScoreManager;
import android.net.NetworkScorerAppData;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;

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
    @NonNull private final Context mContext;
    private final @Security int mSecurity;
    @Nullable private WifiConfiguration mWifiConfig;
    @Nullable private ConnectCallback mConnectCallback;
    @Nullable private DisconnectCallback mDisconnectCallback;
    @Nullable private ForgetCallback mForgetCallback;
    @Nullable private String mRecommendationServiceLabel;

    StandardWifiEntry(@NonNull Context context, @NonNull Handler callbackHandler,
            @NonNull List<ScanResult> scanResults,
            @NonNull WifiManager wifiManager) throws IllegalArgumentException {
        super(callbackHandler, false /* forSavedNetworksPage */, wifiManager);

        checkNotNull(scanResults, "Cannot construct with null ScanResult list!");

        mContext = context;
        if (scanResults.isEmpty()) {
            throw new IllegalArgumentException("Cannot construct with empty ScanResult list!");
        }
        final ScanResult firstScan = scanResults.get(0);
        mKey = scanResultToStandardWifiEntryKey(firstScan);
        mSsid = firstScan.SSID;
        mSecurity = getSecurityFromScanResult(firstScan);
        updateScanResultInfo(scanResults);
        updateRecommendationServiceLabel();
    }

    StandardWifiEntry(@NonNull Context context, @NonNull Handler callbackHandler,
            @NonNull WifiConfiguration config,
            @NonNull WifiManager wifiManager) throws IllegalArgumentException {
        super(callbackHandler, true /* forSavedNetworksPage */, wifiManager);

        checkNotNull(config, "Cannot construct with null config!");
        checkNotNull(config.SSID, "Supplied config must have an SSID!");

        mContext = context;
        mKey = wifiConfigToStandardWifiEntryKey(config);
        mSsid = removeDoubleQuotes(config.SSID);
        mSecurity = getSecurityFromWifiConfiguration(config);
        mWifiConfig = config;
        updateRecommendationServiceLabel();
    }

    StandardWifiEntry(@NonNull Context context, @NonNull Handler callbackHandler,
            @NonNull String key, @NonNull WifiManager wifiManager) {
        // TODO: second argument (isSaved = false) is bogus in this context
        super(callbackHandler, false, wifiManager);

        if (!key.startsWith(KEY_PREFIX)) {
            throw new IllegalArgumentException("Key does not start with correct prefix!");
        }
        mContext = context;
        mKey = key;
        try {
            final int securityDelimiter = key.lastIndexOf(",");
            mSsid = key.substring(KEY_PREFIX.length(), securityDelimiter);
            mSecurity = Integer.valueOf(key.substring(securityDelimiter + 1));
        } catch (StringIndexOutOfBoundsException | NumberFormatException e) {
            throw new IllegalArgumentException("Malformed key: " + key);
        }
        updateRecommendationServiceLabel();
    }

    @Override
    public String getKey() {
        return mKey;
    }

    @Override
    public String getTitle() {
        return mSsid;
    }

    @Override
    public String getSummary() {
        return getSummary(true /* concise */);
    }

    @Override
    public String getSummary(boolean concise) {
        StringJoiner sj = new StringJoiner(mContext.getString(R.string.summary_separator));

        final String speedDescription = getSpeedDescription(mContext, this);
        if (!TextUtils.isEmpty(speedDescription)) {
            sj.add(speedDescription);
        }

        if (!concise && mForSavedNetworksPage && isSaved()) {
            final CharSequence appLabel = getAppLabelForSavedNetwork(mContext, this);
            if (!TextUtils.isEmpty(appLabel)) {
                sj.add(mContext.getString(R.string.saved_network, appLabel));
            }
        }

        if (getConnectedState() == CONNECTED_STATE_DISCONNECTED) {
            String disconnectDescription = getDisconnectedStateDescription();
            if (TextUtils.isEmpty(disconnectDescription)) {
                if (concise) {
                    sj.add(mContext.getString(R.string.wifi_disconnected));
                } else if (!mForSavedNetworksPage && isSaved()) {
                    sj.add(mContext.getString(R.string.wifi_remembered));
                }
            } else {
                sj.add(disconnectDescription);
            }
        } else {
            final String connectDescription = getConnectStateDescription();
            if (!TextUtils.isEmpty(connectDescription)) {
                sj.add(connectDescription);
            }
        }

        final String autoConnectDescription = getAutoConnectDescription(mContext, this);
        if (!TextUtils.isEmpty(autoConnectDescription)) {
            sj.add(autoConnectDescription);
        }

        final String meteredDescription = getMeteredDescription(mContext, this);
        if (!TextUtils.isEmpty(meteredDescription)) {
            sj.add(meteredDescription);
        }

        if (!concise) {
            final String verboseLoggingDescription = getVerboseLoggingDescription(this);
            if (!TextUtils.isEmpty(verboseLoggingDescription)) {
                sj.add(verboseLoggingDescription);
            }
        }

        return sj.toString();
    }

    private String getConnectStateDescription() {
        if (getConnectedState() == CONNECTED_STATE_CONNECTED) {
            if (!isSaved()) {
                // For ephemeral networks.
                final String suggestionOrSpecifierPackageName = mWifiInfo != null
                        ? mWifiInfo.getAppPackageName() : null;
                if (!TextUtils.isEmpty(suggestionOrSpecifierPackageName)) {
                    return mContext.getString(R.string.connected_via_app,
                            getAppLabel(mContext, suggestionOrSpecifierPackageName));
                }

                // Special case for connected + ephemeral networks.
                if (!TextUtils.isEmpty(mRecommendationServiceLabel)) {
                    return String.format(mContext.getString(R.string.connected_via_network_scorer),
                            mRecommendationServiceLabel);
                }
                return mContext.getString(R.string.connected_via_network_scorer_default);
            }

            // Check NetworkCapabilities.
            final ConnectivityManager cm =
                    (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            final NetworkCapabilities nc =
                    cm.getNetworkCapabilities(mWifiManager.getCurrentNetwork());
            if (nc != null) {
                if (nc.hasCapability(nc.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                    return mContext.getString(mContext.getResources()
                            .getIdentifier("network_available_sign_in", "string", "android"));
                }

                if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY)) {
                    return mContext.getString(R.string.wifi_limited_connection);
                }

                if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    if (nc.isPrivateDnsBroken()) {
                        return mContext.getString(R.string.private_dns_broken);
                    }
                    return mContext.getString(R.string.wifi_connected_no_internet);
                }
            }
        }

        if (mNetworkInfo == null) {
            return "";
        }
        final DetailedState detailState = mNetworkInfo.getDetailedState();
        if (detailState == null) {
            return "";
        }

        final String[] wifiStatusArray = mContext.getResources()
                .getStringArray(R.array.wifi_status);
        final int index = detailState.ordinal();
        return index >= wifiStatusArray.length ? "" : wifiStatusArray[index];
    }

    private String getDisconnectedStateDescription() {
        if (isSaved() && mWifiConfig.hasNoInternetAccess()) {
            final int messageID =
                    mWifiConfig.getNetworkSelectionStatus().isNetworkPermanentlyDisabled()
                    ? R.string.wifi_no_internet_no_reconnect : R.string.wifi_no_internet;
            return mContext.getString(messageID);
        } else if (isSaved() && !mWifiConfig.getNetworkSelectionStatus().isNetworkEnabled()) {
            final WifiConfiguration.NetworkSelectionStatus networkStatus =
                    mWifiConfig.getNetworkSelectionStatus();
            switch (networkStatus.getNetworkSelectionDisableReason()) {
                case WifiConfiguration.NetworkSelectionStatus.DISABLED_AUTHENTICATION_FAILURE:
                    return mContext.getString(R.string.wifi_disabled_password_failure);
                case WifiConfiguration.NetworkSelectionStatus.DISABLED_BY_WRONG_PASSWORD:
                    return mContext.getString(R.string.wifi_check_password_try_again);
                case WifiConfiguration.NetworkSelectionStatus.DISABLED_DHCP_FAILURE:
                    return mContext.getString(R.string.wifi_disabled_network_failure);
                case WifiConfiguration.NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION:
                    return mContext.getString(R.string.wifi_disabled_generic);
                default:
                    break;
            }
        } else if (getLevel() == WIFI_LEVEL_UNREACHABLE) {
            // Do nothing because users know it by signal icon.
        } else { // In range, not disabled.
            if (mWifiConfig != null && mWifiConfig.recentFailure.getAssociationStatus()
                    == WifiConfiguration.RecentFailure.STATUS_AP_UNABLE_TO_HANDLE_NEW_STA) {
                return mContext.getString(R.string.wifi_ap_unable_to_handle_new_sta);
            }
        }
        return "";
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
        return mConnectedInfo;
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

    @Override
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
        return isSaved();
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
        if (!canSetPrivacy()) {
            return;
        }

        mWifiConfig.macRandomizationSetting = privacy == PRIVACY_RANDOMIZED_MAC
                ? WifiConfiguration.RANDOMIZATION_PERSISTENT : WifiConfiguration.RANDOMIZATION_NONE;
        mWifiManager.save(mWifiConfig, null /* listener */);
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

    @WorkerThread
    protected boolean connectionInfoMatches(@NonNull WifiInfo wifiInfo,
            @NonNull NetworkInfo networkInfo) {
        if (wifiInfo.isPasspointAp() || wifiInfo.isOsuAp()) {
            return false;
        }

        return mWifiConfig != null && mWifiConfig.networkId == wifiInfo.getNetworkId();
    }

    private void updateRecommendationServiceLabel() {
        final NetworkScorerAppData scorer = ((NetworkScoreManager) mContext
                .getSystemService(Context.NETWORK_SCORE_SERVICE)).getActiveScorer();
        if (scorer != null) {
            mRecommendationServiceLabel = scorer.getRecommendationServiceLabel();
        }
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

    @Override
    String getScanResultDescription() {
        if (mCurrentScanResults.size() == 0) {
            return "";
        }

        final StringBuilder description = new StringBuilder();
        description.append("[");
        description.append(getScanResultDescription(MIN_FREQ_24GHZ, MAX_FREQ_24GHZ)).append(";");
        description.append(getScanResultDescription(MIN_FREQ_5GHZ, MAX_FREQ_5GHZ)).append(";");
        description.append(getScanResultDescription(MIN_FREQ_6GHZ, MAX_FREQ_6GHZ));
        description.append("]");
        return description.toString();
    }

    private String getScanResultDescription(int minFrequency, int maxFrequency) {
        final List<ScanResult> scanResults = mCurrentScanResults.stream()
                .filter(scanResult -> scanResult.frequency >= minFrequency
                        && scanResult.frequency <= maxFrequency)
                .sorted(Comparator.comparingInt(scanResult -> -1 * scanResult.level))
                .collect(Collectors.toList());

        final int scanResultCount = scanResults.size();
        if (scanResultCount == 0) {
            return "";
        }

        final StringBuilder description = new StringBuilder();
        description.append("(").append(scanResultCount).append(")");
        if (scanResultCount > MAX_VERBOSE_LOG_DISPLAY_SCANRESULT_COUNT) {
            final int maxLavel = scanResults.stream()
                    .mapToInt(scanResult -> scanResult.level).max().getAsInt();
            description.append("max=").append(maxLavel).append(",");
        }
        final long nowMs = SystemClock.elapsedRealtime();
        scanResults.forEach(scanResult ->
                description.append(getScanResultDescription(scanResult, nowMs)));
        return description.toString();
    }

    private String getScanResultDescription(ScanResult scanResult, long nowMs) {
        final StringBuilder description = new StringBuilder();
        description.append(" \n{");
        description.append(scanResult.BSSID);
        if (mWifiInfo != null && scanResult.BSSID.equals(mWifiInfo.getBSSID())) {
            description.append("*");
        }
        description.append("=").append(scanResult.frequency);
        description.append(",").append(scanResult.level);
        // TODO(b/70983952): Append speed of the ScanResult here.
        final int ageSeconds = (int) (nowMs - scanResult.timestamp / 1000) / 1000;
        description.append(",").append(ageSeconds).append("s");
        description.append("}");
        return description.toString();
    }
}
