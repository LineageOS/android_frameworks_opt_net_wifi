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
import android.net.wifi.WifiInfo;

import com.android.server.wifi.WifiCandidates.Candidate;
import com.android.server.wifi.WifiCandidates.ScoredCandidate;
import com.android.server.wifi.WifiScoreCardProto.Event;

import java.util.Collection;

/**
 * A candidate scorer that uses the scorecard to influence the choice.
 */
final class ScoreCardBasedScorer implements WifiCandidates.CandidateScorer {

    private final ScoringParams mScoringParams;

    // config_wifi_framework_RSSI_SCORE_OFFSET
    public static final int RSSI_SCORE_OFFSET = 85;

    // config_wifi_framework_RSSI_SCORE_SLOPE
    public static final int RSSI_SCORE_SLOPE_IS_4 = 4;

    // config_wifi_framework_5GHz_preference_boost_factor
    public static final int BAND_5GHZ_AWARD_IS_40 = 40;

    // config_wifi_framework_SECURITY_AWARD
    public static final int SECURITY_AWARD_IS_80 = 80;

    // config_wifi_framework_LAST_SELECTION_AWARD
    public static final int LAST_SELECTION_AWARD_IS_480 = 480;

    // Only use scorecard id we have data from this many polls
    public static final int MIN_POLLS_FOR_SIGNIFICANCE = 30;

    ScoreCardBasedScorer(ScoringParams scoringParams) {
        mScoringParams = scoringParams;
    }

    @Override
    public String getIdentifier() {
        return "ScoreCardBasedScorer";
    }

    /**
     * Calculates an individual candidate's score.
     */
    private ScoredCandidate scoreCandidate(Candidate candidate) {
        // Start with the score that the evaluator supplied
        int rssiSaturationThreshold = mScoringParams.getGoodRssi(candidate.getFrequency());
        int rssi = Math.min(candidate.getScanRssi(), rssiSaturationThreshold);
        int cutoff = estimatedCutoff(candidate);
        int score = (rssi - cutoff) * RSSI_SCORE_SLOPE_IS_4;

        if (candidate.getFrequency() >= ScoringParams.MINIMUM_5GHZ_BAND_FREQUENCY_IN_MEGAHERTZ) {
            score += BAND_5GHZ_AWARD_IS_40;
        }
        if (candidate.isOpenNetwork()) {
            score += SECURITY_AWARD_IS_80;
        }
        score += (int) (candidate.getLastSelectionWeight() * LAST_SELECTION_AWARD_IS_480);
        // XXX - skipping award for same network
        //        config_wifi_framework_current_network_boost = 16
        // XXX - skipping award for equivalent / same BSSID
        //        config_wifi_framework_SAME_BSSID_AWARD = 24

        // To simulate the old strict priority rule, subtract a penalty based on
        // which evaluator added the candidate.
        score -= 1000 * candidate.getEvaluatorIndex();

        return new ScoredCandidate(score, 10, candidate);
    }

    private int estimatedCutoff(Candidate candidate) {
        int cutoff = -RSSI_SCORE_OFFSET;
        WifiScoreCardProto.Signal signal = candidate.getEventStatistics(Event.SIGNAL_POLL);
        if (signal == null) return cutoff;
        if (!signal.hasRssi()) return cutoff;
        if (signal.getRssi().getCount() > MIN_POLLS_FOR_SIGNIFICANCE) {
            double mean = signal.getRssi().getSum() / signal.getRssi().getCount();
            double mean_square = signal.getRssi().getSumOfSquares() / signal.getRssi().getCount();
            double variance = mean_square - mean * mean;
            double sigma = Math.sqrt(variance);
            double value = mean - 2.0 * sigma;
            cutoff = (int) Math.min(Math.max(value, WifiInfo.MIN_RSSI), WifiInfo.MAX_RSSI);
        }
        return cutoff;
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
