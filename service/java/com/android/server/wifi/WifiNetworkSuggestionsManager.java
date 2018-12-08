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

import static android.app.AppOpsManager.OPSTR_CHANGE_WIFI_STATE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Handler;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.WifiPermissionsUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Network Suggestions Manager.
 * NOTE: This class should always be invoked from the main wifi service thread.
 */
@NotThreadSafe
public class WifiNetworkSuggestionsManager {
    private static final String TAG = "WifiNetworkSuggestionsManager";

    private final Context mContext;
    private final Handler mHandler;
    private final AppOpsManager mAppOps;
    private final WifiPermissionsUtil mWifiPermissionsUtil;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiInjector mWifiInjector;

    /**
     * Per app meta data to store network suggestions, status, etc for each app providing network
     * suggestions on the device.
     */
    public static class PerAppInfo {
        /**
         * Whether we have shown the user a notification for this app.
         */
        public boolean hasUserApproved = false;
        /**
         * Set of active network suggestions provided by the app.
         */
        public Set<WifiNetworkSuggestion> networkSuggestions = new HashSet<>();

        // This is only needed for comparison in unit tests.
        @Override
        public boolean equals(Object other) {
            if (other == null) return false;
            if (!(other instanceof PerAppInfo)) return false;
            PerAppInfo otherPerAppInfo = (PerAppInfo) other;
            return hasUserApproved == otherPerAppInfo.hasUserApproved
                    && Objects.equals(networkSuggestions, otherPerAppInfo.networkSuggestions);
        }

        // This is only needed for comparison in unit tests.
        @Override
        public int hashCode() {
            return Objects.hash(hasUserApproved, networkSuggestions);
        }
    }

    /**
     * Map of package name of an app to the set of active network suggestions provided by the app.
     */
    private final Map<String, PerAppInfo> mActiveNetworkSuggestionsPerApp = new HashMap<>();
    /**
     * Map of package name of an app to the app ops changed listener for the app.
     */
    private final Map<String, AppOpsChangedListener> mAppOpsChangedListenerPerApp = new HashMap<>();
    /**
     * Map maintained to help lookup all the network suggestions (with no bssid) that match a
     * provided scan result.
     * Note:
     * <li>There could be multiple suggestions (provided by different apps) that match a single
     * scan result.</li>
     * <li>Adding/Removing to this set for scan result lookup is expensive. But, we expect scan
     * result lookup to happen much more often than apps modifying network suggestions.</li>
     */
    private final Map<ScanResultMatchInfo, Set<WifiNetworkSuggestion>>
            mActiveScanResultMatchInfoWithNoBssid = new HashMap<>();
    /**
     * Map maintained to help lookup all the network suggestions (with bssid) that match a provided
     * scan result.
     * Note:
     * <li>There could be multiple suggestions (provided by different apps) that match a single
     * scan result.</li>
     * <li>Adding/Removing to this set for scan result lookup is expensive. But, we expect scan
     * result lookup to happen much more often than apps modifying network suggestions.</li>
     */
    private final Map<Pair<ScanResultMatchInfo, MacAddress>, Set<WifiNetworkSuggestion>>
            mActiveScanResultMatchInfoWithBssid = new HashMap<>();
    /**
     * List of {@link WifiNetworkSuggestion} matching the current connected network.
     */
    private Set<WifiNetworkSuggestion> mActiveNetworkSuggestionsMatchingConnection;

    /**
     * Verbose logging flag.
     */
    private boolean mVerboseLoggingEnabled = false;
    /**
     * Indicates that we have new data to serialize.
     */
    private boolean mHasNewDataToSerialize = false;

    /**
     * Listener for app-ops changes for active suggestor apps.
     */
    private final class AppOpsChangedListener implements AppOpsManager.OnOpChangedListener {
        private final String mPackageName;
        private final int mUid;

        AppOpsChangedListener(@NonNull String packageName, int uid) {
            mPackageName = packageName;
            mUid = uid;
        }

        @Override
        public void onOpChanged(String op, String packageName) {
            mHandler.post(() -> {
                if (!mPackageName.equals(packageName)) return;
                if (!OPSTR_CHANGE_WIFI_STATE.equals(op)) return;

                // Ensure the uid to package mapping is still correct.
                try {
                    mAppOps.checkPackage(mUid, mPackageName);
                } catch (SecurityException e) {
                    Log.wtf(TAG, "Invalid uid/package" + packageName);
                    return;
                }

                if (mAppOps.unsafeCheckOpNoThrow(OPSTR_CHANGE_WIFI_STATE, mUid, mPackageName)
                        == AppOpsManager.MODE_IGNORED) {
                    Log.i(TAG, "User disallowed change wifi state for " + packageName);
                    // User disabled the app, remove app from database. We want the notification
                    // again if the user enabled the app-op back.
                    removeApp(mPackageName);
                }
            });
        }
    };

