/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;

import com.android.server.wifi.util.ScanResultUtil;

import java.lang.annotation.Retention;
import java.util.Objects;

/**
 * Class to store the info needed to match a scan result to the provided network configuration.
 */
public class ScanResultMatchInfo {
    public static final int NETWORK_TYPE_OPEN = 0;
    public static final int NETWORK_TYPE_WEP = 1;
    public static final int NETWORK_TYPE_PSK = 2;
    public static final int NETWORK_TYPE_EAP = 3;

    @Retention(SOURCE)
    @IntDef(prefix = { "NETWORK_TYPE_" }, value = {
            NETWORK_TYPE_OPEN,
            NETWORK_TYPE_WEP,
            NETWORK_TYPE_PSK,
            NETWORK_TYPE_EAP
    })
    public @interface NetworkType {}

    /**
     * SSID of the network.
     */
    public String networkSsid;
    /**
     * Security Type of the network.
     */
    public @NetworkType int networkType;

    /**
     * Fetch network type from network configuration.
     */
    public static @NetworkType int getNetworkType(WifiConfiguration config) {
        if (WifiConfigurationUtil.isConfigForPskNetwork(config)) {
            return NETWORK_TYPE_PSK;
        } else if (WifiConfigurationUtil.isConfigForEapNetwork(config)) {
            return NETWORK_TYPE_EAP;
        } else if (WifiConfigurationUtil.isConfigForWepNetwork(config)) {
            return NETWORK_TYPE_WEP;
        } else if (WifiConfigurationUtil.isConfigForOpenNetwork(config)) {
            return NETWORK_TYPE_OPEN;
        }
        throw new IllegalArgumentException("Invalid WifiConfiguration: " + config);
    }

    /**
     * Get the ScanResultMatchInfo for the given WifiConfiguration
     */
    public static ScanResultMatchInfo fromWifiConfiguration(WifiConfiguration config) {
        ScanResultMatchInfo info = new ScanResultMatchInfo();
        info.networkSsid = config.SSID;
        info.networkType = getNetworkType(config);
        return info;
    }

    /**
     * Fetch network type from scan result.
     */
    public static @NetworkType int getNetworkType(ScanResult scanResult) {
        if (ScanResultUtil.isScanResultForPskNetwork(scanResult)) {
            return NETWORK_TYPE_PSK;
        } else if (ScanResultUtil.isScanResultForEapNetwork(scanResult)) {
            return NETWORK_TYPE_EAP;
        } else if (ScanResultUtil.isScanResultForWepNetwork(scanResult)) {
            return NETWORK_TYPE_WEP;
        } else if (ScanResultUtil.isScanResultForOpenNetwork(scanResult)) {
            return NETWORK_TYPE_OPEN;
        }
        throw new IllegalArgumentException("Invalid ScanResult: " + scanResult);
    }

    /**
     * Get the ScanResultMatchInfo for the given ScanResult
     */
    public static ScanResultMatchInfo fromScanResult(ScanResult scanResult) {
        ScanResultMatchInfo info = new ScanResultMatchInfo();
        // Scan result ssid's are not quoted, hence add quotes.
        // TODO: This matching algo works only if the scan result contains a string SSID.
        // However, according to our public documentation ths {@link WifiConfiguration#SSID} can
        // either have a hex string or quoted ASCII string SSID.
        info.networkSsid = ScanResultUtil.createQuotedSSID(scanResult.SSID);
        info.networkType = getNetworkType(scanResult);
        return info;
    }

    @Override
    public boolean equals(Object otherObj) {
        if (this == otherObj) {
            return true;
        } else if (!(otherObj instanceof ScanResultMatchInfo)) {
            return false;
        }
        ScanResultMatchInfo other = (ScanResultMatchInfo) otherObj;
        return Objects.equals(networkSsid, other.networkSsid)
                && networkType == other.networkType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(networkSsid, networkType);
    }

    @Override
    public String toString() {
        return "ScanResultMatchInfo: " + networkSsid + ", type: " + networkType;
    }
}
