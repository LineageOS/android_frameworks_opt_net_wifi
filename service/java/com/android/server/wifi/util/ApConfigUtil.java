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

package com.android.server.wifi.util;

import android.annotation.NonNull;
import android.net.MacAddress;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApConfiguration.BandType;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiScanner;
import android.util.Log;

import com.android.server.wifi.WifiNative;

import java.util.ArrayList;
import java.util.Random;

/**
 * Provide utility functions for updating soft AP related configuration.
 */
public class ApConfigUtil {
    private static final String TAG = "ApConfigUtil";

    public static final int DEFAULT_AP_BAND = SoftApConfiguration.BAND_2GHZ;
    public static final int DEFAULT_AP_CHANNEL = 6;
    public static final int HIGHEST_2G_AP_CHANNEL = 14;

    /* Return code for updateConfiguration. */
    public static final int SUCCESS = 0;
    public static final int ERROR_NO_CHANNEL = 1;
    public static final int ERROR_GENERIC = 2;
    public static final int ERROR_UNSUPPORTED_CONFIGURATION = 3;

    /* Reason code in IEEE Std 802.11-2016, 9.4.1.7, Table 9-45. */
    public static final int DISCONNECT_REASON_CODE_UNSPECIFIED_REASON = 1;
    public static final int DISCONNECT_REASON_CODE_INVALID_AUTHENTICATION = 2;
    public static final int DISCONNECT_REASON_CODE_NO_MORE_STAS = 5;

    /* Random number generator used for AP channel selection. */
    private static final Random sRandom = new Random();

    /**
     * Convert channel/band to frequency.
     * Note: the utility does not perform any regulatory domain compliance.
     * @param channel number to convert
     * @param band of channel to convert
     * @return center frequency in Mhz of the channel, -1 if no match
     */
    public static int convertChannelToFrequency(int channel, int band) {
        if (band == SoftApConfiguration.BAND_2GHZ) {
            if (channel == 14) {
                return 2484;
            } else if (channel >= 1 && channel <= 14) {
                return ((channel - 1) * 5) + 2412;
            } else {
                return -1;
            }
        }
        if (band == SoftApConfiguration.BAND_5GHZ) {
            if (channel >= 34 && channel <= 173) {
                return ((channel - 34) * 5) + 5170;
            } else {
                return -1;
            }
        }
        if (band == SoftApConfiguration.BAND_6GHZ) {
            if (channel >= 1 && channel <= 254) {
                return (channel * 5) + 5940;
            } else {
                return -1;
            }
        }

        return -1;
    }

    /**
     * Convert frequency to channel.
     * Note: the utility does not perform any regulatory domain compliance.
     * @param frequency frequency to convert
     * @return channel number associated with given frequency, -1 if no match
     */
    public static int convertFrequencyToChannel(int frequency) {
        if (frequency >= 2412 && frequency <= 2472) {
            return (frequency - 2412) / 5 + 1;
        } else if (frequency == 2484) {
            return 14;
        } else if (frequency >= 5170  &&  frequency <= 5865) {
            /* DFS is included. */
            return (frequency - 5170) / 5 + 34;
        } else if (frequency > 5940  && frequency < 7210) {
            return ((frequency - 5940) / 5);
        }

        return -1;
    }

    /**
     * Convert frequency to band.
     * Note: the utility does not perform any regulatory domain compliance.
     * @param frequency frequency to convert
     * @return band, -1 if no match
     */
    public static int convertFrequencyToBand(int frequency) {
        if (frequency >= 2412 && frequency <= 2484) {
            return SoftApConfiguration.BAND_2GHZ;
        } else if (frequency >= 5170  &&  frequency <= 5865) {
            return SoftApConfiguration.BAND_5GHZ;
        } else if (frequency > 5940  && frequency < 7210) {
            return SoftApConfiguration.BAND_6GHZ;
        }

        return -1;
    }

    /**
     * Convert band from WifiConfiguration into SoftApConfiguration
     *
     * @param wifiConfigBand band encoded as WifiConfiguration.AP_BAND_xxxx
     * @return band as encoded as SoftApConfiguration.BAND_xxx
     */
    public static int convertWifiConfigBandToSoftApConfigBand(int wifiConfigBand) {
        switch (wifiConfigBand) {
            case WifiConfiguration.AP_BAND_2GHZ:
                return SoftApConfiguration.BAND_2GHZ;
            case WifiConfiguration.AP_BAND_5GHZ:
                return SoftApConfiguration.BAND_5GHZ;
            case WifiConfiguration.AP_BAND_ANY:
                return SoftApConfiguration.BAND_2GHZ | SoftApConfiguration.BAND_5GHZ;
            default:
                return -1;
        }
    }

