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

import android.net.wifi.WifiNetworkSuggestion;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Network Suggestions Manager.
 * NOTE: This class should always be invoked from the main wifi service thread.
 */
@NotThreadSafe
public class WifiNetworkSuggestionsManager {
    private static final String TAG = "WifiNetworkSuggestionsManager";
    /**
     * Map of package name of an app to the set of active network suggestions provided by the app.
     */
    private final Map<String, Set<WifiNetworkSuggestion>> mActiveNetworkSuggestionsPerApp =
            new HashMap<>();

    /**
     * Verbose logging flag.
     */
    private boolean mVerboseLoggingEnabled = false;

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = verbose > 0;
    }

    /**
     * Add the provided list of network suggestions from the corresponding app's active list.
     */
    public boolean add(List<WifiNetworkSuggestion> networkSuggestions, String packageName) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Adding " + networkSuggestions.size() + " networks from " + packageName);
        }
        Set<WifiNetworkSuggestion> activeNetworkSuggestionsForApp =
                mActiveNetworkSuggestionsPerApp.get(packageName);
        if (activeNetworkSuggestionsForApp == null) {
            activeNetworkSuggestionsForApp = new HashSet<>();
            mActiveNetworkSuggestionsPerApp.put(packageName, activeNetworkSuggestionsForApp);
        }
        // check if the app is trying to in-place modify network suggestions.
        if (!Collections.disjoint(activeNetworkSuggestionsForApp, networkSuggestions)) {
            Log.e(TAG, "Failed to add network suggestions for " + packageName
                    + ". Modification of active network suggestions disallowed");
            return false;
        }
        activeNetworkSuggestionsForApp.addAll(networkSuggestions);
        return true;
    }

    /**
     * Remove the provided list of network suggestions from the corresponding app's active list.
     */
    public boolean remove(List<WifiNetworkSuggestion> networkSuggestions, String packageName) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Removing " + networkSuggestions.size() + " networks from " + packageName);
        }
        Set<WifiNetworkSuggestion> activeNetworkSuggestionsForApp =
                mActiveNetworkSuggestionsPerApp.get(packageName);
        if (activeNetworkSuggestionsForApp == null) {
            Log.e(TAG, "Failed to remove network suggestions for " + packageName
                    + ". No active network suggestions found");
            return false;
        }
        if (!networkSuggestions.isEmpty()) {
            // check if all the request network suggestions are present in the active list.
            if (!activeNetworkSuggestionsForApp.containsAll(networkSuggestions)) {
                Log.e(TAG, "Failed to remove network suggestions for " + packageName
                        + ". Network suggestions not found in active network suggestions");
                return false;
            }
            activeNetworkSuggestionsForApp.removeAll(networkSuggestions);
        } else {
            // empty list is used to clear everything for the app.
            activeNetworkSuggestionsForApp.clear();
        }
        // Remove the set from map if empty.
        if (activeNetworkSuggestionsForApp.isEmpty()) {
            mActiveNetworkSuggestionsPerApp.remove(packageName);
        }
        return true;
    }

    /**
     * Returns a set of all network suggestions across all apps.
     */
    @VisibleForTesting
    public Set<WifiNetworkSuggestion> getAllNetworkSuggestions() {
        return mActiveNetworkSuggestionsPerApp.values()
                .stream()
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
    }

    /**
     * Dump of {@link WifiNetworkSuggestionsManager}.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiNetworkSuggestionsManager");
        pw.println("WifiNetworkSuggestionsManager - Networks Begin ----");
        for (Map.Entry<String, Set<WifiNetworkSuggestion>> networkSuggestionsEntry
                : mActiveNetworkSuggestionsPerApp.entrySet()) {
            pw.println("Package Name: " + networkSuggestionsEntry.getKey());
            for (WifiNetworkSuggestion networkSuggestions : networkSuggestionsEntry.getValue()) {
                pw.println("Network: " + networkSuggestions);
            }
        }
        pw.println("WifiNetworkSuggestionsManager - Networks End ----");
    }
}

