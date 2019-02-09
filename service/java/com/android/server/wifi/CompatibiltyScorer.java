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
 * A candidate scorer that attempts to match the previous behavior.
 */
final class CompatibiltyScorer implements WifiCandidates.CandidateScorer {

    private final ScoringParams mScoringParams;

    // config_wifi_framework_RSSI_SCORE_OFFSET
    public static final int RSSI_SCORE_OFFSET = 85;

    // config_wifi_framework_RSSI_SCORE_SLOPE
    public static final int RSSI_SCORE_SLOPE_IS_4 = 4;

    // config_wifi_framework_5GHz_preference_boost_factor
    public static final int BAND_5GHZ_AWARD_IS_40 = 40;

    // config_wifi_framework_SECURITY_AWARD
    public static final int SECURITY_AWARD_IS_80 = 80;

    CompatibiltyScorer(ScoringParams scoringParams) {
        mScoringParams = scoringParams;
    }

    @Override
    public String getIdentifier() {
        return "CompatibiltyScorer";
    }

    /**
     * Calculates an individual candidate's score.
     *
     * This relies mostly on the scores provided by the evaluator.
     */
    private ScoredCandidate scoreCandidate(Candidate candidate) {
        // Start with the score that the evaluator supplied
        int score = candidate.getEvaluatorScore();
        if (score == 0) {
            // If the evaluator simply returned a score of zero, supply one based on the RSSI
            int rssiSaturationThreshold = mScoringParams.getGoodRssi(candidate.getFrequency());
            int rssi = Math.min(candidate.getScanRssi(), rssiSaturationThreshold);
            score = (rssi + RSSI_SCORE_OFFSET) * RSSI_SCORE_SLOPE_IS_4;
            if (candidate.getFrequency()
                     >= ScoringParams.MINIMUM_5GHZ_BAND_FREQUENCY_IN_MEGAHERTZ) {
                score += BAND_5GHZ_AWARD_IS_40;
            }
            // Note - compared to the saved network evaluator, we are skipping some
            // awards and bonuses. As long as we are still generating a score in the
            // saved network evaluator, this is not a problem because for saved
            // networks this code will not be used at all.
            // - skipping award for last user selection
            //       config_wifi_framework_LAST_SELECTION_AWARD
            //           = 480 - (time in minutes since choice)
            // - skipping award for same network
            //       config_wifi_framework_current_network_boost
            //           = 16
            // - skipping award for equivalent / same BSSID
            //       config_wifi_framework_SAME_BSSID_AWARD
            //           = 24
            if (!candidate.isOpenNetwork()) {
                score += SECURITY_AWARD_IS_80;
            }
        }

        // To simulate the old strict priority rule, subtract a penalty based on
        // which evaluator added the candidate.
        score -= 1000 * candidate.getEvaluatorIndex();

        return new ScoredCandidate(score, 10, candidate);
    }

    @Override
    public ScoredCandidate scoreCandidates(@NonNull Collection<Candidate> group) {
        ScoredCandidate choice = ScoredCandidate.NONE;
        for (Candidate candidate : group) {
            ScoredCandidate scoredCandidate = scoreCandidate(candidate);
            if (scoredCandidate.value > choice.value) {
                choice = scoredCandidate;
            }
        }
        // Here we just return the highest scored candidate; we could
        // compute a new score, if desired.
        return choice;
    }

    @Override
    public boolean userConnectChoiceOverrideWanted() {
        return true;
    }

}
