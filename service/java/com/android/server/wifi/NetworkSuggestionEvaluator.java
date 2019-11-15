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
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.util.LocalLog;
import android.util.Log;

import com.android.server.wifi.WifiNetworkSuggestionsManager.ExtendedWifiNetworkSuggestion;
import com.android.server.wifi.util.ScanResultUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Evaluator nominate the highest available suggestion candidates.
 * Note:
 * <li> This class is not thread safe and meant to be used only from {@link WifiNetworkSelector}.
 * </li>
 *
 */
@NotThreadSafe
public class NetworkSuggestionEvaluator implements WifiNetworkSelector.NetworkEvaluator {
    private static final String TAG = "NetworkSuggestionEvaluator";

    private final WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    private final WifiConfigManager mWifiConfigManager;
    private final LocalLog mLocalLog;

    NetworkSuggestionEvaluator(WifiNetworkSuggestionsManager networkSuggestionsManager,
            WifiConfigManager wifiConfigManager, LocalLog localLog) {
        mWifiNetworkSuggestionsManager = networkSuggestionsManager;
        mWifiConfigManager = wifiConfigManager;
        mLocalLog = localLog;
    }

    @Override
    public void update(List<ScanDetail> scanDetails) {
        // TODO(b/115504887): This could be used to re-evaluate any temporary blacklists.
    }

    @Override
    public void evaluateNetworks(List<ScanDetail> scanDetails,
            WifiConfiguration currentNetwork, String currentBssid, boolean connected,
            boolean untrustedNetworkAllowed,
            @NonNull OnConnectableListener onConnectableListener) {
        MatchMetaInfo matchMetaInfo = new MatchMetaInfo();
        for (int i = 0; i < scanDetails.size(); i++) {
            ScanDetail scanDetail = scanDetails.get(i);
            ScanResult scanResult = scanDetail.getScanResult();
            // If the user previously forgot this network, don't select it.
            if (mWifiConfigManager.wasEphemeralNetworkDeleted(
                    ScanResultUtil.createQuotedSSID(scanResult.SSID))) {
                mLocalLog.log("Ignoring disabled ephemeral SSID: "
                        + WifiNetworkSelector.toScanId(scanResult));
                continue;
            }
            Set<ExtendedWifiNetworkSuggestion> matchingExtNetworkSuggestions =
                    mWifiNetworkSuggestionsManager.getNetworkSuggestionsForScanDetail(scanDetail);
            if (matchingExtNetworkSuggestions == null || matchingExtNetworkSuggestions.isEmpty()) {
                continue;
            }
            // All matching suggestions have the same network credentials type. So, use any one of
            // them to lookup/add the credentials to WifiConfigManager.
            // Note: Apps could provide different credentials (password, ceritificate) for the same
            // network, need to handle that in the future.
            ExtendedWifiNetworkSuggestion matchingExtNetworkSuggestion =
                    matchingExtNetworkSuggestions.stream().findAny().get();
            // Check if we already have a network with the same credentials in WifiConfigManager
            // database.
            WifiConfiguration wCmConfiguredNetwork =
                    mWifiConfigManager.getConfiguredNetwork(
                            matchingExtNetworkSuggestion.wns.wifiConfiguration.getKey());
            if (wCmConfiguredNetwork != null) {
                // If existing network is not from suggestion, ignore.
                if (!wCmConfiguredNetwork.fromWifiNetworkSuggestion) {
                    continue;
                }
                // Update the WifiConfigManager with the latest WifiConfig
                NetworkUpdateResult result = mWifiConfigManager.addOrUpdateNetwork(
                                matchingExtNetworkSuggestion.wns.wifiConfiguration,
                                matchingExtNetworkSuggestion.perAppInfo.uid,
                                matchingExtNetworkSuggestion.perAppInfo.packageName);
                if (result.isSuccess()) {
                    wCmConfiguredNetwork = mWifiConfigManager.getConfiguredNetwork(
                            result.getNetworkId());
                }
                // If the network is currently blacklisted, ignore.
                if (!wCmConfiguredNetwork.getNetworkSelectionStatus().isNetworkEnabled()
                        && !mWifiConfigManager.tryEnableNetwork(wCmConfiguredNetwork.networkId)) {
                    mLocalLog.log("Ignoring blacklisted network: "
                            + WifiNetworkSelector.toNetworkString(wCmConfiguredNetwork));
                    continue;
                }
            }
            matchMetaInfo.putAll(matchingExtNetworkSuggestions, wCmConfiguredNetwork, scanDetail);
        }
        // Return early on no match.
        if (matchMetaInfo.isEmpty()) {
            mLocalLog.log("did not see any matching network suggestions.");
            return;
        }
        matchMetaInfo.findConnectableNetworksAndHighestPriority(onConnectableListener);
    }

