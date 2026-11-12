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
import android.text.SpannableString;

import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.Distance;
import androidx.car.app.model.DistanceSpan;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate;

import art.dart.a2ui.nav.NavFlow;
import art.dart.a2ui.nav.NavRoute;
import art.dart.a2ui.nav.NavScreen;
import art.dart.a2ui.nav.NavTrip;
import art.dart.a2ui.telemetry.A2uiTelemetry;
import art.dart.a2ui.telemetry.TelemetryEvent;

/**
 * Step 1 of the navigation flow: the route preview with a navigate action. The list holds one
 * row per ROUTE — tapping a row selects that route — so the single anchored route is a single
 * row with its distance and time; the steps appear as maneuvers during guidance, never here.
 */
public final class A2uiNavPreviewScreen extends Screen implements NavScreen {

    private final NavTrip mTrip;
    private int mSelectedRoute;

    public A2uiNavPreviewScreen(@NonNull CarContext carContext, @NonNull NavTrip trip) {
        super(carContext);
        mTrip = trip;
    }

    @Override
    public int navDepth() {
        return 1;
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        A2uiTelemetry.event(TelemetryEvent.TEMPLATE_RENDERED, "template",
                "RoutePreviewNavigationTemplate", "host", "a");
        NavRoute route = mTrip.getRoute();
        ItemList.Builder routes = new ItemList.Builder()
                .setOnSelectedListener(this::selectRoute)
                .setSelectedIndex(mSelectedRoute);
        routes.addItem(new Row.Builder()
                .setTitle(route.getDestination().getName())
                .addText(spannedRouteSummary(
                        route.getTotalDistanceMeters(), route.getTotalTimeSeconds()))
                .build());
        return new RoutePreviewNavigationTemplate.Builder()
                .setTitle(route.getDestination().getName())
                .setHeaderAction(Action.BACK)
                .setItemList(routes.build())
                .setNavigateAction(new Action.Builder()
                        .setTitle("Go")
                        .setOnClickListener(this::startNavigation)
                        .build())
                .build();
    }

    /**
     * Records the driver's route choice. One anchored route exists, so anything but zero is a
     * host bug, refused loudly rather than rendered wrong.
     */
    @VisibleForTesting
    void selectRoute(int index) {
        if (index != 0) {
            throw new IllegalArgumentException("one anchored route exists; selected " + index);
        }
        mSelectedRoute = index;
        invalidate();
    }

    /** Starts guidance and pushes the navigating screen; the flow cap decides whether it lands. */
    @VisibleForTesting
    boolean startNavigation() {
        mTrip.start();
        return NavFlow.push(this,
                new A2uiNavScreen(getCarContext(), mTrip, NavSurfaces.of(getCarContext())));
    }

    /**
     * The route's summary line. The route-preview template rejects rows without a
     * {@link DistanceSpan}, the way the place-list template does; the span covers the distance
     * and the host formats it for the locale.
     */
    @VisibleForTesting
    static SpannableString spannedRouteSummary(int meters, int seconds) {
        String distance = formatDistance(meters);
        SpannableString line = new SpannableString(distance + " · " + formatDuration(seconds));
        line.setSpan(DistanceSpan.create(Distance.create(meters, Distance.UNIT_METERS)),
                0, distance.length(), 0);
        return line;
    }

    private static String formatDistance(int meters) {
        if (meters < 1000) {
            return meters + " m";
        }
        int tenths = meters / 100;
        return (tenths / 10) + "." + (tenths % 10) + " km";
    }

    private static String formatDuration(int seconds) {
        int minutes = Math.max(1, (seconds + 30) / 60);
        return minutes + " min";
    }
}
