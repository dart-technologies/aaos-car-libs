#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Asserts the gate still detects the defects it exists to detect.

Each mutation is applied, its suite is run, and the run must fail. The source is restored from a
byte copy, never `git checkout`, which can revert uncommitted work.

Usage:
  scripts/check-gate-sensitivity.py [--list] [--check-registry]

Exit codes: 0 every mutation detected, 1 a mutation went undetected, 2 could not be run.
"""

import os
import argparse
import glob
import shutil
import subprocess
import sys
import tempfile
# stdlib ElementTree is fine: the input is this repo's own build output.
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GP = ROOT / 'aaos-apps-gradle-project'

# Floor = the registry's size, so deleting a mutation is a visible edit. Raise it with each addition.
MINIMUM_MUTATIONS = 128

# (name, file, old, new, gradle module, results dir under build/)
MUTATIONS = [
    (
        # Detected by the existing stop_unregistersFromTheUtil.
        'content limiter stop() registers instead of unregistering',
        'car-uxr-client-lib/src/com/android/car/uxr/UxrContentLimiterImpl.java',
        '        mCarUxRestrictionsUtil.unregister(mListener);',
        '        mCarUxRestrictionsUtil.register(mListener);',
        ':car-uxr-client-lib:testDebugUnitTest',
        'car-uxr-client-lib',
    ),
    (
        # The bundle crosses IPC from another app; without the KEY_ID check an id-less one parses.
        'conversation accepts a bundle with no id',
        'car-messenger-common/src/com/android/car/messenger/common/Conversation.java',
        """        if (bundle.getString(KEY_ID) == null
                || bundle.getBundle(KEY_USER) == null""",
        """        if (bundle.getBundle(KEY_USER) == null""",
        ':car-messenger-common:model:testDebugUnitTest',
        'model',
    ),
    (
        # The guard tested the parameter name (a literal), so a null description became "null".
        'subaction dialog description guard tests the constant',
        'car-apps-common/src/com/android/car/apps/common/UriUtils.java',
        '        if (!TextUtils.isEmpty(dialogDescription)) {',
        '        if (!TextUtils.isEmpty(DETAIL_DIALOG_URI_DIALOG_DESCRIPTION)) {',
        ':car-apps-common:testDebugUnitTest',
        'car-apps-common',
    ),
    (
        # Copy-paste inversion; compare car-ui-lib's DelegatingContentLimitingAdapter.
        'plugin adapter accumulates observers instead of releasing them',
        'car-ui-lib/referencedesign/plugin/src/main/java/com/chassis/car/ui/plugin/recyclerview/AdapterWrapper.java',
        '            mAdapter.unregisterAdapterDataObserver(mAdapterDataObserver);',
        '            mAdapter.registerAdapterDataObserver(mAdapterDataObserver);',
        ':plugin:testDebugUnitTest',
        'plugin',
    ),
    (
        # Delivery reaches a binder thread while UxrButton registers on the UI thread.
        'distraction fan-out iterates the observer set unlocked',
        'car-ui-lib/car-ui-lib/src/main/java/com/android/car/ui/utils/CarUxRestrictionsUtil.java',
        """        List<OnUxRestrictionsChangedListener> snapshot;
        synchronized (mObserverLock) {
            snapshot = new ArrayList<>(mObservers);
        }
        CarUxRestrictions reading = mCarUxRestrictions;
        for (OnUxRestrictionsChangedListener observer : snapshot) {
            observer.onRestrictionsChanged(reading);
        }""",
        """        for (OnUxRestrictionsChangedListener observer : mObservers) {
            observer.onRestrictionsChanged(mCarUxRestrictions);
        }""",
        ':car-ui-lib:testDebugUnitTest',
        'car-ui-lib',
    ),
    (
        # DELIVERED is the provenance every check in this fork keys on.
        'release build accepts an injected DELIVERED reading',
        'car-ui-lib/car-ui-lib/src/main/java/com/android/car/ui/utils/CarUxRestrictionsUtil.java',
        """        if (!mayInjectAReading()) {
            Log.w(TAG, "injected reading ignored: this is a test seam and this is a release "
                    + "build. A forged DELIVERED reading is indistinguishable from a real one to "
                    + "every provenance check in this library.");
            return;
        }
""",
        '',
        ':car-ui-lib:testDebugUnitTest',
        'car-ui-lib',
    ),
    (
        # The fail-closed direction; every other voice mutation pushes toward fail-open.
        'voice gate refuses unconditionally',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiRestrictionPolicy.java',
        '        return trustworthy && allowsVoiceTranscription(activeRestrictions);',
        '        return false;',
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        # The wiring, not the body: the method it called still passes its own tests.
        'host A restriction listener stops cancelling',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '            uxr -> onVehicleStateChanged();',
        '            uxr -> invalidate();',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        # Host A must re-check at delivery, not only at entry.
        'host A accepts a transcript the vehicle forbids',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiVoiceCapture.java',
        """                if (!mGate.allowsListening()) {
                    listener.onUnavailable(mGate.reason(), null);
                    return;
                }
                VoiceOutcome outcome =""",
        '                VoiceOutcome outcome =',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'host A leaves the microphone open across the transition',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        """        if (!mVoiceGate.allowsListening()) {
            mVoice.cancel();
        }
        invalidate();""",
        '        invalidate();',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        # onHeard() has a second caller (the debug SPEAK receiver), and a session can outlive its
        # reading, so gating startListening() alone is not enough.
        'host B accepts a transcript the vehicle forbids',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        """        CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
                mRestrictions.getRestrictionsSnapshot();
        if (!allowsListening(snapshot.getSource(), snapshot.getRestrictions())) {
            Log.i(TAG, "transcript refused: the vehicle forbids transcription");
            Toast.makeText(this, A2uiActionBinder.REFUSED_NOTE, Toast.LENGTH_LONG).show();
            return;
        }
""",
        '',
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        # Withholding the control does nothing to a session already running.
        'host B leaves the microphone open across the transition',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        """        if (!mayListen) {
            stopListening();
        }
""",
        '',
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        'host B offers the Speak control while transcription is forbidden',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        '            mSpeakItem.setEnabled(mayListen);',
        '            mSpeakItem.setEnabled(true);',
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        # Registered in both halves: deleting this call left every rule test green.
        'host B opens the mic when transcription is forbidden',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        """        CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
                mRestrictions.getRestrictionsSnapshot();
        if (!allowsListening(snapshot.getSource(), snapshot.getRestrictions())) {
            Toast.makeText(this, A2uiActionBinder.REFUSED_NOTE, Toast.LENGTH_LONG).show();
            return;
        }
""",
        '',
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        # A never-delivered reading is all-zero, byte-identical to a genuine BASELINE.
        'voice gate ignores provenance',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiRestrictionPolicy.java',
        '        return trustworthy && allowsVoiceTranscription(activeRestrictions);',
        '        return allowsVoiceTranscription(activeRestrictions);',
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        'isRestricted fails OPEN',
        'car-ui-lib/car-ui-lib/src/main/java/com/android/car/ui/utils/CarUxRestrictionsUtil.java',
        '        return (uxr == null) || ((uxr.getActiveRestrictions() & restrictionFlags) != 0);',
        '        return false;',
        ':car-ui-lib:testDebugUnitTest',
        'car-ui-lib',
    ),
    (
        'fail-closed default becomes BASELINE',
        'car-ui-lib/car-ui-lib/src/main/java/com/android/car/ui/utils/CarUxRestrictionsUtil.java',
        '''    static final int FAIL_CLOSED_ACTIVE_RESTRICTIONS =
            CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED;''',
        '''    static final int FAIL_CLOSED_ACTIVE_RESTRICTIONS =
            CarUxRestrictions.UX_RESTRICTIONS_BASELINE;''',
        ':car-ui-lib:testDebugUnitTest',
        'car-ui-lib',
    ),
    (
        'provenance ignored: bits alone decide',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''        if (!isAuthoritative(source)) {
            return A2uiRestrictionPolicy.failClosedComponents();
        }
''',
        '',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'composite mask tested with the any-of predicate',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiRestrictionPolicy.java',
        '''        if ((activeRestrictions & UX_RESTRICTIONS_FULLY_RESTRICTED)
                == UX_RESTRICTIONS_FULLY_RESTRICTED) {''',
        '        if ((activeRestrictions & UX_RESTRICTIONS_FULLY_RESTRICTED) != 0) {',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'value limits ignore provenance',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''        if (!isAuthoritative(source)) {
            return A2uiValueLimits.failClosed();
        }''',
        '',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'absent vehicle limit read as unlimited',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiValueLimits.java',
        '        return vehicleValue > 0 ? vehicleValue : conservativeFallback;',
        '        return vehicleValue > 0 ? vehicleValue : UNLIMITED;',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'unknown action treated as SAFE',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiActionPolicy.java',
        '    private static final A2uiActionClass UNKNOWN_ACTION = A2uiActionClass.CONSEQUENTIAL;',
        '    private static final A2uiActionClass UNKNOWN_ACTION = A2uiActionClass.SAFE;',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'agent declaration can downgrade an action',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiActionPolicy.java',
        '''        return agentDeclared == null
                ? fromTable
                : A2uiActionClass.mostRestrictive(fromTable, agentDeclared);''',
        '        return agentDeclared == null ? fromTable : agentDeclared;',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'confirmation offered while the vehicle is restricting',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiActionPolicy.java',
        '''            case CONSEQUENTIAL:
            case DESTRUCTIVE:
            default:''',
        '''            case CONSEQUENTIAL:
            case DESTRUCTIVE:
            default:
                if (true) {
                    return Decision.CONFIRM;
                }''',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'confirmation is not re-decided at confirm time',
        'a2ui-model/src/main/java/art/dart/a2ui/protocol/A2uiMessageProcessor.java',
        '''        if (decision != A2uiActionPolicy.Decision.REFUSE && mActionSink != null) {
            mActionSink.onAction(surfaceId, componentId, action, payload);
        }
        return decision;
    }''',
        '''        if (mActionSink != null) {
            mActionSink.onAction(surfaceId, componentId, action, payload);
        }
        return decision;
    }''',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'a refused control is still clickable',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/render/CarAppRenderer.java',
        '''            if (decision == A2uiActionPolicy.Decision.REFUSE) {''',
        '''            if (false) {''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'pruning silently erases the action',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiPruner.java',
        '''        A2uiComponent.Builder builder = new A2uiComponent.Builder(component.getType())
                .copyPropertiesFrom(component);''',
        '''        A2uiComponent.Builder builder = new A2uiComponent.Builder(component.getType())
                .setTitle(component.getTitle())
                .setSubtitle(component.getSubtitle());''',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'caller names its own action for a component',
        'a2ui-model/src/main/java/art/dart/a2ui/protocol/A2uiMessageProcessor.java',
        '''        if (!declared.equals(action)) {''',
        '''        if (false) {''',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'surface-declared class cannot raise the classification',
        'a2ui-model/src/main/java/art/dart/a2ui/protocol/A2uiMessageProcessor.java',
        '''        A2uiActionClass fromSurface = surface.declaredActionClass(componentId);''',
        '''        A2uiActionClass fromSurface = null;''',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'Host B offers a refused control',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/CarUiRenderer.java',
        '''            if (decision == A2uiActionPolicy.Decision.REFUSE) {''',
        '''            if (false) {''',
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        'Host B ignores provenance for value limits',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        '''        if (!isAuthoritative(source)) {
            return A2uiValueLimits.failClosed();
        }''',
        '',
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        'component update commits before validating',
        'a2ui-model/src/main/java/art/dart/a2ui/protocol/A2uiMessageProcessor.java',
        '''        // Pass 2: commit. Every entry has already been checked, so this loop cannot throw partway.
        for (Map<String, Object> props : message.getComponents()) {
            surface.putComponent((String) props.get("id"), props);
        }''',
        '',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'surface getters are only shallowly immutable',
        'a2ui-model/src/main/java/art/dart/a2ui/protocol/A2uiSurface.java',
        '''            out.put(e.getKey(), (Map<String, Object>) deepImmutable(e.getValue()));''',
        '''            out.put(e.getKey(), e.getValue());''',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'ingest copies only the outer map',
        'a2ui-model/src/main/java/art/dart/a2ui/protocol/A2uiSurface.java',
        '''        Map<String, Object> copy = (Map<String, Object>) deepCopy(props);''',
        '''        Map<String, Object> copy = new LinkedHashMap<>(props);''',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'complyString exceeds the limit it enforces',
        'car-ui-lib/car-ui-lib/src/main/java/com/android/car/ui/utils/CarUxRestrictionsUtil.java',
        '        int keep = max - ellipsis.length();',
        '        int keep = max;',
        ':car-ui-lib:testDebugUnitTest',
        'car-ui-lib',
    ),
    (
        'every reading is trusted, override or not',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''        return isAuthoritative(source);''',
        '        return true;',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'voice bypasses the action policy',
        'a2ui-model/src/main/java/art/dart/a2ui/protocol/A2uiMessageProcessor.java',
        '''        A2uiActionPolicy.Decision decision = dispatchAction(surfaceId,
                String.valueOf(target.getId()), target.getAction(), null, target.getActionClass(),
                reader);''',
        '''        if (mActionSink != null) {
            mActionSink.onAction(surfaceId, String.valueOf(target.getId()),
                    target.getAction(), null);
        }
        A2uiActionPolicy.Decision decision = A2uiActionPolicy.Decision.ALLOW;''',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'an ambiguous utterance picks the first match',
        'a2ui-model/src/main/java/art/dart/a2ui/protocol/A2uiMessageProcessor.java',
        '''        if (matches.size() > 1) {
            return new VoiceDispatchResult(VoiceOutcome.AMBIGUOUS, null);
        }''',
        '',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'voice searches retained state instead of what was rendered',
        'a2ui-model/src/main/java/art/dart/a2ui/protocol/A2uiMessageProcessor.java',
        '''        List<A2uiComponent> matches = new ArrayList<>();
        collectSpeakable(renderedRoot, needle, matches);''',
        '''        List<A2uiComponent> matches = new ArrayList<>();
        for (String id : surface.getComponents().keySet()) {
            collectSpeakable(surface.project(id), needle, matches);
        }''',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'handoff forwards the rendered tree instead of re-projecting',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiHandoff.java',
        '''        A2uiComponent atDestination = render(projected, destination);''',
        '''        A2uiComponent atDestination =
                atSource == null ? null : render(atSource, destination);''',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        # Button is glanceable, so pruning keeps a purchase control; only this decision stops it.
        'handoff decides destination actions against the SOURCE display',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiHandoff.java',
        '''                decide(atDestination, destination));''',
        '''                decide(atDestination, source));''',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        # Right restrictions, wrong tree: decisions for components the destination never renders.
        'handoff decides the SOURCE tree at the destination',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiHandoff.java',
        '''                decide(atDestination, destination));''',
        '''                decide(atSource, destination));''',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        # The control the driver touches must be authorized against its own display.
        'invocation is authorized by the processor display, not the control display',
        'a2ui-model/src/main/java/art/dart/a2ui/protocol/A2uiDisplayBinder.java',
        '''        mOutcome.onDecided(component, mProcessor.dispatchAction(
                mSurfaceId, String.valueOf(component.getId()), component.getAction(), null,
                component.getActionClass(), mReader));''',
        '''        mOutcome.onDecided(component, mProcessor.dispatchAction(
                mSurfaceId, String.valueOf(component.getId()), component.getAction(), null,
                component.getActionClass()));''',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        # A reading captured at construction turns every later re-read into a replay.
        'the destination reading is captured once instead of re-read',
        'a2ui-model/src/main/java/art/dart/a2ui/protocol/A2uiDisplayBinder.java',
        '''        mReader = reader;''',
        '''        final int capturedActive = reader.activeRestrictions();
        final boolean capturedTrustworthy = reader.isTrustworthy();
        mReader = new A2uiMessageProcessor.RestrictionReader() {
            @Override
            public int activeRestrictions() {
                return capturedActive;
            }

            @Override
            public boolean isTrustworthy() {
                return capturedTrustworthy;
            }
        };''',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        # Re-read across time, but against the wrong display.
        'a handed-off confirmation falls back to the processor display',
        'a2ui-model/src/main/java/art/dart/a2ui/protocol/A2uiDisplayBinder.java',
        '''        return mProcessor.confirmAction(mSurfaceId, String.valueOf(component.getId()),
                component.getAction(), null, component.getActionClass(), mReader);''',
        '''        return mProcessor.confirmAction(mSurfaceId, String.valueOf(component.getId()),
                component.getAction(), null, component.getActionClass());''',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        # Every enforcement mutation lived in :a2ui-model; this defect lives in the host.
        "Host B renders the destination with the processor's own reader",
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        '''        return new Rendered(pruned, CarUiRenderer.render(pruned, new StaleGuard(
                new A2uiDisplayBinder(processor, surfaceId, scope.mReader, outcome),''',
        '''        return new Rendered(pruned, CarUiRenderer.render(pruned, new StaleGuard(
                new A2uiDisplayBinder(processor, surfaceId,
                        new A2uiMessageProcessor.RestrictionReader() {
                            @Override
                            public int activeRestrictions() {
                                return A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE;
                            }

                            @Override
                            public boolean isTrustworthy() {
                                return true;
                            }
                        }, outcome),''',
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        # Right reader, wrong prune: the source's set would put a Video on the driver's screen.
        'Host B prunes the destination with an unrestricted set',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        '''        A2uiComponent pruned = projected == null ? null
                : A2uiPruner.prune(projected, scope.mAllowed);
        if (pruned != null) {
            pruned = A2uiPruner.applyLimits(pruned, scope.mLimits);
        }''',
        '''        A2uiComponent pruned = projected == null ? null
                : A2uiPruner.prune(projected, A2uiRestrictionPolicy.allowedComponents(
                        A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
        if (pruned != null) {
            pruned = A2uiPruner.applyLimits(pruned, A2uiValueLimits.unrestricted());
        }''',
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        # An unwatched destination stays frozen at transfer time. Reachable only under Robolectric.
        'the destination is never watched for restriction changes',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        '''        mHandoffUxrListener = ignored -> runOnMain(() -> refreshHandoff(uiContext));
        destination.register(mHandoffUxrListener);''',
        '''        mHandoffUxrListener = ignored -> runOnMain(() -> refreshHandoff(uiContext));''',
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        # A released handoff that keeps its listener re-renders a display it no longer shows.
        'a released handoff keeps its destination listener',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        '''    private void releaseHandoffState() {
        unregisterHandoffListener();''',
        '''    private void releaseHandoffState() {''',
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        # Media3 browsable/playable are Boolean; reading unset as true invents a capability.
        'Media3 unset browsable/playable read as true',
        'car-media-common/src/com/android/car/media/common/Media3Adapters.java',
        '''                .setBrowsable(Boolean.TRUE.equals(metadata.isBrowsable))
                .setPlayable(Boolean.TRUE.equals(metadata.isPlayable))''',
        '''                .setBrowsable(!Boolean.FALSE.equals(metadata.isBrowsable))
                .setPlayable(!Boolean.FALSE.equals(metadata.isPlayable))''',
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # The two fields the legacy constructor reads separately from the description.
        'Media3 conversion drops album and artist',
        'car-media-common/src/com/android/car/media/common/Media3Adapters.java',
        '''                .setAlbumTitle(toStringOrNull(metadata.albumTitle))
                .setArtist(toStringOrNull(metadata.artist));''',
        '''                .setAlbumTitle(null)
                .setArtist(null);''',
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # displayTitle is what the app chose to show; title may be a canonical track name.
        'Media3 conversion prefers title over displayTitle',
        'car-media-common/src/com/android/car/media/common/Media3Adapters.java',
        '''        CharSequence title = metadata.displayTitle != null ? metadata.displayTitle : metadata.title;''',
        '''        CharSequence title = metadata.title != null ? metadata.title : metadata.displayTitle;''',
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # The only release test went through onStop, which releases explicitly.
        'a system-dismissed presentation releases nothing',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        """            final Presentation shown = presentation;
            presentation.setOnDismissListener(dialog -> {
                if (shown != mHandoffPresentation) {
                    Log.i(TAG, "ignoring dismissal of a superseded handoff presentation");
                    return;
                }
                Log.i(TAG, "handoff presentation dismissed; releasing the destination listener");
                releaseHandoffState();
            });""",
        "",
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        # The one place playback and browse meet; a stray controller controls the wrong session.
        'BrowsingState hands out a controller from nowhere',
        'car-media-common/src/com/android/car/media/common/source/MediaBrowserConnector.java',
        """        public MediaController getController() {
            return mBrowser;
        }""",
        """        public MediaController getController() {
            return null;
        }""",
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # dismiss() is async: a superseded callback lands after its replacement is installed.
        'a superseded presentation releases its replacement',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        """                if (shown != mHandoffPresentation) {
                    Log.i(TAG, "ignoring dismissal of a superseded handoff presentation");
                    return;
                }""",
        "",
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        # One saved slot for all displays discards the first display's genuine reading.
        'one saved reading for all displays instead of one each',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        """            sGenuineDestinationRestrictions.put(
                    destination.getDisplayId(), destination.getCurrentRestrictions());""",
        """            sGenuineDestinationRestrictions.clear();
            sGenuineDestinationRestrictions.put(
                    destination.getDisplayId(), destination.getCurrentRestrictions());""",
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        # Without the car-ui factory the first row bind throws ClassNotFoundException.
        'the handoff presentation inflater has no car-ui factory',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        """            if (presentationInflater.getFactory2() == null) {
                presentationInflater.setFactory2(new CarUiLayoutInflaterFactory());
            }""",
        "",
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        # The capture outlives the Activity because the reading it undoes is in a static cache.
        # Expressed as a clear in onDestroy: making the field non-static does not compile.
        'the genuine capture is dropped with the Activity',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        """    protected void onDestroy() {
        super.onDestroy();""",
        """    protected void onDestroy() {
        super.onDestroy();
        sGenuineDestinationRestrictions.clear();""",
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        # from() must read through on every call; a snapshot is indistinguishable in production.
        'PlaybackTiming snapshots instead of reading through',
        'car-media-common/src/com/android/car/media/common/playback/PlaybackTiming.java',
        """            @Override
            public boolean keepsTicking() {
                return state.getState() != PlaybackStateCompat.STATE_PAUSED
                        && state.getState() != PlaybackStateCompat.STATE_STOPPED
                        && state.getPlaybackSpeed() != 0;
            }""",
        """            private final int mFrozenState = state.getState();
            private final float mFrozenSpeed = state.getPlaybackSpeed();

            @Override
            public boolean keepsTicking() {
                return mFrozenState != PlaybackStateCompat.STATE_PAUSED
                        && mFrozenState != PlaybackStateCompat.STATE_STOPPED
                        && mFrozenSpeed != 0;
            }""",
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # Copied wire values are only safe while drift fails a test.
        'a copied media error code drifts from the platform',
        'car-media-common/src/com/android/car/media/common/MediaErrorCodes.java',
        '    public static final int ERROR_CODE_SKIP_LIMIT_REACHED = 9;',
        '    public static final int ERROR_CODE_SKIP_LIMIT_REACHED = 99;',
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # Anti-vacuity: a constant added and never pinned must fail.
        'an unpinned media error code goes unnoticed',
        'car-media-common/src/com/android/car/media/common/MediaErrorCodes.java',
        '    public static final int STATE_ERROR = 7;',
        """    public static final int STATE_ERROR = 7;
    public static final int ERROR_CODE_ADDED_AND_FORGOTTEN = 42;""",
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # Media3 splits play state across getPlaybackState() and getPlayWhenReady().
        'Media3 READY read as playing regardless of playWhenReady',
        'car-media-common/src/com/android/car/media/common/playback/Media3PlaybackState.java',
        """                return player.getPlayWhenReady()
                        ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;""",
        '                return PlaybackStateCompat.STATE_PLAYING;',
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # A command bit the player rejects offers a control that does nothing.
        'legacy actions claim commands the player does not have',
        'car-media-common/src/com/android/car/media/common/playback/Media3PlaybackState.java',
        '        long actions = 0L;',
        '        long actions = PlaybackStateCompat.ACTION_SKIP_TO_NEXT;',
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # A future outlives its connection: a late answer must not fill the current source's cache.
        # Pinned by the two *ArrivingAfterTheSourceChanged tests.
        'repository accepts a browse answer from a superseded connection',
        'car-media-common/src/com/android/car/media/common/browse/MediaItemsRepository.java',
        """    private boolean isStale(@NonNull BrowsingState state) {
        return mBrowsingState != state;""",
        """    private boolean isStale(@NonNull BrowsingState state) {
        return false;""",
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # Supersession within one connection: only the query comparison separates them.
        'a superseded search overwrites the newer query it was replaced by',
        'car-media-common/src/com/android/car/media/common/browse/MediaItemsRepository.java',
        """        addCallback(browser.getSearchResult(query, PAGE_INDEX, PAGE_SIZE, null), result -> {
            if (isStale(state) || !Objects.equals(mSearchQuery, query)) return;""",
        """        addCallback(browser.getSearchResult(query, PAGE_INDEX, PAGE_SIZE, null), result -> {
            if (isStale(state)) return;""",
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # null is the legacy onError shape; an empty list means no children.
        'a subscribe failure is reported as an empty child list rather than an error',
        'car-media-common/src/com/android/car/media/common/browse/MediaItemsRepository.java',
        """                } else {
                    // The legacy SubscriptionCallback#onError equivalent.
                    onBrowseData(nodeId, null);
                }""",
        """                } else {
                    onBrowseData(nodeId, Collections.emptyList());
                }""",
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # Fetching straight from a push skips both membership checks.
        'a push for a node nobody is showing is fetched anyway',
        'car-media-common/src/com/android/car/media/common/browse/MediaItemsRepository.java',
        """                    if (!getCache().mChildrenByNodeId.containsKey(parentId)) {
                        // Not a node this repository is showing; nothing to update.
                        return;
                    }
                    requestChildren(state, parentId);""",
        """                    state.mBrowser.getChildren(parentId, PAGE_INDEX, PAGE_SIZE, null);""",
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # The increment is the only thing retiring an in-flight connection on connectTo(null).
        'connectTo(null) leaves an in-flight connection valid',
        'car-media-common/src/com/android/car/media/common/source/MediaBrowserConnector.java',
        """    private void retireOutstandingCallbacks() {
        mBrowserConnectionCallbackCounter++;
        mCurrentCallback = null;""",
        """    private void retireOutstandingCallbacks() {
        mCurrentCallback = null;""",
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # A disconnected browser still holds a binder.
        'a disconnected browser is dropped without being released',
        'car-media-common/src/com/android/car/media/common/source/MediaBrowserConnector.java',
        """            if (mBrowser.isConnected()) {
                sendNewState(ConnectionStatus.DISCONNECTING);
            }
            mBrowser.release();""",
        """            if (mBrowser.isConnected()) {
                sendNewState(ConnectionStatus.DISCONNECTING);
                mBrowser.release();
            }""",
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # The parcel format has no version tag: one write out of order corrupts every later field.
        'the parcel writes its fields in a different order than it reads them',
        'car-media-common/src/com/android/car/media/common/MediaItemMetadata.java',
        """        dest.writeString(mId);
        writeCharSequence(dest, mTitle);
        writeCharSequence(dest, mSubtitle);""",
        """        writeCharSequence(dest, mTitle);
        dest.writeString(mId);
        writeCharSequence(dest, mSubtitle);""",
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # Rating is in the session command set, which the Player overload cannot see.
        'the rating action is dropped by taking the player-only answer',
        'car-media-common/src/com/android/car/media/common/playback/Media3PlaybackState.java',
        """        long actions = toLegacyActions((Player) controller);
        if (controller.getAvailableSessionCommands()
                .contains(SessionCommand.COMMAND_CODE_SESSION_SET_RATING)) {
            actions |= PlaybackStateCompat.ACTION_SET_RATING;
        }
        return actions;""",
        """        return toLegacyActions((Player) controller);""",
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # Same outcome either way; the test asserts listen() is never called.
        'voice capture opens the microphone before asking whether it may',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiVoiceCapture.java',
        """        if (!mGate.allowsListening()) {
            listener.onUnavailable(mGate.reason(), null);
            return;
        }""",
        """        if (false) {
            listener.onUnavailable(mGate.reason(), null);
            return;
        }""",
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        # Media3 has no queue-item id; the timeline index takes its role.
        'queue item index is not carried as the queue id',
        'car-media-common/src/com/android/car/media/common/Media3Adapters.java',
        """                .setQueueId(queueIndex)""",
        """                .setQueueId(null)""",
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # A future from an earlier connectTo can complete after a later one started.
        'connector staleness guard dropped in the Media3 port',
        'car-media-common/src/com/android/car/media/common/source/MediaBrowserConnector.java',
        """        private boolean isValidCall(String method) {
            if (mSequenceNumber != mBrowserConnectionCallbackCounter) {""",
        """        private boolean isValidCall(String method) {
            if (false) {""",
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # isConnected() == false is a failed handshake: publishing it leaks an unusable client.
        'a browser that says it is not connected is published as CONNECTED',
        'car-media-common/src/com/android/car/media/common/source/MediaBrowserConnector.java',
        """            if (browser.isConnected()) {
                mBrowser = browser;
                sendNewState(ConnectionStatus.CONNECTED);
            } else {
                browser.release();
                sendNewState(ConnectionStatus.REJECTED);
            }""",
        """            mBrowser = browser;
            sendNewState(ConnectionStatus.CONNECTED);""",
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # Media3 reports nothing playing as EMPTY, never null.
        'Media3 EMPTY metadata no longer collapsed to "nothing playing"',
        'car-media-common/src/com/android/car/media/common/playback/PlaybackViewModel.java',
        '            mMediaMetadata = EMPTY_MEDIA_METADATA.equals(mediaMetadata) '
        + '? null : mediaMetadata;',
        '            mMediaMetadata = mediaMetadata;',
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        # Media3 always has metadata, so this half of the test alone pins the filter.
        'untitled queue items are no longer filtered out',
        'car-media-common/src/com/android/car/media/common/playback/PlaybackViewModel.java',
        '                if (md.displayTitle == null && md.title == null) continue;',
        '                if (false) continue;',
        ':car-media-common:testDebugUnitTest',
        'car-media-common',
    ),
    (
        'protocol accepts an out-of-catalog component',
        'a2ui-model/src/main/java/art/dart/a2ui/protocol/A2uiMessageProcessor.java',
        '''            if (ComponentType.fromCatalogName((String) component) == null) {''',
        '            if (false) {',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'platform recogniser opens the microphone before checking the permission',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/PlatformTranscriptSource.java',
        '''        if (!mRecognition.isRecordAudioGranted()) {
            callback.onUnavailable(Unavailable.PERMISSION_DENIED, null);
            return;
        }
''',
        '',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'platform recogniser reports the permission ahead of the missing service',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/PlatformTranscriptSource.java',
        '''        if (!mRecognition.isAvailable()) {
            callback.onUnavailable(Unavailable.NO_RECOGNITION_SERVICE, null);
            return;
        }
        if (!mRecognition.isRecordAudioGranted()) {
            callback.onUnavailable(Unavailable.PERMISSION_DENIED, null);
            return;
        }''',
        '''        if (!mRecognition.isRecordAudioGranted()) {
            callback.onUnavailable(Unavailable.PERMISSION_DENIED, null);
            return;
        }
        if (!mRecognition.isAvailable()) {
            callback.onUnavailable(Unavailable.NO_RECOGNITION_SERVICE, null);
            return;
        }''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'platform recogniser never asks for on-device recognition',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/PlatformTranscriptSource.java',
        '            mRecognition.start(mRecognition.isOnDeviceAvailable(), events);',
        '            mRecognition.start(false, events);',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'platform recogniser passes an absent result off as an empty phrase',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/PlatformTranscriptSource.java',
        '''                if (alternatives == null || alternatives.isEmpty() || alternatives.get(0) == null) {
                    owed.onUnavailable(Unavailable.FAILED, "recogniser returned no result");
                    return;
                }
                owed.onTranscript(alternatives.get(0));''',
        '''                owed.onTranscript(alternatives == null || alternatives.isEmpty()
                        || alternatives.get(0) == null ? "" : alternatives.get(0));''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'platform recogniser loses its once-only guard',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/PlatformTranscriptSource.java',
        '''        Callback owed = mPending;
        mPending = null;
        return owed;''',
        '        return mPending;',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'platform recogniser leaks a finished recogniser on cancel',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/PlatformTranscriptSource.java',
        '''    public void cancel() {
        mPending = null;
        mActive = null;
        mRecognition.cancel();
    }''',
        '''    public void cancel() {
        if (mPending == null) {
            return;
        }
        mPending = null;
        mActive = null;
        mRecognition.cancel();
    }''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'voice gate opens the microphone on a reading that never arrived',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''        return A2uiRestrictionPolicy.allowsListening(
                effectiveRestrictions(override, live), isReadingTrustworthy(override, source));''',
        '''        return A2uiRestrictionPolicy.allowsListening(
                effectiveRestrictions(override, live), true);''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'voice gate ignores the vehicle no-transcription bit',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''        return A2uiRestrictionPolicy.allowsListening(
                effectiveRestrictions(override, live), isReadingTrustworthy(override, source));''',
        '        return isReadingTrustworthy(override, source);',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'voice transcription bit declared and read by nothing, again',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiRestrictionPolicy.java',
        '        return !isSet(activeRestrictions, UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION);',
        '        return true;',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'voice transcription tests the mask for equality instead of presence',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiRestrictionPolicy.java',
        '''    public static boolean allowsVoiceTranscription(int activeRestrictions) {
        return !isSet(activeRestrictions, UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION);''',
        '''    public static boolean allowsVoiceTranscription(int activeRestrictions) {
        return activeRestrictions != UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION;''',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
    (
        'microphone dialog is raised again after the driver declines',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '        return reason == TranscriptSource.Unavailable.PERMISSION_DENIED && !alreadyAsked;',
        '        return reason == TranscriptSource.Unavailable.PERMISSION_DENIED;',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'every voice failure is treated as a missing permission',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '        return reason == TranscriptSource.Unavailable.PERMISSION_DENIED && !alreadyAsked;',
        '        return !alreadyAsked;',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'showcase screen captures the tree at listen time instead of re-reading it',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''        mVoice.listen(surfaceId,
                () -> {
                    Integer now = mDemoOverride;
                    return visibleRoot(surfaceId, now, allowedNow(now), contentLimit());
                },''',
        '''        final Integer captured = mDemoOverride;
        mVoice.listen(surfaceId,
                () -> visibleRoot(surfaceId, captured, allowedNow(captured), contentLimit()),''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'showcase screen offers the Speak action even when the vehicle forbids transcription',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''        if (!mVoiceGate.allowsListening()) {
            return null;
        }
        boolean listening = mVoice.isListening();''',
        '        boolean listening = mVoice.isListening();',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'binding dispatches partial results as if they were final',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/SpeechRecognizerBinding.java',
        '''        @Override
        public void onPartialResults(Bundle partialResults) {}''',
        '''        @Override
        public void onPartialResults(Bundle partialResults) {
            onResults(partialResults);
        }''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'binding leaves the previous recogniser listening',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/SpeechRecognizerBinding.java',
        '''        // Release first. Two live recognisers would open the microphone twice for one intent, and
        // the platform reports that as ERROR_RECOGNIZER_BUSY on whichever loses.
        cancel();
''',
        '''''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'binding loses the transcript carried in the result bundle',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/SpeechRecognizerBinding.java',
        '''            mEvents.onPhrase(results == null
                    ? null
                    : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));''',
        '            mEvents.onPhrase(null);',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'showcase screen ignores the corpus case that was selected',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''        mSurfaceId = surfaceId;
        Log.i(TAG, "showing case " + surfaceId);''',
        '        Log.i(TAG, "showing case " + surfaceId);',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'showcase screen accepts a case id that names no surface',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '        if (surfaceId == null || !projects(surfaceId)) {',
        '        if (surfaceId == null) {',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'voice dispatches into the showcase trace whatever case is selected',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '        mVoice.listen(surfaceId,',
        '        mVoice.listen(DemoTrace.SURFACE_ID,',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'a replaced recogniser settles the listen that replaced it',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/PlatformTranscriptSource.java',
        '''        if (generation != mActive) {
            return null;
        }
        mActive = null;''',
        '        mActive = null;',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'a recogniser that will not start leaves the source wedged',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/PlatformTranscriptSource.java',
        '            Callback owed = settle(events);',
        '            Callback owed = null;',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'a case switch leaves the microphone open on the old surface',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''        mVoice.cancel();
        mSurfaceId = surfaceId;''',
        '        mSurfaceId = surfaceId;',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'the surface is re-read while a phrase is still in flight',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '                    return visibleRoot(surfaceId, now, allowedNow(now), contentLimit());',
        '                    return visibleRoot(mSurfaceId, now, allowedNow(now), contentLimit());',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'a confirmation executes against whatever case is selected when it is answered',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''                return mProcessor.confirmAction(surfaceId,
                        String.valueOf(component.getId()), component.getAction(), null,
                        component.getActionClass());''',
        '''                return mProcessor.confirmAction(mSurfaceId,
                        String.valueOf(component.getId()), component.getAction(), null,
                        component.getActionClass());''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'a press dispatches into the case selected now, not the one rendered',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''            A2uiActionPolicy.Decision decision = mProcessor.dispatchAction(
                    surfaceId, String.valueOf(component.getId()),''',
        '''            A2uiActionPolicy.Decision decision = mProcessor.dispatchAction(
                    mSurfaceId, String.valueOf(component.getId()),''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'voice can reach a control the host truncated off the screen',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '        return CarAppRenderer.visibleRoot(renderedRoot(surfaceId, override, allowed), contentLimit);',
        '        return renderedRoot(surfaceId, override, allowed);',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'the screen never releases its debug receivers',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''                for (BroadcastReceiver receiver : mDebugReceivers) {
                    carContext.unregisterReceiver(receiver);
                }
                mDebugReceivers.clear();''',
        '''''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'the microphone answer is acted on after the screen stops',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''                    if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                        Log.i(TAG, "microphone answer arrived after the screen stopped; ignoring");
                        return;
                    }''',
        '''''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'the action does not say it is listening',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '                .setTitle(listening ? "Listening…" : "Speak")',
        '                .setTitle("Speak")',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'voice outcomes are reported to the driver as enum names',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        r'        String quoted = "\u201c" + spoken + "\u201d";',
        '        String quoted = outcome.name();',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'the visible tree keeps controls nested inside a drawn row',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiVisibleTree.java',
        '''            visible.addChild(
                    new A2uiComponent.Builder(row.getType()).copyPropertiesFrom(row).build());''',
        '            visible.addChild(row);',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'the visible tree ignores the host list limit',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiVisibleTree.java',
        '''                .setActionClass(null);
        int shown = Math.min(rows.size(), contentLimit);''',
        '''                .setActionClass(null);
        int shown = rows.size();''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'a stale press listens on the case selected now, not the one rendered',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''                .setOnClickListener(listening
                        ? this::stopListening
                        : () -> startListening(surfaceId))''',
        '                .setOnClickListener(listening ? this::stopListening : this::startListening)',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'a crash in voice dispatch is swallowed',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/PlatformTranscriptSource.java',
        '''                throw e;
            }
            owed.onUnavailable(Unavailable.FAILED, "recogniser did not start: " + e);''',
        '''                return;
            }
            owed.onUnavailable(Unavailable.FAILED, "recogniser did not start: " + e);''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'a refusal reaches the driver as an enum name',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''        switch (reason) {
            case NO_RECOGNITION_SERVICE:''',
        '''        if (true) {
            return reason.name();
        }
        switch (reason) {
            case NO_RECOGNITION_SERVICE:''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'a crash in the listener chain is absorbed by the capture',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiVoiceCapture.java',
        '                throw e;',
        '                return;',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'the microphone retry listens on the case selected now',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''                        startListening(surfaceId);
                        return;''',
        '''                        startListening();
                        return;''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'a refusal blames driving for a reading that was never delivered',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '                return "Voice input is not available right now";',
        '                return "Voice input is not available while driving";',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'spoken confirmation redispatches the phrase instead of confirming the captured component',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        '''                        A2uiActionPolicy.Decision decision = capturedBinder.confirm(matched);''',
        '''                        A2uiMessageProcessor.VoiceOutcome redispatched =
                                mProcessor.dispatchByVoice(mSurfaceId, spoken, mRenderedRoot);
                        A2uiActionPolicy.Decision decision =
                                redispatched == A2uiMessageProcessor.VoiceOutcome.DISPATCHED
                                        ? A2uiActionPolicy.Decision.ALLOW
                                        : A2uiActionPolicy.Decision.CONFIRM;''',
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        'one utterance reaches the dispatch twice',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiVoiceCapture.java',
        '''                // hearing about it would make that quieter, not less true.
                if (!claim(attempt)) {
                    return;
                }''',
        '                // hearing about it would make that quieter, not less true.',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'a finished listen clears a newer listen that replaced it',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiVoiceCapture.java',
        '        mOutstanding.compareAndSet(attempt, null);',
        '        mOutstanding.set(null);',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'two presses at once both open a microphone',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiVoiceCapture.java',
        '        if (!mOutstanding.compareAndSet(null, attempt)) {',
        '''        if (mOutstanding.get() != null) {
            mOutstanding.set(attempt);
        } else if (false) {''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'cancel latches the outcome and silences a late phrase',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiVoiceCapture.java',
        '''        mOutstanding.set(null);
        mSource.cancel();''',
        '''        AtomicBoolean outstanding = mOutstanding.getAndSet(null);
        if (outstanding != null) {
            outstanding.set(true);
        }
        mSource.cancel();''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'the visible tree answers at a limit the renderer refuses',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiVisibleTree.java',
        '''        if (contentLimit < MIN_USABLE_CONTENT_LIMIT) {
            // Fail closed.''',
        '''        if (false) {
            // Fail closed.''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        'an undrawn container root keeps its action',
        'a2ui-model/src/main/java/art/dart/a2ui/policy/A2uiVisibleTree.java',
        '''                .setAction(null)
''',
        '''''',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        # processAll rolls back half-written recordings; only a rootless one that applies gets here.
        'a registered surface that does not project is accepted on an existence check',
        'a2ui-host-carapp/src/main/java/art/dart/a2ui/carapp/A2uiShowcaseScreen.java',
        '''        A2uiSurface surface = mProcessor.getSurface(surfaceId);
        if (surface == null) {
            return false;
        }
        try {
            return surface.project(DemoTrace.ROOT_ID) != null;
        } catch (RuntimeException e) {
            Log.i(TAG, "case " + surfaceId + " does not project from its root: " + e);
            return false;
        }''',
        '        return mProcessor.getSurface(surfaceId) != null;',
        ':a2ui-host-carapp:testDebugUnitTest',
        'a2ui-host-carapp',
    ),
    (
        # Live-agent surfaces must take the corpus path, prune included.
        'Host B renders agent surfaces unpruned',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        '        A2uiComponent pruned = projected == null ? null : A2uiPruner.prune(projected, allowed);',
        '        A2uiComponent pruned = projected == null ? null\n'
        '                : mShowingAgent ? projected : A2uiPruner.prune(projected, allowed);',
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        # Debug-only; INTERNET being absent in release is defence in depth, not the guard.
        'the agent lane exists on a non-debuggable build',
        'a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java',
        '''        List<com.android.car.ui.toolbar.MenuItem> menuItems = new ArrayList<>();''',
        '''        mAgent = AgentLane.create(getMainExecutor(), mAgentListener);
        List<com.android.car.ui.toolbar.MenuItem> menuItems = new ArrayList<>();''',
        ':a2ui-host-carui:testDebugUnitTest',
        'a2ui-host-carui',
    ),
    (
        # A pruner ignoring the mask scores every vendor's Video as glanceable.
        'the provider-run verifier prunes every mask as baseline',
        'a2ui-model/src/test/java/art/dart/a2ui/harness/GatewayOutputVerification.java',
        '        Set<ComponentType> allowed = trustworthy ? A2uiRestrictionPolicy.allowedComponents(mask)',
        '        Set<ComponentType> allowed = trustworthy ? A2uiRestrictionPolicy.allowedComponents(\n'
        '                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE)',
        ':a2ui-model:testDebugUnitTest',
        'a2ui-model',
    ),
]


def failing_tests_in(task: str, module: str):
    """The names of a module's failing tests, so a red positive control says which one."""
    names = []
    pattern = str(results_dir(task, module) / 'TEST-*.xml')
    for path in glob.glob(pattern):
        root = ET.parse(path).getroot()
        for case in root.iter('testcase'):
            for child in case:
                if child.tag in ('failure', 'error'):
                    first = (child.text or '').strip().splitlines()
                    names.append(f"{case.get('classname')}.{case.get('name')}"
                                 + (f" — {first[0]}" if first else ''))
    return names


def results_dir(task: str, module: str) -> Path:
    """Where this task writes results; reading another suite's XML makes the control vacuous."""
    if task.endswith(':connectedDebugAndroidTest'):
        return GP / 'build' / module / 'outputs' / 'androidTest-results' / 'connected' / 'debug'
    return GP / 'build' / module / 'test-results' / 'testDebugUnitTest'


def counts_in(task: str, module: str):
    """(executed, failed) for a module's suite; (-1, -1) if it produced no results."""
    executed = failed = 0
    pattern = str(results_dir(task, module) / 'TEST-*.xml')
    files = glob.glob(pattern)
    if not files:
        return -1, -1
    for path in files:
        root = ET.parse(path).getroot()
        executed += int(root.get('tests', 0)) - int(root.get('skipped', 0))
        failed += int(root.get('failures', 0)) + int(root.get('errors', 0))
    return executed, failed


LAST_SUITE_STDERR = ''


def run_suite(task: str, module: str):
    """Runs one suite. Returns (gradle_rc, executed, failed); executed<0 means no results."""
    global LAST_SUITE_STDERR
    # Clear old results first, or a build that fails before the tests leaves the previous
    # (possibly mutated) run's XML to be read as this run's.
    shutil.rmtree(results_dir(task, module), ignore_errors=True)
    proc = subprocess.run(['./gradlew', task, '--rerun-tasks', '-q'],
                          cwd=GP, capture_output=True, text=True)
    # Kept so a failing positive control can say why.
    lines = [ln for ln in (proc.stderr + '\n' + proc.stdout).splitlines()
             if 'warning:' not in ln and ln.strip()]
    LAST_SUITE_STDERR = '\n'.join(lines[-20:])
    executed, failed = counts_in(task, module)
    return proc.returncode, executed, failed


# How many tests each suite runs unmutated, filled in by baseline_is_green.
BASELINE_EXECUTED = {}

# Which test is expected to notice a given mutation, by substring. Without it any red test, even
# one red for an unrelated reason, credits the mutation. Unnamed entries print their detectors.
EXPECTED_DETECTOR = {
    # The voice gate, both hosts.
    'host B opens the mic when transcription is forbidden': 'A2uiVoiceWiringTest',
    'host B accepts a transcript the vehicle forbids': 'A2uiVoiceWiringTest',
    'host B leaves the microphone open across the transition': 'A2uiVoiceWiringTest',
    'host B offers the Speak control while transcription is forbidden': 'A2uiVoiceWiringTest',
    'voice gate ignores provenance': 'A2uiVoiceGateTest',
    'voice gate refuses unconditionally': 'A2uiVoiceWiringTest',
    'host A accepts a transcript the vehicle forbids': 'A2uiVoiceCaptureTest',
    'host A leaves the microphone open across the transition': 'A2uiShowcaseScreenVoiceTest',
    'host A restriction listener stops cancelling': 'A2uiShowcaseScreenVoiceTest',
    # Forged readings.
    'release build accepts an injected DELIVERED reading': 'CarUxRestrictionsUtilTest',
    # The distraction fan-out.
    'distraction fan-out iterates the observer set unlocked': 'CarUxRestrictionsUtilTest',
    'plugin adapter accumulates observers instead of releasing them': 'AdapterWrapperTest',
    'subaction dialog description guard tests the constant': 'UriUtilsTest',
    # Modules whose first mutation came with the distribution check.
    'content limiter stop() registers instead of unregistering': 'UxrContentLimiterImplTest',
    'conversation accepts a bundle with no id': 'ConversationBundleTest',

    # Each detected by exactly one test in a full sweep, so the pairing was observed, not chosen.
    'Host B prunes the destination with an unrestricted set': 'A2uiHostWiringTest',
    'Media3 EMPTY metadata no longer collapsed to "nothing playing"': 'PlaybackViewModelTest',
    'Media3 unset browsable/playable read as true': 'Media3AdaptersTest',
    'PlaybackTiming snapshots instead of reading through': 'ProgressLiveDataTest',
    'a browser that says it is not connected is published as CONNECTED': 'MediaBrowserConnectorTest',
    'a case switch leaves the microphone open on the old surface': 'A2uiShowcaseScreenCaseTest',
    'a confirmation executes against whatever case is selected when it is answered': 'A2uiShowcaseScreenCaseTest',
    'a copied media error code drifts from the platform': 'MediaErrorCodesTest',
    'a crash in voice dispatch is swallowed': 'PlatformTranscriptSourceTest',
    'a disconnected browser is dropped without being released': 'MediaBrowserConnectorTest',
    'a finished listen clears a newer listen that replaced it': 'A2uiVoiceCaptureTest',
    'a handed-off confirmation falls back to the processor display': 'A2uiDisplayBinderTest',
    'a press dispatches into the case selected now, not the one rendered': 'A2uiShowcaseScreenCaseTest',
    'a recogniser that will not start leaves the source wedged': 'PlatformTranscriptSourceTest',
    'a refusal blames driving for a reading that was never delivered': 'A2uiShowcaseScreenVoiceTest',
    'a refusal reaches the driver as an enum name': 'A2uiShowcaseScreenVoiceTest',
    'a stale press listens on the case selected now, not the one rendered': 'A2uiShowcaseScreenVoiceTest',
    'a subscribe failure is reported as an empty child list rather than an error': 'MediaItemsRepositoryTest',
    'a superseded presentation releases its replacement': 'A2uiHandoffLifecycleTest',
    'a superseded search overwrites the newer query it was replaced by': 'MediaItemsRepositoryTest',
    'a system-dismissed presentation releases nothing': 'A2uiHandoffLifecycleTest',
    'an ambiguous utterance picks the first match': 'A2uiMessageProcessorTest',
    'an unpinned media error code goes unnoticed': 'MediaErrorCodesTest',
    'binding dispatches partial results as if they were final': 'SpeechRecognizerBindingTest',
    'binding leaves the previous recogniser listening': 'SpeechRecognizerBindingTest',
    'connectTo(null) leaves an in-flight connection valid': 'MediaBrowserConnectorTest',
    'fail-closed default becomes BASELINE': 'CarUxRestrictionsUtilTest',
    'handoff forwards the rendered tree instead of re-projecting': 'A2uiHandoffTest',
    'ingest copies only the outer map': 'A2uiMessageProcessorTest',
    'invocation is authorized by the processor display, not the control display': 'A2uiDisplayBinderTest',
    'one saved reading for all displays instead of one each': 'A2uiHandoffLifecycleTest',
    'platform recogniser leaks a finished recogniser on cancel': 'PlatformTranscriptSourceTest',
    'platform recogniser never asks for on-device recognition': 'PlatformTranscriptSourceTest',
    'platform recogniser opens the microphone before checking the permission': 'PlatformTranscriptSourceTest',
    'platform recogniser passes an absent result off as an empty phrase': 'PlatformTranscriptSourceTest',
    'platform recogniser reports the permission ahead of the missing service': 'PlatformTranscriptSourceTest',
    'repository accepts a browse answer from a superseded connection': 'MediaItemsRepositoryTest',
    'showcase screen captures the tree at listen time instead of re-reading it': 'A2uiShowcaseScreenVoiceTest',
    'showcase screen offers the Speak action even when the vehicle forbids transcription': 'A2uiShowcaseScreenVoiceTest',
    'surface-declared class cannot raise the classification': 'A2uiMessageProcessorTest',
    'the action does not say it is listening': 'A2uiShowcaseScreenVoiceTest',
    'the microphone answer is acted on after the screen stops': 'A2uiShowcaseScreenVoiceTest',
    'the microphone retry listens on the case selected now': 'A2uiShowcaseScreenVoiceTest',
    'the screen never releases its debug receivers': 'A2uiShowcaseScreenVoiceTest',
    'untitled queue items are no longer filtered out': 'PlaybackViewModelTest',
    'voice outcomes are reported to the driver as enum names': 'A2uiShowcaseScreenVoiceTest',
    'voice transcription tests the mask for equality instead of presence': 'A2uiRestrictionPolicyTest',
    'a registered surface that does not project is accepted on an existence check':
        'aRegisteredRootlessSurfaceIsRefused',
}


def baseline_is_green(task: str, module: str) -> str:
    """The positive control: '' if the unmutated suite is green, else why not."""
    rc, executed, failed = run_suite(task, module)
    # Stashed for run(): a mutation that changes how many tests ran stopped the suite asking.
    BASELINE_EXECUTED[(task, module)] = executed
    # rc separates "no results" from "the build failed before the tests".
    if executed < 0:
        return f'produced no results at all (gradle rc={rc})'
    if executed == 0:
        return f'ran zero tests (gradle rc={rc})'
    # Build failure first: a red suite only means something if the build ran.
    if rc != 0:
        return f'gradle returned {rc} on an unmutated tree'
    if failed > 0:
        names = failing_tests_in(task, module)
        detail = '; '.join(names[:3]) if names else 'no test names in the results'
        return f'{failed} test(s) already failing before any mutation: {detail}'
    return ''


def run(name, rel, old, new, task, module) -> str:
    """Returns 'detected', 'MISSED', or an error string."""
    target = ROOT / rel
    if not target.is_file():
        return f'ERROR: {rel} not found'
    source = target.read_text()
    if old not in source:
        return 'ERROR: anchor not found (the code moved; update this mutation)'

    with tempfile.NamedTemporaryFile(delete=False) as pristine:
        pristine_path = Path(pristine.name)
    shutil.copy2(target, pristine_path)
    try:
        target.write_text(source.replace(old, new, 1))
        rc, executed, failed = run_suite(task, module)
        if executed < 0:
            # Did not compile or never ran: not detection.
            return 'ERROR: the mutated run produced no results (did the mutation compile?)'
        baseline = BASELINE_EXECUTED.get((task, module))
        if baseline is not None and executed != baseline:
            # A different executed count means tests vanished, and vanished tests noticed nothing.
            return (f'ERROR: the mutated run executed {executed} tests, the unmutated run '
                    f'{baseline}. The suite did not ask the same questions, so whatever went red '
                    'is not evidence about this defect.')
        if failed > 0:
            names = failing_tests_in(task, module)
            expected = EXPECTED_DETECTOR.get(name)
            if expected:
                if not any(expected in n for n in names):
                    shown = '; '.join(n.split(' — ')[0] for n in names[:3]) or 'none named'
                    return (f'ERROR: red tests do not include the expected detector '
                            f'{expected!r}; got {shown}. Something else went wrong, and crediting '
                            'this mutation would record a blind spot as covered.')
                return 'detected'
            shown = '; '.join(n.split(' — ')[0] for n in names[:3]) or 'no test names'
            # No expected detector: report which tests noticed.
            return f'detected ({shown})'
        if rc != 0:
            return (f'ERROR: no test failed but gradle returned {rc} — the build broke for some '
                    'other reason, so this mutation measured nothing')
        return 'MISSED'
    finally:
        # Restore content and bump mtime: copy2 keeps the old mtime, so Gradle's incremental
        # compiler would keep the mutated .class (--rerun-tasks does not defeat that).
        shutil.copy2(pristine_path, target)   # never `git checkout`
        os.utime(target, None)
        pristine_path.unlink(missing_ok=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--list', action='store_true', help='print the mutations and exit')
    ap.add_argument('--check-registry', action='store_true',
                    help='validate the registry and anchors, run no suites, and exit')
    args = ap.parse_args()

    if args.list:
        for name, rel, *_ in MUTATIONS:
            print(f'  {name}\n      {rel}')
        return 0

    if not (GP / 'gradlew').is_file():
        print('INDETERMINATE: no gradle wrapper; nothing was checked', file=sys.stderr)
        return 2

    # An empty registry makes every loop below vacuous and reports success.
    if len(MUTATIONS) < MINIMUM_MUTATIONS:
        print(f'INDETERMINATE: {len(MUTATIONS)} mutation(s) registered, expected at least '
              f'{MINIMUM_MUTATIONS}. A shrinking registry is indistinguishable from a passing run.',
              file=sys.stderr)
        return 2
    names = [name for name, *_ in MUTATIONS]
    duplicate_names = sorted({n for n in names if names.count(n) > 1})
    if duplicate_names:
        print('INDETERMINATE: duplicate mutation name(s) — the total is inflated and two entries '
              'cannot be told apart in the output:', file=sys.stderr)
        for n in duplicate_names:
            print(f'  {n}', file=sys.stderr)
        return 2
    edits = [(rel, old_src, new_src) for _n, rel, old_src, new_src, *_ in MUTATIONS]
    duplicate_edits = sorted({e for e in edits if edits.count(e) > 1})
    if duplicate_edits:
        print('INDETERMINATE: the same edit is registered more than once — it inflates the total '
              'while measuring one thing:', file=sys.stderr)
        for rel, old_src, _ in duplicate_edits:
            print(f'  {rel}: {old_src.strip().splitlines()[0][:70]}', file=sys.stderr)
        return 2
    print(f'  registry  {len(MUTATIONS)} mutations, names and edits distinct')

    # Each anchor must match exactly once: a missing one leaves a mutation unmeasured, and a
    # double match mutates whichever site comes first (str.replace(old, new, 1)).
    anchor_problems = []
    for name, rel, old, _new, _task, _module in MUTATIONS:
        target = ROOT / rel
        if not target.is_file():
            anchor_problems.append(f'{name}: {rel} not found')
            continue
        hits = target.read_text().count(old)
        if hits != 1:
            anchor_problems.append(
                f'{name}: anchor matches {hits} time(s) in {rel}, expected exactly 1')
    if anchor_problems:
        print('INDETERMINATE: mutation anchors are stale; nothing was measured.', file=sys.stderr)
        for problem in anchor_problems:
            print(f'  {problem}', file=sys.stderr)
        return 2
    print(f'  anchors   all {len(MUTATIONS)} resolve exactly once')

    # A typo'd key would silently never apply.
    stray = sorted(k for k in EXPECTED_DETECTOR if k not in {m[0] for m in MUTATIONS})
    if stray:
        print('INDETERMINATE: EXPECTED_DETECTOR names no such mutation:', file=sys.stderr)
        for k in stray:
            print(f'  {k!r}', file=sys.stderr)
        return 2
    print(f'  detectors {len(EXPECTED_DETECTOR)} named, all matching a registered mutation')

    # Distribution, not just count: every gated module needs a mutation or a recorded exemption.
    gated, exempt = {}, {}
    for line in (ROOT / 'scripts' / 'gate-modules.tsv').read_text().splitlines():
        if line.startswith('#') or not line.strip():
            continue
        parts = line.split('\t')
        if len(parts) >= 2:
            gated[parts[0].strip()] = parts[1].strip()
    exemptions_file = ROOT / 'docs' / 'mutation-exemptions.tsv'
    if exemptions_file.is_file():
        for line in exemptions_file.read_text().splitlines():
            if line.startswith('#') or not line.strip():
                continue
            parts = line.split('\t')
            if len(parts) >= 2 and parts[1].strip():
                exempt[parts[0].strip()] = parts[1].strip()
    covered = {task.rsplit(':', 1)[0] for _, _, _, _, task, _ in MUTATIONS}
    uncovered = sorted(p for p in gated if p not in covered and p not in exempt)
    stale_exempt = sorted(p for p in exempt if p in covered)
    if uncovered or stale_exempt:
        print('INDETERMINATE: mutation coverage is not distributed across the gated modules.',
              file=sys.stderr)
        for p_ in uncovered:
            print(f'  {p_}: gated, no mutation, no exemption. Add one, or record why not in '
                  'docs/mutation-exemptions.tsv.', file=sys.stderr)
        for p_ in stale_exempt:
            print(f'  {p_}: exempted in docs/mutation-exemptions.tsv but now HAS a mutation. '
                  'Remove the row -- an exemption outliving its condition is a silent hole.',
                  file=sys.stderr)
        return 2
    print(f'  spread    {len(covered & set(gated))} of {len(gated)} gated module(s) carry a '
          f'mutation, {len(exempt)} exempted with a reason')

    # Registry and anchors take a second, so CI runs this on every push.
    if args.check_registry:
        return 0

    # Positive controls first: detection against an already-red suite means nothing.
    suites = sorted({(task, module) for _, _, _, _, task, module in MUTATIONS})
    for task, module in suites:
        why = baseline_is_green(task, module)
        if why:
            print(f'INDETERMINATE: positive control failed for {module} — {why}.\n'
                  f'Nothing was measured. Fix the suite, then re-run.', file=sys.stderr)
            if LAST_SUITE_STDERR:
                print('  gradle said:', file=sys.stderr)
                for line in LAST_SUITE_STDERR.splitlines():
                    print(f'    {line}', file=sys.stderr)
            return 2
        print(f'  control   {module} is green before mutation')

    missed, errors, detected = [], [], []
    for name, rel, old, new, task, module in MUTATIONS:
        verdict = run(name, rel, old, new, task, module)
        print(f'  {verdict:9s} {name}')
        if verdict == 'MISSED':
            missed.append(name)
        elif verdict.startswith('ERROR'):
            errors.append(f'{name}: {verdict}')
        else:
            detected.append(name)

    if errors:
        print('\nINDETERMINATE — some mutations could not be applied:', file=sys.stderr)
        for e in errors:
            print(f'  {e}', file=sys.stderr)
        return 2
    if missed:
        print(f'\nFAIL: {len(missed)} mutation(s) NOT detected — the gate is blind there:',
              file=sys.stderr)
        for m in missed:
            print(f'  {m}', file=sys.stderr)
        return 1
    # Counted, not assumed.
    if len(detected) != len(MUTATIONS):
        print(f'\nINDETERMINATE: {len(detected)} detected but the registry holds '
              f'{len(MUTATIONS)}; some verdict was neither detected, MISSED nor ERROR.',
              file=sys.stderr)
        return 2
    annotated = sum(1 for n in detected if n in EXPECTED_DETECTOR)
    print(f'\nOK: {len(detected)} of {len(MUTATIONS)} mutations detected; the gate can still see.')
    print(f'    {annotated} were matched against a NAMED expected detector; the remaining '
          f'{len(detected) - annotated} report which tests noticed, for audit.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