    /**
     * Module to interact with the wifi config store.
     */
    private class NetworkSuggestionDataSource implements NetworkSuggestionStoreData.DataSource {
        @Override
        public Map<String, PerAppInfo> toSerialize() {
            // Clear the flag after writing to disk.
            // TODO(b/115504887): Don't reset the flag on write failure.
            mHasNewDataToSerialize = false;
            return mActiveNetworkSuggestionsPerApp;
        }

        @Override
        public void fromDeserialized(Map<String, PerAppInfo> networkSuggestionsMap) {
            mActiveNetworkSuggestionsPerApp.putAll(networkSuggestionsMap);
            // Build the scan cache.
            for (Map.Entry<String, PerAppInfo> entry : networkSuggestionsMap.entrySet()) {
                String packageName = entry.getKey();
                Set<WifiNetworkSuggestion> networkSuggestions = entry.getValue().networkSuggestions;
                if (!networkSuggestions.isEmpty()) {
                    // Start tracking app-op changes from the app if they have active suggestions.
                    startTrackingAppOpsChange(packageName,
                            networkSuggestions.iterator().next().suggestorUid);
                }
                addToScanResultMatchInfoMap(networkSuggestions);
            }
        }

        @Override
        public void reset() {
            mActiveNetworkSuggestionsPerApp.clear();
            mActiveScanResultMatchInfoWithBssid.clear();
            mActiveScanResultMatchInfoWithNoBssid.clear();
        }

        @Override
        public boolean hasNewDataToSerialize() {
            return mHasNewDataToSerialize;
        }
    }

    public WifiNetworkSuggestionsManager(Context context, Handler handler,
                                         WifiInjector wifiInjector,
                                         WifiPermissionsUtil wifiPermissionsUtil,
                                         WifiConfigManager wifiConfigManager,
                                         WifiConfigStore wifiConfigStore) {
        mContext = context;
        mHandler = handler;
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mWifiInjector = wifiInjector;
        mWifiPermissionsUtil = wifiPermissionsUtil;
        mWifiConfigManager = wifiConfigManager;

        // register the data store for serializing/deserializing data.
        wifiConfigStore.registerStoreData(
                wifiInjector.makeNetworkSuggestionStoreData(new NetworkSuggestionDataSource()));
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = verbose > 0;
    }

    private void saveToStore() {
        // Set the flag to let WifiConfigStore that we have new data to write.
        mHasNewDataToSerialize = true;
        if (!mWifiConfigManager.saveToStore(true)) {
            Log.w(TAG, "Failed to save to store");
        }
    }

    private void addToScanResultMatchInfoMap(
            @NonNull Collection<WifiNetworkSuggestion> networkSuggestions) {
        for (WifiNetworkSuggestion networkSuggestion : networkSuggestions) {
            ScanResultMatchInfo scanResultMatchInfo =
                    ScanResultMatchInfo.fromWifiConfiguration(networkSuggestion.wifiConfiguration);
            Set<WifiNetworkSuggestion> networkSuggestionsForScanResultMatchInfo;
            if (!TextUtils.isEmpty(networkSuggestion.wifiConfiguration.BSSID)) {
                Pair<ScanResultMatchInfo, MacAddress> lookupPair =
                        Pair.create(scanResultMatchInfo,
                                MacAddress.fromString(networkSuggestion.wifiConfiguration.BSSID));
                networkSuggestionsForScanResultMatchInfo =
                        mActiveScanResultMatchInfoWithBssid.get(lookupPair);
                if (networkSuggestionsForScanResultMatchInfo == null) {
                    networkSuggestionsForScanResultMatchInfo = new HashSet<>();
                    mActiveScanResultMatchInfoWithBssid.put(
                            lookupPair, networkSuggestionsForScanResultMatchInfo);
                }
            } else {
                networkSuggestionsForScanResultMatchInfo =
                        mActiveScanResultMatchInfoWithNoBssid.get(scanResultMatchInfo);
                if (networkSuggestionsForScanResultMatchInfo == null) {
                    networkSuggestionsForScanResultMatchInfo = new HashSet<>();
                    mActiveScanResultMatchInfoWithNoBssid.put(
                            scanResultMatchInfo, networkSuggestionsForScanResultMatchInfo);
                }
            }
            networkSuggestionsForScanResultMatchInfo.add(networkSuggestion);
        }
    }

