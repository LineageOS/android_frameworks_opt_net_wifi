/*
 * Copyright 2019 The Android Open Source Project
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

import com.android.server.wifi.WifiCandidates.Candidate;
import com.android.server.wifi.WifiCandidates.ScoredCandidate;

import java.util.Collection;

/**
 * An example scorer that roughly matches SavedNetworkEvaluator#calculateBssidScore
 */
final class CandidateScorerExample implements WifiCandidates.CandidateScorer {

    private final ScoringParams mScoringParams;

    // config_wifi_framework_RSSI_SCORE_OFFSET
    public static final int RSSI_SCORE_OFFSET = 85;

    // config_wifi_framework_RSSI_SCORE_SLOPE
    public static final int RSSI_SCORE_SLOPE_IS_4 = 4;

    // config_wifi_framework_5GHz_preference_boost_factor
    public static final int BAND_5GHZ_AWARD_IS_40 = 40;

    // config_wifi_framework_SECURITY_AWARD
    public static final int SECURITY_AWARD_IS_80 = 80;

    CandidateScorerExample(ScoringParams scoringParams) {
        mScoringParams = scoringParams;
    }

    @Override
    public String getIdentifier() {
        return "CandidateScorerExample";
    }

    /**
     * Calculates an individual candidate's score.
     *
     * Ideally, this is a pure function of the candidate, and side-effect free.
     */
    private ScoredCandidate scoreCandidate(Candidate candidate) {
        final boolean is5GHz = (candidate.getFrequency() >= 5000);
        final int rssiSaturationThreshold = mScoringParams.getGoodRssi(candidate.getFrequency());
        final int rssi = Math.min(candidate.getScanRssi(), rssiSaturationThreshold);

        int score = (rssi + RSSI_SCORE_OFFSET) * RSSI_SCORE_SLOPE_IS_4;
        if (is5GHz) {
            score += BAND_5GHZ_AWARD_IS_40;
        }
        // Award for last user selection
        //       config_wifi_framework_LAST_SELECTION_AWARD = 480 - (time in minutes since choice)
        score += (int) (candidate.lastSelectionWeight * 480);

        // XXX - skipping award for same network
        //       config_wifi_framework_current_network_boost = 16
        // XXX - skipping award for equivalent / same BSSID
        //       config_wifi_framework_SAME_BSSID_AWARD = 24
        if (!WifiConfigurationUtil.isConfigForOpenNetwork(candidate.config)) {
            score += SECURITY_AWARD_IS_80;
        }
        return new ScoredCandidate(score, 10, candidate);
    }

    @Override
    public ScoredCandidate scoreCandidates(@NonNull Collection<Candidate> group) {
        ScoredCandidate choice = ScoredCandidate.NONE;
        for (Candidate candidate : group) {
            ScoredCandidate scoredCandidate = scoreCandidate(candidate);
            if (scoredCandidate != null && scoredCandidate.value > choice.value) {
                choice = scoredCandidate;
            }
        }
        // Here we just return the highest scored candidate; we could
        // compute a new score, if desired.
        return choice;
    }

    @Override
    public boolean userConnectChoiceOverrideWanted() {
        return false;
    }
}