    /**
     * Checks if band is a valid combination of {link  SoftApConfiguration#BandType} values
     */
    public static boolean isBandValid(@BandType int band) {
        return ((band != 0) && ((band & ~SoftApConfiguration.BAND_ANY) == 0));
    }

    /**
     * Check if the band contains a certain sub-band
     *
     * @param band The combination of bands to validate
     * @param testBand the test band to validate on
     * @return true if band contains testBand, false otherwise
     */
    public static boolean containsBand(@BandType int band, @BandType int testBand) {
        return ((band & testBand) != 0);
    }

    /**
     * Checks if band contains multiple sub-bands
     * @param band a combination of sub-bands
     * @return true if band has multiple sub-bands, false otherwise
     */
    public static boolean isMultiband(@BandType int band) {
        return ((band & (band - 1)) != 0);
    }

    /**
     * Return a channel number for AP setup based on the frequency band.
     * @param apBand one or combination of the values of SoftApConfiguration.BAND_*.
     * @param allowed2GChannels list of allowed 2GHz channels
     * @param allowed5GFreqList list of allowed 5GHz frequencies
     * @param allowed6GFreqList list of allowed 6GHz frequencies
     * @return a valid channel frequency on success, -1 on failure.
     */
    public static int chooseApChannel(int apBand,
                                      ArrayList<Integer> allowed2GChannels,
                                      int[] allowed5GFreqList,
                                      int[] allowed6GFreqList) {
        if (!isBandValid(apBand)) {
            Log.e(TAG, "Invalid band: " + apBand);
            return -1;
        }

        int totalChannelCount = 0;
        int size2gList = (allowed2GChannels != null) ? allowed2GChannels.size() : 0;
        int size5gList = (allowed5GFreqList != null) ? allowed5GFreqList.length : 0;
        int size6gList = (allowed6GFreqList != null) ? allowed6GFreqList.length : 0;

        if ((apBand & SoftApConfiguration.BAND_2GHZ) != 0) {
            totalChannelCount += size2gList;
        }
        if ((apBand & SoftApConfiguration.BAND_5GHZ) != 0) {
            totalChannelCount += size5gList;
        }
        if ((apBand & SoftApConfiguration.BAND_6GHZ) != 0) {
            totalChannelCount += size6gList;
        }

        if (totalChannelCount == 0) {
            // If the default AP band is allowed, just use the default channel
            if (containsBand(apBand, DEFAULT_AP_BAND)) {
                Log.d(TAG, "Allowed channel list not specified, selecting default channel");
                /* Use default channel. */
                return convertChannelToFrequency(DEFAULT_AP_CHANNEL,
                        DEFAULT_AP_BAND);
            } else {
                Log.e(TAG, "No available channels");
                return -1;
            }
        }

        // Pick a channel
        int selectedChannelIndex = sRandom.nextInt(totalChannelCount);

        if ((apBand & SoftApConfiguration.BAND_2GHZ) != 0) {
            if (selectedChannelIndex < size2gList) {
                return convertChannelToFrequency(
                    allowed2GChannels.get(selectedChannelIndex).intValue(),
                    SoftApConfiguration.BAND_2GHZ);
            } else {
                selectedChannelIndex -= size2gList;
            }
        }

        if ((apBand & SoftApConfiguration.BAND_5GHZ) != 0) {
            if (selectedChannelIndex < size5gList) {
                return allowed5GFreqList[selectedChannelIndex];
            } else {
                selectedChannelIndex -= size5gList;
            }
        }

        return allowed6GFreqList[selectedChannelIndex];
    }

