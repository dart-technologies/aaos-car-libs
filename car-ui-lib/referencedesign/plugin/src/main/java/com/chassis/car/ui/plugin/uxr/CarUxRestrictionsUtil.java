/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.chassis.car.ui.plugin.uxr;

import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Utility class to access {@link CarUxRestrictionsManager}.
 *
 * <p>This class must be a singleton because only one listener can be registered with {@link
 * CarUxRestrictionsManager} at a time, as documented in
 * {@link CarUxRestrictionsManager#registerListener}.
 */
public class CarUxRestrictionsUtil {
    private static final String TAG = "CarUxRestrictionsUtil";

    @NonNull
    private volatile CarUxRestrictions mCarUxRestrictions = getDefaultRestrictions();

    public enum RestrictionsSource { NEVER_DELIVERED, DELIVERED, DEMO, DISCONNECTED }

    /** An atomic restrictions/provenance pair captured from one lifecycle state. */
    public static final class RestrictionsSnapshot {
        private final CarUxRestrictions mRestrictions;
        private final RestrictionsSource mSource;

        private RestrictionsSnapshot(@NonNull CarUxRestrictions restrictions,
                @NonNull RestrictionsSource source) {
            mRestrictions = restrictions;
            mSource = source;
        }

        @NonNull public CarUxRestrictions getRestrictions() { return mRestrictions; }
        @NonNull public RestrictionsSource getSource() { return mSource; }
    }

    @NonNull
    private volatile RestrictionsSource mRestrictionsSource = RestrictionsSource.NEVER_DELIVERED;

    private final Object mStateLock = new Object();
    private final Object mObserverLock = new Object();
    @Nullable private Car mCar;
    @Nullable private CarUxRestrictionsManager mManager;
    private boolean mDisconnected;
    private final int mDisplayId;

    private final Set<OnUxRestrictionsChangedListener> mObservers =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final SparseArray<CarUxRestrictionsUtil> sInstances = new SparseArray<>();
    @Nullable private static DisplayManager.DisplayListener sDisplayOwner;

    private CarUxRestrictionsUtil(Context context, int displayId) {
        mDisplayId = displayId;
        CarUxRestrictionsManager.OnUxRestrictionsChangedListener listener =
                this::applyDelivered;

        try {
            // Dart fork fix: bind with the APPLICATION context, not the caller's.
            //
            // This class is a process-lifetime singleton (see getInstance), but callers hand it a
            // View's context — SearchController passes mStub.getContext(), RecyclerViewImpl passes
            // its own — which is an Activity. The ServiceConnection created here was therefore
            // owned by the first Activity to touch the plugin and outlived it, producing on every
            // subsequent Activity teardown:
            //
            //   E/ActivityThread: Activity ... has leaked ServiceConnection android.car.Car$3@...
            //       at android.car.Car.createCar(Car.java:2032)
            //       at ...uxr.CarUxRestrictionsUtil.<init>(CarUxRestrictionsUtil.java:75)
            //
            // Observed once per test activity across car-rotary-lib's instrumented run. car-ui-lib's
            // copy of this class already does the right thing; this copy did not, and this copy is
            // the one OEMs clone.
            // ...but NOT unconditionally. A plugin Context is created with the plugin's
            // classloader via createPackageContext, and its getApplicationContext() can be NULL —
            // the plugin has no Application object in the host process. Passing that straight to
            // Car.createCar throws inside assertNonNullContext, which surfaces as an
            // InflateException when CarUiRecyclerView is inflated and kills the process.
            //
            // Caught by the full instrumented sweep after the first version of this fix shipped:
            // car-ui-lib truncated at 108/497 with
            //   Caused by: java.lang.NullPointerException
            //       at android.car.Car.assertNonNullContext(Car.java:2442)
            //       at ...uxr.CarUxRestrictionsUtil.<init>(CarUxRestrictionsUtil.java:79)
            //
            // So: prefer the application context when there is one, keep the caller's when there
            // is not. The leak is avoided wherever it can be, and a null is never passed.
            Context carContext = displayContextFor(context, displayId);
            mCar = Car.createCar(carContext, null,
                    Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                    (Car car, boolean ready) -> {
                        synchronized (mStateLock) {
                            if (mDisconnected) {
                                releaseCar(car);
                                return;
                            }
                            mCar = car;
                        }
                        if (ready) {
                            CarUxRestrictionsManager carUxRestrictionsManager =
                                    (CarUxRestrictionsManager) car.getCarManager(
                                            Car.CAR_UX_RESTRICTION_SERVICE);
                            CarUxRestrictions initial;
                            synchronized (mStateLock) {
                                if (mDisconnected) {
                                    releaseCar(car);
                                    return;
                                }
                                mManager = carUxRestrictionsManager;
                                // Registration and lifecycle teardown are one transition. If
                                // disconnect could run between assigning mManager and registering,
                                // it would unregister an empty slot and this callback would then
                                // install a listener on a terminal instance.
                                carUxRestrictionsManager.registerListener(listener);
                                initial = carUxRestrictionsManager.getCurrentCarUxRestrictions();
                            }
                            applyDelivered(initial);
                        } else {
                            Log.w(TAG, "Car service disconnected, assuming fully restricted uxr");
                            applyCarServiceLost(RestrictionsSource.DISCONNECTED);
                        }
                    });

        } catch (SecurityException e) {
            // Dart fork note: upstream treated SecurityException as unrestricted (fail-open), as in
            // car-ui-lib's copy. null routes to getDefaultRestrictions(), which fails closed.
            Log.w(TAG, "Unable to connect to car service, assuming fully restricted", e);
            applyCarServiceLost(RestrictionsSource.NEVER_DELIVERED);
        }
    }

    private static Context displayContextFor(Context context, int displayId) {
        Context app = context.getApplicationContext();
        Context base = app != null ? app : context;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || displayId == Display.DEFAULT_DISPLAY) {
            return base;
        }
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        Display display = dm == null ? null : dm.getDisplay(displayId);
        return display == null ? base : base.createDisplayContext(display);
    }

    /**
     * Dart fork note: the fail-closed decision, kept apart from the Builder call (which cannot run
     * in a JVM test, since android.car.jar is a stub) so tests assert the production flags rather
     * than an injected supplier's. sFailClosedFactory is replaceable for the same reason.
     */
    @VisibleForTesting
    static final boolean FAIL_CLOSED_REQUIRES_DISTRACTION_OPTIMIZATION = true;

    @VisibleForTesting
    static final int FAIL_CLOSED_ACTIVE_RESTRICTIONS =
            CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED;

    private static java.util.function.Supplier<CarUxRestrictions> sFailClosedFactory =
            () -> new CarUxRestrictions.Builder(
                    FAIL_CLOSED_REQUIRES_DISTRACTION_OPTIMIZATION,
                    FAIL_CLOSED_ACTIVE_RESTRICTIONS, 0)
                    .build();

    @VisibleForTesting
    static void setFailClosedFactoryForTesting(
            @NonNull java.util.function.Supplier<CarUxRestrictions> factory) {
        sFailClosedFactory = factory;
    }

    /**
     * Dart fork addition: builds an instance with no car-service connection, so the observer
     * contract can be tested at all. The production path stays the private {@code Context}
     * constructor.
     */
    @VisibleForTesting
    static CarUxRestrictionsUtil newForTesting() {
        return new CarUxRestrictionsUtil(Display.DEFAULT_DISPLAY);
    }

    @VisibleForTesting
    static CarUxRestrictionsUtil newForTesting(int displayId) {
        return new CarUxRestrictionsUtil(displayId);
    }

    /** No-connection constructor backing {@link #newForTesting()}. */
    private CarUxRestrictionsUtil(int displayId) {
        mDisplayId = displayId;
    }

    @VisibleForTesting
    void deliverForTesting(@Nullable CarUxRestrictions carUxRestrictions) {
        applyDelivered(carUxRestrictions);
    }

    @NonNull
    private static CarUxRestrictions getDefaultRestrictions() {
        return sFailClosedFactory.get();
    }

    /**
     * Listener interface used to update clients on UxRestrictions changes
     */
    public interface OnUxRestrictionsChangedListener {
        /**
         * Called when CarUxRestrictions changes
         */
        void onRestrictionsChanged(@NonNull CarUxRestrictions carUxRestrictions);
    }

    /**
     * Returns the singleton sInstance of this class
     */
    @NonNull
    public static CarUxRestrictionsUtil getInstance(Context context) {
        int displayId = displayIdOf(context);
        synchronized (sInstances) {
            CarUxRestrictionsUtil instance = sInstances.get(displayId);
            if (instance == null) {
                instance = new CarUxRestrictionsUtil(context, displayId);
                sInstances.put(displayId, instance);
                ensureDisplayOwner(context);
            }
            return instance;
        }
    }

    private static int displayIdOf(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Display display = context.getDisplay();
                if (display != null) return display.getDisplayId();
            } catch (RuntimeException ignored) {
                // A plugin package context may not be associated with a display.
            }
        }
        return Display.DEFAULT_DISPLAY;
    }

    private static void ensureDisplayOwner(Context context) {
        synchronized (sInstances) {
            if (sDisplayOwner != null) return;
            DisplayManager dm = context.getSystemService(DisplayManager.class);
            if (dm == null) return;
            sDisplayOwner = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) { }
                @Override public void onDisplayChanged(int displayId) { }
                @Override public void onDisplayRemoved(int displayId) {
                    CarUxRestrictionsUtil removed;
                    synchronized (sInstances) {
                        removed = sInstances.get(displayId);
                    }
                    if (removed != null) removed.disconnect();
                }
            };
            dm.registerDisplayListener(sDisplayOwner, null);
        }
    }

    /**
     * Registers a listener on this class for updates to CarUxRestrictions. Multiple listeners may
     * be registered. Note that this class will only hold a weak reference to the listener, you must
     * maintain a strong reference to it elsewhere.
     */
    public void register(OnUxRestrictionsChangedListener listener) {
        CarUxRestrictions reading;
        synchronized (mObserverLock) {
            mObservers.add(listener);
            reading = mCarUxRestrictions;
        }
        listener.onRestrictionsChanged(reading);
    }

    /**
     * Unregisters a registered listener
     */
    public void unregister(OnUxRestrictionsChangedListener listener) {
        synchronized (mObserverLock) {
            mObservers.remove(listener);
        }
    }

    @NonNull public CarUxRestrictions getCurrentRestrictions() { return mCarUxRestrictions; }
    @NonNull public RestrictionsSource getRestrictionsSource() { return mRestrictionsSource; }
    @NonNull public RestrictionsSnapshot getRestrictionsSnapshot() {
        synchronized (mStateLock) {
            return new RestrictionsSnapshot(mCarUxRestrictions, mRestrictionsSource);
        }
    }
    public int getDisplayId() { return mDisplayId; }

    public void disconnect() {
        Car car;
        CarUxRestrictionsManager manager;
        synchronized (mStateLock) {
            if (mDisconnected) return;
            mDisconnected = true;
            car = mCar;
            manager = mManager;
            mCar = null;
            mManager = null;
            mCarUxRestrictions = getDefaultRestrictions();
            mRestrictionsSource = RestrictionsSource.DISCONNECTED;
        }
        synchronized (sInstances) {
            if (sInstances.get(mDisplayId) == this) sInstances.remove(mDisplayId);
        }
        if (manager != null) {
            try { manager.unregisterListener(); } catch (RuntimeException e) {
                Log.w(TAG, "Unable to unregister UXR listener", e);
            }
        }
        releaseCar(car);
        notifyObservers();
    }

    private void applyDelivered(@Nullable CarUxRestrictions restrictions) {
        synchronized (mStateLock) {
            if (mDisconnected) return;
            if (restrictions == null) {
                mCarUxRestrictions = getDefaultRestrictions();
                mRestrictionsSource = RestrictionsSource.DISCONNECTED;
            } else {
                mCarUxRestrictions = restrictions;
                mRestrictionsSource = RestrictionsSource.DELIVERED;
            }
        }
        notifyObservers();
    }

    private void applyCarServiceLost(@NonNull RestrictionsSource source) {
        synchronized (mStateLock) {
            if (mDisconnected) return;
            mCarUxRestrictions = getDefaultRestrictions();
            mRestrictionsSource = source;
        }
        notifyObservers();
    }

    private void notifyObservers() {
        List<OnUxRestrictionsChangedListener> observers;
        CarUxRestrictions reading;
        synchronized (mObserverLock) {
            observers = new ArrayList<>(mObservers);
            reading = mCarUxRestrictions;
        }
        for (OnUxRestrictionsChangedListener observer : observers) {
            observer.onRestrictionsChanged(reading);
        }
    }

    private static void releaseCar(@Nullable Car car) {
        if (car == null) return;
        try { car.disconnect(); } catch (RuntimeException e) {
            Log.w(TAG, "Unable to disconnect car service", e);
        }
    }
}