    private void removeFromScanResultMatchInfoMap(
            @NonNull Collection<WifiNetworkSuggestion> networkSuggestions) {
        for (WifiNetworkSuggestion networkSuggestion : networkSuggestions) {
            ScanResultMatchInfo scanResultMatchInfo =
                    ScanResultMatchInfo.fromWifiConfiguration(networkSuggestion.wifiConfiguration);
            Set<WifiNetworkSuggestion> networkSuggestionsForScanResultMatchInfo;
            if (!TextUtils.isEmpty(networkSuggestion.wifiConfiguration.BSSID)) {
                Pair<ScanResultMatchInfo, MacAddress> lookupPair =
                        Pair.create(scanResultMatchInfo,
                                MacAddress.fromString(networkSuggestion.wifiConfiguration.BSSID));
                networkSuggestionsForScanResultMatchInfo =
                        mActiveScanResultMatchInfoWithBssid.get(lookupPair);
                // This should never happen because we should have done necessary error checks in
                // the parent method.
                if (networkSuggestionsForScanResultMatchInfo == null) {
                    Log.wtf(TAG, "No scan result match info found.");
                }
                networkSuggestionsForScanResultMatchInfo.remove(networkSuggestion);
                // Remove the set from map if empty.
                if (networkSuggestionsForScanResultMatchInfo.isEmpty()) {
                    mActiveScanResultMatchInfoWithBssid.remove(lookupPair);
                }
            } else {
                networkSuggestionsForScanResultMatchInfo =
                        mActiveScanResultMatchInfoWithNoBssid.get(scanResultMatchInfo);
                // This should never happen because we should have done necessary error checks in
                // the parent method.
                if (networkSuggestionsForScanResultMatchInfo == null) {
                    Log.wtf(TAG, "No scan result match info found.");
                }
                networkSuggestionsForScanResultMatchInfo.remove(networkSuggestion);
                // Remove the set from map if empty.
                if (networkSuggestionsForScanResultMatchInfo.isEmpty()) {
                    mActiveScanResultMatchInfoWithNoBssid.remove(scanResultMatchInfo);
                }
            }
        }
    }

    // Issues a disconnect if the only serving network suggestion is removed.
    // TODO (b/115504887): What if there is also a saved network with the same credentials?
    private void triggerDisconnectIfServingNetworkSuggestionRemoved(
            Collection<WifiNetworkSuggestion> networkSuggestionsRemoved) {
        if (mActiveNetworkSuggestionsMatchingConnection == null
                || mActiveNetworkSuggestionsMatchingConnection.isEmpty()) {
            return;
        }
        if (mActiveNetworkSuggestionsMatchingConnection.removeAll(networkSuggestionsRemoved)) {
            if (mActiveNetworkSuggestionsMatchingConnection.isEmpty()) {
                Log.i(TAG, "Only network suggestion matching the connected network removed. "
                        + "Disconnecting...");
                mWifiInjector.getClientModeImpl().disconnectCommand();
            }
        }
    }

    private void startTrackingAppOpsChange(@NonNull String packageName, int uid) {
        AppOpsChangedListener appOpsChangedListener =
                new AppOpsChangedListener(packageName, uid);
        mAppOps.startWatchingMode(OPSTR_CHANGE_WIFI_STATE, packageName, appOpsChangedListener);
        mAppOpsChangedListenerPerApp.put(packageName, appOpsChangedListener);
    }

