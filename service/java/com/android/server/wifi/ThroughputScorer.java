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
import android.util.Log;

import com.android.server.wifi.WifiCandidates.Candidate;
import com.android.server.wifi.WifiCandidates.ScoredCandidate;

import java.util.Collection;

/**
 * A candidate scorer that combines RSSI base score and network throughput score.
 */
final class ThroughputScorer implements WifiCandidates.CandidateScorer {
    private static final String TAG = "ThroughputScorer";
    private static final boolean DBG = false;
    /**
     * This should match WifiNetworkSelector.experimentIdFromIdentifier(getIdentifier())
     * when using the default ScoringParams.
     */
    public static final int THROUGHPUT_SCORER_DEFAULT_EXPID = 42330058;

    private final ScoringParams mScoringParams;

    // config_wifi_framework_RSSI_SCORE_OFFSET
    public static final int RSSI_SCORE_OFFSET = 85;

    // config_wifi_framework_RSSI_SCORE_SLOPE
    public static final int RSSI_SCORE_SLOPE_IS_4 = 4;

    // config_wifi_framework_SECURITY_AWARD
    public static final int SECURITY_AWARD = 10;

    // config_wifi_framework_LAST_SELECTION_AWARD
    public static final int LAST_SELECTION_AWARD_IS_480 = 480;

    // Bonus score for current network
    // High RSSI case:
    //   Bonus RSSI score: 10 (equivalent to RSSI variation 2.5dB)
    //   Bonus throughput score: 10 (equivalent to ~ 40Mbps).
    // Low RSSI case:
    //   Bonus RSSI score: 16 (equivalent to RSSI variation 4dB)
    //   Bonus throughput score: 4 (equivalent to ~ 16Mbps).
    public static final int CURRENT_NETWORK_BOOST = 20;

    // Max throughput in 11AC 40MHz 2SS mode with zero channel utilization
    public static final int MAX_THROUGHPUT_AC_40_MHZ_2SS_MBPS = 433;
    // Max throughput score in 11AC 40MHz 2SS mode
    public static final int MAX_THROUGHPUT_BONUS_SCORE_AC_40_MHZ_2SS = 120;

    // Max throughput bonus score for all possible modes
    public static final int MAX_THROUGHPUT_BONUS_SCORE = 200;

    private static final boolean USE_USER_CONNECT_CHOICE = true;

    ThroughputScorer(ScoringParams scoringParams) {
        mScoringParams = scoringParams;
    }

    @Override
    public String getIdentifier() {
        return "ThroughputScorer";
    }

    /**
     * Calculates an individual candidate's score.
     */
    private ScoredCandidate scoreCandidate(Candidate candidate) {
        int rssiSaturationThreshold = mScoringParams.getGoodRssi(candidate.getFrequency());
        int rssi = Math.min(candidate.getScanRssi(), rssiSaturationThreshold);
        int rssiBaseScore = (rssi + RSSI_SCORE_OFFSET) * RSSI_SCORE_SLOPE_IS_4;

        int throughputBonusScore = calculateThroughputBonusScore(candidate);

        int lastSelectionBonusScore = (int)
                (candidate.getLastSelectionWeight() * LAST_SELECTION_AWARD_IS_480);

        int currentNetworkBoost = candidate.isCurrentNetwork() ? CURRENT_NETWORK_BOOST : 0;

        int securityAward = candidate.isOpenNetwork() ? 0 : SECURITY_AWARD;

        // To simulate the old strict priority rule, subtract a penalty based on
        // which evaluator added the candidate.
        int evaluatorGroupScore = -1000 * candidate.getEvaluatorId();

        int score = rssiBaseScore + throughputBonusScore + lastSelectionBonusScore
                + currentNetworkBoost + securityAward + evaluatorGroupScore;

        if (DBG) {
            Log.d(TAG, " rssiScore: " + rssiBaseScore
                    + " throughputScore: " + throughputBonusScore
                    + " lastSelectionBonus: " + lastSelectionBonusScore
                    + " currentNetworkBoost: " + currentNetworkBoost
                    + " securityAward: " + securityAward
                    + " evaluatorScore: " + evaluatorGroupScore
                    + " final score: " + score);
        }

        // The old method breaks ties on the basis of RSSI, which we can
        // emulate easily since our score does not need to be an integer.
        double tieBreaker = candidate.getScanRssi() / 1000.0;
        return new ScoredCandidate(score + tieBreaker, 10,
                USE_USER_CONNECT_CHOICE, candidate);
    }

    private int calculateThroughputBonusScore(Candidate candidate) {
        int throughputScoreRaw = candidate.getPredictedThroughputMbps()
                * MAX_THROUGHPUT_BONUS_SCORE_AC_40_MHZ_2SS
                / MAX_THROUGHPUT_AC_40_MHZ_2SS_MBPS;
        return Math.min(throughputScoreRaw, MAX_THROUGHPUT_BONUS_SCORE);
    }

    @Override
    public ScoredCandidate scoreCandidates(@NonNull Collection<Candidate> candidates) {
        ScoredCandidate choice = ScoredCandidate.NONE;
        for (Candidate candidate : candidates) {
            ScoredCandidate scoredCandidate = scoreCandidate(candidate);
            if (scoredCandidate.value > choice.value) {
                choice = scoredCandidate;
            }
        }
        // Here we just return the highest scored candidate; we could
        // compute a new score, if desired.
        return choice;
    }

}