    // Add and enable this network to the central database (i.e WifiConfigManager).
    // Returns the copy of WifiConfiguration with the allocated network ID filled in.
    private WifiConfiguration addCandidateToWifiConfigManager(
            @NonNull WifiConfiguration wifiConfiguration, int uid, @NonNull String packageName) {
        // Mark the network ephemeral because we don't want these persisted by WifiConfigManager.
        wifiConfiguration.ephemeral = true;
        wifiConfiguration.fromWifiNetworkSuggestion = true;

        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(wifiConfiguration, uid, packageName);
        if (!result.isSuccess()) {
            mLocalLog.log("Failed to add network suggestion");
            return null;
        }
        if (!mWifiConfigManager.updateNetworkSelectionStatus(result.getNetworkId(),
                WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE)) {
            mLocalLog.log("Failed to make network suggestion selectable");
            return null;
        }
        int candidateNetworkId = result.getNetworkId();
        return mWifiConfigManager.getConfiguredNetwork(candidateNetworkId);
    }

    @Override
    public @EvaluatorId int getId() {
        return EVALUATOR_ID_SUGGESTION;
    }

    @Override
    public String getName() {
        return TAG;
    }

    // Container classes to handle book-keeping while we're iterating through the scan list.
    private class PerNetworkSuggestionMatchMetaInfo {
        public final ExtendedWifiNetworkSuggestion extWifiNetworkSuggestion;
        public final ScanDetail matchingScanDetail;
        public WifiConfiguration wCmConfiguredNetwork; // Added to WifiConfigManager.

        PerNetworkSuggestionMatchMetaInfo(
                @NonNull ExtendedWifiNetworkSuggestion extWifiNetworkSuggestion,
                @Nullable WifiConfiguration wCmConfiguredNetwork,
                @NonNull ScanDetail matchingScanDetail) {
            this.extWifiNetworkSuggestion = extWifiNetworkSuggestion;
            this.wCmConfiguredNetwork = wCmConfiguredNetwork;
            this.matchingScanDetail = matchingScanDetail;
        }
    }

    private class PerAppMatchMetaInfo {
        public final List<PerNetworkSuggestionMatchMetaInfo> networkInfos = new ArrayList<>();

        /**
         * Add the network suggestion & associated info to this package meta info.
         */
        public void put(ExtendedWifiNetworkSuggestion wifiNetworkSuggestion,
                        WifiConfiguration matchingWifiConfiguration,
                        ScanDetail matchingScanDetail) {
            networkInfos.add(new PerNetworkSuggestionMatchMetaInfo(
                    wifiNetworkSuggestion, matchingWifiConfiguration, matchingScanDetail));
        }

