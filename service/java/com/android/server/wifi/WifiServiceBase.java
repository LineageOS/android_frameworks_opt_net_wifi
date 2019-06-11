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

/**
 * Base class for all the wifi services. This is used to manage the lifetime of the services.
 * Each service should override the methods corresponding to the lifetime events they care about.
 *
 * Note: Services can listen to these system broadcasts on their own, but they're explicitly listed
 * here to better manage inter-service dependencies. (For ex: wifi aware service needs wifi service
 * to initialize the HAL first).
 */
public interface WifiServiceBase {
    /**
     * Invoked when the APK service is bound. Should bed used to publish
     * it's binder service & perform necessary initialization. This should happen very close to
     * bootup phase {@link SystemService#PHASE_BOOT_COMPLETED} in system_server.
     */
    default void onStart() {}

    /**
     * Invoked when the user switches.
     *
     * @param userId Id for the new user.
     */
    default void onSwitchUser(int userId) {}

    /**
     * Invoked when the user unlocks.
     *
     * @param userId Id for the user.
     */
    default void onUnlockUser(int userId) {}

    /**
     * Invoked when the user stops.
     *
     * @param userId Id for the user.
     */
    default void onStopUser(int userId) {}
}
