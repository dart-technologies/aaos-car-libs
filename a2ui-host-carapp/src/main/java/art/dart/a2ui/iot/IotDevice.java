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
package art.dart.a2ui.iot;

import androidx.annotation.NonNull;

/**
 * One controllable device: a light with on/off plus brightness, or a thermostat with on/off
 * plus a set point. Level semantics belong to the type — percent for lights, half-degrees for
 * thermostats — and the repository clamps them.
 */
public final class IotDevice {

    /** The controllable types the anchor set exercises. */
    // metalava api-lint: Enum. Suppressed, not converted to @IntDef: callers switch on the
    // type and an int def would trade exhaustiveness for nothing at this scale.
    @SuppressWarnings("Enum")
    public enum Type {
        LIGHT, THERMOSTAT
    }

    private final String mId;
    private final String mName;
    private final Type mType;
    private boolean mOn;
    private int mLevel;

    public IotDevice(@NonNull String id, @NonNull String name, @NonNull Type type, boolean on,
            int level) {
        mId = id;
        mName = name;
        mType = type;
        mOn = on;
        mLevel = level;
    }

    @NonNull public String getId() { return mId; }
    @NonNull public String getName() { return mName; }
    @NonNull public Type getType() { return mType; }
    public boolean isOn() { return mOn; }
    public int getLevel() { return mLevel; }

    void setOn(boolean on) {
        mOn = on;
    }

    void setLevel(int level) {
        mLevel = level;
    }
}
