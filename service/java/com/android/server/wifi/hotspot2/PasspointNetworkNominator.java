/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.hotspot2;

import android.annotation.NonNull;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.os.Process;
import android.telephony.SubscriptionManager;
import android.util.LocalLog;
import android.util.Pair;

import com.android.server.wifi.NetworkUpdateResult;
import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.WifiConfigManager;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.WifiNetworkSelector;
import com.android.server.wifi.util.ScanResultUtil;

import java.util.List;
import java.util.stream.Collectors;

/**
 * This class is the WifiNetworkSelector.NetworkNominator implementation for
 * Passpoint networks.
 */
public class PasspointNetworkNominator implements WifiNetworkSelector.NetworkNominator {
    private static final String NAME = "PasspointNetworkNominator";

    private final PasspointManager mPasspointManager;
    private final WifiConfigManager mWifiConfigManager;
    private final LocalLog mLocalLog;
    private final WifiInjector mWifiInjector;
    private SubscriptionManager mSubscriptionManager;
    /**
     * Contained information for a Passpoint network candidate.
     */
    private class PasspointNetworkCandidate {
        PasspointNetworkCandidate(PasspointProvider provider, PasspointMatch matchStatus,
                ScanDetail scanDetail) {
            mProvider = provider;
            mMatchStatus = matchStatus;
            mScanDetail = scanDetail;
        }
        PasspointProvider mProvider;
        PasspointMatch mMatchStatus;
        ScanDetail mScanDetail;
    }

    public PasspointNetworkNominator(PasspointManager passpointManager,
            WifiConfigManager wifiConfigManager, LocalLog localLog,
            WifiInjector wifiInjector,
            SubscriptionManager subscriptionManager) {
        mPasspointManager = passpointManager;
        mWifiConfigManager = wifiConfigManager;
        mLocalLog = localLog;
        mWifiInjector = wifiInjector;
        mSubscriptionManager = subscriptionManager;
    }

    @Override
    public @NominatorId int getId() {
        return NOMINATOR_ID_PASSPOINT;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void update(List<ScanDetail> scanDetails) {}

    @Override
    public void nominateNetworks(List<ScanDetail> scanDetails,
                    WifiConfiguration currentNetwork, String currentBssid,
                    boolean connected, boolean untrustedNetworkAllowed,
                    @NonNull OnConnectableListener onConnectableListener) {
        // Sweep the ANQP cache to remove any expired ANQP entries.
        mPasspointManager.sweepCache();
        List<ScanDetail> filteredScanDetails = scanDetails.stream()
                .filter(s -> s.getNetworkDetail().isInterworking())
                .filter(s -> {
                    if (!mWifiConfigManager.wasEphemeralNetworkDeleted(
                            ScanResultUtil.createQuotedSSID(s.getScanResult().SSID))) {
                        return true;
                    } else {
                        // If the user previously disconnects this network, don't select it.
                        mLocalLog.log("Ignoring disabled the SSID of Passpoint AP: "
                                + WifiNetworkSelector.toScanId(s.getScanResult()));
                        return false;
                    }
                }).collect(Collectors.toList());

        // Go through each ScanDetail and find the best provider for each ScanDetail.
        for (ScanDetail scanDetail : filteredScanDetails) {
            ScanResult scanResult = scanDetail.getScanResult();

            // Find the best provider for this ScanDetail.
            Pair<PasspointProvider, PasspointMatch> bestProvider =
                    mPasspointManager.matchProvider(scanResult);
            if (bestProvider != null) {
                WifiConfiguration candidate = createWifiConfigForProvider(bestProvider, scanDetail);
                if (candidate == null) {
                    continue;
                }
                onConnectableListener.onConnectable(scanDetail, candidate);
            }
        }
    }

    /**
     * Create and return a WifiConfiguration for the given ScanDetail and PasspointProvider.
     * The newly created WifiConfiguration will also be added to WifiConfigManager.
     *
     * @return {@link WifiConfiguration}
     */
    private WifiConfiguration createWifiConfigForProvider(
            Pair<PasspointProvider, PasspointMatch> bestProvider, ScanDetail scanDetail) {
        WifiConfiguration config = bestProvider.first.getWifiConfig();
        config.SSID = ScanResultUtil.createQuotedSSID(scanDetail.getSSID());
        if (bestProvider.second == PasspointMatch.HomeProvider) {
            config.isHomeProviderNetwork = true;
        }

        WifiConfiguration existingNetwork = mWifiConfigManager.getConfiguredNetwork(
                config.getKey());
        if (existingNetwork != null) {
            WifiConfiguration.NetworkSelectionStatus status =
                    existingNetwork.getNetworkSelectionStatus();
            if (!(status.isNetworkEnabled()
                    || mWifiConfigManager.tryEnableNetwork(existingNetwork.networkId))) {
                localLog("Current configuration for the Passpoint AP " + config.SSID
                        + " is disabled, skip this candidate");
                return null;
            }
        }

        // Add or update with the newly created WifiConfiguration to WifiConfigManager.
        NetworkUpdateResult result;
        if (config.fromWifiNetworkSuggestion) {
            result = mWifiConfigManager.addOrUpdateNetwork(
                    config, config.creatorUid, config.creatorName);
        } else {
            result = mWifiConfigManager.addOrUpdateNetwork(config, Process.WIFI_UID);
        }
        if (!result.isSuccess()) {
            localLog("Failed to add passpoint network");
            return existingNetwork;
        }

        mWifiConfigManager.enableNetwork(result.getNetworkId(), false, Process.WIFI_UID, null);
        mWifiConfigManager.setNetworkCandidateScanResult(result.getNetworkId(),
                scanDetail.getScanResult(), 0);
        mWifiConfigManager.updateScanDetailForNetwork(
                result.getNetworkId(), scanDetail);
        return mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
    }

    private void localLog(String log) {
        mLocalLog.log(log);
    }
}
