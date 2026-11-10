/*
 * Copyright (C) 2026 Dart Technologies
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
package art.dart.a2ui.carapp;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

import art.dart.a2ui.telemetry.A2uiTelemetry;
import art.dart.a2ui.telemetry.LogBackend;

/**
 * The entry point the templates host binds to: {@code com.google.android.apps.automotive.templates.host},
 * present on stock GAS images, so nothing extra is installed.
 */
public final class A2uiCarAppService extends CarAppService {

    @Override
    public void onCreate() {
        super.onCreate();
        // Debuggable builds log telemetry to logcat; release stays silent until Firebase lands.
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)
                != 0) {
            A2uiTelemetry.install(new LogBackend());
        }
    }

    // metalava api-lint: OnNameExpected, suppressed: this overrides CarAppService's abstract
    // createHostValidator().
    @SuppressWarnings("OnNameExpected")
    @NonNull
    @Override
    public HostValidator createHostValidator() {
        // ALLOW_ALL_HOSTS lets any process claiming to be a host drive this UI, so it is limited to
        // debuggable builds; release validates known host signatures.
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        }
        return new HostValidator.Builder(getApplicationContext())
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build();
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new A2uiSession();
    }
}
