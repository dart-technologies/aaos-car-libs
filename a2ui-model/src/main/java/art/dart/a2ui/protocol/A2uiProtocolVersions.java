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
package art.dart.a2ui.protocol;

import androidx.annotation.NonNull;

/** The wire {@code version} strings this implementation routes, and the v1.0 cutover. */
public final class A2uiProtocolVersions {
    /** The pre-1.0 wire version: bare envelope keys, stored nulls, mandatory surface catalog. */
    @NonNull
    public static final String V09 = "v0.9";
    /** The v1.0 wire version: {@code @} envelope mixins, null-deletes, optional catalog. */
    @NonNull
    public static final String V1 = "1.0";

    private A2uiProtocolVersions() {
    }

    /** Whether the surface or message follows the v1.0 wire rules. */
    public static boolean isV1(@NonNull String version) {
        return V1.equals(version);
    }
}
