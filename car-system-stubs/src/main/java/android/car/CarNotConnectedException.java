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

package android.car;

/**
 * COMPILE-TIME STUB. Never packaged, never executed. See {@code FORK.md}.
 *
 * <p>Signature verified with {@code javap} against the public {@code android.car.jar}: it extends
 * {@link RuntimeException} (i.e. unchecked), which is why {@code car-media-common} can declare
 * {@code throws CarNotConnectedException} on an interface method and still use lambdas.
 */
public class CarNotConnectedException extends RuntimeException {

    public CarNotConnectedException() {
        super();
    }

    public CarNotConnectedException(String message) {
        super(message);
    }

    public CarNotConnectedException(String message, Throwable cause) {
        super(message, cause);
    }

    public CarNotConnectedException(Exception cause) {
        super(cause);
    }
}
