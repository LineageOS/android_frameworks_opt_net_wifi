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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.UserHandle;
import android.util.Log;

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
    private final WifiPermissionsUtil mWifiPermissionsUtil;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiInjector mWifiInjector;
    /**
     * Map of package name of an app to the set of active network suggestions provided by the app.
     */
    private final Map<String, Set<WifiNetworkSuggestion>> mActiveNetworkSuggestionsPerApp =
            new HashMap<>();
    /**
     * Map maintained to help lookup all the network suggestions that match a provided scan result.
     * Note:
     * <li>There could be multiple suggestions (provided by different apps) that match a single
     * scan result.</li>
     * <li>Adding/Removing to this set for scan result lookup is expensive. But, we expect scan
     * result lookup to happen much more often than apps modifying network suggestions.</li>
     */
    private final Map<ScanResultMatchInfo, Set<WifiNetworkSuggestion>> mActiveScanResultMatchInfo =
            new HashMap<>();
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
     * Module to interact with the wifi config store.
     */
    private class NetworkSuggestionDataSource implements NetworkSuggestionStoreData.DataSource {
        @Override
        public Map<String, Set<WifiNetworkSuggestion>> toSerialize() {
            // Clear the flag after writing to disk.
            // TODO(b/115504887): Don't reset the flag on write failure.
            mHasNewDataToSerialize = false;
            return mActiveNetworkSuggestionsPerApp;
        }

        @Override
        public void fromDeserialized(
                Map<String, Set<WifiNetworkSuggestion>> networkSuggestionsMap) {
            mActiveNetworkSuggestionsPerApp.putAll(networkSuggestionsMap);
            // Build the scan cache.
            for (Set<WifiNetworkSuggestion> networkSuggestions : networkSuggestionsMap.values()) {
                addToScanResultMatchInfoMap(networkSuggestions);
            }
        }

        @Override
        public void reset() {
            mActiveNetworkSuggestionsPerApp.clear();
            mActiveScanResultMatchInfo.clear();
        }

        @Override
        public boolean hasNewDataToSerialize() {
            return mHasNewDataToSerialize;
        }
    }

    public WifiNetworkSuggestionsManager(Context context, WifiInjector wifiInjector,
                                         WifiPermissionsUtil wifiPermissionsUtil,
                                         WifiConfigManager wifiConfigManager,
                                         WifiConfigStore wifiConfigStore) {
        mContext = context;
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

    private void addToScanResultMatchInfoMap(Collection<WifiNetworkSuggestion> networkSuggestions) {
        for (WifiNetworkSuggestion networkSuggestion : networkSuggestions) {
            ScanResultMatchInfo scanResultMatchInfo =
                    ScanResultMatchInfo.fromWifiConfiguration(networkSuggestion.wifiConfiguration);
            Set<WifiNetworkSuggestion> activeNetworkSuggestionsForScanResultMatchInfo =
                    mActiveScanResultMatchInfo.get(scanResultMatchInfo);
            if (activeNetworkSuggestionsForScanResultMatchInfo == null) {
                activeNetworkSuggestionsForScanResultMatchInfo = new HashSet<>();
                mActiveScanResultMatchInfo.put(
                        scanResultMatchInfo, activeNetworkSuggestionsForScanResultMatchInfo);
            }
            activeNetworkSuggestionsForScanResultMatchInfo.add(networkSuggestion);
        }
    }

    private void removeFromScanResultMatchInfoMap(List<WifiNetworkSuggestion> networkSuggestions) {
        for (WifiNetworkSuggestion networkSuggestion : networkSuggestions) {
            ScanResultMatchInfo scanResultMatchInfo =
                    ScanResultMatchInfo.fromWifiConfiguration(networkSuggestion.wifiConfiguration);
            Set<WifiNetworkSuggestion> activeNetworkSuggestionsForScanResultMatchInfo =
                    mActiveScanResultMatchInfo.get(scanResultMatchInfo);
            // This should never happen because we should have done necessary error checks in
            // the parent method.
            if (activeNetworkSuggestionsForScanResultMatchInfo == null) {
                Log.wtf(TAG, "No scan result match info found.");
            }
            activeNetworkSuggestionsForScanResultMatchInfo.remove(networkSuggestion);
            // Remove the set from map if empty.
            if (activeNetworkSuggestionsForScanResultMatchInfo.isEmpty()) {
                mActiveScanResultMatchInfo.remove(scanResultMatchInfo);
            }
        }
    }

    // Issues a disconnect if the only serving network suggestion is removed.
    // TODO (b/115504887): What if there is also a saved network with the same credentials?
    private void triggerDisconnectIfServingNetworkSuggestionRemoved(
            List<WifiNetworkSuggestion> networkSuggestionsRemoved) {
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

    /**
     * Add the provided list of network suggestions from the corresponding app's active list.
     */
    public boolean add(List<WifiNetworkSuggestion> networkSuggestions, String packageName) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Adding " + networkSuggestions.size() + " networks from " + packageName);
        }
        Set<WifiNetworkSuggestion> activeNetworkSuggestionsForApp =
                mActiveNetworkSuggestionsPerApp.get(packageName);
        if (activeNetworkSuggestionsForApp == null) {
            activeNetworkSuggestionsForApp = new HashSet<>();
            mActiveNetworkSuggestionsPerApp.put(packageName, activeNetworkSuggestionsForApp);
        }
        // check if the app is trying to in-place modify network suggestions.
        if (!Collections.disjoint(activeNetworkSuggestionsForApp, networkSuggestions)) {
            Log.e(TAG, "Failed to add network suggestions for " + packageName
                    + ". Modification of active network suggestions disallowed");
            return false;
        }
        activeNetworkSuggestionsForApp.addAll(networkSuggestions);
        addToScanResultMatchInfoMap(networkSuggestions);
        saveToStore();
        return true;
    }

    /**
     * Remove the provided list of network suggestions from the corresponding app's active list.
     */
    public boolean remove(List<WifiNetworkSuggestion> networkSuggestions, String packageName) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Removing " + networkSuggestions.size() + " networks from " + packageName);
        }
        Set<WifiNetworkSuggestion> activeNetworkSuggestionsForApp =
                mActiveNetworkSuggestionsPerApp.get(packageName);
        if (activeNetworkSuggestionsForApp == null) {
            Log.e(TAG, "Failed to remove network suggestions for " + packageName
                    + ". No active network suggestions found");
            return false;
        }
        if (!networkSuggestions.isEmpty()) {
            // check if all the request network suggestions are present in the active list.
            if (!activeNetworkSuggestionsForApp.containsAll(networkSuggestions)) {
                Log.e(TAG, "Failed to remove network suggestions for " + packageName
                        + ". Network suggestions not found in active network suggestions");
                return false;
            }
            activeNetworkSuggestionsForApp.removeAll(networkSuggestions);
        } else {
            // empty list is used to clear everything for the app.
            activeNetworkSuggestionsForApp.clear();
        }
        // Remove the set from map if empty.
        if (activeNetworkSuggestionsForApp.isEmpty()) {
            mActiveNetworkSuggestionsPerApp.remove(packageName);
        }
        removeFromScanResultMatchInfoMap(networkSuggestions);
        saveToStore();
        // Disconnect from the current network, if the suggestion was removed.
        triggerDisconnectIfServingNetworkSuggestionRemoved(networkSuggestions);
        return true;
    }

    /**
     * Returns a set of all network suggestions across all apps.
     */
    @VisibleForTesting
    public Set<WifiNetworkSuggestion> getAllNetworkSuggestions() {
        return mActiveNetworkSuggestionsPerApp.values()
                .stream()
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
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
            networkSuggestions = mActiveScanResultMatchInfo.get(
                    ScanResultMatchInfo.fromScanResult(scanResult));
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
            @NonNull WifiConfiguration wifiConfiguration) {
        Set<WifiNetworkSuggestion> networkSuggestions = null;
        try {
            networkSuggestions = mActiveScanResultMatchInfo.get(
                    ScanResultMatchInfo.fromWifiConfiguration(wifiConfiguration));
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
     * Send out the {@link WifiManager#ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION} to all the
     * network suggestion credentials that match the current connection network.
     *
     * @param connectedNetwork {@link WifiConfiguration} representing the network connected to.
     */
    private void handleConnectionSuccess(@NonNull WifiConfiguration connectedNetwork) {
        Set<WifiNetworkSuggestion> matchingNetworkSuggestions =
                getNetworkSuggestionsForWifiConfiguration(connectedNetwork);
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
        for (Map.Entry<String, Set<WifiNetworkSuggestion>> entry :
                mActiveNetworkSuggestionsPerApp.entrySet()) {
            WifiNetworkSuggestion matchingNetworkSuggestion =
                    entry.getValue()
                            .stream()
                            .filter(matchingNetworkSuggestionsWithReqAppInteraction::contains)
                            .findFirst()
                            .orElse(null);
            if (matchingNetworkSuggestion == null) continue;
            try {
                mWifiPermissionsUtil.enforceCanAccessScanResults(
                        entry.getKey(), matchingNetworkSuggestion.suggestorUid);
            } catch (SecurityException se) {
                continue;
            }
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Sending post connection broadcast to " + entry.getKey());
            }
            sendPostConnectionBroadcast(entry.getKey(), matchingNetworkSuggestion);
        }
    }

    /**
     * Invoked by {@link ClientModeImpl} on end of connection attempt to a network.
     *
     * @param failureCode Failure codes representing {@link WifiMetrics.ConnectionEvent} codes.
     * @param network WifiConfiguration corresponding to the current network.
     */
    public void handleConnectionAttemptEnded(
            int failureCode, @NonNull WifiConfiguration network) {
        Log.v(TAG, "handleConnectionAttemptEnded " + failureCode + ", " + network);
        mActiveNetworkSuggestionsMatchingConnection = null;
        if (failureCode == WifiMetrics.ConnectionEvent.FAILURE_NONE) {
            handleConnectionSuccess(network);
        } else {
            // TODO (b/115504887): Blacklist the corresponding network suggestion if the connection
            // failed.
        }
    }

    /**
     * Dump of {@link WifiNetworkSuggestionsManager}.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiNetworkSuggestionsManager");
        pw.println("WifiNetworkSuggestionsManager - Networks Begin ----");
        for (Map.Entry<String, Set<WifiNetworkSuggestion>> networkSuggestionsEntry
                : mActiveNetworkSuggestionsPerApp.entrySet()) {
            pw.println("Package Name: " + networkSuggestionsEntry.getKey());
            for (WifiNetworkSuggestion networkSuggestions : networkSuggestionsEntry.getValue()) {
                pw.println("Network: " + networkSuggestions);
            }
        }
        pw.println("WifiNetworkSuggestionsManager - Networks End ----");
        pw.println("WifiNetworkSuggestionsManager - Network Suggestions matching connection: "
                + mActiveNetworkSuggestionsMatchingConnection);
    }
}

