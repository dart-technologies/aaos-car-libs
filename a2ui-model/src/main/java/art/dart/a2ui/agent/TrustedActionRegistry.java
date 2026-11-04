/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.agent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import art.dart.a2ui.policy.A2uiActionClass;
import art.dart.a2ui.protocol.A2uiMessageProcessor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable, host-owned action authority.
 *
 * <p>Agent labels and visible text never create capabilities. An unknown id resolves to
 * {@link A2uiActionClass#DESTRUCTIVE} for policy and is refused by {@link #onAction} even when
 * parked. A declared class may raise the host classification, never lower it.
 */
public final class TrustedActionRegistry implements A2uiMessageProcessor.ActionClassResolver,
        A2uiMessageProcessor.ActionSink {
    public interface Handler {
        void onAction(@NonNull String surfaceId, @NonNull String componentId,
                @Nullable Object payload);
    }

    public interface RejectionListener {
        void onUnknownAction(@NonNull String actionId);
    }

    private static final class Entry {
        final A2uiActionClass actionClass;
        final Handler handler;

        Entry(A2uiActionClass actionClass, Handler handler) {
            this.actionClass = actionClass;
            this.handler = handler;
        }
    }

    private final Map<String, Entry> mEntries;
    @Nullable private final RejectionListener mRejectionListener;

    private TrustedActionRegistry(Map<String, Entry> entries,
            @Nullable RejectionListener rejectionListener) {
        mEntries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        mRejectionListener = rejectionListener;
    }

    @Override
    @NonNull
    public A2uiActionClass resolve(@NonNull String actionId,
            @Nullable A2uiActionClass agentDeclared) {
        Entry entry = mEntries.get(actionId);
        A2uiActionClass owned = entry == null ? A2uiActionClass.DESTRUCTIVE : entry.actionClass;
        return agentDeclared == null ? owned
                : A2uiActionClass.mostRestrictive(owned, agentDeclared);
    }

    @Override
    public void onAction(@NonNull String surfaceId, @NonNull String componentId,
            @NonNull String actionId, @Nullable Object payload) {
        Entry entry = mEntries.get(actionId);
        if (entry == null) {
            if (mRejectionListener != null) {
                mRejectionListener.onUnknownAction(actionId);
            }
            return;
        }
        entry.handler.onAction(surfaceId, componentId, payload);
    }

    public boolean hasAction(@NonNull String actionId) {
        return mEntries.containsKey(actionId);
    }

    public static final class Builder {
        private final Map<String, Entry> mEntries = new LinkedHashMap<>();
        @Nullable private RejectionListener mRejectionListener;

        @NonNull
        public Builder addAction(@NonNull String actionId, @NonNull A2uiActionClass actionClass,
                @NonNull Handler handler) {
            if (!AgentId.isCanonical(actionId)) {
                throw new IllegalArgumentException("action id must match [A-Za-z0-9._:-]{1,256}");
            }
            if (mEntries.put(actionId, new Entry(actionClass, handler)) != null) {
                throw new IllegalArgumentException("duplicate action id " + actionId);
            }
            return this;
        }

        @NonNull
        public Builder setRejectionListener(@Nullable RejectionListener listener) {
            mRejectionListener = listener;
            return this;
        }

        @NonNull
        public TrustedActionRegistry build() {
            return new TrustedActionRegistry(mEntries, mRejectionListener);
        }
    }
}
