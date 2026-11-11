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
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Distance;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.Maneuver;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.navigation.model.RoutingInfo;
import androidx.car.app.navigation.model.Step;
import androidx.car.app.navigation.model.TravelEstimate;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import art.dart.a2ui.nav.NavScreen;
import art.dart.a2ui.nav.NavStep;
import art.dart.a2ui.nav.NavSurfaceController;
import art.dart.a2ui.nav.NavTrip;
import art.dart.a2ui.telemetry.A2uiTelemetry;
import art.dart.a2ui.telemetry.TelemetryEvent;

import java.time.ZonedDateTime;

/**
 * Step 2 of the navigation flow: active guidance. Renders the trip's current and next steps as
 * routing info with a destination estimate, owns the map surface while on top, and stops both
 * when the driver exits. Instructions are the anchored short strings; cluster glanceability is
 * by construction until a real router supplies its own.
 */
public final class A2uiNavScreen extends Screen implements NavScreen {

    private final NavTrip mTrip;
    private final NavSurfaceController mSurfaces;

    public A2uiNavScreen(@NonNull CarContext carContext, @NonNull NavTrip trip,
            @NonNull NavSurfaceController surfaces) {
        super(carContext);
        mTrip = trip;
        mSurfaces = surfaces;
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onResume(@NonNull LifecycleOwner owner) {
                mSurfaces.start();
            }

            @Override
            public void onPause(@NonNull LifecycleOwner owner) {
                mSurfaces.stop();
            }
        });
    }

    @Override
    public int navDepth() {
        return 2;
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        A2uiTelemetry.event(TelemetryEvent.TEMPLATE_RENDERED, "template", "NavigationTemplate",
                "host", "a");
        NavStep current = mTrip.currentStep();
        RoutingInfo.Builder routing = new RoutingInfo.Builder();
        if (current != null) {
            routing.setCurrentStep(toStep(current),
                    Distance.create(current.getDistanceMeters(), Distance.UNIT_METERS));
            NavStep next = mTrip.nextStep();
            if (next != null) {
                routing.setNextStep(toStep(next));
            }
        } else {
            routing.setLoading(false);
        }
        return new NavigationTemplate.Builder()
                .setNavigationInfo(routing.build())
                .setDestinationTravelEstimate(new TravelEstimate.Builder(
                        Distance.create(mTrip.remainingDistanceMeters(), Distance.UNIT_METERS),
                        ZonedDateTime.now().plusSeconds(
                                Math.max(0, mTrip.getRoute().getTotalTimeSeconds())))
                        .build())
                .setActionStrip(new ActionStrip.Builder()
                        .addAction(new Action.Builder()
                                .setTitle("Stop")
                                .setOnClickListener(this::stopNavigation)
                                .build())
                        .build())
                .build();
    }

    /** Ends guidance and pops back to the preview. */
    @VisibleForTesting
    boolean stopNavigation() {
        mTrip.cancel();
        getScreenManager().pop();
        return true;
    }

    private static Step toStep(NavStep step) {
        return new Step.Builder(step.getInstruction())
                .setManeuver(new Maneuver.Builder(step.getManeuverType()).build())
                .build();
    }
}
