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

package com.android.server.wifi.scanner;

import android.net.wifi.WifiScanner;
import android.util.Log;

import com.android.server.wifi.WifiNative;

import java.util.Collections;
import java.util.List;

/**
 * KnownBandsChannelHelper that uses band to channel mappings retrieved from wificond.
 * Also supporting updating the channel list from the wificond on demand.
 */
public class WificondChannelHelper extends KnownBandsChannelHelper {
    private static final String TAG = "WificondChannelHelper";

    private final WifiNative mWifiNative;

    public WificondChannelHelper(WifiNative wifiNative) {
        mWifiNative = wifiNative;
        final List<Integer> emptyFreqList = Collections.emptyList();
        setBandChannels(emptyFreqList, emptyFreqList, emptyFreqList, emptyFreqList);
        updateChannels();
    }

    @Override
    public void updateChannels() {
        List<Integer> channels24G =
                mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_24_GHZ);
        if (channels24G.isEmpty()) Log.e(TAG, "Failed to get channels for 2.4GHz band");
        List<Integer> channels5G = mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ);
        if (channels5G.isEmpty()) Log.e(TAG, "Failed to get channels for 5GHz band");
        List<Integer> channelsDfs =
                mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY);
        if (channelsDfs.isEmpty()) Log.e(TAG, "Failed to get channels for 5GHz DFS only band");
        List<Integer> channels6G =
                mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_6_GHZ);
        if (channels6G.isEmpty()) Log.e(TAG, "Failed to get channels for 6GHz band");

        if (!channels24G.isEmpty() || !channels5G.isEmpty() || !channelsDfs.isEmpty()
                || !channels6G.isEmpty()) {
            setBandChannels(channels24G, channels5G, channelsDfs, channels6G);
        } else {
            Log.e(TAG, "Got zero length for all channel lists");
        }
    }
}
