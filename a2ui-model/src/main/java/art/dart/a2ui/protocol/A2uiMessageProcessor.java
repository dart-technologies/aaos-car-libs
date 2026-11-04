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
package art.dart.a2ui.protocol;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.policy.A2uiActionClass;
import art.dart.a2ui.policy.A2uiActionPolicy;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;

import art.dart.a2ui.model.ComponentType;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Applies A2UI lifecycle messages in order and holds the resulting surfaces.
 *
 * <p>Out-of-order updates, foreign catalogs and unknown component types are refused, never
 * skipped. Streaming, the v0.8 shapes, themes and styles are not implemented.
 */
public final class A2uiMessageProcessor {

    /** Called when a rendered surface reports user intent back toward the agent. */
    public interface ActionSink {
        void onAction(@NonNull String surfaceId, @NonNull String componentId,
                @NonNull String action, @Nullable Object payload);
    }

    /** Host-owned classifier for live-agent actions. */
    public interface ActionClassResolver {
        @NonNull A2uiActionClass resolve(@NonNull String action,
                @Nullable A2uiActionClass agentDeclared);
    }

    /**
     * The vehicle state an action is decided against, read again when a confirmation is answered.
     * Platform-free so it runs in a local JVM.
     */
    public interface RestrictionReader {
        /** The mask from {@code CarUxRestrictions.getActiveRestrictions()}. */
        int activeRestrictions();

        /** Whether that mask is a real reading — {@code RestrictionsSource.DELIVERED}. */
        boolean isTrustworthy();

        /** Captures both values as one decision input. Implementations should override atomically. */
        @NonNull
        default RestrictionReading currentReading() {
            return new RestrictionReading(activeRestrictions(), isTrustworthy());
        }
    }

    /** Immutable input used for one policy decision. */
    public static final class RestrictionReading {
        private final int mActiveRestrictions;
        private final boolean mTrustworthy;

        public RestrictionReading(int activeRestrictions, boolean trustworthy) {
            mActiveRestrictions = activeRestrictions;
            mTrustworthy = trustworthy;
        }

        public int getActiveRestrictions() { return mActiveRestrictions; }
        public boolean isTrustworthy() { return mTrustworthy; }
    }

    /** The default reader: fully restricted and untrustworthy, so an unwired host stays gated. */
    private static final RestrictionReader FAIL_CLOSED_READER = new RestrictionReader() {
        @Override
        public int activeRestrictions() {
            return A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;
        }

        @Override
        public boolean isTrustworthy() {
            return false;
        }
    };

    private final String mAcceptedCatalogId;
    private final Set<String> mAcceptedVersions;
    private final Map<String, A2uiSurface> mSurfaces = new LinkedHashMap<>();
    @Nullable
    private ActionSink mActionSink;
    @Nullable
    private ActionClassResolver mActionClassResolver;
    @NonNull
    private RestrictionReader mRestrictionReader = FAIL_CLOSED_READER;

    public A2uiMessageProcessor(@NonNull String acceptedCatalogId,
            @NonNull Set<String> acceptedVersions) {
        mAcceptedCatalogId = acceptedCatalogId;
        mAcceptedVersions = acceptedVersions;
    }

    public void setActionSink(@Nullable ActionSink sink) {
        mActionSink = sink;
    }

    /**
     * Installs host-owned action authority. Null preserves the recorded-demo compatibility table.
     * A live transport must install a resolver such as {@code TrustedActionRegistry}.
     */
    public void setActionClassResolver(@Nullable ActionClassResolver resolver) {
        mActionClassResolver = resolver;
    }

    /** Supplies the vehicle state actions are decided against. Null restores the fail-closed one. */
    public void setRestrictionReader(@Nullable RestrictionReader reader) {
        mRestrictionReader = reader == null ? FAIL_CLOSED_READER : reader;
    }

    @NonNull
    public Map<String, A2uiSurface> getSurfaces() {
        return Collections.unmodifiableMap(mSurfaces);
    }

    @Nullable
    public A2uiSurface getSurface(@NonNull String surfaceId) {
        return mSurfaces.get(surfaceId);
    }

    /** Clears all protocol state at an authenticated session or provider boundary. */
    public void reset() {
        mSurfaces.clear();
    }

