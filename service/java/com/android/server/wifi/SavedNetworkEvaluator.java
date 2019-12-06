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

package com.android.server.wifi;

import android.annotation.NonNull;
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.util.LocalLog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.TelephonyUtil;
import com.android.wifi.resources.R;

import java.util.List;

/**
 * This class is the WifiNetworkSelector.NetworkEvaluator implementation for
 * saved networks.
 */
public class SavedNetworkEvaluator implements WifiNetworkSelector.NetworkEvaluator {
    private static final String NAME = "SavedNetworkEvaluator";
    private final WifiConfigManager mWifiConfigManager;
    private final Context mContext;
    private final Clock mClock;
    private final LocalLog mLocalLog;
    private final WifiConnectivityHelper mConnectivityHelper;
    private final TelephonyUtil mTelephonyUtil;
    private final ScoringParams mScoringParams;

    /**
     * Time it takes for the lastSelectionAward to decay by one point, in milliseconds
     */
    @VisibleForTesting
    public static final int LAST_SELECTION_AWARD_DECAY_MSEC = 60 * 1000;


    SavedNetworkEvaluator(final Context context, ScoringParams scoringParams,
            WifiConfigManager configManager, Clock clock,
            LocalLog localLog, WifiConnectivityHelper connectivityHelper,
            TelephonyUtil telephonyUtil) {
        mContext = context;
        mScoringParams = scoringParams;
        mWifiConfigManager = configManager;
        mClock = clock;
        mLocalLog = localLog;
        mConnectivityHelper = connectivityHelper;
        mTelephonyUtil = telephonyUtil;

    }

    private void localLog(String log) {
        mLocalLog.log(log);
    }

    /**
     * Get the evaluator type.
     */
    @Override
    public @EvaluatorId int getId() {
        return EVALUATOR_ID_SAVED;
    }

    /**
     * Get the evaluator name.
     */
    @Override
    public String getName() {
        return NAME;
    }

    /**
     * Update the evaluator.
     */
    @Override
    public void update(List<ScanDetail> scanDetails) { }