        /**
         * Pick the highest priority networks among the current match info candidates for this
         * app.
         */
        public List<PerNetworkSuggestionMatchMetaInfo> getHighestPriorityNetworks() {
            // Partition the list to a map of network suggestions keyed in by the priorities.
            // There can be multiple networks with the same priority, hence a list in the value.
            Map<Integer, List<PerNetworkSuggestionMatchMetaInfo>> matchedNetworkInfosPerPriority =
                    networkInfos.stream()
                            .collect(Collectors.toMap(
                                    e -> e.extWifiNetworkSuggestion.wns.wifiConfiguration.priority,
                                    e -> Arrays.asList(e),
                                    (v1, v2) -> { // concatenate networks with the same priority.
                                        List<PerNetworkSuggestionMatchMetaInfo> concatList =
                                                new ArrayList<>(v1);
                                        concatList.addAll(v2);
                                        return concatList;
                                    }));
            if (matchedNetworkInfosPerPriority.isEmpty()) { // should never happen.
                Log.wtf(TAG, "Unexepectedly got empty");
                return Collections.EMPTY_LIST;
            }
            // Return the list associated with the highest priority value.
            return matchedNetworkInfosPerPriority.get(Collections.max(
                    matchedNetworkInfosPerPriority.keySet()));
        }
    }

    private class MatchMetaInfo {
        private Map<String, PerAppMatchMetaInfo> mAppInfos = new HashMap<>();

        /**
         * Add all the network suggestion & associated info.
         */
        public void putAll(Set<ExtendedWifiNetworkSuggestion> wifiNetworkSuggestions,
                           WifiConfiguration wCmConfiguredNetwork,
                           ScanDetail matchingScanDetail) {
            // Separate the suggestions into buckets for each app to allow sorting based on
            // priorities set by app.
            for (ExtendedWifiNetworkSuggestion wifiNetworkSuggestion : wifiNetworkSuggestions) {
                PerAppMatchMetaInfo appInfo = mAppInfos.computeIfAbsent(
                        wifiNetworkSuggestion.perAppInfo.packageName,
                        k -> new PerAppMatchMetaInfo());
                appInfo.put(wifiNetworkSuggestion, wCmConfiguredNetwork, matchingScanDetail);
            }
        }

        /**
         * Are there any matched candidates?
         */
        public boolean isEmpty() {
            return mAppInfos.isEmpty();
        }

        /**
         * Run through all connectable suggestions and nominate highest priority networks from each
         * app as candidates to {@link WifiNetworkSelector}.
         */
        public void findConnectableNetworksAndHighestPriority(
                @NonNull OnConnectableListener onConnectableListener) {
            for (PerAppMatchMetaInfo appInfo : mAppInfos.values()) {
                List<PerNetworkSuggestionMatchMetaInfo> matchedNetworkInfos =
                        appInfo.getHighestPriorityNetworks();
                for (PerNetworkSuggestionMatchMetaInfo matchedNetworkInfo : matchedNetworkInfos) {
                    // if the network does not already exist in WifiConfigManager, add now.
                    if (matchedNetworkInfo.wCmConfiguredNetwork == null) {
                        matchedNetworkInfo.wCmConfiguredNetwork = addCandidateToWifiConfigManager(
                                matchedNetworkInfo.extWifiNetworkSuggestion.wns.wifiConfiguration,
                                matchedNetworkInfo.extWifiNetworkSuggestion.perAppInfo.uid,
                                matchedNetworkInfo.extWifiNetworkSuggestion.perAppInfo.packageName);
                        if (matchedNetworkInfo.wCmConfiguredNetwork == null) continue;
                        mLocalLog.log(String.format("network suggestion candidate %s (new)",
                                WifiNetworkSelector.toNetworkString(
                                        matchedNetworkInfo.wCmConfiguredNetwork)));
                    } else {
                        mLocalLog.log(String.format("network suggestion candidate %s (existing)",
                                WifiNetworkSelector.toNetworkString(
                                        matchedNetworkInfo.wCmConfiguredNetwork)));
                    }
                    onConnectableListener.onConnectable(
                            matchedNetworkInfo.matchingScanDetail,
                            matchedNetworkInfo.wCmConfiguredNetwork);
                }
            }
        }
    }

}