    /** Applies one message, or throws {@link A2uiProtocolException}. */
    public void process(@NonNull A2uiMessage message) {
        if (!mAcceptedVersions.contains(message.getVersion())) {
            throw new A2uiProtocolException(A2uiProtocolException.Category.CATALOG,
                    "protocol version " + message.getVersion() + " not negotiated; accepted "
                            + mAcceptedVersions);
        }
        switch (message.getKind()) {
            case CREATE_SURFACE:
                createSurface(message);
                return;
            case UPDATE_COMPONENTS:
                updateComponents(message);
                return;
            case UPDATE_DATA_MODEL:
                requireSurface(message.getSurfaceId())
                        .writeDataModel(requirePath(message), message.getValue());
                return;
            case DELETE_SURFACE:
                requireSurface(message.getSurfaceId());
                mSurfaces.remove(message.getSurfaceId());
                return;
            default:
                // Unreachable: all four Kinds are handled above. A new Kind must fail here, not
                // fall through.
                throw new A2uiProtocolException(A2uiProtocolException.Category.MALFORMED,
                        "unhandled message kind " + message.getKind());
        }
    }

    /** Applies a trace atomically; the first refusal restores the complete pre-batch state. */
    public void processAll(@NonNull Iterable<A2uiMessage> messages) {
        Map<String, A2uiSurface> before = new LinkedHashMap<>();
        for (Map.Entry<String, A2uiSurface> entry : mSurfaces.entrySet()) {
            before.put(entry.getKey(), new A2uiSurface(entry.getValue()));
        }
        try {
            for (A2uiMessage m : messages) {
                process(m);
            }
        } catch (RuntimeException failure) {
            mSurfaces.clear();
            mSurfaces.putAll(before);
            throw failure;
        }
    }

    /**
     * Routes user intent from a rendered surface back toward the agent, if policy permits it. The
     * sink is reached only on ALLOW; on CONFIRM the caller asks the driver, then calls
     * {@link #confirmAction}.
     *
     * @param declaredClass the class the agent claims for this action, or null. It can raise the
     *        classification, never lower it — see {@link A2uiActionPolicy#classify}.
     */
    @NonNull
    public A2uiActionPolicy.Decision dispatchAction(@NonNull String surfaceId,
            @NonNull String componentId, @NonNull String action, @Nullable Object payload,
            @Nullable A2uiActionClass declaredClass) {
        return dispatchAction(surfaceId, componentId, action, payload, declaredClass,
                mRestrictionReader);
    }

    /**
     * Dispatches against a specific display's vehicle state, read now: a handed-off control is
     * authorized by the display it is rendered on at click time, not by a transfer-time snapshot.
     */
    @NonNull
    public A2uiActionPolicy.Decision dispatchAction(@NonNull String surfaceId,
            @NonNull String componentId, @NonNull String action, @Nullable Object payload,
            @Nullable A2uiActionClass declaredClass, @NonNull RestrictionReader reader) {
        A2uiActionClass actionClass = classifyAgainstSurface(surfaceId, componentId, action,
                declaredClass);
        RestrictionReading reading = reader.currentReading();
        A2uiActionPolicy.Decision decision = A2uiActionPolicy.decide(actionClass,
                reading.getActiveRestrictions(), reading.isTrustworthy());
        if (decision == A2uiActionPolicy.Decision.ALLOW && mActionSink != null) {
            mActionSink.onAction(surfaceId, componentId, action, payload);
        }
        return decision;
    }

    /** Convenience for an action the agent did not classify. */
    @NonNull
    public A2uiActionPolicy.Decision dispatchAction(@NonNull String surfaceId,
            @NonNull String componentId, @NonNull String action, @Nullable Object payload) {
        return dispatchAction(surfaceId, componentId, action, payload, null);
    }

    /** What a spoken utterance did. Every value except the first two ran nothing. */
    // metalava api-lint: Enum. Suppressed, not converted to @IntDef: it is already public API
    // (docs/api-surface/a2ui-model.txt), and the memory rationale does not apply at this scale.
    @SuppressWarnings("Enum")
    public enum VoiceOutcome {
        /** Matched one control, and policy allowed it. The sink was reached. */
        DISPATCHED,
        /** Matched one control, and policy wants the driver asked first. */
        CONFIRMATION_REQUIRED,
        /** Matched one control, and policy refused it. */
        REFUSED_BY_POLICY,
        /** Nothing on the surface answers to that phrase. */
        NO_MATCH,
        /** More than one control answers to it. Deliberately not a guess. */
        AMBIGUOUS
    }