    private int calculateBssidScore(ScanResult scanResult, WifiConfiguration network,
                        WifiConfiguration currentNetwork, String currentBssid,
                        StringBuffer sbuf) {
        int score = 0;
        boolean is5GHz = scanResult.is5GHz();
        boolean is6GHz = scanResult.is6GHz();

        final int rssiScoreSlope = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_RSSI_SCORE_SLOPE);
        final int rssiScoreOffset = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_RSSI_SCORE_OFFSET);
        final int sameBssidAward = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_SAME_BSSID_AWARD);
        final int sameNetworkAward = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_current_network_boost);
        final int lastSelectionAward = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_LAST_SELECTION_AWARD);
        final int securityAward = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_SECURITY_AWARD);
        final int band5GHzAward = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_5GHz_preference_boost_factor);
        final int band6GHzAward = mContext.getResources().getInteger(
                R.integer.config_wifiFramework6ghzPreferenceBoostFactor);

        sbuf.append("[ ").append(scanResult.SSID).append(" ").append(scanResult.BSSID)
                .append(" RSSI:").append(scanResult.level).append(" ] ");
        // Calculate the RSSI score.
        int rssiSaturationThreshold = mScoringParams.getGoodRssi(scanResult.frequency);
        int rssi = Math.min(scanResult.level, rssiSaturationThreshold);
        score += (rssi + rssiScoreOffset) * rssiScoreSlope;
        sbuf.append(" RSSI score: ").append(score).append(",");

        // 5GHz band bonus.
        if (is5GHz) {
            score += band5GHzAward;
            sbuf.append(" 5GHz bonus: ").append(band5GHzAward).append(",");
        } else if (is6GHz) {
            score += band6GHzAward;
            sbuf.append(" 6GHz bonus: ").append(band6GHzAward).append(",");
        }

        // Last user selection award.
        int lastUserSelectedNetworkId = mWifiConfigManager.getLastSelectedNetwork();
        if (lastUserSelectedNetworkId != WifiConfiguration.INVALID_NETWORK_ID
                && lastUserSelectedNetworkId == network.networkId) {
            long timeDifference = mClock.getElapsedSinceBootMillis()
                    - mWifiConfigManager.getLastSelectedTimeStamp();
            if (timeDifference > 0) {
                int decay = (int) (timeDifference / LAST_SELECTION_AWARD_DECAY_MSEC);
                int bonus = Math.max(lastSelectionAward - decay, 0);
                score += bonus;
                sbuf.append(" User selection ").append(timeDifference)
                        .append(" ms ago, bonus: ").append(bonus).append(",");
            }
        }

        // Same network award.
        if (currentNetwork != null && network.networkId == currentNetwork.networkId) {
            score += sameNetworkAward;
            sbuf.append(" Same network bonus: ").append(sameNetworkAward).append(",");

            // When firmware roaming is supported, equivalent BSSIDs (the ones under the
            // same network as the currently connected one) get the same BSSID award.
            if (mConnectivityHelper.isFirmwareRoamingSupported()
                    && currentBssid != null && !currentBssid.equals(scanResult.BSSID)) {
                score += sameBssidAward;
                sbuf.append(" Equivalent BSSID bonus: ").append(sameBssidAward).append(",");
            }
        }

        // Same BSSID award.
        if (currentBssid != null && currentBssid.equals(scanResult.BSSID)) {
            score += sameBssidAward;
            sbuf.append(" Same BSSID bonus: ").append(sameBssidAward).append(",");
        }

        // Security award.
        if (!WifiConfigurationUtil.isConfigForOpenNetwork(network)) {
            score += securityAward;
            sbuf.append(" Secure network bonus: ").append(securityAward).append(",");
        }

        sbuf.append(" ## Total score: ").append(score).append("\n");

        return score;
    }

    /**
     * Evaluate all the networks from the scan results and return
     * the WifiConfiguration of the network chosen for connection.
     *
     * @return configuration of the chosen network;
     *         null if no network in this category is available.
     */
    @Override
    public WifiConfiguration evaluateNetworks(List<ScanDetail> scanDetails,
                    WifiConfiguration currentNetwork, String currentBssid, boolean connected,
                    boolean untrustedNetworkAllowed,
                    @NonNull OnConnectableListener onConnectableListener) {
        int highestScore = Integer.MIN_VALUE;
        ScanResult scanResultCandidate = null;
        WifiConfiguration candidate = null;
        StringBuffer scoreHistory = new StringBuffer();

        for (ScanDetail scanDetail : scanDetails) {
            ScanResult scanResult = scanDetail.getScanResult();

            // One ScanResult can be associated with more than one network, hence we calculate all
            // the scores and use the highest one as the ScanResult's score.
            // TODO(b/112196799): this has side effects, rather not do that in an evaluator
            WifiConfiguration network =
                    mWifiConfigManager.getConfiguredNetworkForScanDetailAndCache(scanDetail);

            if (network == null) {
                continue;
            }

            // Ignore networks that the user has disallowed auto-join for.
            if (!network.allowAutojoin) {
                continue;
            }

            /**
             * Ignore Passpoint and Ephemeral networks. They are configured networks,
             * but without being persisted to the storage. They are evaluated by
             * {@link PasspointNetworkEvaluator} and {@link ScoredNetworkEvaluator}
             * respectively.
             */
            if (network.isPasspoint() || network.isEphemeral()) {
                continue;
            }

            WifiConfiguration.NetworkSelectionStatus status =
                    network.getNetworkSelectionStatus();
            // TODO (b/112196799): another side effect
            status.setSeenInLastQualifiedNetworkSelection(true);

            if (!status.isNetworkEnabled()) {
                continue;
            } else if (network.BSSID != null &&  !network.BSSID.equals("any")
                    && !network.BSSID.equals(scanResult.BSSID)) {
                // App has specified the only BSSID to connect for this
                // configuration. So only the matching ScanResult can be a candidate.
                localLog("Network " + WifiNetworkSelector.toNetworkString(network)
                        + " has specified BSSID " + network.BSSID + ". Skip "
                        + scanResult.BSSID);
                continue;
            } else if (network.enterpriseConfig != null
                    && network.enterpriseConfig.requireSimCredential()) {
                int subId = mTelephonyUtil.getBestMatchSubscriptionId(network);
                if (!mTelephonyUtil.isSimPresent(subId)) {
                    // Don't select if security type is EAP SIM/AKA/AKA' when SIM is not present.
                    localLog("No SIM card is good for Network "
                            + WifiNetworkSelector.toNetworkString(network));
                    continue;
                }
            }

            int score = calculateBssidScore(scanResult, network, currentNetwork, currentBssid,
                    scoreHistory);

            // Set candidate ScanResult for all saved networks to ensure that users can
            // override network selection. See WifiNetworkSelector#setUserConnectChoice.
            if (score > status.getCandidateScore()
                    || (score == status.getCandidateScore()
                        && status.getCandidate() != null
                        && scanResult.level > status.getCandidate().level)) {
                mWifiConfigManager.setNetworkCandidateScanResult(
                        network.networkId, scanResult, score);
            }

            // If the network is marked to use external scores, or is an open network with
            // curate saved open networks enabled, do not consider it for network selection.
            if (network.useExternalScores) {
                localLog("Network " + WifiNetworkSelector.toNetworkString(network)
                        + " has external score.");
                continue;
            }

            onConnectableListener.onConnectable(scanDetail,
                    mWifiConfigManager.getConfiguredNetwork(network.networkId), score);

            // TODO(b/112196799) - pull into common code
            if (score > highestScore
                    || (score == highestScore
                    && scanResultCandidate != null
                    && scanResult.level > scanResultCandidate.level)) {
                highestScore = score;
                scanResultCandidate = scanResult;
                mWifiConfigManager.setNetworkCandidateScanResult(
                        network.networkId, scanResultCandidate, highestScore);
                // Reload the network config with the updated info.
                candidate = mWifiConfigManager.getConfiguredNetwork(network.networkId);
            }
        }

        if (scoreHistory.length() > 0) {
            localLog("\n" + scoreHistory.toString());
        }

        if (scanResultCandidate == null) {
            localLog("did not see any good candidates.");
        }
        return candidate;
    }
}
