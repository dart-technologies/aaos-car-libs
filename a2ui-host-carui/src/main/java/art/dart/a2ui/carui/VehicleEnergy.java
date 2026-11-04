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
package art.dart.a2ui.carui;

import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.function.IntConsumer;

/**
 * The traction battery's state of charge from the vehicle: EV_BATTERY_LEVEL over
 * INFO_EV_BATTERY_CAPACITY through CarPropertyManager. Needs the runtime CAR_ENERGY permission;
 * without it, or without car service, it reports nothing (the debug VEHICLE broadcast remains).
 */
final class VehicleEnergy {

    private static final String TAG = "A2uiVehicleEnergy";
    static final String CAR_ENERGY = "android.car.permission.CAR_ENERGY";

    private final IntConsumer mOnPercent;
    @Nullable private Car mCar;
    @Nullable private CarPropertyManager mProperties;
    private float mCapacityWh = -1f;

    private final CarPropertyManager.CarPropertyEventCallback mCallback =
            new CarPropertyManager.CarPropertyEventCallback() {
                @Override
                public void onChangeEvent(CarPropertyValue value) {
                    if (value.getPropertyId() == VehiclePropertyIds.EV_BATTERY_LEVEL
                            && value.getValue() instanceof Float) {
                        deliver((Float) value.getValue());
                    }
                }

                @Override
                public void onErrorEvent(int propertyId, int areaId) {
                    Log.w(TAG, "property error " + propertyId);
                }
            };

    VehicleEnergy(@NonNull IntConsumer onPercent) {
        mOnPercent = onPercent;
    }

    /** Connects and subscribes; a no-op without the permission or car service. */
    void start(@NonNull Context context, @NonNull Handler main) {
        if (context.checkSelfPermission(CAR_ENERGY) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "no " + CAR_ENERGY + "; battery level unavailable (adb shell pm grant "
                    + context.getPackageName() + " " + CAR_ENERGY + ")");
            return;
        }
        try {
            mCar = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                    (car, ready) -> {
                        if (ready) subscribe(car);
                        else mProperties = null;
                    });
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "car service unavailable; battery level unavailable", unavailable);
        }
    }

    void stop() {
        CarPropertyManager properties = mProperties;
        mProperties = null;
        try {
            if (properties != null) properties.unregisterCallback(mCallback);
            if (mCar != null) mCar.disconnect();
        } catch (RuntimeException ignored) {
            // Already gone.
        }
        mCar = null;
    }

    private void subscribe(@NonNull Car car) {
        try {
            CarPropertyManager properties =
                    (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
            if (properties == null) return;
            CarPropertyValue<Float> capacity = properties.getProperty(Float.class,
                    VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY, 0);
            mCapacityWh = capacity == null || capacity.getValue() == null ? -1f
                    : capacity.getValue();
            mProperties = properties;
            // EV_BATTERY_LEVEL is a continuous property: it needs a sample rate.
            boolean subscribed = properties.registerCallback(mCallback,
                    VehiclePropertyIds.EV_BATTERY_LEVEL, CarPropertyManager.SENSOR_RATE_NORMAL);
            Log.i(TAG, "EV_BATTERY_LEVEL subscribed=" + subscribed + " capacityWh=" + mCapacityWh);
            CarPropertyValue<Float> level = properties.getProperty(Float.class,
                    VehiclePropertyIds.EV_BATTERY_LEVEL, 0);
            if (level != null && level.getValue() != null) deliver(level.getValue());
        } catch (RuntimeException unreadable) {
            Log.w(TAG, "battery properties unreadable", unreadable);
        }
    }

    private void deliver(float levelWh) {
        int percent = percentOf(levelWh, mCapacityWh);
        if (percent >= 0) mOnPercent.accept(percent);
    }

    /** Rounded percent, or -1 when the capacity is unknown or the values are nonsense. */
    static int percentOf(float levelWh, float capacityWh) {
        if (!(capacityWh > 0f) || levelWh < 0f || levelWh > capacityWh * 1.05f) return -1;
        return Math.min(100, Math.round(levelWh * 100f / capacityWh));
    }
}