    /** Result of resolving and dispatching one spoken utterance. */
    public static final class VoiceDispatchResult {
        private final VoiceOutcome mOutcome;
        @Nullable private final A2uiComponent mMatchedComponent;

        private VoiceDispatchResult(@NonNull VoiceOutcome outcome,
                @Nullable A2uiComponent matchedComponent) {
            mOutcome = outcome;
            mMatchedComponent = matchedComponent;
        }

        @NonNull public VoiceOutcome getOutcome() { return mOutcome; }
        @Nullable public A2uiComponent getMatchedComponent() { return mMatchedComponent; }
    }

    /**
     * Invokes a control by voice through exactly the same policy as a touch, searching only
     * {@code renderedRoot} (what the host actually rendered), so voice reaches nothing off screen.
     *
     * <p>Matches {@link A2uiComponent#getVoicePhrase()} case-insensitively after trimming.
     * {@link VoiceOutcome#NO_MATCH} and {@link VoiceOutcome#AMBIGUOUS} run nothing; an ambiguous
     * phrase never picks the first match.
     */
    @NonNull
    public VoiceOutcome dispatchByVoice(@NonNull String surfaceId, @Nullable String spoken,
            @Nullable A2uiComponent renderedRoot) {
        return dispatchByVoiceForResult(surfaceId, spoken, renderedRoot, mRestrictionReader)
                .getOutcome();
    }

    /** Resolves voice against rendered content and authorizes it for the supplied display. */
    @NonNull
    public VoiceDispatchResult dispatchByVoiceForResult(@NonNull String surfaceId,
            @Nullable String spoken, @Nullable A2uiComponent renderedRoot,
            @NonNull RestrictionReader reader) {
        A2uiSurface surface = requireSurface(surfaceId);
        if (spoken == null || spoken.trim().isEmpty() || renderedRoot == null) {
            return new VoiceDispatchResult(VoiceOutcome.NO_MATCH, null);
        }
        String needle = spoken.trim().toLowerCase(Locale.ROOT);
        // `surface` is bound but deliberately not searched (see the javadoc). The assert's failing
        // branch is unreachable, so coverage reports this line as partial.
        assert surface != null;

        List<A2uiComponent> matches = new ArrayList<>();
        collectSpeakable(renderedRoot, needle, matches);
        if (matches.isEmpty()) {
            return new VoiceDispatchResult(VoiceOutcome.NO_MATCH, null);
        }
        if (matches.size() > 1) {
            return new VoiceDispatchResult(VoiceOutcome.AMBIGUOUS, null);
        }

        A2uiComponent target = matches.get(0);
        // Ownership is still validated by dispatchAction: visibility comes from the screen, the
        // owned action only from the surface.
        A2uiActionPolicy.Decision decision = dispatchAction(surfaceId,
                String.valueOf(target.getId()), target.getAction(), null, target.getActionClass(),
                reader);
        switch (decision) {
            case ALLOW:
                return new VoiceDispatchResult(VoiceOutcome.DISPATCHED, target);
            case CONFIRM:
                return new VoiceDispatchResult(VoiceOutcome.CONFIRMATION_REQUIRED, target);
            case REFUSE:
            default:
                return new VoiceDispatchResult(VoiceOutcome.REFUSED_BY_POLICY, target);
        }
    }

    private static void collectSpeakable(
            A2uiComponent node, String needle, List<A2uiComponent> out) {
        String phrase = node.getVoicePhrase();
        if (phrase != null && phrase.trim().toLowerCase(Locale.ROOT).equals(needle)
                && node.getId() != null) {
            out.add(node);
        }
        for (A2uiComponent child : node.getChildren()) {
            collectSpeakable(child, needle, out);
        }
    }

    /**
     * Runs an action the driver has just confirmed, deciding again against the current state: a
     * dialog raised while parked can be answered while moving.
     *
     * @return the decision made at confirmation time; the sink is reached only if it is not
     *         {@link A2uiActionPolicy.Decision#REFUSE}
     */
    @NonNull
    public A2uiActionPolicy.Decision confirmAction(@NonNull String surfaceId,
            @NonNull String componentId, @NonNull String action, @Nullable Object payload,
            @Nullable A2uiActionClass declaredClass) {
        return confirmAction(surfaceId, componentId, action, payload, declaredClass,
                mRestrictionReader);
    }

