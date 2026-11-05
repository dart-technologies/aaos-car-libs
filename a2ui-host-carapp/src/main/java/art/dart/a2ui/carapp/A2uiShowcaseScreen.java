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

import android.Manifest;
import android.car.drivingstate.CarUxRestrictions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Template;

import androidx.core.graphics.drawable.IconCompat;

import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.demo.DemoRestrictions;
import art.dart.a2ui.demo.DemoSurface;
import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.policy.A2uiPruner;
import art.dart.a2ui.protocol.A2uiMessage;
import art.dart.a2ui.protocol.A2uiMessageProcessor;
import art.dart.a2ui.protocol.A2uiSurface;
import art.dart.a2ui.policy.A2uiActionBinder;
import art.dart.a2ui.policy.A2uiActionPolicy;
import art.dart.a2ui.demo.DemoTrace;
import art.dart.a2ui.demo.UseCaseCorpus;
import androidx.car.app.CarToast;
import art.dart.a2ui.policy.A2uiValueLimits;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.render.CarAppRenderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The showcase surface: one A2UI model, pruned by a live restriction reading, bounded by the host's
 * own content limit, rendered as a template the host validates.
 *
 * <p>Four mechanisms compose: provenance ({@link CarUxRestrictionsUtil.RestrictionsSource}, which
 * the conformance format cannot express, "Scope note 2"), kind ({@code prune}), quantity
 * ({@link ConstraintManager}, "Scope note 1") and magnitude ({@link A2uiValueLimits}, which changes
 * with the reading while the template's content limit does not).
 */
public final class A2uiShowcaseScreen extends Screen {

    private static final String TAG = "A2uiShowcaseScreen";

    /** Debug-only trigger for the voice path. Registered in debuggable builds only. */
    @VisibleForTesting
    static final String ACTION_LISTEN = "art.dart.a2ui.carapp.action.LISTEN";

    /** Debug-only surface picker. Registered in debuggable builds only. */
    @VisibleForTesting
    static final String ACTION_CASE = "art.dart.a2ui.carapp.action.CASE";

    /** The case id to show, e.g. {@code passenger_video}. Absent or unknown leaves it unchanged. */
    @VisibleForTesting
    static final String EXTRA_CASE_ID = "art.dart.a2ui.carapp.extra.CASE_ID";

    private final CarUxRestrictionsUtil mRestrictions;

    /**
     * Held in a field: {@code CarUxRestrictionsUtil.register} keeps only a weak reference, so an
     * inline lambda would be collected and the screen would stop seeing restriction changes.
     */
    private final CarUxRestrictionsUtil.OnUxRestrictionsChangedListener mUxrListener =
            uxr -> onVehicleStateChanged();

    /** The protocol state, retained so an action can travel back to the surface it came from. */
    private final A2uiMessageProcessor mProcessor = DemoTrace.newProcessor();

    /** Accepted synthetic state for this screen only; never sourced from process-global state. */
    @Nullable
    private Integer mDemoOverride;

    /**
     * Supplies the vehicle state the action policy decides against. A demo override may replace
     * the bits but never upgrades their provenance.
     */
    private final A2uiMessageProcessor.RestrictionReader mReader =
            new A2uiMessageProcessor.RestrictionReader() {
                @Override
                public int activeRestrictions() {
                    return effectiveRestrictions(mDemoOverride,
                            mRestrictions.getCurrentRestrictions());
                }

                @Override
                public boolean isTrustworthy() {
                    return isReadingTrustworthy(mDemoOverride,
                            mRestrictions.getRestrictionsSource());
                }

                @Override
                public A2uiMessageProcessor.RestrictionReading currentReading() {
                    CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
                            mRestrictions.getRestrictionsSnapshot();
                    return new A2uiMessageProcessor.RestrictionReading(
                            effectiveRestrictions(mDemoOverride, snapshot.getRestrictions()),
                            isReadingTrustworthy(mDemoOverride, snapshot.getSource()));
                }
            };

    /**
     * Cancels a running capture as well as re-rendering: withholding the button stops a new
     * capture, not one already running. A method because mVoice and mVoiceGate are declared below.
     */
    @VisibleForTesting
    void onVehicleStateChanged() {
        if (!mVoiceGate.allowsListening()) {
            mVoice.cancel();
        }
        invalidate();
    }

    /**
     * Whether the vehicle permits transcription, provenance honoured. Gates capture only; what a
     * phrase then does is decided again by the action policy.
     */
    private final A2uiVoiceCapture.Gate mVoiceGate = new A2uiVoiceCapture.Gate() {
        @Override
        public boolean allowsListening() {
            CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
                    mRestrictions.getRestrictionsSnapshot();
            return A2uiShowcaseScreen.allowsListening(
                    mDemoOverride, snapshot.getSource(), snapshot.getRestrictions());
        }

        @Override
        public TranscriptSource.Unavailable reason() {
            return TranscriptSource.Unavailable.RESTRICTED;
        }
    };

    /**
     * The voice path, wired to the platform recogniser via {@link PlatformTranscriptSource}; on an
     * emulator without microphone input it reports a specific refusal without opening the mic.
     */
    private final A2uiVoiceCapture mVoice;

    /** Whether the microphone has been asked for on this screen; asked at most once. */
    private boolean mAskedForMicrophone;

    /**
     * Which surface is on screen. Every corpus case lives in the same processor, so switching is a
     * field assignment and {@link A2uiVoiceCapture} keeps dispatching into the visible surface.
     */
    @NonNull
    private String mSurfaceId = DemoTrace.SURFACE_ID;

    /**
     * The debug receivers, held so they can be released: each references this Screen, and
     * androidx.car.app builds a new Screen on every host re-bind.
     */
    private final List<BroadcastReceiver> mDebugReceivers = new ArrayList<>();

    /**
     * The outcome of the most recent voice dispatch, kept so {@code A2uiShowcaseScreenVoiceTest}
     * and {@code A2uiShowcaseScreenCaseTest} can assert the wiring.
     */
    @Nullable
    private A2uiMessageProcessor.VoiceOutcome mLastVoiceOutcome;

    public A2uiShowcaseScreen(@NonNull CarContext carContext) {
        this(carContext, new PlatformTranscriptSource(new SpeechRecognizerBinding(carContext)));
    }

    /** Substitutes the transcript source, so the wiring around it can be tested. */
    @VisibleForTesting
    A2uiShowcaseScreen(@NonNull CarContext carContext, @NonNull TranscriptSource source) {
        this(carContext, source, Collections.emptyList());
    }

    /**
     * Also loads one extra recorded trace through {@link #loadGuarded}, so a surface that exists
     * but does not project can reach {@link #projects}; no corpus case produces that state.
     */
    @VisibleForTesting
    A2uiShowcaseScreen(@NonNull CarContext carContext, @NonNull TranscriptSource source,
            @NonNull List<A2uiMessage> extraTrace) {
        super(carContext);
        mRestrictions = CarUxRestrictionsUtil.getInstance(carContext);

        // mVoice must exist before register(): register() seeds the listener synchronously, and at
        // cold start the gate refuses and reaches mVoice. Pinned by
        // A2uiShowcaseScreenVoiceTest#constructorSurvivesWithNoDemoOverride.
        mVoice = new A2uiVoiceCapture(mProcessor, source, mVoiceGate);

        mRestrictions.register(mUxrListener);
        registerDemoOverrideReceiverIfDebuggable(carContext);
        registerListenReceiverIfDebuggable(carContext);
        registerCaseReceiverIfDebuggable(carContext);

        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                // A live recogniser holds the microphone; release it when the surface goes away.
                mVoice.cancel();
            }

            @Override
            public void onDestroy(@NonNull LifecycleOwner owner) {
                mVoice.cancel();
                for (BroadcastReceiver receiver : mDebugReceivers) {
                    carContext.unregisterReceiver(receiver);
                }
                mDebugReceivers.clear();
                mRestrictions.unregister(mUxrListener);
            }
        });

        mProcessor.processAll(DemoTrace.messages());
        // The corpus, loaded so it is reachable as a demo without rebuilding.
        for (UseCaseCorpus.UseCase useCase : UseCaseCorpus.all()) {
            loadGuarded(useCase.getId(), useCase.getMessages());
        }
        // Last, and through the same call, so a test trace cannot displace a corpus case and cannot
        // reach a load path the corpus does not use.
        loadGuarded("extra trace", extraTrace);
        mProcessor.setRestrictionReader(mReader);
        mProcessor.setActionSink((surfaceId, componentId, action, payload) ->
                Log.i(TAG, "action dispatched: surface=" + surfaceId + " component=" + componentId
                        + " action=" + action));
    }

    /**
     * Loads one recorded trace and survives it failing, so one bad recording costs that case
     * rather than app startup. The catch can leave an empty registered surface, which
     * {@link #projects} guards; the test trace uses this same path.
     */
    private void loadGuarded(@NonNull String id, @NonNull Iterable<A2uiMessage> messages) {
        try {
            mProcessor.processAll(messages);
        } catch (RuntimeException e) {
            Log.i(TAG, "case " + id + " did not load: " + e);
        }
    }

    /**
     * Decides and invokes actions on behalf of the renderer, through {@link #mProcessor} so the
     * policy is not copied into the host.
     */
    @NonNull
    @VisibleForTesting
    A2uiActionBinder actionBinder(@NonNull String surfaceId) {
        return new A2uiActionBinder() {
        @Override
        public A2uiActionPolicy.Decision decisionFor(@NonNull A2uiComponent component) {
            if (!component.isInteractive()) {
                return null;
            }
            return A2uiActionPolicy.decide(
                    A2uiActionPolicy.classify(component.getAction(), component.getActionClass()),
                    mReader.activeRestrictions(), mReader.isTrustworthy());
        }

        @Override
        public void onInvoke(@NonNull A2uiComponent component) {
            // The surface this template was rendered against, not the one selected now: a press on
            // a stale template must not dispatch into another surface.
            A2uiActionPolicy.Decision decision = mProcessor.dispatchAction(
                    surfaceId, String.valueOf(component.getId()),
                    component.getAction(), null, component.getActionClass());
            Log.i(TAG, "invoked " + component.getAction() + " -> " + decision);
            switch (decision) {
                case ALLOW:
                    CarToast.makeText(getCarContext(), component.getTitle() + " — done",
                            CarToast.LENGTH_SHORT).show();
                    break;
                case CONFIRM:
                    getScreenManager().push(new A2uiConfirmScreen(getCarContext(),
                            confirmable(surfaceId, component)));
                    break;
                case REFUSE:
                default:
                    // Reachable: the vehicle can start moving between render and press. The
                    // renderer's refusal is a courtesy; this is the enforcement.
                    CarToast.makeText(getCarContext(), A2uiActionBinder.REFUSED_NOTE,
                            CarToast.LENGTH_LONG).show();
                    break;
            }
        }
        };
    }

    /**
     * Freezes what is being confirmed; the vehicle state is still re-read when the driver answers.
     * The surface id is a parameter so a case switch cannot redirect the driver's "yes".
     */
    @VisibleForTesting
    A2uiConfirmScreen.Confirmable confirmable(
            @NonNull String surfaceId, A2uiComponent component) {
        return new A2uiConfirmScreen.Confirmable() {
            @Override
            @NonNull
            public A2uiActionPolicy.Decision confirm() {
                return mProcessor.confirmAction(surfaceId,
                        String.valueOf(component.getId()), component.getAction(), null,
                        component.getActionClass());
            }

            @Override
            @NonNull
            public String describe() {
                return String.valueOf(component.getTitle());
            }
        };
    }

    /**
     * The surface as it would be rendered now: projected, pruned and limit-applied. Recomputed per
     * call, because a phrase must act on what the driver sees when they finish speaking.
     */
    @Nullable
    private A2uiComponent renderedRoot(@NonNull String surfaceId, @Nullable Integer override,
            @NonNull Set<ComponentType> allowed) {
        A2uiSurface surface = mProcessor.getSurface(surfaceId);
        if (surface == null) {
            // Only reachable if a surface is removed underneath a selection. An NPE here would
            // crash the app on every invalidate.
            Log.i(TAG, "surface " + surfaceId + " is gone; nothing to render");
            return null;
        }
        A2uiComponent pruned = A2uiPruner.prune(surface.project(DemoTrace.ROOT_ID), allowed);
        A2uiValueLimits limits = override != null
                // An override carries no vehicle configuration; zeroes select the conservative
                // limits.
                ? A2uiValueLimits.from(override, 0, 0, 0)
                : limitsFor(mRestrictions.getRestrictionsSource(),
                        mRestrictions.getCurrentRestrictions());
        if (pruned != null) {
            pruned = A2uiPruner.applyLimits(pruned, limits);
        }
        return pruned;
    }

    /**
     * What the driver can actually see: {@link #renderedRoot} cut to the host's list limit, with
     * descendants of drawn rows removed. Delegated to {@link CarAppRenderer#visibleRoot} so voice
     * and the screen cannot disagree.
     */
    @Nullable
    private A2uiComponent visibleRoot(@NonNull String surfaceId, @Nullable Integer override,
            @NonNull Set<ComponentType> allowed, int contentLimit) {
        return CarAppRenderer.visibleRoot(renderedRoot(surfaceId, override, allowed), contentLimit);
    }

    /**
     * Whether a surface exists and can be projected from its root. A failed trace can leave an
     * empty registered surface; accepting it would make every template fetch throw.
     * {@code A2uiShowcaseScreenCaseTest} feeds such a recording.
     */
    private boolean projects(@NonNull String surfaceId) {
        A2uiSurface surface = mProcessor.getSurface(surfaceId);
        if (surface == null) {
            return false;
        }
        try {
            return surface.project(DemoTrace.ROOT_ID) != null;
        } catch (RuntimeException e) {
            Log.i(TAG, "case " + surfaceId + " does not project from its root: " + e);
            return false;
        }
    }

    /** The host's list limit. Read fresh; a host may change it. */
    private int contentLimit() {
        return getCarContext().getCarService(ConstraintManager.class)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST);
    }

    /** The allowed set for the current reading, demo override honoured. */
    @NonNull
    private Set<ComponentType> allowedNow(@Nullable Integer override) {
        return override != null
                ? A2uiRestrictionPolicy.allowedComponents(override)
                : allowedFor(mRestrictions.getRestrictionsSource(),
                        mRestrictions.getCurrentRestrictions());
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        Integer override = mDemoOverride;
        Set<ComponentType> allowed = allowedNow(override);

        A2uiComponent pruned = renderedRoot(mSurfaceId, override, allowed);
        A2uiValueLimits limits = override != null
                ? A2uiValueLimits.from(override, 0, 0, 0)
                : limitsFor(mRestrictions.getRestrictionsSource(),
                        mRestrictions.getCurrentRestrictions());

        int limit = contentLimit();

        // Permanent diagnostic: a real reading and a fallback render identically, so all four
        // inputs are logged on every template.
        Log.i(TAG, "display=" + mRestrictions.getDisplayId()
                + " surface=" + mSurfaceId
                + " source=" + mRestrictions.getRestrictionsSource()
                + " active=0x" + Integer.toHexString(
                        mRestrictions.getCurrentRestrictions().getActiveRestrictions())
                + (override == null ? "" : " DEMO_OVERRIDE=0x" + Integer.toHexString(override))
                + " allowed=" + allowed.size() + "/" + ComponentType.values().length
                + " limits=" + limits
                + " contentLimit=" + limit);

        int overflow = CarAppRenderer.overflowCount(pruned, limit);
        if (overflow > 0) {
            // Recorded rather than silent. A limit that quietly removes content is a content
            // policy nobody wrote down.
            Log.i(TAG, "host content limit " + limit + " dropped " + overflow + " item(s)");
        }
        return CarAppRenderer.render(pruned, title(override, subjectFor(mSurfaceId, pruned)), limit,
                actionBinder(mSurfaceId),
                voiceAction(mSurfaceId));
    }

    /**
     * The header: the surface's own words, plus the DEMO mask while an override is in force, so a
     * screenshot of an override cannot pass for a live reading.
     */
    @VisibleForTesting
    @NonNull
    static String title(@Nullable Integer override, @NonNull String subject) {
        return override == null ? subject
                : subject + " · DEMO 0x" + Integer.toHexString(override);
    }

    /** The root's title from the trace, value-limited like any other string; never the id. */
    @VisibleForTesting
    @NonNull
    static String subjectFor(@NonNull String surfaceId, @Nullable A2uiComponent root) {
        if (DemoTrace.SURFACE_ID.equals(surfaceId)) {
            return "A2UI showcase";
        }
        String title = root == null ? null : root.getTitle();
        return title == null || title.trim().isEmpty() ? "A2UI" : title.trim();
    }

    /**
     * Shows a recorded corpus case, or leaves the surface alone if the id names nothing (a blank
     * surface would look like a prune that removed everything).
     */
    @VisibleForTesting
    boolean selectCase(@Nullable String surfaceId) {
        if (surfaceId == null || !projects(surfaceId)) {
            Log.i(TAG, "case " + surfaceId + " is not loaded; staying on " + mSurfaceId);
            return false;
        }
        // Stop listening before switching, or a phrase resolves against a surface nobody sees.
        mVoice.cancel();
        mSurfaceId = surfaceId;
        Log.i(TAG, "showing case " + surfaceId);
        invalidate();
        return true;
    }

    /**
     * Whether the microphone may be opened: a trustworthy reading and no transcription bit,
     * composed from {@link #isReadingTrustworthy} and {@link #effectiveRestrictions}. Static so the
     * rule itself is tested.
     */
    @VisibleForTesting
    static boolean allowsListening(@Nullable Integer override,
            @NonNull CarUxRestrictionsUtil.RestrictionsSource source,
            @NonNull CarUxRestrictions live) {
        // Delegates to the model's composition, shared with Host B; only the override is Host A's.
        return A2uiRestrictionPolicy.allowsListening(
                effectiveRestrictions(override, live), isReadingTrustworthy(override, source));
    }

    /**
     * The allowed set for a reading, with provenance honoured: a never-delivered or disconnected
     * reading does not decide what is legal, whatever its bits say. Static so the rule itself is
     * tested.
     */
    @VisibleForTesting
    @NonNull
    static Set<ComponentType> allowedFor(
            @NonNull CarUxRestrictionsUtil.RestrictionsSource source,
            @NonNull CarUxRestrictions restrictions) {
        if (!isAuthoritative(source)) {
            return A2uiRestrictionPolicy.failClosedComponents();
        }
        return A2uiRestrictionPolicy.allowedComponents(restrictions.getActiveRestrictions());
    }

    /**
     * The mask the action policy decides against: the demo override if one is set, else the live
     * reading.
     */
    @VisibleForTesting
    static int effectiveRestrictions(
            @Nullable Integer override, @NonNull CarUxRestrictions live) {
        return override != null ? override : live.getActiveRestrictions();
    }

    /**
     * Whether the mask above may unlock things. Only the source decides: a private demo override
     * never turns NEVER_DELIVERED into authority; a DEMO-tagged reading counts only on the debug bus.
     */
    @VisibleForTesting
    static boolean isReadingTrustworthy(
            @Nullable Integer override, @NonNull CarUxRestrictionsUtil.RestrictionsSource source) {
        // A demo mask can replace the bits, never their provenance. In particular, an override of
        // zero must not turn NEVER_DELIVERED into authority to unlock consequential actions.
        return isAuthoritative(source);
    }

    private static boolean isAuthoritative(
            @NonNull CarUxRestrictionsUtil.RestrictionsSource source) {
        return source == CarUxRestrictionsUtil.RestrictionsSource.DELIVERED
                || source == CarUxRestrictionsUtil.RestrictionsSource.DEMO;
    }

    /**
     * The value limits for a reading, gated on provenance like {@link #allowedFor}: a fail-closed
     * default's numbers describe no vehicle.
     */
    @NonNull
    @VisibleForTesting
    static A2uiValueLimits limitsFor(
            @NonNull CarUxRestrictionsUtil.RestrictionsSource source,
            @NonNull CarUxRestrictions restrictions) {
        if (!isAuthoritative(source)) {
            return A2uiValueLimits.failClosed();
        }
        return A2uiValueLimits.from(
                restrictions.getActiveRestrictions(),
                restrictions.getMaxRestrictedStringLength(),
                restrictions.getMaxCumulativeContentItems(),
                restrictions.getMaxContentDepth());
    }

    /**
     * The header's voice action, or null when the vehicle forbids transcription: absent rather than
     * present-and-refusing. The debug LISTEN broadcast can still demonstrate a refusal.
     */
    @Nullable
    @VisibleForTesting
    Action voiceAction(@NonNull String surfaceId) {
        if (!mVoiceGate.allowsListening()) {
            return null;
        }
        boolean listening = mVoice.isListening();
        return new Action.Builder()
                .setTitle(listening ? "Listening…" : "Speak")
                // An app resource: the templates host draws this in another process and tints it.
                .setIcon(new CarIcon.Builder(IconCompat.createWithResource(
                        getCarContext(), R.drawable.ic_voice)).build())
                // While listening, the action stops, so the driver can always close the microphone.
                .setOnClickListener(listening
                        ? this::stopListening
                        : () -> startListening(surfaceId))
                .build();
    }

    /** Stops an outstanding listen and puts the action back. */
    @VisibleForTesting
    void stopListening() {
        mVoice.cancel();
        invalidate();
    }

    /** Why listening did not happen, in words a driver can act on, never an enum name. */
    @VisibleForTesting
    @NonNull
    static String unavailableMessage(@NonNull TranscriptSource.Unavailable reason) {
        switch (reason) {
            case NO_RECOGNITION_SERVICE:
                return "Voice input is not available on this car";
            case PERMISSION_DENIED:
                return "Voice needs the microphone";
            case RESTRICTED:
                // Not "while driving": the gate also refuses an untrustworthy reading in a
                // stationary car, and the reason does not say which.
                return "Voice input is not available right now";
            case CANCELLED:
                return "Already listening";
            case FAILED:
            default:
                return "Didn't catch that";
        }
    }

    /**
     * What the driver is told about a phrase. CONFIRMATION_REQUIRED points at the control instead
     * of raising a confirmation, since {@code dispatchByVoice} does not return the matched component.
     */
    @VisibleForTesting
    @NonNull
    static String voiceMessage(@NonNull String spoken,
            @NonNull A2uiMessageProcessor.VoiceOutcome outcome) {
        String quoted = "\u201c" + spoken + "\u201d";
        switch (outcome) {
            case DISPATCHED:
                return quoted + " — done";
            case CONFIRMATION_REQUIRED:
                return quoted + " needs confirming — press it to continue";
            case REFUSED_BY_POLICY:
                return quoted + " is not available while driving";
            case AMBIGUOUS:
                return "More than one thing answers to " + quoted;
            case NO_MATCH:
            default:
                return "Nothing here answers to " + quoted;
        }
    }

    @VisibleForTesting
    @Nullable
    A2uiMessageProcessor.VoiceOutcome lastVoiceOutcome() {
        return mLastVoiceOutcome;
    }

    /** Listens against the surface currently selected. The debug broadcast has no template. */
    @VisibleForTesting
    void startListening() {
        startListening(mSurfaceId);
    }

    /**
     * Listens once and dispatches whatever is heard against the surface as rendered then. The
     * supplier re-reads the restrictions when the phrase arrives. Threading: refusals arrive
     * synchronously and {@code SpeechRecognizer} delivers on the main thread.
     */
    @VisibleForTesting
    void startListening(@NonNull String surfaceId) {
        // The surface is frozen, the restrictions are not: re-reading restrictions acts on what the
        // driver sees, while re-reading the surface would let a case switch redirect the phrase.
        mVoice.listen(surfaceId,
                () -> {
                    Integer now = mDemoOverride;
                    return visibleRoot(surfaceId, now, allowedNow(now), contentLimit());
                },
                new A2uiVoiceCapture.Listener() {
                    @Override
                    public void onDispatched(@NonNull String spoken,
                            @NonNull A2uiMessageProcessor.VoiceOutcome outcome) {
                        mLastVoiceOutcome = outcome;
                        Log.i(TAG, "voice outcome: " + outcome);
                        CarToast.makeText(getCarContext(), voiceMessage(spoken, outcome),
                                CarToast.LENGTH_LONG).show();
                        // The phrase may have run an action, and an action may change the surface.
                        invalidate();
                    }

                    @Override
                    public void onUnavailable(@NonNull TranscriptSource.Unavailable reason,
                            @Nullable String detail) {
                        Log.i(TAG, "voice unavailable: " + reason
                                + (detail == null ? "" : " (" + detail + ")"));
                        // Asked on a press that needed it, never at launch.
                        if (shouldRequestMicrophone(reason, mAskedForMicrophone)) {
                            mAskedForMicrophone = true;
                            requestMicrophone(surfaceId);
                            return;
                        }
                        CarToast.makeText(getCarContext(), unavailableMessage(reason),
                                CarToast.LENGTH_LONG).show();
                        invalidate();
                    }
                });
        invalidate();
    }

    /**
     * Whether a refusal is one the driver can fix by granting the microphone, and has not already
     * been asked about.
     */
    @VisibleForTesting
    static boolean shouldRequestMicrophone(@NonNull TranscriptSource.Unavailable reason,
            boolean alreadyAsked) {
        return reason == TranscriptSource.Unavailable.PERMISSION_DENIED && !alreadyAsked;
    }

    /**
     * Asks the host for {@code RECORD_AUDIO} via {@code CarContext.requestPermissions} (there is no
     * app Activity), then retries the press. It cannot loop: the flag is set before the request.
     */
    private void requestMicrophone(@NonNull String surfaceId) {
        getCarContext().requestPermissions(
                Collections.singletonList(Manifest.permission.RECORD_AUDIO),
                (List<String> approved, List<String> rejected) -> {
                    Log.i(TAG, "microphone approved=" + approved + " rejected=" + rejected);
                    // The app may have been dismissed while the dialog was up.
                    if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                        Log.i(TAG, "microphone answer arrived after the screen stopped; ignoring");
                        return;
                    }
                    if (approved.contains(Manifest.permission.RECORD_AUDIO)) {
                        // The surface the press was made against, not the one selected now.
                        startListening(surfaceId);
                        return;
                    }
                    CarToast.makeText(getCarContext(),
                            "Voice needs the microphone", CarToast.LENGTH_LONG).show();
                    invalidate();
                });
    }

    /**
     * Registers the listen trigger in a debuggable build only, so a refusal can be demonstrated
     * when the on-screen action is withheld. Registered in code, so release has no exported
     * component that can start a microphone.
     */
    private void registerListenReceiverIfDebuggable(@NonNull CarContext carContext) {
        if (!debugBusAllowed(carContext.getApplicationInfo(), android.os.Build.TYPE)) {
            return;
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "listen requested by broadcast");
                startListening();
            }
        };
        carContext.registerReceiver(receiver, new IntentFilter(ACTION_LISTEN),
                Context.RECEIVER_EXPORTED);
        mDebugReceivers.add(receiver);
    }

    /**
     * Registers the corpus picker in a debuggable build only. A broadcast, not a picker screen, so
     * the navigation stack under test is unchanged.
     */
    private void registerCaseReceiverIfDebuggable(@NonNull CarContext carContext) {
        if (!debugBusAllowed(carContext.getApplicationInfo(), android.os.Build.TYPE)) {
            return;
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                selectCase(intent.getStringExtra(EXTRA_CASE_ID));
            }
        };
        carContext.registerReceiver(receiver, new IntentFilter(ACTION_CASE),
                Context.RECEIVER_EXPORTED);
        mDebugReceivers.add(receiver);
    }

    /**
     * Registers the demo-override receiver in a debuggable build only, in code so release has no
     * exported component that changes driver-distraction gating.
     */
    private void registerDemoOverrideReceiverIfDebuggable(@NonNull CarContext carContext) {
        if (!debugBusAllowed(carContext.getApplicationInfo(), android.os.Build.TYPE)) {
            return;
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int active = intent.getIntExtra(DemoRestrictions.EXTRA_ACTIVE, -1);
                mDemoOverride = active < 0 ? null : active;
                Log.i(TAG, "demo override set to " + (active < 0 ? "OFF (live reading)"
                        : "0x" + Integer.toHexString(active)));
                // onVehicleStateChanged(), not invalidate(): an override that only re-renders
                // leaves a running capture open.
                onVehicleStateChanged();
            }
        };
        carContext.registerReceiver(receiver, new IntentFilter(DemoRestrictions.ACTION_SET),
                Context.RECEIVER_EXPORTED);
        mDebugReceivers.add(receiver);
    }

    @VisibleForTesting
    static boolean debugBusAllowed(@NonNull ApplicationInfo info, @NonNull String buildType) {
        return (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && !"user".equals(buildType);
    }

    @VisibleForTesting
    void setDemoOverrideForTesting(@Nullable Integer active) {
        mDemoOverride = active == null || active < 0 ? null : active;
        onVehicleStateChanged();
    }
}