    /**
     * Update AP band and channel based on the provided country code and band.
     * This will also set
     * @param wifiNative reference to WifiNative
     * @param countryCode country code
     * @param allowed2GChannels list of allowed 2GHz channels
     * @param config configuration to update
     * @return an integer result code
     */
    public static int updateApChannelConfig(WifiNative wifiNative,
                                            String countryCode,
                                            ArrayList<Integer> allowed2GChannels,
                                            SoftApConfiguration.Builder configBuilder,
                                            SoftApConfiguration config,
                                            boolean acsEnabled) {
        /* Use default band and channel for device without HAL. */
        if (!wifiNative.isHalStarted()) {
            configBuilder.setChannel(DEFAULT_AP_CHANNEL, DEFAULT_AP_BAND);
            return SUCCESS;
        }

        /* Country code is mandatory for 5GHz band. */
        if (config.getBand() == SoftApConfiguration.BAND_5GHZ
                && countryCode == null) {
            Log.e(TAG, "5GHz band is not allowed without country code");
            return ERROR_GENERIC;
        }

        /* Select a channel if it is not specified and ACS is not enabled */
        if ((config.getChannel() == 0) && !acsEnabled) {
            int freq = chooseApChannel(config.getBand(), allowed2GChannels,
                    wifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ),
                    wifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_6_GHZ));
            if (freq == -1) {
                /* We're not able to get channel from wificond. */
                Log.e(TAG, "Failed to get available channel.");
                return ERROR_NO_CHANNEL;
            }
            configBuilder.setChannel(
                    convertFrequencyToChannel(freq), convertFrequencyToBand(freq));
        }

        return SUCCESS;
    }

    /**
     * Helper function for converting SoftapConfiguration to WifiConfiguration.
     * Note that WifiConfiguration only Supports 2GHz, 5GHz, 2GHz+5GHz bands,
     * so conversion is limited to these bands.
     */
    @NonNull
    public static WifiConfiguration convertToWifiConfiguration(
            @NonNull SoftApConfiguration softApConfig) {
        WifiConfiguration wifiConfig = new WifiConfiguration();

        wifiConfig.SSID = softApConfig.getSsid();
        if (softApConfig.getBssid() != null) {
            wifiConfig.BSSID = softApConfig.getBssid().toString();
        }
        wifiConfig.preSharedKey = softApConfig.getWpa2Passphrase();
        wifiConfig.hiddenSSID = softApConfig.isHiddenSsid();
        switch (softApConfig.getBand()) {
            case SoftApConfiguration.BAND_2GHZ:
                wifiConfig.apBand  = WifiConfiguration.AP_BAND_2GHZ;
                break;
            case SoftApConfiguration.BAND_5GHZ:
                wifiConfig.apBand  = WifiConfiguration.AP_BAND_5GHZ;
                break;
            default:
                wifiConfig.apBand  = WifiConfiguration.AP_BAND_ANY;
                break;
        }
        wifiConfig.apChannel = softApConfig.getChannel();
        int authType = softApConfig.getSecurityType();
        switch (authType) {
            case SoftApConfiguration.SECURITY_TYPE_WPA2_PSK:
                authType = WifiConfiguration.KeyMgmt.WPA2_PSK;
                break;
            default:
                authType = WifiConfiguration.KeyMgmt.NONE;
                break;
        }
        wifiConfig.allowedKeyManagement.set(authType);

        return wifiConfig;
    }

    /**
     * Helper function for converting WifiConfiguration to SoftApConfiguration.
     *
     * Only Support None and WPA2 configuration conversion.
     * Note that WifiConfiguration only Supports 2GHz, 5GHz, 2GHz+5GHz bands,
     * so conversion is limited to these bands.
     */
    @NonNull
    public static SoftApConfiguration fromWifiConfiguration(
            @NonNull WifiConfiguration wifiConfig) {
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
        configBuilder.setSsid(wifiConfig.SSID);
        if (wifiConfig.BSSID != null) {
            configBuilder.setBssid(MacAddress.fromString(wifiConfig.BSSID));
        }
        if (wifiConfig.getAuthType() == WifiConfiguration.KeyMgmt.WPA2_PSK) {
            configBuilder.setWpa2Passphrase(wifiConfig.preSharedKey);
        }
        configBuilder.setHiddenSsid(wifiConfig.hiddenSSID);

        int band;
        switch (wifiConfig.apBand) {
            case WifiConfiguration.AP_BAND_2GHZ:
                band = SoftApConfiguration.BAND_2GHZ;
                break;
            case WifiConfiguration.AP_BAND_5GHZ:
                band = SoftApConfiguration.BAND_5GHZ;
                break;
            default:
                // WifiConfiguration.AP_BAND_ANY means only 2GHz and 5GHz bands
                band = SoftApConfiguration.BAND_2GHZ | SoftApConfiguration.BAND_5GHZ;
                break;
        }
        if (wifiConfig.apChannel == 0) {
            configBuilder.setBand(band);
        } else {
            configBuilder.setChannel(wifiConfig.apChannel, band);
        }
        return configBuilder.build();
    }
}
