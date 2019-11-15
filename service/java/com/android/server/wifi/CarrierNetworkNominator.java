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

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.os.Process;
import android.telephony.TelephonyManager;
import android.util.LocalLog;

import com.android.server.wifi.WifiNetworkSelector.NetworkNominator;
import com.android.server.wifi.util.ScanResultUtil;
import com.android.server.wifi.util.TelephonyUtil;

import java.util.List;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Nominator to select a Carrier Wi-Fi network which can be connected to. The Nominator performs
 * two functions:
 *
 * 1. Filtering: figure out which of the networks is a Carrier Wi-Fi network (using the
 * {@link CarrierNetworkConfig} APIs).
 * 2. Nominating: Connectable networks: all networks which match #1 will be fed to this API
 *
 * Note: This class is not thread safe and meant to be used only from {@link WifiNetworkSelector}.
 */
@NotThreadSafe
public class CarrierNetworkNominator implements NetworkNominator {
    private static final String TAG = "CarrierNetworkNominator";

    private final WifiConfigManager mWifiConfigManager;
    private final CarrierNetworkConfig mCarrierNetworkConfig;
    private final LocalLog mLocalLog;
    private final WifiInjector mWifiInjector;
    private TelephonyManager mTelephonyManager;

    public CarrierNetworkNominator(WifiConfigManager wifiConfigManager,
            CarrierNetworkConfig carrierNetworkConfig, LocalLog localLog,
            WifiInjector wifiInjector) {
        mWifiConfigManager = wifiConfigManager;
        mCarrierNetworkConfig = carrierNetworkConfig;
        mLocalLog = localLog;
        mWifiInjector = wifiInjector;
    }

    private TelephonyManager getTelephonyManager() {
        if (mTelephonyManager == null) {
            mTelephonyManager = mWifiInjector.makeTelephonyManager();
        }
        return mTelephonyManager;
    }

    @Override
    public @NominatorId int getId() {
        return NOMINATOR_ID_CARRIER;
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void update(List<ScanDetail> scanDetails) {
        // nothing to be done
    }

    @Override
    public void nominateNetworks(List<ScanDetail> scanDetails,
            WifiConfiguration currentNetwork, String currentBssid, boolean connected,
            boolean untrustedNetworkAllowed, OnConnectableListener onConnectableListener) {
        if (!mCarrierNetworkConfig.isCarrierEncryptionInfoAvailable()) {
            return;
        }

        for (ScanDetail scanDetail : scanDetails) {
            ScanResult scanResult = scanDetail.getScanResult();

            if (!ScanResultUtil.isScanResultForEapNetwork(scanResult)
                    || !mCarrierNetworkConfig.isCarrierNetwork(scanResult.SSID)) {
                continue;
            }
            int eapType =  mCarrierNetworkConfig.getNetworkEapType(scanResult.SSID);
            if (!TelephonyUtil.isSimEapMethod(eapType)) {
                mLocalLog.log(TAG + ": eapType is not a carrier eap method: " + eapType);
                continue;
            }
            // If the user previously forgot this network, don't select it.
            if (mWifiConfigManager.wasEphemeralNetworkDeleted(
                    ScanResultUtil.createQuotedSSID(scanResult.SSID))) {
                mLocalLog.log(TAG + ": Ignoring disabled ephemeral SSID: "
                        + WifiNetworkSelector.toScanId(scanResult));
                continue;
            }

            WifiConfiguration config = ScanResultUtil.createNetworkFromScanResult(scanResult);
            config.ephemeral = true;
            if (config.enterpriseConfig == null) {
                config.enterpriseConfig = new WifiEnterpriseConfig();
            }
            config.enterpriseConfig.setEapMethod(eapType);

            // Check if we already have a network with the same credentials in WifiConfigManager
            // database. If yes, we should check if the network is currently blacklisted.
            WifiConfiguration existingNetwork =
                    mWifiConfigManager.getConfiguredNetwork(config.getKey());
            if (existingNetwork != null
                    && !existingNetwork.getNetworkSelectionStatus().isNetworkEnabled()
                    && !mWifiConfigManager.tryEnableNetwork(existingNetwork.networkId)) {
                mLocalLog.log(TAG + ": Ignoring blacklisted network: "
                        + WifiNetworkSelector.toNetworkString(existingNetwork));
                continue;
            }

            // Add the newly created WifiConfiguration to WifiConfigManager.
            NetworkUpdateResult result = mWifiConfigManager.addOrUpdateNetwork(config,
                    Process.WIFI_UID);
            if (!result.isSuccess()) {
                mLocalLog.log(TAG + ": Failed to add carrier network: " + config);
                continue;
            }
            if (!mWifiConfigManager.enableNetwork(
                    result.getNetworkId(), false, Process.WIFI_UID, null)) {
                mLocalLog.log(TAG + ": Failed to enable carrier network: " + config);
                continue;
            }
            if (!mWifiConfigManager.setNetworkCandidateScanResult(result.getNetworkId(), scanResult,
                    0)) {
                mLocalLog.log(
                        TAG + ": Failed to set network candidate for carrier network: " + config);

                continue;
            }

            config = mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());

            WifiConfiguration.NetworkSelectionStatus nss = null;
            if (config != null) {
                nss = config.getNetworkSelectionStatus();
            }
            if (nss == null) {
                mLocalLog.log(TAG + ": null network selection status for: " + config);
                continue;
            }
            if (nss.getCandidate() != null && nss.getCandidate().level < scanResult.level) {
                mWifiConfigManager.updateScanDetailForNetwork(result.getNetworkId(), scanDetail);
            }

            onConnectableListener.onConnectable(scanDetail, config);
        }
    }
}
