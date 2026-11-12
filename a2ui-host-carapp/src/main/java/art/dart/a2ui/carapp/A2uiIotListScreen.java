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
import androidx.annotation.VisibleForTesting;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.model.Toggle;

import art.dart.a2ui.iot.IotDevice;
import art.dart.a2ui.iot.IotRepository;
import art.dart.a2ui.iot.IotScreen;
import art.dart.a2ui.telemetry.A2uiTelemetry;
import art.dart.a2ui.telemetry.TelemetryEvent;

/**
 * The IoT flow's device list: every anchored device as a row with a toggle switch. Flipping a
 * switch writes through to the repository (local state today, the gateway client per the R3
 * phasing) and refreshes; three anchored devices stay under the six-row limit by construction.
 */
public final class A2uiIotListScreen extends Screen implements IotScreen {

    private final IotRepository mRepository;

    public A2uiIotListScreen(@NonNull CarContext carContext, @NonNull IotRepository repository) {
        super(carContext);
        mRepository = repository;
    }

    @Override
    public int iotDepth() {
        return 1;
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        A2uiTelemetry.event(TelemetryEvent.TEMPLATE_RENDERED, "template", "ListTemplate",
                "host", "a");
        ItemList.Builder devices = new ItemList.Builder();
        for (IotDevice device : mRepository.devices()) {
            final String id = device.getId();
            // A row with a toggle must not carry an on-click listener; the switch flips.
            devices.addItem(new Row.Builder()
                    .setTitle(device.getName())
                    .addText(describe(device))
                    .setToggle(new Toggle.Builder(
                            checked -> setDevice(id, checked))
                            .setChecked(device.isOn())
                            .build())
                    .build());
        }
        return new ListTemplate.Builder()
                .setTitle("Devices")
                .setHeaderAction(Action.BACK)
                .setSingleList(devices.build())
                .build();
    }

    /** Sets a device to the switch's state and refreshes. */
    @VisibleForTesting
    void setDevice(@NonNull String id, boolean checked) {
        if (mRepository.setOn(id, checked)) {
            invalidate();
        }
    }

    @VisibleForTesting
    static String describe(@NonNull IotDevice device) {
        String state = device.isOn() ? "On" : "Off";
        if (device.getType() == IotDevice.Type.THERMOSTAT) {
            int whole = device.getLevel() / 2;
            String half = device.getLevel() % 2 == 0 ? "" : ".5";
            return state + " · " + whole + half + "°C";
        }
        return state + " · " + device.getLevel() + "%";
    }
}