    /** Confirms against a specific display, re-reading it now. */
    @NonNull
    public A2uiActionPolicy.Decision confirmAction(@NonNull String surfaceId,
            @NonNull String componentId, @NonNull String action, @Nullable Object payload,
            @Nullable A2uiActionClass declaredClass, @NonNull RestrictionReader reader) {
        A2uiActionClass actionClass = classifyAgainstSurface(surfaceId, componentId, action,
                declaredClass);
        RestrictionReading reading = reader.currentReading();
        A2uiActionPolicy.Decision decision = A2uiActionPolicy.decide(actionClass,
                reading.getActiveRestrictions(), reading.isTrustworthy());
        if (decision != A2uiActionPolicy.Decision.REFUSE && mActionSink != null) {
            mActionSink.onAction(surfaceId, componentId, action, payload);
        }
        return decision;
    }

    /**
     * Classifies an action against what the surface says, not what the caller claims: the
     * component must declare exactly this action, and the effective class is the more restrictive
     * of the surface's and the caller's.
     */
    @NonNull
    private A2uiActionClass classifyAgainstSurface(String surfaceId, String componentId,
            String action, @Nullable A2uiActionClass callerDeclared) {
        A2uiSurface surface = requireSurface(surfaceId);
        String declared = surface.requireDeclaredAction(componentId);
        if (!declared.equals(action)) {
            throw new A2uiProtocolException(A2uiProtocolException.Category.INTEGRITY,
                    "component " + componentId + " declares action " + declared
                            + ", not " + action);
        }
        A2uiActionClass fromSurface = surface.declaredActionClass(componentId);
        A2uiActionClass effective = callerDeclared;
        if (fromSurface != null) {
            effective = effective == null ? fromSurface
                    : A2uiActionClass.mostRestrictive(effective, fromSurface);
        }
        return mActionClassResolver == null
                ? A2uiActionPolicy.classify(action, effective)
                : mActionClassResolver.resolve(action, effective);
    }

    private void createSurface(A2uiMessage message) {
        if (mSurfaces.containsKey(message.getSurfaceId())) {
            // Upstream asserts IntegrityError here; same word deliberately.
            throw new A2uiProtocolException(A2uiProtocolException.Category.INTEGRITY,
                    "surface " + message.getSurfaceId() + " already exists");
        }
        String catalogId = message.getCatalogId();
        if (!mAcceptedCatalogId.equals(catalogId)) {
            throw new A2uiProtocolException(A2uiProtocolException.Category.CATALOG,
                    "catalog " + catalogId + " was not negotiated; this host accepts "
                            + mAcceptedCatalogId);
        }
        mSurfaces.put(message.getSurfaceId(),
                new A2uiSurface(message.getSurfaceId(), catalogId));
    }

    /** Applies a component update atomically, so a rejected message leaves the surface unchanged. */
    private void updateComponents(A2uiMessage message) {
        A2uiSurface surface = requireSurface(message.getSurfaceId());

        // Pass 1: validate. Nothing has been written yet, so any throw leaves the surface untouched.
        for (Map<String, Object> props : message.getComponents()) {
            Object id = props.get("id");
            Object component = props.get("component");
            if (!(id instanceof String) || !(component instanceof String)) {
                throw new A2uiProtocolException(A2uiProtocolException.Category.MALFORMED,
                        "component entry needs string 'id' and 'component': " + props);
            }
            if (ComponentType.fromCatalogName((String) component) == null) {
                // Refused at ingest too, so no later policy relaxation can surface it.
                throw new A2uiProtocolException(A2uiProtocolException.Category.CATALOG,
                        "component type " + component + " is not in catalog " + mAcceptedCatalogId);
            }
        }

        // Pass 2: commit. Every entry has already been checked, so this loop cannot throw partway.
        for (Map<String, Object> props : message.getComponents()) {
            surface.putComponent((String) props.get("id"), props);
        }
    }

    @NonNull
    private A2uiSurface requireSurface(@NonNull String surfaceId) {
        A2uiSurface surface = mSurfaces.get(surfaceId);
        if (surface == null) {
            throw new A2uiProtocolException(A2uiProtocolException.Category.INTEGRITY,
                    "no surface with id " + surfaceId);
        }
        return surface;
    }

    @NonNull
    private String requirePath(A2uiMessage message) {
        String path = message.getPath();
        if (path == null) {
            throw new A2uiProtocolException(A2uiProtocolException.Category.MALFORMED,
                    "updateDataModel needs a path");
        }
        return path;
    }
}
