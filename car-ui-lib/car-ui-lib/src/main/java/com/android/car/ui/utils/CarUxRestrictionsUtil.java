/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.car.ui.utils;

import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH;

import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.car.ui.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Utility class to access Car Restriction Manager.
 *
 * <p>Instances are per-display. UX restrictions are a per-display concept in AAOS — a front
 * passenger screen can legitimately be at {@code BASELINE} while the driver's screen is fully
 * restricted — so callers get the instance matching the display their {@link Context} belongs to.
 *
 * <p>There is still at most one instance per display, because only one listener can be registered
 * with {@link CarUxRestrictionsManager} at a time, as documented in
 * {@link CarUxRestrictionsManager#registerListener}.
 *
 * <p>Each instance holds a binding to car service for its display. Instances for the driver display
 * live as long as the process and need no teardown; an instance created for a display that is later
 * removed must be released with {@link #disconnect()}, or its binding outlives everything that
 * could reach it. Nothing in this library creates or destroys displays, so no call site here does
 * that — it is API for whoever owns the display's lifetime.
 *
 * <p><b>This class fails closed.</b> Until car service delivers a real reading, and any time the
 * connection is lost, the reported state is {@code FULLY_RESTRICTED} with distraction optimisation
 * required. See {@link RestrictionsSource} for telling "we have never heard from car service" apart
 * from "car service told us the vehicle is fully restricted" — the two are otherwise
 * indistinguishable, and only the latter is a real reading.
 */
public class CarUxRestrictionsUtil {
    private static final String TAG = "CarUxRestrictionsUtil";

    /**
     * Where the currently-reported restrictions came from.
     *
     * <p>Dart fork addition. A default {@code CarUxRestrictions} object built to be fail-closed is
     * byte-identical to a genuine "the vehicle is fully restricted" reading, so callers that need
     * to distinguish a real signal from the absence of one — telemetry, diagnostics, deciding
     * whether to show a "restrictions unavailable" affordance — had no way to do it.
     *
     * <p>Every value other than {@link #DELIVERED} means the restrictions being reported are the
     * conservative fallback, not a measurement.
     */
    public enum RestrictionsSource {
        /** Car service has never delivered a reading. Reported value is the fail-closed default. */
        NEVER_DELIVERED,
        /** A genuine reading from car service. */
        DELIVERED,
        /** A debug-only synthetic reading. It must never be represented as live delivery. */
        DEMO,
        /** Car service delivered readings and then went away. Reported value is fail-closed. */
        DISCONNECTED
    }

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
    // VOLATILE, like mCar/mManager/mDisconnected beside them, and for the same reason those are.
    // These two were the exception, and they are the pair every provenance check reads: the
    // delivering path is a BINDER thread (see the mLifecycleListener comment above) while readers
    // are on the UI thread, so without this a reader could observe mRestrictionsSource == DELIVERED
    // alongside a stale mCarUxRestrictions -- a permissive value wearing a trusted label.
    private volatile CarUxRestrictions mCarUxRestrictions = getDefaultRestrictions();

    @NonNull
    private volatile RestrictionsSource mRestrictionsSource = RestrictionsSource.NEVER_DELIVERED;

    /**
     * The binding to car service held on behalf of this display, or null if none is held.
     *
     * <p>Restored from AOSP {@code a92cdc2ed} ("Address leaked service violations", b/344639226).
     * That commit is in this fork's history, but the Phase 2 per-display rewrite dropped the field
     * and {@link #disconnect()} along with the {@code WeakHashMap<Context, …>} keying it was written
     * against. Dropping the map was deliberate and is why this fork does not have the unbounded
     * {@code ActivityLifecycleCallbacks} growth that commit introduced; dropping the field was not,
     * and it reinstated the {@code ServiceConnectionLeaked} violation the commit set out to fix.
     *
     * <p>Volatile because {@code CarServiceLifecycleListener} is invoked on a binder thread while
     * {@link #disconnect()} is called from whichever thread owns the display's teardown.
     */
    @Nullable
    private volatile Car mCar;

    /**
     * The manager this instance's listener is registered with, retained so it can be unregistered.
     *
     * <p>Neither upstream nor this fork's first version kept it, so nothing ever called
     * {@link CarUxRestrictionsManager#unregisterListener()}. That matters more here than it looks:
     * a manager accepts <b>one</b> listener at a time, as
     * {@link CarUxRestrictionsManager#registerListener} documents, so a stale registration is not
     * merely a leak — it is a slot another instance needs.
     */
    @Nullable
    private volatile CarUxRestrictionsManager mManager;

    /**
     * Set by {@link #disconnect()}, and never cleared. A disconnected instance is terminal.
     *
     * <p>Without this, {@code disconnect()} is racy in a way a real head unit will hit.
     * {@code Car.createCar(..., CAR_WAIT_TIMEOUT_DO_NOT_WAIT, listener)} keeps invoking its
     * lifecycle listener on a binder thread; a {@code ready = true} callback that lands after the
     * disconnect would re-register the listener and re-seed the state, flipping a released instance
     * back to {@link RestrictionsSource#DELIVERED} for a display that may no longer exist — and, by
     * re-registering, taking the manager's single listener slot away from whichever instance now
     * legitimately holds it.
     *
     * <p>Found by {@code CarUxRestrictionsPerDisplayTest#disconnect_evictsSoTheNextGetInstance-
     * Reconnects}, which failed intermittently on a real device precisely when that callback landed
     * late.
     */
    private volatile boolean mDisconnected;

    /** Serializes binding lifecycle with the reading/provenance pair. */
    private final Object mStateLock = new Object();

    private final int mDisplayId;

    /**
     * Guards {@link #mObservers}. A dedicated object rather than the set itself, so that the lock
     * cannot be acquired by anyone outside this class.
     */
    private final Object mObserverLock = new Object();

    /**
     * The observers, WEAKLY held.
     *
     * <p>{@code WeakHashMap} is load-bearing, not incidental: {@link #register}'s contract is that
     * this class keeps only a weak reference and the caller must hold a strong one, which is what
     * makes an un-unregistered listener inert rather than a leak. That rules out swapping in a
     * concurrent set — the JDK has no weak concurrent set — so every access is taken under
     * {@link #mObserverLock} instead.
     */
    @GuardedBy("mObserverLock")
    private final Set<OnUxRestrictionsChangedListener> mObservers =
            Collections.newSetFromMap(new WeakHashMap<>());

    private final CarUxRestrictionsManager.OnUxRestrictionsChangedListener mListener =
            this::applyDelivered;

    /**
     * Dart fork note: upstream held a single process-wide instance, which collapsed every display
     * onto one set of restrictions. Keyed by display id instead.
     */
    private static final SparseArray<CarUxRestrictionsUtil> sInstances = new SparseArray<>();

    /**
     * Listens for display removal and releases that display's instance. Null until the first
     * instance is created; see {@link #ensureDisplayOwner}.
     */
    @GuardedBy("sInstances")
    @Nullable
    private static DisplayManager.DisplayListener sDisplayOwner;

    /**
     * Whether the app hosting this library is debuggable, captured once at construction.
     *
     * <p>A boolean, not the Context it came from. This class deliberately retains no Context — that
     * is why it does not leak an Activity the way several of its neighbours do — and reading the
     * flag lazily would have meant keeping one.
     */
    private final boolean mDebuggable;

    private CarUxRestrictionsUtil(Context context, int displayId) {
        mDisplayId = displayId;
        mDebuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        connect(context);
    }

    /**
     * Dart fork addition: test seam. Builds an instance in its initial fail-closed state without
     * touching car service, so the delivery/disconnect/rebind transitions can be driven directly.
     */
    @VisibleForTesting
    CarUxRestrictionsUtil(int displayId) {
        // True, because this constructor IS the test seam: it is package-private, takes no Context
        // to read the real flag from, and exists so a test can drive the delivery transitions
        // directly. An instance built this way never reaches a shipped app -- the public
        // getInstance path always goes through the Context constructor above, which reads the
        // genuine flag.
        this(displayId, true);
    }

    /**
     * The same seam, with the debuggable flag chosen explicitly.
     *
     * <p>Exists so the release-build behaviour of {@link #setUxRestrictions} is testable at all.
     * Without it the gate could only be exercised through {@code getInstance(Context)}, which needs
     * a Context complete enough to survive {@code displayIdOf}, {@code createDisplayContext} and
     * {@code connect} — and a test that elaborate would be testing the mock.
     */
    @VisibleForTesting
    CarUxRestrictionsUtil(int displayId, boolean debuggable) {
        mDisplayId = displayId;
        mDebuggable = debuggable;
    }

    private void connect(Context context) {
        try {
            // Dart fork note: a context bound to this display, not the application context (always
            // the default display); still rooted in the application context, so nothing leaks.
            Context carContext = displayContextFor(context, mDisplayId);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Dart fork note: capture createCar's return too, so a connection that never
                // becomes ready (DO_NOT_WAIT) can still be released. Both assignments agree.
                mCar = Car.createCar(carContext, null,
                        Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                        (Car car, boolean ready) -> {
                            synchronized (mStateLock) {
                                if (!mDisconnected) {
                                    mCar = car;
                                }
                            }
                            if (mDisconnected) {
                                // A callback that landed after disconnect(). Release what it
                                // handed us rather than adopting it; adopting it would revive a
                                // released instance.
                                Log.i(TAG, "ignoring post-disconnect car lifecycle callback on "
                                        + "display " + mDisplayId);
                                releaseCar(car, mDisplayId);
                                return;
                            }
                            if (ready) {
                                // Re-seeds on every (re)connection, not just the first.
                                registerCarUxRestrictionsListener(car, mListener);
                            } else {
                                Log.w(TAG, "Car service disconnected on display " + mDisplayId
                                        + ", assuming fully restricted");
                                onCarServiceLost(RestrictionsSource.DISCONNECTED);
                            }
                        });
            } else {
                // Dart fork note: reachable at minSdk 28. Without death reporting on this path, at
                // least surface a connect failure as fail-closed.
                mCar = Car.createCar(carContext);
                registerCarUxRestrictionsListener(mCar, mListener);
            }
        } catch (RuntimeException e) {
            // Can't catch a more specific exception, because Car literally throws RuntimeException
            // or SecurityException on error.
            //
            // Dart fork note: upstream assumed unrestricted here, failing open; now fails closed
            // (docs/upstream/AOSP-FAIL-OPEN-REPORT.md).
            Log.w(TAG, "Unable to connect to car service on display " + mDisplayId
                    + ", assuming fully restricted", e);
            onCarServiceLost(RestrictionsSource.NEVER_DELIVERED);
        }
    }

    private static Context displayContextFor(Context context, int displayId) {
        Context appContext = context.getApplicationContext();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || displayId == Display.DEFAULT_DISPLAY) {
            return appContext;
        }
        try {
            android.hardware.display.DisplayManager dm =
                    context.getSystemService(android.hardware.display.DisplayManager.class);
            Display display = dm == null ? null : dm.getDisplay(displayId);
            return display == null ? appContext : appContext.createDisplayContext(display);
        } catch (RuntimeException e) {
            // Never let display resolution be the reason a client cannot get restrictions at all;
            // the default-display context still yields a usable (and conservative) signal.
            Log.w(TAG, "Could not build a display context for display " + displayId
                    + ", falling back to the application context", e);
            return appContext;
        }
    }

    /**
     * Builds the fail-closed value used before car service reports anything and whenever the
     * connection is lost.
     *
     * <p>Dart fork addition: indirected through a replaceable supplier purely as a test seam.
     * {@code android.car.jar} is a stub jar whose methods throw {@code RuntimeException("Stub!")},
     * and AGP's {@code returnDefaultValues} only rewrites {@code android.jar}, not the
     * {@code useLibrary} car jar — so {@code new CarUxRestrictions.Builder(...)} cannot run in a
     * local JVM test. Production behaviour is unchanged.
     */
    /**
     * The fail-closed DECISION, separated from its construction.
     *
     * <p>Extracted 2026-08-15 because {@code scripts/check-gate-sensitivity.py} found this value
     * unguarded on its first run: mutating the factory to {@code (false, BASELINE, 0)} — turning
     * the fail-closed default fail-OPEN — was detected by NOTHING. Every test in
     * {@code CarUxRestrictionsUtilTest} replaces this factory via
     * {@link #setFailClosedFactoryForTesting} in {@code @Before}, so the production value was never
     * exercised. A seam hiding its own subject, which is a shape this repository has hit before and
     * fixed the same way on the plugin side.
     *
     * <p>Constants can be asserted without constructing anything, which matters here: the
     * {@code CarUxRestrictions.Builder} is a stub that throws in a local JVM test, and that is why
     * the seam exists at all.
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

    /**
     * Package-private, not public. It replaces the PROCESS-WIDE fail-closed supplier permanently,
     * with no reset — so a single call defeats fail-closed for every instance in the process,
     * including ones created later.
     *
     * <p>It shipped in the published AAR (docs/api-surface/car-ui-lib.txt) because
     * {@code @VisibleForTesting} is a lint hint and changes nothing about compiled visibility. Both
     * of its callers turned out to be reachable without it: car-ui-lib's own test is in this
     * package, and the reference plugin's test targets the plugin's OWN copy of this class in
     * {@code com.chassis.car.ui.plugin.uxr}. So withdrawing it cost nothing.
     *
     * <p>Fork-added, which is why it can be narrowed at all. The neighbouring
     * {@link #setUxRestrictions} is upstream and is handled differently — see its javadoc.
     */
    @VisibleForTesting
    static void setFailClosedFactoryForTesting(
            @NonNull java.util.function.Supplier<CarUxRestrictions> factory) {
        sFailClosedFactory = factory;
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
     * The instance for a SPECIFIC display, rather than for the caller's own.
     *
     * <p>Dart fork addition. {@link #getInstance(Context)} derives the display from the context,
     * which is right for a renderer asking about itself and useless for the one case that needs two
     * answers at once: moving a surface between displays. A handoff must project the destination's
     * restrictions while still holding the source's, and there was no supported way to name a
     * display.
     *
     * <p>Falls back to the caller's own display when the requested one cannot be resolved — the same
     * conservative behaviour {@link #displayContextFor} already has, for the same reason: display
     * resolution failing must never be why a caller gets no restrictions at all.
     */
    @NonNull
    public static CarUxRestrictionsUtil getInstanceForDisplay(
            @NonNull Context context, int displayId) {
        return getInstance(displayContextFor(context, displayId));
    }

    /**
     * Returns the instance for the display the given {@link Context} belongs to.
     *
     * <p>Dart fork note: upstream returned a single process-wide instance regardless of display.
     * Passing an Activity context on a secondary display now yields that display's restrictions.
     */
    @NonNull
    public static CarUxRestrictionsUtil getInstance(Context context) {
        int displayId = displayIdOf(context);
        synchronized (sInstances) {
            CarUxRestrictionsUtil instance = sInstances.get(displayId);
            if (instance == null) {
                instance = new CarUxRestrictionsUtil(context, displayId);
                sInstances.put(displayId, instance);
                // The owner this cache never had — see ensureDisplayOwner.
                ensureDisplayOwner(context);
            }
            return instance;
        }
    }

    /**
     * Registers the process-wide {@link DisplayManager.DisplayListener} that disconnects an
     * instance when its display goes away.
     *
     * <p><b>The gap this closes.</b> {@link #sInstances} is keyed by display id and grows one entry
     * per display ever seen, and every entry holds a live binding to car service. {@link #disconnect}
     * exists to release one — but nothing called it, because nothing owned a display's lifetime.
     * Documented as a caller responsibility, which is another way of saying nobody's. On a head unit
     * that creates and destroys virtual or passenger displays, the bindings accumulated for the life
     * of the process.
     *
     * <p>The platform already announces this. {@code onDisplayRemoved} is the owner; it simply was
     * never listened to. Registered once, lazily, from the first {@code getInstance} that creates an
     * instance — a static listener living for the process is the correct lifetime for a
     * process-wide cache, and registering it eagerly in a static initialiser would need a Context
     * this class does not have.
     *
     * <p>{@link Display#DEFAULT_DISPLAY} is never removed by the platform, so the driver display's
     * instance is unaffected by design rather than by exception.
     */
    @GuardedBy("sInstances")
    private static void ensureDisplayOwner(Context context) {
        if (sDisplayOwner != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        Context appContext = context.getApplicationContext();
        DisplayManager displayManager = (appContext != null ? appContext : context)
                .getSystemService(DisplayManager.class);
        if (displayManager == null) {
            Log.w(TAG, "No DisplayManager; per-display instances will not be released on removal");
            return;
        }
        sDisplayOwner = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayChanged(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                CarUxRestrictionsUtil instance;
                synchronized (sInstances) {
                    instance = sInstances.get(displayId);
                }
                if (instance != null) {
                    Log.i(TAG, "display " + displayId + " removed; releasing its car binding");
                    // Outside the lock: disconnect() notifies observers, which is arbitrary code.
                    instance.disconnect();
                }
            }
        };
        displayManager.registerDisplayListener(sDisplayOwner, null);
    }

    private static int displayIdOf(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return displayIdOrDefault(context);
        }
        return Display.DEFAULT_DISPLAY;
    }

    /**
     * Resolves the display for a {@link Context}, falling back to the default display when the
     * platform refuses to answer.
     *
     * <p>{@link Context#getDisplay()} throws {@link UnsupportedOperationException} for any
     * non-visual context — Application and Service contexts included. Callers deep in this library
     * pass whatever context they were handed: {@code MenuItem} builds one during
     * {@code ToolbarControllerImpl} construction and reaches here with a non-visual context, which
     * crashed PaintBooth on launch until this was caught. Treat "cannot determine a display" as the
     * default display, which is what a process-wide context effectively meant before restrictions
     * became per-display.
     */
    @VisibleForTesting
    static int displayIdOrDefault(Context context) {
        try {
            Display display = context.getDisplay();
            return display != null ? display.getDisplayId() : Display.DEFAULT_DISPLAY;
        } catch (UnsupportedOperationException e) {
            return Display.DEFAULT_DISPLAY;
        }
    }

    /** The display this instance reports restrictions for. */
    public int getDisplayId() {
        return mDisplayId;
    }

    /**
     * Releases this display's binding to car service.
     *
     * <p>Restored from AOSP {@code a92cdc2ed}. Callers that own a display's lifetime — above all
     * anything that creates and later removes a virtual or secondary display — must call this
     * before the display goes away, or the binding this instance holds stays alive with nothing
     * left to reach it. That accumulation is specific to this fork's per-display keying: upstream's
     * single process-wide instance could only ever hold one connection, whereas {@link #sInstances}
     * grows one entry per display id ever seen.
     *
     * <p>Two deliberate differences from the upstream method, both consequences of this class
     * failing closed:
     *
     * <ul>
     *   <li><b>The reported state moves to {@link RestrictionsSource#DISCONNECTED} and fails
     *       closed.</b> Upstream's {@code disconnect()} released the binding and left the last
     *       delivered reading in place, so an instance disconnected while the vehicle happened to
     *       be parked went on reporting {@code BASELINE} — no restrictions — indefinitely, with
     *       nothing able to correct it. That is the latched-permissive shape this class was
     *       rewritten to remove; see {@link #onCarServiceLost}.
     *   <li><b>The instance is evicted from the per-display cache.</b> Upstream left the
     *       disconnected instance in its map, so every subsequent {@code getInstance()} for that
     *       context handed back a permanently dead object. Evicting means the next caller builds a
     *       live one. References already held by existing callers stay fail-closed, which is the
     *       conservative outcome for a view whose display is being torn down.
     * </ul>
     *
     * <p>Idempotent, and safe on an instance that never connected.
     */
    public void disconnect() {
        Car car;
        CarUxRestrictionsManager manager;
        synchronized (mStateLock) {
            if (mDisconnected) {
                return;
            }
            mDisconnected = true;
            car = mCar;
            mCar = null;
            manager = mManager;
            mManager = null;
            mCarUxRestrictions = getDefaultRestrictions();
            mRestrictionsSource = RestrictionsSource.DISCONNECTED;
        }

        synchronized (sInstances) {
            if (sInstances.get(mDisplayId) == this) {
                sInstances.remove(mDisplayId);
            }
        }
        if (manager != null) {
            try {
                manager.unregisterListener();
            } catch (RuntimeException e) {
                Log.w(TAG, "Ignoring failure unregistering the restrictions listener on display "
                        + mDisplayId, e);
            }
        }
        releaseCar(car, mDisplayId);
        notifyObservers();
    }

    private static void releaseCar(@Nullable Car car, int displayId) {
        if (car == null) {
            return;
        }
        try {
            car.disconnect();
        } catch (RuntimeException e) {
            // Car.disconnect() throws IllegalStateException if the service already died, and the
            // stub jar throws RuntimeException("Stub!") in a local JVM test. Neither is a reason to
            // propagate out of a teardown path.
            Log.w(TAG, "Ignoring failure releasing car service on display " + displayId, e);
        }
    }

    /**
     * Registers a listener on this class for updates to CarUxRestrictions. Multiple listeners may
     * be registered. Note that this class will only hold a weak reference to the listener, you must
     * maintain a strong reference to it elsewhere.
     */
    public void register(OnUxRestrictionsChangedListener listener) {
        synchronized (mObserverLock) {
            mObservers.add(listener);
        }
        // Seeds the new listener immediately: CarUxRestrictionsManager's own listener is
        // change-only, so a client binding while already restricted would otherwise never hear.
        //
        // OUTSIDE the lock, deliberately. Calling a listener while holding a lock invites the
        // deadlock where that listener reaches back into this class -- and it can: UxrButton's
        // callback runs on the UI thread and may attach or detach a sibling view.
        listener.onRestrictionsChanged(mCarUxRestrictions);
    }

    /**
     * Unregisters a registered listener
     */
    public void unregister(OnUxRestrictionsChangedListener listener) {
        synchronized (mObserverLock) {
            mObservers.remove(listener);
        }
    }

    @NonNull
    public CarUxRestrictions getCurrentRestrictions() {
        return mCarUxRestrictions;
    }

    /**
     * Where {@link #getCurrentRestrictions()} came from. Anything other than
     * {@link RestrictionsSource#DELIVERED} means the value is the fail-closed fallback rather than
     * a real reading.
     *
     * <p>Dart fork addition.
     */
    @NonNull
    public RestrictionsSource getRestrictionsSource() {
        return mRestrictionsSource;
    }

    /** Returns restrictions and provenance from the same locked lifecycle state. */
    @NonNull
    public RestrictionsSnapshot getRestrictionsSnapshot() {
        synchronized (mStateLock) {
            return new RestrictionsSnapshot(mCarUxRestrictions, mRestrictionsSource);
        }
    }

    /**
     * Returns whether <b>any</b> of the given flags are blocked by the specified restrictions. If
     * null is given, the method returns true for safety.
     *
     * <p><b>This is an any-of test.</b> {@code isRestricted(A | B, uxr)} is true when either A or B
     * is active. That is the intended contract and callers such as {@code UxrButton} rely on it.
     *
     * <p>It is therefore the WRONG test for {@code UX_RESTRICTIONS_FULLY_RESTRICTED}, which is not
     * a flag but a composite of all nine restriction bits (0x1ff). {@code
     * isRestricted(UX_RESTRICTIONS_FULLY_RESTRICTED, uxr)} reads like "is the vehicle fully
     * restricted?" but actually answers "is anything restricted at all?" — it is true when a single
     * unrelated bit such as {@code UX_RESTRICTIONS_NO_VIDEO} is set. Use
     * {@link #hasAllRestrictions} when you mean the composite.
     */
    public static boolean isRestricted(
            @CarUxRestrictionsInfo int restrictionFlags, @Nullable CarUxRestrictions uxr) {
        return (uxr == null) || ((uxr.getActiveRestrictions() & restrictionFlags) != 0);
    }

    /**
     * Returns whether <b>all</b> of the given flags are active. Null is treated as restricted, for
     * safety.
     *
     * <p>Dart fork addition. This is the correct test for composite masks, above all
     * {@code UX_RESTRICTIONS_FULLY_RESTRICTED} (0x1ff), which is the bitwise union of the nine
     * individual restrictions rather than a distinct bit of its own:
     *
     * <pre>{@code
     * hasAllRestrictions(CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED, uxr)
     * }</pre>
     *
     * <p>Never compare the whole field with {@code ==} instead — a vehicle can report
     * {@code FULLY_RESTRICTED} together with bits added in a later platform version, and an
     * equality test would then silently report "not fully restricted".
     */
    public static boolean hasAllRestrictions(
            @CarUxRestrictionsInfo int restrictionFlags, @Nullable CarUxRestrictions uxr) {
        if (uxr == null) {
            return true;
        }
        return (uxr.getActiveRestrictions() & restrictionFlags) == restrictionFlags;
    }

    /**
     * Truncates to {@code maxLength} characters INCLUDING the ellipsis.
     *
     * <p>Dart fork fix. Upstream returned {@code str.substring(0, maxLength) + ellipsis}, which is
     * {@code maxLength + 1} characters — a method whose entire purpose is "comply with the maximum
     * string length" returned a string one character over it. On the live path
     * ({@code UxrTextView} calls this for every string it renders while restricted) that is a
     * driver-distraction limit being exceeded by every truncated string in the system.
     *
     * <p>Found by {@code scripts/check-test-reachability.py}: {@code complyString} was named by no
     * test, and reading it to decide whether it deserved one is what surfaced the arithmetic. The
     * identical trap is documented in {@code A2uiPruner.truncate}, which was written later and
     * guarded against it deliberately — the two now agree.
     *
     * <p>Also fixes surrogate splitting. {@code substring} at an arbitrary index can cut a
     * two-char code point in half and produce an unpaired surrogate; emoji in media titles are
     * ordinary, not exotic, and a {@code TextView} handed an unpaired surrogate renders a
     * replacement glyph.
     */
    @VisibleForTesting
    static String truncateToComply(String str, int maxLength, String ellipsis) {
        int max = Math.max(0, maxLength);
        if (str == null || str.length() <= max) {
            return str;
        }
        if (max <= ellipsis.length()) {
            // No room for any content alongside the marker. Returning the marker itself would
            // exceed the limit, which is the bug this method exists to remove.
            return ellipsis.substring(0, max);
        }
        int keep = max - ellipsis.length();
        if (Character.isHighSurrogate(str.charAt(keep - 1))) {
            keep--;
        }
        return str.substring(0, keep) + ellipsis;
    }

    /**
     * Complies the input string with the given UX restrictions. Returns the original string if
     * already compliant, otherwise a shortened ellipsized string.
     */
    public static String complyString(Context context, String str, CarUxRestrictions uxr) {

        if (isRestricted(UX_RESTRICTIONS_LIMIT_STRING_LENGTH, uxr)) {
            int maxLength =
                    uxr == null
                            ? context.getResources().getInteger(
                            R.integer.car_ui_default_max_string_length)
                            : uxr.getMaxRestrictedStringLength();

            return truncateToComply(str, maxLength,
                    context.getString(R.string.car_ui_ellipsis));
        }

        return str;
    }

    /**
     * Sets car UX restrictions. Only used for testing — and, on a release build, ignored.
     *
     * <p><b>Why the gate.</b> This routes to {@link #onRestrictionsDelivered}, which sets
     * {@code mRestrictionsSource = DELIVERED}. That is the one value this fork's entire provenance
     * investment keys on: every {@code allowedFor}, {@code limitsFor} and {@code allowsListening}
     * decides whether to trust a reading by asking whether it was DELIVERED. So this method does
     * not merely change the restrictions — it manufactures a reading indistinguishable from car
     * service's, and {@code @VisibleForTesting} does nothing about it at runtime.
     *
     * <p><b>What the exposure actually is</b>, measured rather than assumed: {@code sInstances} is a
     * private static per-process cache, and this class publishes nothing over Binder, a
     * ContentProvider or a broadcast. So a caller forges a reading <em>in its own process only</em>
     * and cannot reach another app's gating. The risk is therefore not a hostile app — one of those
     * can ignore this library entirely — but an unaudited dependency inside an app that was
     * certified on the basis that it uses this gating, or an accidental call left in production.
     * Both are release-build problems, which is what the gate addresses.
     *
     * <p><b>Why gated rather than withdrawn.</b> Unlike {@link #setFailClosedFactoryForTesting},
     * this method is UPSTREAM. Making it package-private would conflict on every
     * {@code git merge upstream/main}, permanently. Gating keeps the signature, so the published
     * baseline does not move and the merge stays clean.
     */
    @VisibleForTesting
    public void setUxRestrictions(CarUxRestrictions carUxRestrictions) {
        // No gate here: onRestrictionsDelivered carries it, and one gate on the single assignment
        // site is the point. A copy here would be a second thing to keep in step.
        onRestrictionsDelivered(carUxRestrictions);
    }

    /**
     * Handles a reading delivered by car service. A null reading means car service could not
     * supply one, which is treated as fully restricted rather than unrestricted.
     */
    /**
     * The TEST SEAM for a delivered reading — gated. The car-service callback does not come
     * through here; it calls {@link #applyDelivered} directly.
     *
     * <p>The gate lives on this method rather than on {@link #setUxRestrictions} because the public
     * method was never the only way in. This one is package-private, and package-private is not a
     * boundary in a shipped AAR: an app's dex and the library's share one {@code PathClassLoader},
     * {@code classes.jar} carries no sealed manifest, and {@code javap -p} on the published
     * artifact lists it. Any consumer that writes {@code package com.android.car.ui.utils;} calls
     * it on the instance {@code getInstance} just handed them — five lines, no reflection. Gating
     * the public method and leaving this one open guarded the wrong door.
     */
    @VisibleForTesting
    void onRestrictionsDelivered(@Nullable CarUxRestrictions carUxRestrictions) {
        if (!mayInjectAReading()) {
            Log.w(TAG, "injected reading ignored: this is a test seam and this is a release "
                    + "build. A forged DELIVERED reading is indistinguishable from a real one to "
                    + "every provenance check in this library.");
            return;
        }
        applyDelivered(carUxRestrictions);
    }

    /**
     * Applies a synthetic demo reading without claiming that car service delivered it.
     *
     * <p>The authority gate is identical to {@link #setUxRestrictions}: a debuggable APK on a
     * non-user system image. The distinct provenance prevents diagnostics and evidence from
     * mislabelling a console-selected state as vehicle motion.
     */
    public void setDemoUxRestrictions(@NonNull CarUxRestrictions carUxRestrictions) {
        if (!mayInjectAReading()) {
            Log.w(TAG, "Ignoring demo UX-restrictions injection outside a debuggable non-user "
                    + "build");
            return;
        }
        applyReading(carUxRestrictions, RestrictionsSource.DEMO);
    }

    /**
     * Whether this process may inject a reading.
     *
     * <p>TWO facts, and the second is the load-bearing one. {@code mDebuggable} comes from the
     * Context the caller passed to {@code getInstance} — and {@code getApplicationInfo()} is a
     * virtual call on a caller-supplied object, which {@code ContextWrapper} may override and
     * {@code createPackageContext} may source from another package entirely. A security check must
     * not take its authority from its own subject, so it is paired with the system build type,
     * which no caller supplies: a production head unit ships {@code user}.
     *
     * <p>Neither alone is sufficient. {@code Build.TYPE} alone would let any app inject on a
     * {@code userdebug} bench image; the Context flag alone is spoofable. Together, injection
     * requires a debuggable app on a non-user build, which is what a test environment is.
     */
    private boolean mayInjectAReading() {
        return mDebuggable && !"user".equals(Build.TYPE);
    }

    private void applyDelivered(@Nullable CarUxRestrictions carUxRestrictions) {
        applyReading(carUxRestrictions, carUxRestrictions == null
                ? RestrictionsSource.DISCONNECTED : RestrictionsSource.DELIVERED);
    }

    private void applyReading(@Nullable CarUxRestrictions carUxRestrictions,
            @NonNull RestrictionsSource source) {
        synchronized (mStateLock) {
            if (mDisconnected) {
                return;
            }
            if (carUxRestrictions == null) {
                mCarUxRestrictions = getDefaultRestrictions();
                mRestrictionsSource = RestrictionsSource.DISCONNECTED;
            } else {
                mCarUxRestrictions = carUxRestrictions;
                mRestrictionsSource = source;
            }
        }
        notifyObservers();
    }

    /**
     * Handles car service becoming unavailable, either because it never connected or because it
     * died. Always fails closed.
     *
     * <p>Dart fork note: NEVER_DELIVERED and DISCONNECTED get the same value by design; serving a
     * stale permissive reading after a disconnect is the fail-open shape this class removed.
     */
    @VisibleForTesting
    void onCarServiceLost(@NonNull RestrictionsSource source) {
        applyCarServiceLost(source);
    }

    /** The transition itself, reachable by {@link #disconnect()} after it has set the flag. */
    private void applyCarServiceLost(@NonNull RestrictionsSource source) {
        synchronized (mStateLock) {
            if (mDisconnected) {
                return;
            }
            mCarUxRestrictions = getDefaultRestrictions();
            mRestrictionsSource = source;
        }
        notifyObservers();
    }

    /**
     * Fans a reading out to every observer, from a SNAPSHOT taken under the lock.
     *
     * <p>This used to iterate {@link #mObservers} directly while {@link #register} and
     * {@link #unregister} mutated it unlocked from another thread. The delivering thread here is a
     * binder thread and the mutating thread is the UI thread — {@code UxrButton} registers in
     * {@code onAttachedToWindow} and unregisters in {@code onDetachedFromWindow} — so a screen
     * attaching while the vehicle started moving could throw {@code ConcurrentModificationException}
     * or corrupt the backing map, inside the driver-distraction fan-out.
     *
     * <p>The reading is read ONCE into a local. Reading the field per-observer would let a
     * mid-iteration delivery give different observers different answers for one event, which is the
     * shape where two views on one screen disagree about whether the vehicle is moving.
     *
     * <p>Callbacks run OUTSIDE the lock, for the reason {@link #register} states.
     */
    private void notifyObservers() {
        List<OnUxRestrictionsChangedListener> snapshot;
        synchronized (mObserverLock) {
            snapshot = new ArrayList<>(mObservers);
        }
        CarUxRestrictions reading = mCarUxRestrictions;
        for (OnUxRestrictionsChangedListener observer : snapshot) {
            observer.onRestrictionsChanged(reading);
        }
    }

    /**
     * Reads the cached instance for a display without creating one. Testing only.
     *
     * <p>Needed because the ownership property is about the CACHE, not about an instance: after a
     * display goes away, the question is whether anything can still be handed out for it.
     */
    @VisibleForTesting
    @Nullable
    static CarUxRestrictionsUtil peekInstanceForTesting(int displayId) {
        synchronized (sInstances) {
            return sInstances.get(displayId);
        }
    }

    /** Whether the display owner has been registered. Testing only. */
    @VisibleForTesting
    static boolean hasDisplayOwnerForTesting() {
        synchronized (sInstances) {
            return sDisplayOwner != null;
        }
    }

    /**
     * How many observers this instance is currently notifying. Testing only.
     *
     * <p>Added because a listener that is never unregistered has NO observable effect on anything
     * a caller renders — the caller's own state has usually been cleared by then, so its callback
     * returns early and the rows look identical. The leak is real (observers accumulate per display
     * across successive registrations) and is invisible from outside without this.
     *
     * <p>Found by mutation: removing the unregister call from a caller's release path was NOT
     * detected by tests asserting on rendered output, because rendered output is exactly what a
     * leaked-but-inert listener does not change.
     *
     * <p>{@link #mObservers} is a {@code WeakHashMap}-backed set, so this counts observers still
     * strongly held by somebody. That is the right question: an observer nobody holds is already
     * gone, and one somebody holds is one this instance will still call.
     */
    @VisibleForTesting
    public int observerCountForTesting() {
        synchronized (mObserverLock) {
            return mObservers.size();
        }
    }

    /**
     * Drives the real removal path without waiting for a display to actually go away. Testing only.
     *
     * <p>Routes through the registered listener rather than calling {@code disconnect()} directly,
     * so a test exercises the code that runs in production instead of a parallel path that happens
     * to have the same effect.
     */
    @VisibleForTesting
    static void notifyDisplayRemovedForTesting(int displayId) {
        DisplayManager.DisplayListener owner;
        synchronized (sInstances) {
            owner = sDisplayOwner;
        }
        if (owner != null) {
            owner.onDisplayRemoved(displayId);
        }
    }

    /**
     * Drops all cached per-display instances, releasing each one's car service binding. Testing
     * only. Dart fork note: releasing, not just clearing, keeps each @Before from leaking one.
     */
    @VisibleForTesting
    public static void clearInstancesForTesting() {
        List<CarUxRestrictionsUtil> dropped = new ArrayList<>();
        synchronized (sInstances) {
            for (int i = 0; i < sInstances.size(); i++) {
                dropped.add(sInstances.valueAt(i));
            }
            sInstances.clear();
        }
        // Outside the lock: Car.disconnect() reaches into the platform.
        //
        // THROUGH disconnect(), not a bare releaseCar. This used to null mCar and release the
        // binding while leaving mDisconnected false, mManager registered, and every observer
        // holding an instance whose mRestrictionsSource was still DELIVERED -- with the only
        // channel that could correct it now cut. Called while parked, that made a genuine BASELINE
        // reading PERMANENT and still passing every provenance check by construction.
        //
        // disconnect()'s own javadoc names the shape: "the latched-permissive shape this class was
        // rewritten to remove". It was reachable from a public method, needing no forgery at all --
        // which is why gating setUxRestrictions did not close it.
        for (CarUxRestrictionsUtil util : dropped) {
            util.disconnect();
        }
    }

    private void registerCarUxRestrictionsListener(
            @NonNull Car car,
            @NonNull CarUxRestrictionsManager.OnUxRestrictionsChangedListener listener) {
        synchronized (mStateLock) {
            if (mDisconnected) return;
        }
        try {
            CarUxRestrictionsManager carUxRestrictionsManager =
                    (CarUxRestrictionsManager) car.getCarManager(
                            Car.CAR_UX_RESTRICTION_SERVICE);
            carUxRestrictionsManager.registerListener(listener);
            synchronized (mStateLock) {
                if (mDisconnected) {
                    carUxRestrictionsManager.unregisterListener();
                    return;
                }
                mManager = carUxRestrictionsManager;
            }
            // Seed from the current value: registerListener only fires on CHANGE, so a unit
            // already restricted at bind time would otherwise never deliver anything.
            listener.onUxRestrictionsChanged(
                    carUxRestrictionsManager.getCurrentCarUxRestrictions());
        } catch (NullPointerException e) {
            Log.e(TAG, "Car not connected on display " + mDisplayId, e);
            // Dart fork note: upstream left the field at its default here and said so in a
            // comment. Made explicit, so the source is recorded rather than inferred.
            onCarServiceLost(RestrictionsSource.NEVER_DELIVERED);
        }
    }
}
