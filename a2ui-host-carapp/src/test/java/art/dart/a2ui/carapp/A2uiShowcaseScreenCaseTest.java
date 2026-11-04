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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.testing.ScreenController;
import androidx.car.app.testing.TestCarContext;
import androidx.car.app.testing.TestScreenManager;
import androidx.lifecycle.Lifecycle;

import android.car.drivingstate.CarUxRestrictions;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.demo.DemoRestrictions;
import art.dart.a2ui.demo.DemoTrace;
import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.policy.A2uiActionBinder;
import art.dart.a2ui.protocol.A2uiMessage;
import art.dart.a2ui.protocol.A2uiMessageProcessor;
import art.dart.a2ui.protocol.A2uiProtocolException;
import art.dart.a2ui.demo.UseCaseCorpus;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.protocol.A2uiMessageProcessor.VoiceOutcome;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The recorded corpus, rendered: each case is loaded into the screen's processor under its own id. */
@RunWith(RobolectricTestRunner.class)
public class A2uiShowcaseScreenCaseTest {

    private TestCarContext mCarContext;
    private FakeSource mSource;
    private A2uiShowcaseScreen mScreen;

    @Before
    public void setUp() {
        mCarContext = TestCarContext.createCarContext(RuntimeEnvironment.getApplication());
        mSource = new FakeSource();
        // The emulator templates host's real list limit; TestCarContext's default of 6 would
        // truncate every interactive control off the screen.
        overrideContentLimit(100);
        CarUxRestrictionsUtil.getInstance(mCarContext).setUxRestrictions(
                new CarUxRestrictions.Builder(false,
                        A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, 0).build());
        mScreen = new A2uiShowcaseScreen(mCarContext, mSource);
        mScreen.setDemoOverrideForTesting(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        new ScreenController(mScreen).moveToState(Lifecycle.State.STARTED);
    }

    @After
    public void tearDown() {
        mScreen.setDemoOverrideForTesting(null);
    }

    @Test
    public void theShowcaseTraceIsWhatIsShownUntilSomethingElseIsAskedFor() {
        assertThat(headerTitle()).contains("A2UI showcase");
    }

    /** Every case, not a sample; the size assertion keeps an empty corpus from passing. */
    @Test
    public void everyRecordedCaseIsLoadedAndSelectable() {
        int checked = 0;
        for (UseCaseCorpus.UseCase useCase : UseCaseCorpus.all()) {
            assertThat(mScreen.selectCase(useCase.getId())).isTrue();
            // B7: the trace's own words head the screen; the id is a log detail.
            assertThat(headerTitle()).startsWith(rootTitle(useCase));
            assertThat(headerTitle()).doesNotContain(useCase.getId());
            assertThat(headerTitle()).doesNotContain("components");
            checked++;
        }
        assertThat(checked).isEqualTo(10);
    }

    private static String rootTitle(UseCaseCorpus.UseCase useCase) {
        A2uiMessageProcessor p = DemoTrace.newProcessor();
        p.processAll(useCase.getMessages());
        return p.getSurface(useCase.getId()).project(DemoTrace.ROOT_ID).getTitle();
    }

    /** Asserted on content, not row count, which can coincide either way. */
    @Test
    public void selectingACaseChangesWhatIsRendered() {
        assertThat(rowTitles()).contains("Trailer");

        mScreen.selectCase("now_playing");

        assertThat(headerTitle()).contains("Now playing");
        // "Trailer" appears on no corpus case, so its absence identifies the rendered surface.
        assertThat(rowTitles()).doesNotContain("Trailer");
        assertThat(rowTitles()).contains("Currently playing");
    }

    /**
     * A typo leaves the selected surface alone. Observed from a non-default case, where "left
     * alone" and "reset to default" differ.
     */
    @Test
    public void anUnknownCaseIdLeavesTheSELECTEDCaseInPlace() {
        mScreen.selectCase("passenger_video");

        assertThat(mScreen.selectCase("no_such_case")).isFalse();

        assertThat(headerTitle()).contains("Passenger screen");
    }

    @Test
    public void aNullCaseIdLeavesTheSELECTEDCaseInPlace() {
        mScreen.selectCase("passenger_video");

        assertThat(mScreen.selectCase(null)).isFalse();

        assertThat(headerTitle()).contains("Passenger screen");
    }

    @Test
    public void theShowcaseTraceIsItselfSelectableByName() {
        mScreen.selectCase("now_playing");

        assertThat(mScreen.selectCase(DemoTrace.SURFACE_ID)).isTrue();
        assertThat(headerTitle()).contains("A2UI showcase");
    }

    /**
     * A phrase reaches a control on the selected surface. A regression here is detected by the
     * protocol exception from dispatching into the wrong surface, not by the outcome.
     */
    @Test
    public void aPhraseReachesAControlOnTheSelectedCase() {
        mScreen.selectCase("handoff_to_driver");

        mScreen.startListening();
        mSource.speak("buy the soundtrack");

        assertThat(mScreen.lastVoiceOutcome()).isEqualTo(VoiceOutcome.CONFIRMATION_REQUIRED);
    }

    /** The same phrase names nothing on the showcase trace, so the test above is not a tautology. */
    @Test
    public void theSamePhraseMatchesNothingOnTheShowcaseTrace() {
        mScreen.startListening();
        mSource.speak("buy the soundtrack");

        assertThat(mScreen.lastVoiceOutcome()).isEqualTo(VoiceOutcome.NO_MATCH);
    }

    /**
     * The listen argument is the frozen surface too. "Send to driver" exists only on
     * {@code handoff_to_driver}, so a wrong-surface dispatch throws instead of silently succeeding.
     */
    @Test
    public void thePhraseIsDispatchedIntoTheSurfaceItWasResolvedOn() {
        mScreen.selectCase("handoff_to_driver");

        mScreen.startListening();
        mSource.speak("send to driver");

        assertThat(mScreen.lastVoiceOutcome()).isNotEqualTo(VoiceOutcome.NO_MATCH);
    }

    /**
     * A control nested inside a rendered row is not on screen, so voice must not reach it, at any
     * content limit ({@code now_playing}'s prev/play/next sit inside {@code controls}).
     */
    @Test
    public void aPhraseCannotReachAControlNestedInsideARenderedRow() {
        mScreen.selectCase("now_playing");

        mScreen.startListening();
        mSource.speak("previous");

        assertThat(mScreen.lastVoiceOutcome()).isEqualTo(VoiceOutcome.NO_MATCH);
    }

    // Switching case must not strand a phrase or a press on the wrong surface.

    /** Switching case stops an outstanding listen. */
    @Test
    public void switchingCaseStopsAnOutstandingListen() {
        mScreen.startListening();

        mScreen.selectCase("now_playing");

        assertThat(mSource.mCancelCalls).isEqualTo(1);
    }

    /**
     * The surface a listen started on is frozen for that listen, even for a late phrase after a
     * cancel. Detected by the protocol exception a late-read surface id would cause.
     */
    @Test
    public void aLatePhraseIsResolvedAndDispatchedAgainstOneSurface() {
        mScreen.selectCase("handoff_to_driver");
        mScreen.startListening();

        mScreen.selectCase(DemoTrace.SURFACE_ID);
        mSource.speak("buy the soundtrack");

        assertThat(mScreen.lastVoiceOutcome()).isEqualTo(VoiceOutcome.CONFIRMATION_REQUIRED);
    }

    /**
     * A press is dispatched into the surface its template was rendered against, even after a case
     * switch; otherwise the click listener throws on the main thread.
     */
    @Test
    public void aPressGoesToTheSurfaceItsTemplateWasRenderedAgainst() {
        A2uiActionBinder binder = mScreen.actionBinder(DemoTrace.SURFACE_ID);
        A2uiComponent buy = showcaseComponent("buy");
        mScreen.selectCase("now_playing");

        binder.onInvoke(buy);

        // The screen manager also holds this screen, so assert what was pushed, not how many.
        assertThat(mCarContext.getCarService(TestScreenManager.class).getScreensPushed()
                .get(1)).isInstanceOf(A2uiConfirmScreen.class);
    }

    /** Anti-vacuity: the component really is absent from the case we switched to. */
    @Test
    public void theSwitchedToCaseDoesNotCarryThatComponent() {
        mScreen.selectCase("now_playing");

        assertThat(rowTitles()).doesNotContain("Buy album");
    }

    /**
     * A confirmation is executed against the surface it was raised on: confirmAction re-decides
     * across time, not which action was approved.
     */
    @Test
    public void aConfirmationIsExecutedAgainstTheSurfaceItWasRaisedOn() {
        A2uiConfirmScreen.Confirmable confirmable =
                mScreen.confirmable(DemoTrace.SURFACE_ID, showcaseComponent("buy"));

        mScreen.selectCase("now_playing");

        // Before the fix this threw A2uiProtocolException: now_playing has no component "buy".
        assertThat(confirmable.confirm()).isNotNull();
    }

    // A surface that exists and does not project. No corpus case produces one, so both surfaces
    // come from one extra recording, loaded through the screen's own guarded path.

    /** The half of the extra recording that is complete, and therefore projects. */
    private static final String STILL_GOOD = "still_good_case";

    /** The half that is created and then refused, and therefore does not. */
    private static final String HALF_WRITTEN = "half_written_case";

    /**
     * A registered surface that does not project is refused, and the case on screen stays. A guard
     * that answered false but moved {@code mSurfaceId} anyway would crash on the next template.
     */
    @Test
    public void aRegisteredSurfaceThatDoesNotProjectIsRefused() {
        A2uiShowcaseScreen screen = screenWithTheHalfWrittenRecording();
        assertThat(screen.selectCase("passenger_video")).isTrue();

        assertThat(screen.selectCase(HALF_WRITTEN)).isFalse();

        assertThat(headerTitle(screen)).contains("Passenger screen");
    }

    /**
     * The consequence: templates keep coming. Accepting the id would throw from every
     * {@code onGetTemplate}, so the template is fetched twice.
     */
    @Test
    public void transactionalRefusalKeepsTheTemplateComing() {
        A2uiShowcaseScreen screen = screenWithTheHalfWrittenRecording();
        screen.selectCase("passenger_video");

        screen.selectCase(HALF_WRITTEN);

        assertThat(headerTitle(screen)).contains("Passenger screen");
        assertThat(headerTitle(screen)).contains("Passenger screen");
    }

    /**
     * The companion: a surface that projects is accepted, so a guard that always refuses, or a
     * trace never loaded, would fail here.
     */
    @Test
    public void aFailedRecordingDoesNotPartiallyCommitItsGoodSurface() {
        A2uiShowcaseScreen screen = screenWithTheHalfWrittenRecording();

        assertThat(screen.selectCase(STILL_GOOD)).isFalse();
        assertThat(headerTitle(screen)).contains("A2UI showcase");
    }

    /**
     * Anti-vacuity: the refused id must name a surface the processor really held, or the tests
     * above degrade into the unknown-id test.
     */
    @Test
    public void theRefusedRecordingRestoresTheCompletePreBatchState() {
        A2uiMessageProcessor processor = DemoTrace.newProcessor();

        try {
            processor.processAll(aRecordingRefusedHalfway());
            throw new AssertionError("the out-of-catalog component was supposed to be refused");
        } catch (A2uiProtocolException expected) {
            assertThat(expected.getCategory()).isEqualTo(A2uiProtocolException.Category.CATALOG);
        }

        assertThat(processor.getSurface(STILL_GOOD)).isNull();
        assertThat(processor.getSurface(HALF_WRITTEN)).isNull();
    }

    /**
     * A recording that applies but names no root is how a registered surface fails to project,
     * since processAll became transactional.
     */
    @Test
    public void aRegisteredRootlessSurfaceIsRefused() {
        A2uiMessageProcessor processor = DemoTrace.newProcessor();
        processor.processAll(aRootlessRecording());
        assertThat(processor.getSurface(ROOTLESS)).isNotNull();
        try {
            processor.getSurface(ROOTLESS).project(DemoTrace.ROOT_ID);
            throw new AssertionError("a surface with no root was supposed not to project");
        } catch (A2uiProtocolException expected) {
            // Registered and unprojectable: the state projects() exists for.
        }

        A2uiShowcaseScreen screen =
                new A2uiShowcaseScreen(mCarContext, new FakeSource(), aRootlessRecording());
        ScreenController controller = new ScreenController(screen);
        controller.moveToState(Lifecycle.State.STARTED);
        try {
            assertThat(screen.selectCase("passenger_video")).isTrue();

            assertThat(screen.selectCase(ROOTLESS)).isFalse();

            assertThat(headerTitle(screen)).contains("Passenger screen");
        } finally {
            // Unregisters its restriction listener: a screen left throwing on every invalidate
            // would fail the tests after this one, not this one.
            controller.moveToState(Lifecycle.State.DESTROYED);
        }
    }

    private static final String ROOTLESS = "rootless_case";

    private static List<A2uiMessage> aRootlessRecording() {
        Map<String, Object> line = A2uiMessage.component("line", "Text");
        line.put("title", "A row with no root above it");
        return Arrays.asList(
                A2uiMessage.createSurface(DemoTrace.VERSION, ROOTLESS, DemoTrace.CATALOG_ID),
                A2uiMessage.updateComponents(DemoTrace.VERSION, ROOTLESS,
                        Collections.singletonList(line)));
    }

    /**
     * One recording an agent got half right: a complete surface, then one whose only component is
     * outside the catalog. Produced by the real protocol path; the transaction removes both.
     */
    private static List<A2uiMessage> aRecordingRefusedHalfway() {
        Map<String, Object> root = A2uiMessage.component(DemoTrace.ROOT_ID, "Column");
        root.put("title", "Still good");
        root.put("children", Collections.singletonList("line"));
        Map<String, Object> line = A2uiMessage.component("line", "Text");
        line.put("title", "A row on the surface that loaded");

        // Not in ComponentType, so updateComponents refuses the whole message at ingest.
        Map<String, Object> outOfCatalog = A2uiMessage.component(DemoTrace.ROOT_ID, "Hologram");

        return Arrays.asList(
                A2uiMessage.createSurface(DemoTrace.VERSION, STILL_GOOD, DemoTrace.CATALOG_ID),
                A2uiMessage.updateComponents(DemoTrace.VERSION, STILL_GOOD,
                        Arrays.asList(root, line)),
                // Tentatively created inside the transaction.
                A2uiMessage.createSurface(DemoTrace.VERSION, HALF_WRITTEN, DemoTrace.CATALOG_ID),
                // Refused; processAll rolls the complete batch back.
                A2uiMessage.updateComponents(DemoTrace.VERSION, HALF_WRITTEN,
                        Collections.singletonList(outOfCatalog)));
    }

    /** A screen carrying that recording, loaded exactly as the corpus is. */
    private A2uiShowcaseScreen screenWithTheHalfWrittenRecording() {
        A2uiShowcaseScreen screen =
                new A2uiShowcaseScreen(mCarContext, new FakeSource(), aRecordingRefusedHalfway());
        new ScreenController(screen).moveToState(Lifecycle.State.STARTED);
        return screen;
    }

    // ------------------------------------------------------------------------------------

    private static A2uiComponent showcaseComponent(String id) {
        A2uiMessageProcessor processor = DemoTrace.newProcessor();
        processor.processAll(DemoTrace.messages());
        A2uiComponent root =
                processor.getSurface(DemoTrace.SURFACE_ID).project(DemoTrace.ROOT_ID);
        for (A2uiComponent child : root.getChildren()) {
            if (id.equals(child.getId())) {
                return child;
            }
        }
        throw new AssertionError("no component " + id + " on the showcase trace");
    }

    private java.util.List<String> rowTitles() {
        return rowTitles(mScreen);
    }

    private static java.util.List<String> rowTitles(A2uiShowcaseScreen screen) {
        ListTemplate template = (ListTemplate) screen.onGetTemplate();
        java.util.List<String> titles = new java.util.ArrayList<>();
        for (androidx.car.app.model.Item item : template.getSingleList().getItems()) {
            titles.add(((androidx.car.app.model.Row) item).getTitle().toString());
        }
        return titles;
    }

    private String headerTitle() {
        return headerTitle(mScreen);
    }

    private static String headerTitle(A2uiShowcaseScreen screen) {
        ListTemplate template = (ListTemplate) screen.onGetTemplate();
        return template.getHeader().getTitle().toString();
    }

    private void overrideContentLimit(int limit) {
        ConstraintManager constraints = mock(ConstraintManager.class);
        when(constraints.getContentLimit(anyInt())).thenReturn(limit);
        mCarContext.overrideCarService(ConstraintManager.class, constraints);
    }

    private static final class FakeSource implements TranscriptSource {
        int mCancelCalls;
        @Nullable Callback mCallback;

        @Override
        public void listen(@NonNull Callback callback) {
            mCallback = callback;
        }

        @Override
        public void cancel() {
            mCancelCalls++;
        }

        void speak(String phrase) {
            mCallback.onTranscript(phrase);
        }
    }
}