    /**
     * Add the provided list of network suggestions from the corresponding app's active list.
     */
    public @WifiManager.NetworkSuggestionsStatusCode int add(
            List<WifiNetworkSuggestion> networkSuggestions, String packageName) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Adding " + networkSuggestions.size() + " networks from " + packageName);
        }
        if (networkSuggestions.isEmpty()) {
            Log.w(TAG, "Empty list of network suggestions for " + packageName + ". Ignoring");
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
        }
        PerAppInfo perAppInfo = mActiveNetworkSuggestionsPerApp.get(packageName);
        if (perAppInfo == null) {
            perAppInfo = new PerAppInfo();
            mActiveNetworkSuggestionsPerApp.put(packageName, perAppInfo);
        }
        // check if the app is trying to in-place modify network suggestions.
        if (!Collections.disjoint(perAppInfo.networkSuggestions, networkSuggestions)) {
            Log.e(TAG, "Failed to add network suggestions for " + packageName
                    + ". Modification of active network suggestions disallowed");
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE;
        }
        if (perAppInfo.networkSuggestions.size() + networkSuggestions.size()
                > WifiManager.NETWORK_SUGGESTIONS_MAX_PER_APP) {
            Log.e(TAG, "Failed to add network suggestions for " + packageName
                    + ". Exceeds max per app, current list size: "
                    + perAppInfo.networkSuggestions.size()
                    + ", new list size: "
                    + networkSuggestions.size());
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP;
        }
        if (perAppInfo.networkSuggestions.isEmpty()) {
            // Start tracking app-op changes from the app if they have active suggestions.
            startTrackingAppOpsChange(packageName, networkSuggestions.get(0).suggestorUid);
        }
        perAppInfo.networkSuggestions.addAll(networkSuggestions);
        addToScanResultMatchInfoMap(networkSuggestions);
        saveToStore();
        return WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
    }

    private void stopTrackingAppOpsChange(@NonNull String packageName) {
        AppOpsChangedListener appOpsChangedListener =
                mAppOpsChangedListenerPerApp.remove(packageName);
        if (appOpsChangedListener == null) {
            Log.wtf(TAG, "No app ops listener found for " + packageName);
            return;
        }
        mAppOps.stopWatchingMode(appOpsChangedListener);
    }

    private void removeInternal(
            @NonNull Collection<WifiNetworkSuggestion> networkSuggestions,
            @NonNull String packageName,
            @NonNull PerAppInfo perAppInfo) {
        if (!networkSuggestions.isEmpty()) {
            perAppInfo.networkSuggestions.removeAll(networkSuggestions);
        } else {
            // empty list is used to clear everything for the app. Store a copy for use below.
            networkSuggestions = new HashSet<>(perAppInfo.networkSuggestions);
            perAppInfo.networkSuggestions.clear();
        }
        if (perAppInfo.networkSuggestions.isEmpty()) {
            // Note: We don't remove the app entry even if there is no active suggestions because
            // we want to keep the notification state for all apps that have ever provided
            // suggestions.
            if (mVerboseLoggingEnabled) Log.v(TAG, "No active suggestions for " + packageName);
            // Stop tracking app-op changes from the app if they don't have active suggestions.
            stopTrackingAppOpsChange(packageName);
        }
        // Clear the scan cache.
        removeFromScanResultMatchInfoMap(networkSuggestions);
        saveToStore();
        // Disconnect from the current network, if the suggestion was removed.
        triggerDisconnectIfServingNetworkSuggestionRemoved(networkSuggestions);
    }

    /**
     * Remove the provided list of network suggestions from the corresponding app's active list.
     */
    public @WifiManager.NetworkSuggestionsStatusCode int remove(
            List<WifiNetworkSuggestion> networkSuggestions, String packageName) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Removing " + networkSuggestions.size() + " networks from " + packageName);
        }
        PerAppInfo perAppInfo = mActiveNetworkSuggestionsPerApp.get(packageName);
        if (perAppInfo == null) {
            Log.e(TAG, "Failed to remove network suggestions for " + packageName
                    + ". No network suggestions found");
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID;
        }
        // check if all the request network suggestions are present in the active list.
        if (!networkSuggestions.isEmpty()
                && !perAppInfo.networkSuggestions.containsAll(networkSuggestions)) {
            Log.e(TAG, "Failed to remove network suggestions for " + packageName
                    + ". Network suggestions not found in active network suggestions");
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID;
        }
        removeInternal(networkSuggestions, packageName, perAppInfo);
        return WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
    }

    /**
     * Remove all tracking of the app that has been uninstalled.
     */
    public void removeApp(@NonNull String packageName) {
        PerAppInfo perAppInfo = mActiveNetworkSuggestionsPerApp.get(packageName);
        if (perAppInfo == null) return;

        removeInternal(Collections.EMPTY_LIST, packageName, perAppInfo);
        // Remove the package fully from the internal database.
        mActiveNetworkSuggestionsPerApp.remove(packageName);
        Log.i(TAG, "Removed " + packageName);
    }

    /**
     * Returns a set of all network suggestions across all apps.
     */
    @VisibleForTesting
    public Set<WifiNetworkSuggestion> getAllNetworkSuggestions() {
        return mActiveNetworkSuggestionsPerApp.values()
                .stream()
                .flatMap(e -> e.networkSuggestions.stream())
                .collect(Collectors.toSet());
    }

    private @Nullable Set<WifiNetworkSuggestion> getNetworkSuggestionsForScanResultMatchInfo(
            @NonNull ScanResultMatchInfo scanResultMatchInfo, @NonNull MacAddress bssid) {
        Set<WifiNetworkSuggestion> networkSuggestions = new HashSet<>();
        Set<WifiNetworkSuggestion> matchingNetworkSuggestionsWithBssid =
                mActiveScanResultMatchInfoWithBssid.get(Pair.create(scanResultMatchInfo, bssid));
        if (matchingNetworkSuggestionsWithBssid != null) {
            networkSuggestions.addAll(matchingNetworkSuggestionsWithBssid);
        }
        Set<WifiNetworkSuggestion> matchingNetworkSuggestionsWithNoBssid =
                mActiveScanResultMatchInfoWithNoBssid.get(scanResultMatchInfo);
        if (matchingNetworkSuggestionsWithNoBssid != null) {
            networkSuggestions.addAll(matchingNetworkSuggestionsWithNoBssid);
        }
        if (networkSuggestions.isEmpty()) {
            return null;
        }
        return networkSuggestions;
    }

    /**
     * Returns a set of all network suggestions matching the provided scan detail.
     */
    public @Nullable Set<WifiNetworkSuggestion> getNetworkSuggestionsForScanDetail(
            @NonNull ScanDetail scanDetail) {
        ScanResult scanResult = scanDetail.getScanResult();
        if (scanResult == null) {
            Log.e(TAG, "No scan result found in scan detail");
            return null;
        }
        Set<WifiNetworkSuggestion> networkSuggestions = null;
        try {
            ScanResultMatchInfo scanResultMatchInfo =
                    ScanResultMatchInfo.fromScanResult(scanResult);
            networkSuggestions = getNetworkSuggestionsForScanResultMatchInfo(
                    scanResultMatchInfo,  MacAddress.fromString(scanResult.BSSID));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to lookup network from scan result match info map", e);
        }
        if (networkSuggestions != null) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "getNetworkSuggestionsForScanDetail Found " + networkSuggestions
                        + " for " + scanResult.SSID + "[" + scanResult.capabilities + "]");
            }
        }
        return networkSuggestions;
    }

    /**
     * Returns a set of all network suggestions matching the provided the WifiConfiguration.
     */
    private @Nullable Set<WifiNetworkSuggestion> getNetworkSuggestionsForWifiConfiguration(
            @NonNull WifiConfiguration wifiConfiguration, @NonNull String bssid) {
        Set<WifiNetworkSuggestion> networkSuggestions = null;
        try {
            ScanResultMatchInfo scanResultMatchInfo =
                    ScanResultMatchInfo.fromWifiConfiguration(wifiConfiguration);
            networkSuggestions = getNetworkSuggestionsForScanResultMatchInfo(
                    scanResultMatchInfo,  MacAddress.fromString(bssid));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to lookup network from scan result match info map", e);
        }
        if (networkSuggestions != null) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "getNetworkSuggestionsFoWifiConfiguration Found "
                        + networkSuggestions + " for " + wifiConfiguration.SSID
                        + "[" + wifiConfiguration.allowedKeyManagement + "]");
            }
        }
        return networkSuggestions;
    }

    /**
     * Helper method to send the post connection broadcast to specified package.
     */
    private void sendPostConnectionBroadcast(
            String packageName, WifiNetworkSuggestion networkSuggestion) {
        Intent intent = new Intent(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION);
        intent.putExtra(WifiManager.EXTRA_NETWORK_SUGGESTION, networkSuggestion);
        // Intended to wakeup the receiving app so set the specific package name.
        intent.setPackage(packageName);
        mContext.sendBroadcastAsUser(
                intent, UserHandle.getUserHandleForUid(networkSuggestion.suggestorUid));
    }

    /**
     * Helper method to send the post connection broadcast to specified package.
     */
    private void sendPostConnectionBroadcastIfAllowed(
            String packageName, WifiNetworkSuggestion matchingSuggestion) {
        try {
            mWifiPermissionsUtil.enforceCanAccessScanResults(
                    packageName, matchingSuggestion.suggestorUid);
        } catch (SecurityException se) {
            return;
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Sending post connection broadcast to " + packageName);
        }
        sendPostConnectionBroadcast(packageName, matchingSuggestion);
    }

    /**
     * Send out the {@link WifiManager#ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION} to all the
     * network suggestion credentials that match the current connection network.
     *
     * @param connectedNetwork {@link WifiConfiguration} representing the network connected to.
     * @param connectedBssid BSSID of the network connected to.
     */
    private void handleConnectionSuccess(
            @NonNull WifiConfiguration connectedNetwork, @NonNull String connectedBssid) {
        Set<WifiNetworkSuggestion> matchingNetworkSuggestions =
                getNetworkSuggestionsForWifiConfiguration(connectedNetwork, connectedBssid);
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Network suggestions matching the connection "
                    + matchingNetworkSuggestions);
        }
        if (matchingNetworkSuggestions == null || matchingNetworkSuggestions.isEmpty()) return;

        // Store the set of matching network suggestions.
        mActiveNetworkSuggestionsMatchingConnection = new HashSet<>(matchingNetworkSuggestions);

        Set<WifiNetworkSuggestion> matchingNetworkSuggestionsWithReqAppInteraction =
                matchingNetworkSuggestions.stream()
                        .filter(x -> x.isAppInteractionRequired)
                        .collect(Collectors.toSet());
        if (matchingNetworkSuggestions.size() == 0) return;

        // Iterate over the active network suggestions list:
        // a) Find package names of all apps which made a matching suggestion.
        // b) Ensure that these apps have the necessary location permissions.
        // c) Send directed broadcast to the app with their corresponding network suggestion.
        for (Map.Entry<String, PerAppInfo> entry : mActiveNetworkSuggestionsPerApp.entrySet()) {
            WifiNetworkSuggestion matchingNetworkSuggestion =
                    entry.getValue()
                            .networkSuggestions
                            .stream()
                            .filter(matchingNetworkSuggestionsWithReqAppInteraction::contains)
                            .findFirst()
                            .orElse(null);
            if (matchingNetworkSuggestion == null) continue;
            sendPostConnectionBroadcastIfAllowed(entry.getKey(), matchingNetworkSuggestion);
        }
    }

    private void resetConnectionState() {
        mActiveNetworkSuggestionsMatchingConnection = null;
    }

    /**
     * Invoked by {@link ClientModeImpl} on end of connection attempt to a network.
     *
     * @param failureCode Failure codes representing {@link WifiMetrics.ConnectionEvent} codes.
     * @param network WifiConfiguration corresponding to the current network.
     * @param bssid BSSID of the current network.
     */
    public void handleConnectionAttemptEnded(
            int failureCode, @NonNull WifiConfiguration network, @Nullable String bssid) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "handleConnectionAttemptEnded " + failureCode + ", " + network);
        }
        resetConnectionState();
        if (failureCode == WifiMetrics.ConnectionEvent.FAILURE_NONE) {
            handleConnectionSuccess(network, bssid);
        } else {
            // TODO (b/115504887, b/112196799): Blacklist the corresponding network suggestion if
            // the connection failed.
        }
    }

    /**
     * Invoked by {@link ClientModeImpl} on disconnect from network.
     */
    public void handleDisconnect(@NonNull WifiConfiguration network, @NonNull String bssid) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "handleDisconnect " + network);
        }
        resetConnectionState();
    }

    /**
     * Dump of {@link WifiNetworkSuggestionsManager}.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiNetworkSuggestionsManager");
        pw.println("WifiNetworkSuggestionsManager - Networks Begin ----");
        for (Map.Entry<String, PerAppInfo> networkSuggestionsEntry
                : mActiveNetworkSuggestionsPerApp.entrySet()) {
            pw.println("Package Name: " + networkSuggestionsEntry.getKey());
            PerAppInfo appInfo = networkSuggestionsEntry.getValue();
            pw.println("Has user approved: " + appInfo.hasUserApproved);
            for (WifiNetworkSuggestion networkSuggestions : appInfo.networkSuggestions) {
                pw.println("Network: " + networkSuggestions);
            }
        }
        pw.println("WifiNetworkSuggestionsManager - Networks End ----");
        pw.println("WifiNetworkSuggestionsManager - Network Suggestions matching connection: "
                + mActiveNetworkSuggestionsMatchingConnection);
    }
}

