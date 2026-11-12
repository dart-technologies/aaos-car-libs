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
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The offline device fixture: two lights and a thermostat. Control is local state today; the
 * gateway client replaces these calls per the R3 phasing, not the screens. Unknown ids are
 * refused (null/false), never created — a typo must not invent a device.
 */
public final class IotRepository {

    private final List<IotDevice> mDevices = new ArrayList<>();

    /** Two lights and a thermostat, the states the emulator walk starts from. */
    @NonNull
    public static IotRepository anchored() {
        IotRepository repository = new IotRepository();
        repository.mDevices.add(new IotDevice("living_room_light", "Living room light",
                IotDevice.Type.LIGHT, true, 80));
        repository.mDevices.add(new IotDevice("porch_light", "Porch light",
                IotDevice.Type.LIGHT, false, 100));
        repository.mDevices.add(new IotDevice("hall_thermostat", "Hall thermostat",
                IotDevice.Type.THERMOSTAT, true, 41));
        return repository;
    }

    @NonNull
    public List<IotDevice> devices() {
        return Collections.unmodifiableList(mDevices);
    }

    @Nullable
    public IotDevice device(@NonNull String id) {
        for (IotDevice device : mDevices) {
            if (device.getId().equals(id)) {
                return device;
            }
        }
        return null;
    }

    /** Flips a device; false when the id names nothing. */
    public boolean toggle(@NonNull String id) {
        IotDevice device = device(id);
        if (device == null) {
            return false;
        }
        device.setOn(!device.isOn());
        return true;
    }

    /** Sets a device on or off; false when the id names nothing. */
    public boolean setOn(@NonNull String id, boolean on) {
        IotDevice device = device(id);
        if (device == null) {
            return false;
        }
        device.setOn(on);
        return true;
    }

    /**
     * Sets a level, clamped to the type (0-100 percent for lights, 30-60 half-degrees, i.e.
     * 15-30 C, for thermostats); false when the id names nothing.
     */
    public boolean setLevel(@NonNull String id, int level) {
        IotDevice device = device(id);
        if (device == null) {
            return false;
        }
        if (device.getType() == IotDevice.Type.THERMOSTAT) {
            device.setLevel(Math.max(30, Math.min(60, level)));
        } else {
            device.setLevel(Math.max(0, Math.min(100, level)));
        }
        return true;
    }
}
