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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.model.Row;
import androidx.car.app.navigation.model.Maneuver;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate;
import androidx.car.app.navigation.model.RoutingInfo;
import androidx.car.app.testing.ScreenController;
import androidx.car.app.testing.TestCarContext;
import androidx.lifecycle.Lifecycle;

import art.dart.a2ui.nav.NavFlow;
import art.dart.a2ui.nav.NavRoute;
import art.dart.a2ui.nav.NavScreen;
import art.dart.a2ui.nav.NavTrip;
import art.dart.a2ui.telemetry.A2uiTelemetry;
import art.dart.a2ui.telemetry.TelemetryBackend;
import art.dart.a2ui.telemetry.TelemetryEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The anchored navigation flow on the JVM: preview renders the route, Go pushes guidance with
 * the trip's current step, Stop ends it — and the flow cap refuses a fifth screen.
 */
@RunWith(RobolectricTestRunner.class)
public class NavScreensTest {

    private TestCarContext mCarContext;

    @Before
    public void setUp() {
        mCarContext = TestCarContext.createCarContext(RuntimeEnvironment.getApplication());
    }

    /**
     * Step 1 renders the route preview with one row per ROUTE, not per step: the ItemList is the
     * route list, and tapping a row selects that route. Steps-as-rows would crash on selection.
     */
    @Test
    public void previewScreenListsOneRowPerRoute() {
        A2uiNavPreviewScreen preview = startedPreview(new NavTrip(NavRoute.anchored()));

        RoutePreviewNavigationTemplate template =
                (RoutePreviewNavigationTemplate) preview.onGetTemplate();

        assertThat(template.getTitle().toString()).contains("Joe's Pizza");
        List<String> titles = new ArrayList<>();
        for (Object item : template.getItemList().getItems()) {
            titles.add(((Row) item).getTitle().toString());
        }
        assertThat(titles).containsExactly("Joe's Pizza");
        assertThat(template.getNavigateAction().getTitle().toString()).isEqualTo("Go");
    }

    /** Go starts the trip and pushes guidance rendering the current step. */
    @Test
    public void goPushesGuidanceWithCurrentStep() {
        NavTrip trip = new NavTrip(NavRoute.anchored());
        trip.preview();
        A2uiNavPreviewScreen preview = startedPreview(trip);

        assertThat(preview.startNavigation()).isTrue();

        assertThat(trip.getState()).isEqualTo(NavTrip.State.ACTIVE);
        Screen top = preview.getScreenManager().getTop();
        assertThat(top).isInstanceOf(A2uiNavScreen.class);
        NavigationTemplate template = (NavigationTemplate) top.onGetTemplate();
        RoutingInfo routing = (RoutingInfo) template.getNavigationInfo();
        assertThat(routing.getCurrentStep().getManeuver().getType())
                .isEqualTo(Maneuver.TYPE_DEPART);
        assertThat(routing.getNextStep().getManeuver().getType())
                .isEqualTo(Maneuver.TYPE_TURN_NORMAL_RIGHT);
        assertThat(template.getDestinationTravelEstimate().getRemainingDistance()
                .getDisplayDistance()).isEqualTo(3800.0);
    }

    /** Route selection accepts the one anchored route and refuses anything else. */
    @Test
    public void routeSelectionIsSingleAnchored() {
        A2uiNavPreviewScreen preview = startedPreview(new NavTrip(NavRoute.anchored()));

        preview.selectRoute(0);
        assertThrows(IllegalArgumentException.class, () -> preview.selectRoute(1));
    }

    /** Stop ends guidance and returns to the preview. */
    @Test
    public void stopEndsGuidanceAndPops() {
        NavTrip trip = new NavTrip(NavRoute.anchored());
        trip.preview();
        A2uiNavPreviewScreen preview = startedPreview(trip);
        preview.startNavigation();
        A2uiNavScreen nav = (A2uiNavScreen) preview.getScreenManager().getTop();
        new ScreenController(nav).moveToState(Lifecycle.State.STARTED);

        assertThat(nav.stopNavigation()).isTrue();

        assertThat(trip.getState()).isEqualTo(NavTrip.State.IDLE);
        assertThat(preview.getScreenManager().getTop()).isSameInstanceAs(preview);
    }

    /** The fourth screen is the last — the same 4-step cap the POI flow asserts. */
    @Test
    public void fifthPushIsRefused() {
        A2uiNavPreviewScreen preview = startedPreview(new NavTrip(NavRoute.anchored()));
        Screen next = new A2uiNavPreviewScreen(mCarContext, new NavTrip(NavRoute.anchored()));

        assertThat(NavFlow.push(preview, next)).isTrue();
        assertThat(NavFlow.push(new DeepScreen(), next)).isFalse();
    }

    /** Preview and guidance report their templates; the session reports the nav root. */
    @Test
    public void templatesAndRootAreReported() {
        Map<String, String> seen = new HashMap<>();
        A2uiTelemetry.install(new TelemetryBackend() {
            @Override
            public void log(TelemetryEvent event, Map<String, String> params) {
                seen.put(event.name() + params.getOrDefault("template",
                        params.getOrDefault("root", "")), "1");
            }
        });
        A2uiSession.reportRoot("nav");
        startedPreview(new NavTrip(NavRoute.anchored())).onGetTemplate();
        assertThat(seen.keySet()).containsAtLeast("SESSION_STARTnav",
                "TEMPLATE_RENDEREDRoutePreviewNavigationTemplate");
    }

    @After
    public void tearDown() {
        A2uiTelemetry.resetForTesting();
    }

    private A2uiNavPreviewScreen startedPreview(NavTrip trip) {
        A2uiNavPreviewScreen preview = new A2uiNavPreviewScreen(mCarContext, trip);
        new ScreenController(preview).moveToState(Lifecycle.State.STARTED);
        return preview;
    }

    /** A navigation screen already at the cap, so the refusal path is reachable. */
    private final class DeepScreen extends Screen implements NavScreen {
        DeepScreen() {
            super(NavScreensTest.this.mCarContext);
        }

        @Override
        public int navDepth() {
            return NavFlow.MAX_SCREENS;
        }

        @NonNull
        @Override
        public androidx.car.app.model.Template onGetTemplate() {
            throw new UnsupportedOperationException("never rendered");
        }
    }
}
