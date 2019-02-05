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
import android.net.wifi.WifiNetworkSuggestion;
import android.util.LocalLog;
import android.util.Pair;

import com.android.server.wifi.util.ScanResultUtil;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Evaluator to pick the best network to connect to from the list of active network suggestions
 * provided by apps.
 * Note:
 * <li> This class is not thread safe and meant to be used only from {@link WifiNetworkSelector}.
 * </li>
 *
 * This is a non-optimal implementation which picks any network suggestion which matches
 * the scan result with the highest RSSI.
 * TODO: More advanced implementation will follow!
 * Params to consider for evaluating network suggestions:
 *  - Regular network evaluator params like security, band, RSSI, etc.
 *  - Priority of suggestions provided by a single app.
 *  - Whether the network suggestions requires user/app interaction or if it is metered.
 *  - Historical quality of suggestions provided by the corresponding app.
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
    public WifiConfiguration evaluateNetworks(List<ScanDetail> scanDetails,
            WifiConfiguration currentNetwork, String currentBssid, boolean connected,
            boolean untrustedNetworkAllowed,
            @NonNull OnConnectableListener onConnectableListener) {
        Map<WifiNetworkSuggestion, Pair<WifiConfiguration, ScanDetail>> candidateMap =
                new HashMap<>();
        for (int i = 0; i < scanDetails.size(); i++) {
            ScanDetail scanDetail = scanDetails.get(i);
            ScanResult scanResult = scanDetail.getScanResult();
            Set<WifiNetworkSuggestion> matchingNetworkSuggestions =
                    mWifiNetworkSuggestionsManager.getNetworkSuggestionsForScanDetail(scanDetail);
            if (matchingNetworkSuggestions == null || matchingNetworkSuggestions.isEmpty()) {
                continue;
            }
            // All matching network credentials are considered equal. So, put any one of them.
            WifiNetworkSuggestion matchingNetworkSuggestion =
                    matchingNetworkSuggestions.stream().findAny().get();
            // If the user previously forgot this network, don't select it.
            if (mWifiConfigManager.wasEphemeralNetworkDeleted(
                    ScanResultUtil.createQuotedSSID(scanResult.SSID))) {
                mLocalLog.log("Ignoring disabled ephemeral SSID: "
                        + WifiNetworkSelector.toScanId(scanResult));
                continue;
            }
            // Check if we already have a network with the same credentials in WifiConfigManager
            // database. If yes, we should check if the network is currently blacklisted.
            WifiConfiguration  wCmConfiguredNetwork =
                    mWifiConfigManager.getConfiguredNetwork(
                            matchingNetworkSuggestion.wifiConfiguration.configKey());
            if (wCmConfiguredNetwork != null) {
                if (!wCmConfiguredNetwork.getNetworkSelectionStatus().isNetworkEnabled()
                        && !mWifiConfigManager.tryEnableNetwork(wCmConfiguredNetwork.networkId)) {
                    mLocalLog.log("Ignoring blacklisted network: "
                            + WifiNetworkSelector.toNetworkString(wCmConfiguredNetwork));
                    continue;
                }
                mLocalLog.log(String.format("network suggestion candidate %s (existing)",
                        WifiNetworkSelector.toNetworkString(wCmConfiguredNetwork)));
            } else {
                wCmConfiguredNetwork = addCandidateToWifiConfigManager(
                        matchingNetworkSuggestion.wifiConfiguration,
                        matchingNetworkSuggestion.suggestorUid,
                        matchingNetworkSuggestion.suggestorPackageName);
                if (wCmConfiguredNetwork == null) continue;
                mLocalLog.log(String.format("network suggestion candidate %s (new)",
                        WifiNetworkSelector.toNetworkString(wCmConfiguredNetwork)));
            }
            candidateMap.put(matchingNetworkSuggestion,
                    Pair.create(wCmConfiguredNetwork, scanDetail));
            onConnectableListener.onConnectable(scanDetail, wCmConfiguredNetwork, 0);
        }

        // Pick the matching network suggestion corresponding to the highest RSSI. This will need to
        // be replaced by a more sophisticated algorithm.
        Map.Entry<WifiNetworkSuggestion, Pair<WifiConfiguration, ScanDetail>>
                candidate = candidateMap
                        .entrySet()
                        .stream()
                        .max(Comparator.comparing(e -> e.getValue().second.getScanResult().level))
                        .orElse(null);
        if (candidate == null) {
            mLocalLog.log("did not see any matching network suggestions.");
            return null;
        }
        return candidate.getValue().first;
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
    public String getName() {
        return TAG;
    }
}
