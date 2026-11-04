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
package art.dart.a2ui.carui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Presentation;
import android.car.drivingstate.CarUxRestrictions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Build;
import android.os.Looper;
import android.os.Handler;
import android.Manifest;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.VideoView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.ui.CarUiLayoutInflaterFactory;
import com.android.car.ui.baselayout.Insets;
import com.android.car.ui.baselayout.InsetsChangedListener;
import com.android.car.ui.core.CarUi;
import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.recyclerview.CarUiListItem;
import com.android.car.ui.recyclerview.CarUiListItemAdapter;
import com.android.car.ui.recyclerview.CarUiRecyclerView;
import com.android.car.ui.recyclerview.DelegatingContentLimitingAdapter;
import com.android.car.ui.toolbar.ToolbarController;
import com.android.car.ui.toolbar.SearchMode;
import com.android.car.ui.toolbar.Tab;
import com.android.car.ui.utils.CarUxRestrictionsUtil;
import com.android.car.uxr.UxrContentLimiterImpl;

import art.dart.a2ui.agent.AgentProvider;
import art.dart.a2ui.agent.AgentTransport;
import art.dart.a2ui.agent.AgentTransportException;
import art.dart.a2ui.demo.DemoRestrictions;
import art.dart.a2ui.demo.DemoSurface;
import art.dart.a2ui.protocol.A2uiDisplayBinder;
import art.dart.a2ui.protocol.A2uiMessageProcessor;
import art.dart.a2ui.protocol.A2uiProtocolException;
import art.dart.a2ui.protocol.A2uiSurface;
import art.dart.a2ui.policy.A2uiValueLimits;
import art.dart.a2ui.policy.A2uiActionPolicy;
import art.dart.a2ui.policy.A2uiActionBinder;
import art.dart.a2ui.demo.DemoTrace;
import art.dart.a2ui.demo.UseCaseCorpus;
import com.android.car.ui.AlertDialogBuilder;
import android.widget.Toast;
import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.policy.A2uiHandoff;
import art.dart.a2ui.policy.A2uiPruner;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.policy.A2uiVisibleTree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Host B: the same A2UI surface as Host A, rendered by {@code car-ui-lib} through the OEM plugin.
 *
 * <p>Two lists over identical data, differing only in which of car-ui-lib's two content-limiting
 * mechanisms drives them: left, {@link DelegatingContentLimitingAdapter} driven by
 * {@link UxrContentLimiterImpl}; right, a bare {@link CarUiListItemAdapter} capped by
 * {@code CarUiRecyclerView.ItemCap}. Under {@code LIMIT_CONTENT} with three items over six rows
 * they report 4 and 3: the left adds a withheld-content row, the right truncates silently (see
 * {@code ContentLimitMechanismTest}). Unlike Host A, where the templates host enforces the limit,
 * here the app enforces it and a wiring mistake is invisible.
 */
// metalava api-lint: ForbiddenSuperClass and ProtectedMember, suppressed: rules for library API,
// and this is an application Activity whose protected methods are framework lifecycle overrides.
@SuppressWarnings("ForbiddenSuperClass")
public class A2uiCarUiActivity extends Activity implements InsetsChangedListener {

    private static final String TAG = "A2uiCarUiActivity";

    @VisibleForTesting
    final A2uiMessageProcessor mProcessor = DemoTrace.newProcessor();
    @NonNull private String mSurfaceId = DemoTrace.SURFACE_ID;

    /** The tree most recently rendered; the only thing voice may reach. */
    @Nullable
    private A2uiComponent mRenderedRoot;
    private TextView mEmptyState;

    /** Live recogniser, or null when not listening. Released in onStop. */
    @Nullable
    // Package-private so A2uiVoiceWiringTest can assert the recogniser is never constructed.
    @VisibleForTesting
    SpeechRecognizer mRecognizer;

    private static final int REQ_RECORD_AUDIO = 1;

    @Nullable
    private BroadcastReceiver mSpeakReceiver;

    @Nullable
    private BroadcastReceiver mHandoffReceiver;
    @Nullable private BroadcastReceiver mCaseReceiver;
    @Nullable private BroadcastReceiver mAgentReceiver;
    @Nullable private BroadcastReceiver mPassengerReceiver;
    @Nullable private BroadcastReceiver mEndCardReceiver;
    /** The closing card: static host text, parked only. */
    private View mEndCard;
    /** Re-renders when the passenger surface changes what it plays. */
    private final PassengerDisplay.Listener mPassengerListener = this::onPassengerChanged;

    private void onPassengerChanged() {
        runOnMain(() -> {
            if (mRestrictions != null) refresh();
        });
    }

    /** The live-agent lane, or null: it exists only where {@link #debugBusAllowed} holds. */
    @Nullable
    @VisibleForTesting
    AgentLane mAgent;

    /** Toolbar chip cycling the providers. Debug builds only, like the lane. */
    @Nullable
    @VisibleForTesting
    com.android.car.ui.toolbar.MenuItem mProviderItem;

    /** True while {@link #mSurfaceId} names an agent surface, which lives on the lane's processor. */
    @VisibleForTesting
    boolean mShowingAgent;

    /** The corpus surface to return to before the lane replaces its surfaces. */
    @NonNull private String mCorpusSurfaceId = DemoTrace.SURFACE_ID;

    /** {@code provider · NNN ms} while the lane is in use, else null. */
    @Nullable
    @VisibleForTesting
    String mAgentBadge;

    /** The window on the destination display, while a handoff is showing. Dismissed in onStop. */
    @Nullable
    private Presentation mHandoffPresentation;

    /** The display a surface is currently handed to, and its live restriction state. */
    @Nullable
    private CarUxRestrictionsUtil mHandoffDestination;

    /** Registered on {@link #mHandoffDestination} while the handoff is up; held in a field because
     * {@code CarUxRestrictionsUtil} keeps observers weakly. */
    @Nullable
    private CarUxRestrictionsUtil.OnUxRestrictionsChangedListener mHandoffUxrListener;

    /** The destination's rows. Mutated in place so its adapter is never replaced — same reason as
     * {@link #mItems}: replacing an adapter races the restriction listener that drives it. */
    private final List<CarUiListItem> mHandoffItems = new ArrayList<>();

    @Nullable
    private CarUiListItemAdapter mHandoffAdapter;

    /**
     * Each display's last genuine reading, captured before a synthetic one replaces it. Not cleared
     * by {@link #releaseHandoffState()}, one entry per display, and static: state that undoes
     * something must outlive what it undoes, and the synthetic reading lives in
     * {@code CarUxRestrictionsUtil}'s static per-display cache.
     */
    private static final android.util.SparseArray<CarUxRestrictions>
            sGenuineDestinationRestrictions = new android.util.SparseArray<>();

    /** Drops every captured genuine reading. Testing only: the static capture outlives a test. */
    @VisibleForTesting
    static void clearGenuineDestinationRestrictionsForTesting() {
        sGenuineDestinationRestrictions.clear();
    }

    /**
     * The rows currently standing on the destination display, exposed so a test can watch the
     * listener's effect rather than its bookkeeping.
     */
    @VisibleForTesting
    @NonNull
    List<CarUiListItem> handoffRowsForTesting() {
        return mHandoffItems;
    }

    /**
     * The live presentation, or null. Exposed so a test can dismiss it as the system does, which
     * fires only the dismiss listener, not onStop's explicit release.
     */
    @VisibleForTesting
    @Nullable
    Presentation handoffPresentationForTesting() {
        return mHandoffPresentation;
    }

    /** True while a destination is being watched. Pairs with {@link #handoffRowsForTesting}. */
    @VisibleForTesting
    boolean isWatchingDestinationForTesting() {
        return mHandoffUxrListener != null && mHandoffDestination != null;
    }

    /**
     * Decides and invokes on behalf of {@link CarUiRenderer} for this activity's display, through
     * {@link #mProcessor} so the policy is not copied into the host. Built in onCreate from
     * {@link #mRestrictions}; another display gets its own binder from {@code binderFor}.
     */
    @Nullable
    private A2uiDisplayBinder mActionBinder;

    /**
     * One display's render inputs, all derived from that display, as a value testable in a local
     * JVM. The allowed set and limits are snapshots that decide what to draw; the reader is live
     * and decides what may run when the finger lands.
     */
    @VisibleForTesting
    static final class DisplayScope {
        final Set<ComponentType> mAllowed;
        final A2uiValueLimits mLimits;
        final A2uiMessageProcessor.RestrictionReader mReader;
        final int mDisplayId;

        DisplayScope(@NonNull Set<ComponentType> allowed, @NonNull A2uiValueLimits limits,
                @NonNull A2uiMessageProcessor.RestrictionReader reader, int displayId) {
            mAllowed = allowed;
            mLimits = limits;
            mReader = reader;
            mDisplayId = displayId;
        }
    }

    /** What one display ended up with: the tree it shows, and the rows built from it. */
    @VisibleForTesting
    static final class Rendered {
        @Nullable final A2uiComponent mTree;
        @NonNull final List<CarUiListItem> mRows;

        Rendered(@Nullable A2uiComponent tree, @NonNull List<CarUiListItem> rows) {
            mTree = tree;
            mRows = rows;
        }
    }

    /**
     * The whole per-display pipeline as one testable call: prune, limit, bind, render. Everything
     * comes from {@code scope}, so the destination's tree cannot meet the source's authorization;
     * covered by {@code A2uiHostWiringTest}.
     */
    @VisibleForTesting
    @NonNull
    static Rendered renderFor(@Nullable A2uiComponent projected,
            @NonNull DisplayScope scope, @NonNull A2uiMessageProcessor processor,
            @NonNull String surfaceId, @NonNull A2uiDisplayBinder.Outcome outcome) {
        A2uiComponent pruned = projected == null ? null
                : A2uiPruner.prune(projected, scope.mAllowed);
        if (pruned != null) {
            pruned = A2uiPruner.applyLimits(pruned, scope.mLimits);
        }
        // Both returned, so callers that need the tree as well as the rows — logEnforcement pairs
        // them to tell a refused Button from an inert Text — cannot re-derive it and drift.
        return new Rendered(pruned, CarUiRenderer.render(pruned, new StaleGuard(
                new A2uiDisplayBinder(processor, surfaceId, scope.mReader, outcome),
                () -> Log.i(TAG, "refused a control whose surface has been replaced"))));
    }

    /**
     * Refuses, rather than crashing on, a control whose surface no longer exists. Agent surfaces
     * are replaced on every ask and provider switch, while a drawn row can still be pressed.
     */
    @VisibleForTesting
    static final class StaleGuard implements A2uiActionBinder {
        private final A2uiActionBinder mDelegate;
        private final Runnable mOnStale;

        StaleGuard(@NonNull A2uiActionBinder delegate, @NonNull Runnable onStale) {
            mDelegate = delegate;
            mOnStale = onStale;
        }

        @Override
        @Nullable
        public A2uiActionPolicy.Decision decisionFor(@NonNull A2uiComponent component) {
            return mDelegate.decisionFor(component);
        }

        @Override
        public void onInvoke(@NonNull A2uiComponent component) {
            try {
                mDelegate.onInvoke(component);
            } catch (A2uiProtocolException gone) {
                mOnStale.run();
            }
        }
    }

    /** The processor that owns {@link #mSurfaceId}: the corpus one, or the agent lane's. */
    @NonNull
    private A2uiMessageProcessor surfaceProcessor() {
        return mShowingAgent && mAgent != null ? mAgent.processor() : mProcessor;
    }

    /** The current surface projected from its root, or null (renders nothing: fail closed). */
    @Nullable
    private A2uiComponent currentProjection() {
        A2uiSurface surface = surfaceProcessor().getSurface(mSurfaceId);
        try {
            return surface == null ? null : surface.project(DemoTrace.ROOT_ID);
        } catch (A2uiProtocolException unprojectable) {
            return null;
        }
    }

    /** {@link DisplayScope} for a live {@code CarUxRestrictionsUtil}. The host-side half. */
    @NonNull
    private static DisplayScope scopeFor(@NonNull CarUxRestrictionsUtil restrictions) {
        CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
                restrictions.getRestrictionsSnapshot();
        CarUxRestrictions reading = snapshot.getRestrictions();
        CarUxRestrictionsUtil.RestrictionsSource source = snapshot.getSource();
        return new DisplayScope(allowedFor(source, reading), limitsFor(source, reading),
                readerFor(restrictions), restrictions.getDisplayId());
    }

    /**
     * A binder speaking for one display: it reads that display's restrictions, dispatches against
     * them, and shows its dialogs and toasts there ({@code uiContext}), so a destination control
     * never asks on the driver's screen.
     */
    @NonNull
    private A2uiDisplayBinder binderFor(@NonNull CarUxRestrictionsUtil restrictions,
            @NonNull Context uiContext) {
        return new A2uiDisplayBinder(surfaceProcessor(), mSurfaceId,
                readerFor(restrictions),
                outcomeOn(restrictions, uiContext));
    }

    /** What the host does with a decision, on the display the control is actually on. */
    @NonNull
    private A2uiDisplayBinder.Outcome outcomeOn(
            @NonNull CarUxRestrictionsUtil restrictions, @NonNull Context uiContext) {
        return (component, decision) -> {
            Log.i(TAG, "invoked " + component.getAction() + " on display "
                    + restrictions.getDisplayId() + " -> " + decision);
            if (mShowingAgent && mAgent != null) mAgent.onDecided(mSurfaceId, component, decision);
            switch (decision) {
                case ALLOW:
                    // Exact corpus-native handlers provide their own state/UI. Calling those
                    // controls "done" before the picker or route preview even opens is a lie.
                    if (mShowingAgent || DemoNativeContract.resolve(mSurfaceId, component) == null) {
                        Toast.makeText(uiContext, component.getTitle() + " — done",
                                Toast.LENGTH_SHORT).show();
                    }
                    break;
                case CONFIRM:
                    confirm(component, restrictions, uiContext);
                    break;
                case REFUSE:
                default:
                    // Reachable: the vehicle can start moving between building the list and the
                    // press.
                    Toast.makeText(uiContext, A2uiActionBinder.REFUSED_NOTE,
                            Toast.LENGTH_LONG).show();
                    break;
            }
        };
    }

    /**
     * Whether this display's vehicle state permits opening a microphone, by
     * {@code A2uiRestrictionPolicy}'s rule, shared with Host A. Static and parameterised so the
     * rule itself is tested; Host B's DEMO injection already carries provenance in the snapshot.
     */
    @VisibleForTesting
    static boolean allowsListening(
            @NonNull CarUxRestrictionsUtil.RestrictionsSource source,
            @NonNull CarUxRestrictions restrictions) {
        return A2uiRestrictionPolicy.allowsListening(
                restrictions.getActiveRestrictions(),
                isAuthoritative(source));
    }

    private static boolean isAuthoritative(
            @NonNull CarUxRestrictionsUtil.RestrictionsSource source) {
        return source == CarUxRestrictionsUtil.RestrictionsSource.DELIVERED
                || source == CarUxRestrictionsUtil.RestrictionsSource.DEMO;
    }

    /** One display's live vehicle state. Live — every call re-reads. */
    @NonNull
    private static A2uiMessageProcessor.RestrictionReader readerFor(
            @NonNull CarUxRestrictionsUtil restrictions) {
        return new A2uiMessageProcessor.RestrictionReader() {
            @Override
            public int activeRestrictions() {
                return restrictions.getCurrentRestrictions().getActiveRestrictions();
            }

            @Override
            public boolean isTrustworthy() {
                return isAuthoritative(restrictions.getRestrictionsSource());
            }

            @Override
            public A2uiMessageProcessor.RestrictionReading currentReading() {
                CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
                        restrictions.getRestrictionsSnapshot();
                return new A2uiMessageProcessor.RestrictionReading(
                        snapshot.getRestrictions().getActiveRestrictions(),
                        isAuthoritative(snapshot.getSource()));
            }
        };
    }

    /**
     * Asks, then has the policy decide again when the driver answers
     * ({@code A2uiMessageProcessor.confirmAction}), against the display the control is on
     * ({@code restrictions}) and on that display ({@code uiContext}).
     */
    private void confirm(A2uiComponent component, CarUxRestrictionsUtil restrictions,
            Context uiContext) {
        A2uiDisplayBinder binder = binderFor(restrictions, uiContext);
        AlertDialog asked = new AlertDialogBuilder(uiContext)
                .setTitle(confirmTitle(component))
                .setMessage(confirmMessage(component))
                .setNegativeButton(R.string.a2ui_cancel, null)
                .setPositiveButton(R.string.a2ui_confirm, (dialog, which) -> {
                    int orders = mOrderSeq;
                    A2uiActionPolicy.Decision again;
                    try {
                        again = binder.confirm(component);
                    } catch (A2uiProtocolException surfaceReplaced) {
                        again = A2uiActionPolicy.Decision.REFUSE;
                    }
                    if (mShowingAgent && mAgent != null) {
                        mAgent.onConfirmed(mSurfaceId, component, again);
                    }
                    Log.i(TAG, "confirmation answered on display " + restrictions.getDisplayId()
                            + "; re-decided as " + again);
                    if (mOrderSeq != orders && uiContext == A2uiCarUiActivity.this) {
                        return; // the pickup card on this display is the confirmation
                    }
                    Toast.makeText(uiContext,
                            again == A2uiActionPolicy.Decision.REFUSE
                                    ? "Not available now — conditions changed"
                                    : component.getTitle() + " — done",
                            Toast.LENGTH_LONG).show();
                })
                .show();
        fitDialog(asked, uiContext);
    }

    /**
     * car-ui's dialog puts its buttons in a column on the right and can lay out wider than the
     * display, clipping both pills at the edge. Pinned to the display's width less a margin each
     * side, the message wraps instead.
     */
    @VisibleForTesting
    static void fitDialog(@NonNull AlertDialog dialog, @NonNull Context uiContext) {
        android.view.Window window = dialog.getWindow();
        if (window == null) return;
        android.util.DisplayMetrics metrics = uiContext.getResources().getDisplayMetrics();
        int margin = Math.round(DIALOG_MARGIN_DP * metrics.density);
        window.setLayout(Math.max(0, metrics.widthPixels - 2 * margin),
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Space kept between a confirm dialog and each side edge of its display. */
    static final int DIALOG_MARGIN_DP = 64;

    private CarUxRestrictionsUtil mRestrictions;
    private ToolbarController mToolbar;
    @Nullable private String mPendingSearchQuery;
    private final Consumer<String> mSearchListener = query -> mPendingSearchQuery = query;
    private final Runnable mSearchCompletedListener = this::dispatchPendingSearch;
    // Package-private for the same reason mRecognizer is: the withholding half had no test, and
    // forcing setEnabled(true) left every suite green.
    @VisibleForTesting
    com.android.car.ui.toolbar.MenuItem mSpeakItem;
    @VisibleForTesting
    com.android.car.ui.toolbar.MenuItem mSearchItem;
    /** The debug console's toolbar entry; visible only with the technical details. */
    @Nullable private com.android.car.ui.toolbar.MenuItem mConsoleItem;
    private UxrContentLimiterImpl mLimiter;
    private TextView mStatusBadge;
    /** The vehicle-state banner: PARKED, DRIVING, DEMO or SAFE MODE. */
    private View mBanner;
    private TextView mBannerTitle;
    private TextView mBannerDetail;
    @Nullable private DriverState.Kind mBannerKind;
    /** The transient "what changed" line, and what it diffs against. */
    private TextView mChangeStrip;
    /** The change line holds the header's end; the hint and heading return when it goes. */
    private boolean mStripShowing;
    private boolean mVoiceHintWanted;
    private boolean mSurfaceHeadingWanted = true;
    private final Runnable mHideChangeStrip = this::hideChangeStrip;
    private final Handler mStripHandler = new Handler(Looper.getMainLooper());
    @Nullable private A2uiComponent mLastPrunedTree;
    private int mLastVisibleRows = -1;
    @Nullable private String mLastSurfaceKey;
    private TextView mSurfaceHeading;
    private TextView mStatus;
    private boolean mDiagnosticsExpanded;
    private TextView mLimitedLabel;
    private TextView mUnlimitedLabel;
    private View mUnlimitedColumn;
    private CarUiRecyclerView mLimitedList;
    private CarUiRecyclerView mUnlimitedList;
    private BroadcastReceiver mDemoReceiver;
    @VisibleForTesting
    DemoMediaController mDemoMedia;
    @Nullable private AlertDialog mDemoContentDialog;
    @Nullable private String mDemoDialogComponentId;
    @Nullable private ComponentType mDemoDialogRequiredType;
    private View mContentColumns;
    private FrameLayout mVideoStage;
    /** Visitor surfaces' cards over {@link #mItems}; the car-ui lists stay the enforcement. */
    private RecyclerView mCardList;
    private CardListAdapter mCards;
    private View mLimitedColumn;
    /** One screen-level label for fixture content. */
    private TextView mDemoPill;
    /** The one voice affordance on a consumer screen. */
    private TextView mVoiceHint;
    /** Large imagery for one surviving component; see {@link #updateVisualPanel}. */
    private View mVisualPanel;
    private ImageView mVisualPanelImage;
    private TextView mVisualPanelTitle;
    private TextView mVisualPanelCaption;
    /** Parked-only artwork, tracked like the video: its component must survive, VIDEO allowed. */
    private ArtworkSlideshow mSlideshow;
    @Nullable private OnBackInvokedCallback mGalleryBack;
    /** The parked-only full-bleed video, or null. Tracked like a dialog, with VIDEO required. */
    @Nullable private VideoView mVideoView;
    @Nullable private OnBackInvokedCallback mVideoBack;
    @Nullable private DemoAudio mAudio;
    /** Between onStart and onStop. The debug bus outlives that window; sound must not. */
    private boolean mStarted;

    /** Set by a confirmed DEMO pickup order; the card reads its words from the trace. */
    @VisibleForTesting
    boolean mOrderPlaced;
    /** Bumped per placed order, so a confirmation can tell the card answered it. */
    private int mOrderSeq;
    private boolean mDroveWithOrder;
    /** At or below this state of charge, the vehicle suggests chargers. */
    @VisibleForTesting
    static final int LOW_BATTERY_PCT = 20;
    /** State of charge from the vehicle (CarPropertyManager), or null before any reading. */
    @Nullable private Integer mVehicleBatteryPct;
    /** A debug VEHICLE broadcast's reading, labelled DEMO; wins while set. */
    @Nullable private Integer mDemoBatteryPct;
    @Nullable private VehicleEnergy mEnergy;
    @Nullable private BroadcastReceiver mVehicleReceiver;
    /** The charger chosen from the comparison (0-based), or -1. */
    @VisibleForTesting
    int mChosenCharger = -1;
    /** The bundled café (OSM, picked by Muse from cited sources), or null without the fixture. */
    @Nullable
    @VisibleForTesting
    CoffeeFixture.Pick mCoffee;
    private boolean mDroveToCharger;
    /** Parked after driving to the chosen charger: the charging window. */
    @VisibleForTesting
    boolean mAtCharger;
    @Nullable private Integer mChargeStartPct;
    /** A charging stop happened: no new prompt until the reading climbs above the threshold. */
    private boolean mPromptSpent;
    /** Host row keys drawn at the last refresh, for the "collapsed" half of the change line. */
    private final List<String> mLastHostKeys = new ArrayList<>();
    /** Artwork plays as the film (true) or the stills (false); the parked-only toggle flips it. */
    @VisibleForTesting
    boolean mArtworkFilm = true;
    /** An agent answer shown as this journey surface's brief, on the lane's processor. */
    @Nullable
    @VisibleForTesting
    String mEmbeddedAgentSurface;
    /** The journey the commitment fields above belong to. */
    @Nullable private HostJourney mJourney;
    /** Parked again after driving with an order: the arrival moment. */
    @VisibleForTesting
    boolean mArrived;
    /** Last authoritative reading was parked; null when the reading is not trusted. */
    @Nullable private Boolean mWasParked;
    /** An agent turn is in flight; a thinking row stands in for streaming. */
    @VisibleForTesting
    boolean mAgentThinking;

    /** Diff keys and content signatures for {@link #mItems}, index for index. */
    private final List<String> mItemKeys = new ArrayList<>();
    private final List<String> mItemSignatures = new ArrayList<>();
    /** Bumped with every binder change, so no row survives into another surface. */
    private int mRowEpoch;
    /** The reading the rows were built from. A new one swaps instantly, then crossfades. */
    @Nullable private String mLastReading;
    /** The installed item animators, null while the animator scale is 0. */
    @Nullable private RecyclerView.ItemAnimator mLimitedAnimator;
    @Nullable private RecyclerView.ItemAnimator mUnlimitedAnimator;
    private boolean mAnimatorsApplied;

    /** The one list both adapters read. Mutated in place so neither adapter is ever replaced. */
    private final List<CarUiListItem> mItems = new ArrayList<>();

    private CarUiListItemAdapter mLimitedDelegate;
    private DelegatingContentLimitingAdapter<?> mLimited;
    private CarUiListItemAdapter mUnlimited;

    /** The last genuine reading, captured before the first demo injection overwrites it. */
    @Nullable
    private CarUxRestrictions mGenuineRestrictions;

    /** Accepted synthetic state for this host only; never presented as live provenance. */
    @Nullable
    private Integer mDemoOverride;

    private final CarUxRestrictionsUtil.OnUxRestrictionsChangedListener mUxrListener =
            uxr -> runOnMain(this::refresh);

    private void runOnMain(@NonNull Runnable work) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            work.run();
        } else {
            new Handler(Looper.getMainLooper()).post(work);
        }
    }

    @Override
    // ProtectedMember: a framework-fixed lifecycle signature. See the class comment.
    @SuppressWarnings("ProtectedMember")
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.a2ui_carui_activity);

        // Supplied by the OEM's plugin, so a vendor restyles it without rebuilding this app.
        mToolbar = CarUi.requireToolbar(this);
        mToolbar.setTitle(getTitle());
        // Held so refresh() can withhold it, as Host A does
        // (A2uiShowcaseScreenVoiceTest#theSpeakActionIsWithheldWhenTheVehicleForbidsTranscription);
        // startListening()'s own check is the enforcement.
        mSpeakItem = com.android.car.ui.toolbar.MenuItem.builder(this)
                .setTitle(R.string.a2ui_speak)
                .setIcon(R.drawable.a2ui_ic_mic)
                .setOnClickListener(ignored -> startListening())
                .build();
        // Search-capable corpus cases expose an OEM-styled affordance, but selecting the case must
        // not immediately enter edit mode and cover the demo with an IME. The driver explicitly
        // opens search; refresh() can still withdraw it synchronously if the field is pruned.
        mSearchItem = com.android.car.ui.toolbar.MenuItem.builder(this)
                .setToSearch()
                .setVisible(false)
                .setOnClickListener(ignored -> beginNativeSearch())
                .build();
        List<com.android.car.ui.toolbar.MenuItem> menuItems = new ArrayList<>();
        menuItems.add(mSpeakItem);
        menuItems.add(mSearchItem);
        if (debugBusAllowed(getApplicationInfo(), Build.TYPE)) {
            // Engineering console: shown with the technical details (tap the banner), not on
            // the presentation screen.
            mConsoleItem = com.android.car.ui.toolbar.MenuItem.builder(this)
                    .setTitle("Demo")
                    .setVisible(false)
                    .setOnClickListener(ignored -> showDemoConsole())
                    .build();
            menuItems.add(mConsoleItem);
            mAgent = AgentLane.create(getMainExecutor(), mAgentListener);
            // Engineering, like the console: shown only with the technical details.
            mProviderItem = com.android.car.ui.toolbar.MenuItem.builder(this)
                    .setTitle(providerLabel(mAgent.provider()))
                    .setVisible(false)
                    .setOnClickListener(ignored -> cycleProvider())
                    .build();
            menuItems.add(mProviderItem);
        }
        mToolbar.setMenuItems(menuItems);
        mToolbar.registerSearchListener(mSearchListener);
        mToolbar.registerSearchCompletedListener(mSearchCompletedListener);

        mStatusBadge = findViewById(R.id.status_badge);
        mBanner = findViewById(R.id.drive_banner);
        mBannerTitle = findViewById(R.id.drive_banner_title);
        mBannerDetail = findViewById(R.id.drive_banner_detail);
        mChangeStrip = findViewById(R.id.change_strip);
        mSurfaceHeading = findViewById(R.id.surface_heading);
        mStatus = findViewById(R.id.status);
        boolean mayShowDiagnostics = debugBusAllowed(getApplicationInfo(), Build.TYPE);
        mStatusBadge.setClickable(mayShowDiagnostics);
        mStatusBadge.setFocusable(mayShowDiagnostics);
        if (mayShowDiagnostics) {
            mStatusBadge.setOnClickListener(ignored -> setDiagnosticsExpanded(
                    !mDiagnosticsExpanded));
            // Consumer screens show no engineering affordance: technical details, the console and
            // the provider chip sit behind a long-press on the screen's title.
            View.OnLongClickListener details = ignored -> {
                setDiagnosticsExpanded(!mDiagnosticsExpanded);
                return true;
            };
            mSurfaceHeading.setOnLongClickListener(details);
            mBanner.setOnLongClickListener(details);
            getWindow().getDecorView().post(() -> attachTitleLongPress(details));
        }
        mDemoPill = findViewById(R.id.demo_pill);
        mVoiceHint = findViewById(R.id.voice_hint);
        android.graphics.drawable.Drawable mic = getDrawable(R.drawable.a2ui_ic_mic);
        if (mic != null) {
            int size = Math.round(18 * getResources().getDisplayMetrics().density);
            mic = mic.mutate();
            mic.setTint(getColor(R.color.a2ui_brand_on_surface_muted));
            mic.setBounds(0, 0, size, size);
            mVoiceHint.setCompoundDrawablesRelative(mic, null, null, null);
        }
        mEmptyState = findViewById(R.id.empty_state);
        mLimitedLabel = findViewById(R.id.limited_label);
        mUnlimitedLabel = findViewById(R.id.unlimited_label);
        mUnlimitedColumn = findViewById(R.id.unlimited_column);
        mLimitedList = findViewById(R.id.limited_list);
        mUnlimitedList = findViewById(R.id.unlimited_list);
        mLimitedList.setLayoutManager(new LinearLayoutManager(this));
        mUnlimitedList.setLayoutManager(new LinearLayoutManager(this));
        mLimitedColumn = findViewById(R.id.limited_column);
        mCardList = findViewById(R.id.card_list);
        mCardList.setLayoutManager(new LinearLayoutManager(this));
        mCardList.setItemAnimator(null);
        mCards = new CardListAdapter(mItems);
        mCardList.setAdapter(mCards);
        // A recorded frame never shows half a card: one that does not fit is hidden, not cut.
        mCardList.getViewTreeObserver().addOnGlobalLayoutListener(this::hidePartialCards);
        mCardList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                hidePartialCards();
            }
        });
        mContentColumns = findViewById(R.id.content_columns);
        mVideoStage = findViewById(R.id.video_stage);
        findViewById(R.id.video_close).setOnClickListener(ignored -> dismissDemoContent());
        mVisualPanel = findViewById(R.id.visual_panel);
        mVisualPanelImage = findViewById(R.id.visual_panel_image);
        mVisualPanelTitle = findViewById(R.id.visual_panel_title);
        mVisualPanelCaption = findViewById(R.id.visual_panel_caption);
        mVisualPanel.setClipToOutline(true);
        mSlideshow = new ArtworkSlideshow(findViewById(R.id.gallery_stage),
                findViewById(R.id.gallery_backdrop), findViewById(R.id.gallery_image),
                findViewById(R.id.gallery_title), findViewById(R.id.gallery_caption),
                findViewById(R.id.gallery_credit), findViewById(R.id.gallery_counter),
                this::animationsEnabled);
        findViewById(R.id.gallery_close).setOnClickListener(ignored -> dismissDemoContent());
        mEndCard = findViewById(R.id.end_card);
        bindEndCardBullets(findViewById(R.id.end_card_bullets));
        PassengerDisplay.addListener(mPassengerListener);

        mRestrictions = CarUxRestrictionsUtil.getInstance(this);
        mLimiter = new UxrContentLimiterImpl(this, R.xml.uxr_config);

        // Protocol state is retained so an action can travel back to its surface.
        mProcessor.processAll(DemoTrace.messages());
        for (UseCaseCorpus.UseCase useCase : UseCaseCorpus.all()) {
            mProcessor.processAll(useCase.getMessages());
        }
        // The processor's own reader is this display's: the fallback for any call that names no
        // display. A handed-off control uses its own (binderFor()).
        mProcessor.setRestrictionReader(readerFor(mRestrictions));
        if (mAgent != null) {
            mAgent.processor().setRestrictionReader(readerFor(mRestrictions));
        }
        // This activity's own display, and this activity as the UI context — which for display 0 is
        // the same thing said twice, and is exactly why the destination case needed both spelled out.
        mActionBinder = binderFor(mRestrictions, this);
        mProcessor.setActionSink((surfaceId, componentId, action, payload) -> {
            Log.i(TAG, "action dispatched: surface=" + surfaceId + " component=" + componentId
                    + " action=" + action);
            if ("handoff_to_driver".equals(surfaceId) && "send".equals(componentId)
                    && "demo.handoff_to_driver".equals(action)) {
                runOnMain(this::showHandoffTargets);
            }
            // An agent surface may reuse a corpus id; native handlers belong to the corpus alone.
            if (!mShowingAgent && surfaceId.equals(mSurfaceId)) {
                runOnMain(() -> handleNativeDemoAction(componentId, payload));
            }
        });

        // Real chargers, when the fixture is bundled, enter through the processor like agent text.
        mProcessor.processAll(ChargerFixture.messages(this));
        mCoffee = CoffeeFixture.load(this);
        mEnergy = new VehicleEnergy(pct -> runOnMain(() -> onVehicleBattery(pct)));

        mDemoMedia = new DemoMediaController(this);
        mAudio = new DemoAudio(this, DemoAudio.SFX_ASSETS);

        // Both adapters are built once over the same list. UxrContentLimiterImpl.setAdapter() does
        // not push the current limit (only start()'s listener does), and replacing adapters per
        // refresh races the limiter's listener, whose notification order is unspecified.
        mLimitedDelegate = new CarUiListItemAdapter(mItems);
        mLimited = new DelegatingContentLimitingAdapter<>(mLimitedDelegate, R.id.a2ui_list);
        mUnlimited = new CarUiListItemAdapter(mItems);
        mLimitedList.setAdapter(mLimited);
        mUnlimitedList.setAdapter(mUnlimited);
        mLimiter.setAdapter(mLimited);

        registerDemoReceiverIfDebuggable();
    }

    @Override
    // ProtectedMember: a framework-fixed lifecycle signature. See the class comment.
    @SuppressWarnings("ProtectedMember")
    protected void onStart() {
        super.onStart();
        // The limiter first: its registration seeds the adapter's limit before the first refresh.
        mLimiter.start();
        mRestrictions.register(mUxrListener);
        mStarted = true;
        if (mEnergy != null) mEnergy.start(this, new Handler(Looper.getMainLooper()));
        refresh();
    }

    @Override
    // ProtectedMember: a framework-fixed lifecycle signature. See the class comment.
    @SuppressWarnings("ProtectedMember")
    protected void onStop() {
        // A live recogniser holds the microphone. Leaving one running behind a backgrounded
        // activity is the kind of thing that shows up as a permanently-lit mic indicator.
        stopListening();
        // A Presentation lives on another display and would outlive this activity.
        dismissHandoffPresentation();
        dismissDemoContent();
        if (mDemoMedia != null) {
            mDemoMedia.stop();
        }
        mStarted = false;
        if (mAudio != null) {
            mAudio.quiet();
        }
        if (mEnergy != null) mEnergy.stop();
        super.onStop();
        mRestrictions.unregister(mUxrListener);
        mLimiter.stop();
    }

    @Override
    // ProtectedMember: a framework-fixed lifecycle signature. See the class comment.
    @SuppressWarnings("ProtectedMember")
    protected void onDestroy() {
        super.onDestroy();
        if (mDemoMedia != null) {
            mDemoMedia.release();
            mDemoMedia = null;
        }
        if (mAudio != null) {
            mAudio.release();
            mAudio = null;
        }
        if (mToolbar != null) {
            mToolbar.unregisterSearchListener(mSearchListener);
            mToolbar.unregisterSearchCompletedListener(mSearchCompletedListener);
        }
        if (mDemoReceiver != null) {
            unregisterReceiver(mDemoReceiver);
            mDemoReceiver = null;
        }
        if (mSpeakReceiver != null) {
            unregisterReceiver(mSpeakReceiver);
            mSpeakReceiver = null;
        }
        if (mHandoffReceiver != null) {
            unregisterReceiver(mHandoffReceiver);
            mHandoffReceiver = null;
        }
        if (mCaseReceiver != null) {
            unregisterReceiver(mCaseReceiver);
            mCaseReceiver = null;
        }
        if (mAgentReceiver != null) {
            unregisterReceiver(mAgentReceiver);
            mAgentReceiver = null;
        }
        if (mPassengerReceiver != null) {
            unregisterReceiver(mPassengerReceiver);
            mPassengerReceiver = null;
        }
        if (mEndCardReceiver != null) {
            unregisterReceiver(mEndCardReceiver);
            mEndCardReceiver = null;
        }
        if (mVehicleReceiver != null) {
            unregisterReceiver(mVehicleReceiver);
            mVehicleReceiver = null;
        }
        PassengerDisplay.removeListener(mPassengerListener);
        if (mAgent != null) {
            mShowingAgent = false;
            mAgent.close();
            mAgent = null;
        }
    }

    /** Required by car-ui-lib's base layout; this activity draws edge to edge inside it. */
    @Override
    public void onCarUiInsetsChanged(@NonNull Insets insets) {
        findViewById(R.id.content_root).setPadding(
                insets.getLeft(), insets.getTop(), insets.getRight(), insets.getBottom());
        findViewById(R.id.video_stage).setPadding(
                insets.getLeft(), insets.getTop(), insets.getRight(), insets.getBottom());
        // Overlays sit below the OEM toolbar, which car-ui-lib draws above the content.
        View gallery = findViewById(R.id.gallery_stage);
        gallery.setPadding(insets.getLeft(), insets.getTop(), insets.getRight(),
                insets.getBottom());
        View end = findViewById(R.id.end_card);
        int side = Math.round(96 * getResources().getDisplayMetrics().density);
        end.setPadding(insets.getLeft() + side, insets.getTop(), insets.getRight() + side,
                insets.getBottom());
    }

    /**
     * Starts listening, asking for the microphone first if needed. (Host A can record too, via
     * {@code androidx.car.app.media.CarAudioRecord}.)
     */
    // Package-private so A2uiVoiceGateTest can pin that this method asks the gate.
    @VisibleForTesting
    void startListening() {
        // The vehicle is asked first, before the permission and the recogniser, so the refusal
        // depends on neither.
        CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
                mRestrictions.getRestrictionsSnapshot();
        if (!allowsListening(snapshot.getSource(), snapshot.getRestrictions())) {
            Toast.makeText(this, A2uiActionBinder.REFUSED_NOTE, Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // Said out loud: emulator images often have no recognition service.
            Toast.makeText(this, "No speech recognition service on this device",
                    Toast.LENGTH_LONG).show();
            return;
        }
        stopListening();
        mRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        mRecognizer.setRecognitionListener(new RecognitionListenerAdapter() {
            @Override
            public void onResults(Bundle results) {
                List<String> heard = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                onHeard(heard == null || heard.isEmpty() ? null : heard.get(0));
            }

            @Override
            public void onError(int error) {
                Log.i(TAG, "recognition error " + error);
                Toast.makeText(A2uiCarUiActivity.this, "Did not catch that", Toast.LENGTH_SHORT)
                        .show();
            }
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        mRecognizer.startListening(intent);
        Toast.makeText(this, "Listening\u2026", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_RECORD_AUDIO) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Re-enters through the vehicle gate: permission dialogs can outlive a parked reading.
            startListening();
        } else {
            Toast.makeText(this, "Microphone permission is required for voice input",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Closes the recogniser, posting when called off the main thread: refresh() calls this from
     * the restriction listener, which runs on a binder thread.
     */
    private void stopListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(this::stopListening);
            return;
        }
        if (mRecognizer != null) {
            mRecognizer.destroy();
            mRecognizer = null;
        }
    }

    /**
     * Routes one utterance through the same policy a tap goes through: {@code dispatchByVoice}
     * against the tree on screen. Hands-free removes the reach, not the consequence.
     */
    @VisibleForTesting
    void onHeard(@Nullable String spoken) {
        // Asked here too, not only in startListening(): the debug SPEAK receiver calls this
        // directly, and a result in flight can outlive the reading that authorised the session.
        CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
                mRestrictions.getRestrictionsSnapshot();
        if (!allowsListening(snapshot.getSource(), snapshot.getRestrictions())) {
            Log.i(TAG, "transcript refused: the vehicle forbids transcription");
            Toast.makeText(this, A2uiActionBinder.REFUSED_NOTE, Toast.LENGTH_LONG).show();
            return;
        }
        A2uiMessageProcessor.VoiceDispatchResult result =
                surfaceProcessor().dispatchByVoiceForResult(mSurfaceId,
                        VoicePhrase.canonical(spoken), mRenderedRoot,
                        mActionBinder.reader());
        A2uiMessageProcessor.VoiceOutcome outcome = result.getOutcome();
        Log.i(TAG, "voice outcome: " + outcome);
        if (mShowingAgent && mAgent != null) mAgent.onVoice(mSurfaceId, result);
        switch (outcome) {
            case DISPATCHED:
                // As on tap: a corpus-native handler (the video, a picker) answers for itself.
                if (mShowingAgent || result.getMatchedComponent() == null
                        || DemoNativeContract.resolve(mSurfaceId,
                                result.getMatchedComponent()) == null) {
                    Toast.makeText(this, "\u201c" + spoken + "\u201d \u2014 done",
                            Toast.LENGTH_SHORT).show();
                }
                break;
            case CONFIRMATION_REQUIRED:
                confirmSpoken(spoken, result.getMatchedComponent());
                break;
            case REFUSED_BY_POLICY:
                Toast.makeText(this, A2uiActionBinder.REFUSED_NOTE, Toast.LENGTH_LONG).show();
                break;
            case AMBIGUOUS:
                Toast.makeText(this, "More than one control answers to that",
                        Toast.LENGTH_LONG).show();
                break;
            case NO_MATCH:
            default:
                Toast.makeText(this, "Nothing on screen answers to that", Toast.LENGTH_SHORT)
                        .show();
                break;
        }
    }

    /** A spoken confirmation is answered by touch, and re-decided exactly as a tapped one is. */
    private void confirmSpoken(String spoken, @Nullable A2uiComponent matched) {
        if (matched == null) {
            Toast.makeText(this, "Not available now — conditions changed", Toast.LENGTH_LONG)
                    .show();
            return;
        }
        A2uiDisplayBinder capturedBinder = mActionBinder;
        String capturedSurfaceId = mSurfaceId;
        AlertDialog asked = new AlertDialogBuilder(this)
                .setTitle(confirmTitle(matched))
                .setMessage(confirmMessage(matched))
                .setNegativeButton(R.string.a2ui_cancel, null)
                .setPositiveButton(R.string.a2ui_confirm, (dialog, which) -> {
                    // Asked a third time: a dialog can be answered long after the vehicle changed.
                    if (capturedBinder == null || capturedBinder != mActionBinder
                            || !capturedSurfaceId.equals(mSurfaceId)) {
                        Toast.makeText(this, "Not available now — conditions changed",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
                            mRestrictions.getRestrictionsSnapshot();
                    if (!allowsListening(snapshot.getSource(), snapshot.getRestrictions())) {
                        Toast.makeText(this, A2uiActionBinder.REFUSED_NOTE, Toast.LENGTH_LONG)
                                .show();
                        return;
                    }
                    try {
                        int orders = mOrderSeq;
                        A2uiActionPolicy.Decision decision = capturedBinder.confirm(matched);
                        Log.i(TAG, "spoken confirmation answered; re-decided as " + decision);
                        if (mShowingAgent && mAgent != null) {
                            mAgent.onConfirmed(mSurfaceId, matched, decision);
                        }
                        if (mOrderSeq == orders) {
                            Toast.makeText(this,
                                    decision == A2uiActionPolicy.Decision.REFUSE
                                            ? "Not available now — conditions changed"
                                            : spoken + " — done",
                                    Toast.LENGTH_LONG).show();
                        }
                    } catch (RuntimeException changed) {
                        Log.i(TAG, "spoken confirmation refused because the surface changed");
                        Toast.makeText(this, "Not available now — conditions changed",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
        fitDialog(asked, this);
    }

    private void refresh() {
        CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
                mRestrictions.getRestrictionsSnapshot();
        CarUxRestrictions current = snapshot.getRestrictions();
        CarUxRestrictionsUtil.RestrictionsSource source = snapshot.getSource();

        // Withhold the microphone control when transcription is forbidden, on every restriction
        // change. setEnabled, not setVisible, so car-ui-lib's restricted styling applies.
        boolean mayListen = allowsListening(source, current);
        if (mSpeakItem != null) {
            mSpeakItem.setEnabled(mayListen);
        }
        // Disabling the control does not stop a session already running; close it.
        if (!mayListen) {
            stopListening();
        }
        // The provider chip re-asks and replaces the screen: parked and trusted only, like Speak.
        boolean parked = isParked(source, current);
        if (mProviderItem != null) {
            mProviderItem.setEnabled(parked);
        }
        trackJourney(isAuthoritative(source), parked);
        trackCharging(isAuthoritative(source), parked);
        if (!parked && mEndCard != null && mEndCard.getVisibility() == View.VISIBLE) {
            hideEndCard();
        }

        // Agent surfaces take this exact path: same provenance gate, prune, limits and binder.
        Set<ComponentType> allowed = allowedFor(source, current);
        if (mDemoMedia != null) {
            mDemoMedia.setImagesAllowed(allowed.contains(ComponentType.IMAGE));
        }
        A2uiComponent projected = currentProjection();
        A2uiComponent pruned = projected == null ? null : A2uiPruner.prune(projected, allowed);
        // A confirmed commitment's card replaces the control that made it: less, never more.
        HostJourney journey = currentJourney();
        if (pruned != null && mOrderPlaced && journey != null && journey.mConfirmReplaces != null) {
            pruned = withoutComponent(pruned, journey.mConfirmReplaces);
        }
        if (pruned != null && embeddedBrief() != null) {
            pruned = withoutComponent(pruned, "brief");
        }
        // The charging window: what it offers leads; the comparison follows.
        if (pruned != null && mAtCharger && parked && "ev_charge_coffee".equals(mSurfaceId)) {
            pruned = withChildrenFirst(pruned, "coffee", "tour");
        }
        // Value limits bound the content inside the allowed components.
        A2uiValueLimits limits = limitsFor(source, current);
        if (pruned != null) {
            pruned = A2uiPruner.applyLimits(pruned, limits);
        }
        // Voice may only reach what is on screen: CarUiRenderer.render draws no grandchildren, and
        // host rows take the first places in both capped lists, so the tree is cut to the surface
        // rows still visible after them.
        List<CarUiListItem> rows = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        addHostRows(rows, keys, allowed, limits, parked, pruned);
        int hostRowCount = rows.size();
        int surfaceBudget = surfaceRowBudget(current, rows.size());
        mRenderedRoot = A2uiVisibleTree.visibleRoot(pruned, surfaceBudget);
        configureNativeChrome(mRenderedRoot);
        List<CarUiListItem> rendered = CarUiRenderer.render(pruned,
                new StaleGuard(mActionBinder, this::conditionsChanged));
        if (!mShowingAgent) {
            // Host-native artwork and dialogs are keyed to corpus ids; agent ids never select one.
            decorateNativeRows(pruned, rendered, allowed);
        }
        if (mDemoDialogComponentId != null
                && findRendered(mRenderedRoot, mDemoDialogComponentId) == null) {
            dismissDemoContent();
        }
        // A host-native view can require a capability its opener does not: the parked-video
        // dialog must go under NO_VIDEO though its Button survives, even before onPrepared.
        if (mDemoDialogRequiredType != null && !allowed.contains(mDemoDialogRequiredType)) {
            dismissDemoContent();
        }
        if (mDemoMedia != null
                && DemoMediaController.shouldStopVideo(allowed, mDemoMedia.getMode())) {
            dismissDemoContent();
            mDemoMedia.stopVideo();
        }
        // Video pixels exist only while VIDEO is allowed, whatever opened them.
        if (mVideoView != null && !allowed.contains(ComponentType.VIDEO)) {
            dismissDemoContent();
        }

        // Host cards lead, then the surface's rows, keyed by component id within this binder.
        List<A2uiComponent> drawn = CarUiRenderer.renderableRows(pruned);
        for (int i = 0; i < rendered.size(); i++) {
            String id = i < drawn.size() ? drawn.get(i).getId() : null;
            keys.add(mRowEpoch + ":" + (id == null ? "#" + i : id));
        }
        rows.addAll(rendered);
        String reading = source + ":" + Integer.toHexString(current.getActiveRestrictions())
                + ":" + limits;
        boolean readingChanged = mLastReading != null && !mLastReading.equals(reading);
        mLastReading = reading;
        applyRows(rows, keys, readingChanged);

        // What changed, from the two trees the policy produced and the rows actually drawn. Only
        // across a reading change on the same surface: a case switch is not a restriction change.
        int visibleRows = hostRowCount + Math.min(rendered.size(), Math.max(0, surfaceBudget));
        String surfaceKey = mRowEpoch + ":" + mSurfaceId;
        List<String> hostKeys = new ArrayList<>(keys.subList(0, hostRowCount));
        if (readingChanged && surfaceKey.equals(mLastSurfaceKey) && mLastVisibleRows >= 0) {
            List<String> collapsed = new ArrayList<>();
            for (String key : mLastHostKeys) {
                String label = HostRows.label(key);
                if (!hostKeys.contains(key) && label != null && !collapsed.contains(label)) {
                    collapsed.add(label);
                }
            }
            showChangeStrip(ChangeSummary.between(mLastPrunedTree, pruned,
                    DriverState.kindFor(source, current.getActiveRestrictions()),
                    audioContinues(allowed), this::changeLabel, collapsed).text());
        }
        mLastHostKeys.clear();
        mLastHostKeys.addAll(hostKeys);
        mCards.setVisibleCount(visibleRows);
        mCards.notifyDataSetChanged();
        mLastPrunedTree = pruned;
        mLastVisibleRows = visibleRows;
        mLastSurfaceKey = surfaceKey;
        updateBanner(source, current.getActiveRestrictions());

        // An empty surface after a prune is correct but looks like a crash unless it says so. On a
        // dedicated view, because the counts below are read from the adapters. Host cards do not
        // count.
        if (mEmptyState != null) {
            boolean surfaceShown = Math.min(rendered.size(), surfaceBudget) > 0;
            mEmptyState.setText(A2uiActionBinder.EMPTY_WHILE_DRIVING_NOTE);
            mEmptyState.setVisibility(surfaceShown ? View.GONE : View.VISIBLE);
        }

        // Counts are read from the adapters (getItemCount() is the enforcement), posted because
        // this refresh may run before the limiter's listener inside notifyObservers().
        final int total = mItems.size();
        final boolean comparison = !mShowingAgent && usesComparisonColumns(mSurfaceId);
        mUnlimitedColumn.setVisibility(comparison ? View.VISIBLE : View.GONE);
        mLimitedLabel.setVisibility(comparison ? View.VISIBLE : View.GONE);
        // Visitor surfaces show cards; the car-ui lists keep counting behind them.
        mLimitedColumn.setVisibility(comparison ? View.VISIBLE : View.GONE);
        mCardList.setVisibility(comparison ? View.GONE : View.VISIBLE);
        mDemoPill.setVisibility(!comparison && !mShowingAgent ? View.VISIBLE : View.GONE);
        mVoiceHintWanted = !comparison && mayListen;
        mSurfaceHeadingWanted = !comparison;
        applyHeaderEnd();
        if (!comparison) {
            mSurfaceHeading.setText(themedSurfaceHeading(mSurfaceId));
        }
        updateVisualPanel(pruned, allowed, parked, comparison);
        mLimitedLabel.post(() -> {
            String heading = comparison ? getString(R.string.limited_label)
                    : themedSurfaceHeading(mSurfaceId);
            String count = comparison || mDiagnosticsExpanded
                    ? "\nshowing " + mLimited.getItemCount() + " of " + total : "";
            mLimitedLabel.setText(heading + count);
            mUnlimitedLabel.setText(getString(R.string.unlimited_label)
                    + "\nshowing " + mUnlimited.getItemCount() + " of " + total);
        });

        Integer override = mDemoOverride;
        String status = "display=" + mRestrictions.getDisplayId()
                + "  surface=" + mSurfaceId
                + "  source=" + source
                + "  active=0x" + Integer.toHexString(current.getActiveRestrictions())
                + "  maxItems=" + current.getMaxCumulativeContentItems()
                + "  allowed=" + allowed.size() + "/" + ComponentType.values().length
                + "  rows=" + mItems.size()
                + (override == null ? "  [live]" : "  [DEMO 0x" + Integer.toHexString(override)
                        + "]")
                + (mShowingAgent && mAgent != null ? "  agent=" + mAgent.provider().wireName()
                        : "");
        mStatus.setText(status);
        // The banner carries the vehicle state; this chip carries only the agent lane, and the
        // exact provenance stays in its description and the diagnostics line.
        String compact = compactStatus(source, current.getActiveRestrictions());
        mStatusBadge.setText(mAgentBadge == null ? compact : mAgentBadge);
        mStatusBadge.setVisibility(mDiagnosticsExpanded ? View.VISIBLE : View.GONE);
        mStatusBadge.setContentDescription((mAgentBadge == null ? "" : mAgentBadge + " · ")
                + compact + ". " + (mDiagnosticsExpanded ? "Hide" : "Show")
                + " technical details.");
        Log.i(TAG, status);
        mLimitedLabel.post(() -> Log.i(TAG, "enforced: limited=" + mLimited.getItemCount()
                + " unlimited=" + mUnlimited.getItemCount() + " of " + mItems.size()));
    }

    private static final long CROSSFADE_MS = 180;

    /**
     * Publishes rows to both adapters. Within one reading a keyed diff animates only what changed.
     * Across a reading change everything swaps at once, so a withheld row never lingers fading out,
     * and the new content crossfades in. Not ListAdapter: its asynchronous diff would leave
     * forbidden rows on screen after a restriction arrived.
     */
    private void applyRows(@NonNull List<CarUiListItem> rows, @NonNull List<String> keys,
            boolean readingChanged) {
        if (readingChanged) {
            // A row removed just before the change must not linger, faint, into the new reading.
            if (mLimitedAnimator != null) mLimitedAnimator.endAnimations();
            if (mUnlimitedAnimator != null) mUnlimitedAnimator.endAnimations();
        }
        int message = mLimited.getScrollingLimitedMessageViewType();
        boolean limitedWasExact = HostRows.exposesAll(mLimited, mItems.size(), message);
        boolean unlimitedWasExact = HostRows.exposesAll(mUnlimited, mItems.size(), message);
        List<String> oldKeys = new ArrayList<>(mItemKeys);
        List<String> oldSignatures = new ArrayList<>(mItemSignatures);

        // Same list object behind both adapters: the two columns differ ONLY in whether the
        // adapter was wrapped.
        mItems.clear();
        mItems.addAll(rows);
        mItemKeys.clear();
        mItemKeys.addAll(keys);
        mItemSignatures.clear();
        for (CarUiListItem row : rows) {
            mItemSignatures.add(HostRows.signature(row));
        }
        // Recompute the wrapper's positional range before either adapter announces the mutation,
        // or a 9 -> 2 re-projection crashes in getItemViewType on FULLY_RESTRICTED.
        mLimited.updateUnderlyingDataChanged(mItems.size(), 0);

        boolean animate = animationsEnabled();
        applyItemAnimators(animate);
        if (readingChanged || !animate) {
            mLimitedDelegate.notifyDataSetChanged();
            mUnlimited.notifyDataSetChanged();
            if (readingChanged) {
                crossfade(mContentColumns, animate);
            }
            return;
        }
        // A diff is dispatched only where positions are one to one; a capped or "limited" list
        // takes the full refresh it always had.
        DiffUtil.DiffResult diff = HostRows.diff(oldKeys, oldSignatures, mItemKeys,
                mItemSignatures);
        if (limitedWasExact && HostRows.exposesAll(mLimited, mItems.size(), message)) {
            diff.dispatchUpdatesTo(mLimited);
        } else {
            mLimitedDelegate.notifyDataSetChanged();
        }
        if (unlimitedWasExact && HostRows.exposesAll(mUnlimited, mItems.size(), message)) {
            diff.dispatchUpdatesTo(mUnlimited);
        } else {
            mUnlimited.notifyDataSetChanged();
        }
    }

    /** The gate sets the animator scale to 0; so may a driver. Read every time, never cached. */
    private boolean animationsEnabled() {
        return Settings.Global.getFloat(getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    private void applyItemAnimators(boolean animate) {
        if (mAnimatorsApplied && (mLimitedAnimator != null) == animate) {
            return;
        }
        mAnimatorsApplied = true;
        mLimitedAnimator = animate ? itemAnimator() : null;
        mUnlimitedAnimator = animate ? itemAnimator() : null;
        mLimitedList.setItemAnimator(mLimitedAnimator);
        mUnlimitedList.setItemAnimator(mUnlimitedAnimator);
    }

    /**
     * Surface rows the capped lists still show once host rows take the first places. The limiter's
     * own rule, as uxr_config.xml sets no per-list override: a cap only under LIMIT_CONTENT, and a
     * negative one is none.
     */
    @VisibleForTesting
    static int surfaceRowBudget(@NonNull CarUxRestrictions reading, int hostRows) {
        int max = reading.getMaxCumulativeContentItems();
        if ((reading.getActiveRestrictions()
                & A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_CONTENT) == 0 || max < 0) {
            return Integer.MAX_VALUE;
        }
        return max - hostRows;
    }

    @NonNull
    private static RecyclerView.ItemAnimator itemAnimator() {
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setAddDuration(CROSSFADE_MS);
        animator.setRemoveDuration(CROSSFADE_MS / 2);
        animator.setMoveDuration(CROSSFADE_MS);
        animator.setChangeDuration(CROSSFADE_MS);
        return animator;
    }

    /** Fades new content in; the old is already gone, so nothing withheld is ever faded out. */
    private static void crossfade(@Nullable View content, boolean animate) {
        if (content == null) {
            return;
        }
        content.animate().cancel();
        if (!animate) {
            content.setAlpha(1f);
            return;
        }
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(CROSSFADE_MS).start();
    }

    /** How long the "what changed" line stays up. */
    @VisibleForTesting
    static final long CHANGE_STRIP_MS = 3500;

    /** The banner from the reading the prune used; a change of state pulses once. */
    private void updateBanner(@NonNull CarUxRestrictionsUtil.RestrictionsSource source,
            int active) {
        DriverState.Kind kind = DriverState.kindFor(source, active);
        CharSequence title = getText(DriverState.titleRes(kind));
        CharSequence detail = getText(DriverState.detailRes(source, active));
        mBannerTitle.setText(title);
        mBannerDetail.setText(detail);
        mBanner.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(DriverState.colorRes(kind))));
        mBanner.setContentDescription(title + ". " + detail + ".");
        if (mBannerKind != null && mBannerKind != kind && animationsEnabled()) {
            mBanner.animate().cancel();
            mBanner.setScaleX(1.06f);
            mBanner.setScaleY(1.06f);
            mBanner.animate().scaleX(1f).scaleY(1f).setDuration(260).start();
        }
        mBannerKind = kind;
    }

    /**
     * Shows the line for {@link #CHANGE_STRIP_MS}, in the header band where the voice hint and
     * heading were, so it never covers a card. Non-interactive; instant at animator scale 0.
     */
    private void showChangeStrip(@NonNull String text) {
        mStripHandler.removeCallbacks(mHideChangeStrip);
        mChangeStrip.animate().cancel();
        mChangeStrip.setText(text);
        mChangeStrip.setVisibility(View.VISIBLE);
        mStripShowing = true;
        applyHeaderEnd();
        if (animationsEnabled()) {
            mChangeStrip.setAlpha(0f);
            mChangeStrip.animate().alpha(1f).setDuration(220).start();
        } else {
            mChangeStrip.setAlpha(1f);
        }
        mStripHandler.postDelayed(mHideChangeStrip, CHANGE_STRIP_MS);
        Log.i(TAG, "changed: " + text);
    }

    private void hideChangeStrip() {
        mChangeStrip.animate().cancel();
        if (animationsEnabled()) {
            mChangeStrip.animate().alpha(0f).setDuration(300)
                    .withEndAction(this::changeStripGone).start();
        } else {
            changeStripGone();
        }
    }

    private void changeStripGone() {
        mChangeStrip.setVisibility(View.GONE);
        mStripShowing = false;
        applyHeaderEnd();
    }

    /** The voice hint and heading, unless the change line holds their place in the header. */
    private void applyHeaderEnd() {
        mVoiceHint.setVisibility(mVoiceHintWanted && !mStripShowing ? View.VISIBLE : View.GONE);
        mSurfaceHeading.setVisibility(!mSurfaceHeadingWanted ? View.GONE
                : mStripShowing ? View.INVISIBLE : View.VISIBLE);
    }

    /** Audio is said to continue only if it is playing and the new reading still allows it. */
    private boolean audioContinues(@NonNull Set<ComponentType> allowed) {
        if (!allowed.contains(ComponentType.AUDIO_PLAYER)) return false;
        PassengerMedia passenger = PassengerMedia.forName(PassengerDisplay.playing());
        return (passenger != null && passenger.mCabinAudio) || (mDemoMedia != null
                && mDemoMedia.getMode() == DemoMediaController.Mode.AUDIO
                && mDemoMedia.isPlaying());
    }

    /** Host-owned nouns for exact corpus components; agent surfaces fall back to type nouns. */
    @Nullable
    private String changeLabel(@NonNull A2uiComponent component) {
        return mShowingAgent ? null : DemoNativeContract.changeLabel(mSurfaceId, component);
    }

    @VisibleForTesting
    @NonNull
    TextView changeStripForTesting() {
        return mChangeStrip;
    }

    @VisibleForTesting
    @NonNull
    TextView bannerTitleForTesting() {
        return mBannerTitle;
    }

    @VisibleForTesting
    @NonNull
    TextView bannerDetailForTesting() {
        return mBannerDetail;
    }

    /** Thinking, arrival and pickup, ahead of the surface's rows. Glanceable text, no actions. */
    private void addHostRows(@NonNull List<CarUiListItem> rows, @NonNull List<String> keys,
            @NonNull Set<ComponentType> allowed, @NonNull A2uiValueLimits limits,
            boolean parked, @Nullable A2uiComponent pruned) {
        if (mAgentThinking && mAgent != null) {
            addRow(rows, keys, HostRows.THINKING, HostRows.card(allowed, limits,
                    getString(R.string.a2ui_brand_thinking_title),
                    Collections.singletonList(getString(R.string.a2ui_brand_thinking_body,
                            providerLabel(mAgent.provider()))),
                    null, getDrawable(R.drawable.a2ui_ic_thinking)));
        }
        HostJourney journey = currentJourney();
        if (journey == null) {
            return;
        }
        // Arrival leads, then what plays now, then commitments, then the long brief.
        if (mArrived && parked) {
            addRow(rows, keys, HostRows.ARRIVAL, HostRows.card(allowed, limits,
                    journeyData(journey, journey.mArrivalTitle),
                    Collections.singletonList(journeyData(journey, journey.mArrivalDetail)),
                    // With a hero panel the image is there; the row stays text.
                    journey.mHeroArt != 0 || journey.mArrivalArt == 0 ? null
                            : getDrawable(journey.mArrivalArt), null));
        }
        addGuideRow(rows, keys, allowed, limits);
        addChargingRows(rows, keys, allowed, limits, parked, pruned);
        if (mOrderPlaced && (!journey.mConfirmParkedOnly || parked)) {
            String title = journeyData(journey, journey.mConfirmTitle);
            List<String> body = new ArrayList<>();
            for (String pointer : journey.mConfirmBody) body.add(journeyData(journey, pointer));
            // The real café's name only for the charger it was picked for. Its one line says the
            // order was not sent, so the trace's note would only repeat it.
            CoffeeFixture.Pick coffee = journey == HostJourney.forSurface("ev_charge_coffee")
                    ? coffeeNearCharger() : null;
            if (coffee != null && !body.isEmpty()) {
                title = coffee.pickupTitle();
                body.clear();
                body.add(coffee.pickupDetail());
            }
            addRow(rows, keys, HostRows.ORDER, HostRows.card(allowed, limits, title, body,
                    null, getDrawable(journey.mConfirmGlyph)));
        }
        if (parked) addCoffeeWhyRow(rows, keys, allowed, limits);
        // The brief is detail: parked only, like the narration says.
        if (parked) addAgentBriefRow(rows, keys, allowed, limits);
    }

    /** The state of charge in force: a DEMO broadcast while set, else the vehicle's. */
    @Nullable
    private Integer batteryPct() {
        return mDemoBatteryPct != null ? mDemoBatteryPct : mVehicleBatteryPct;
    }

    /** True when a reading exists and is at or below {@link #LOW_BATTERY_PCT}. */
    @VisibleForTesting
    static boolean lowBattery(@Nullable Integer pct) {
        return pct != null && pct >= 0 && pct <= LOW_BATTERY_PCT;
    }

    @VisibleForTesting
    void onVehicleBattery(int pct) {
        Integer previous = mVehicleBatteryPct;
        mVehicleBatteryPct = pct;
        if (previous == null || previous != pct) {
            Log.i(TAG, "battery from the vehicle: " + pct + "%");
            if (mRestrictions != null) refresh();
        }
    }

    /** Drive after choosing a charger, then park: the charging window opens. */
    private void trackCharging(boolean authoritative, boolean parked) {
        if (authoritative && !parked) {
            if (mChosenCharger >= 0) mDroveToCharger = true;
            mAtCharger = false;
        } else if (parked && mDroveToCharger) {
            mDroveToCharger = false;
            mAtCharger = true;
            mPromptSpent = true;
            mChargeStartPct = batteryPct();
        } else if (!authoritative) {
            mAtCharger = false;
        }
    }

    /**
     * The vehicle's own prompt (low battery, while not charging) and the parked charging session.
     * Glanceable host text; the comparison itself is the surface's rows.
     */
    private void addChargingRows(@NonNull List<CarUiListItem> rows, @NonNull List<String> keys,
            @NonNull Set<ComponentType> allowed, @NonNull A2uiValueLimits limits, boolean parked,
            @Nullable A2uiComponent pruned) {
        if (!"ev_charge_coffee".equals(mSurfaceId)) return;
        Integer pct = batteryPct();
        if (mAtCharger && parked) {
            // Only what is known: the station and the vehicle's reading. No charge rate, so no
            // target or time to full.
            String station = chargerName(mChosenCharger);
            Integer now = pct != null ? pct : mChargeStartPct;
            if (station != null) {
                addRow(rows, keys, HostRows.SESSION, HostRows.card(allowed, limits,
                        now == null ? getString(R.string.a2ui_ev_session_title_no_reading, station)
                                : getString(R.string.a2ui_ev_session_title, station, now),
                        Collections.emptyList(), null, getDrawable(R.drawable.a2ui_ic_battery)));
            }
            return;
        }
        if (!lowBattery(pct)) {
            mPromptSpent = false;
            return;
        }
        if (mPromptSpent) return;
        int chargers = 0;
        for (String id : new String[]{"c1", "c2", "c3"}) {
            if (findRendered(pruned, id) != null) chargers++;
        }
        String line = mChosenCharger >= 0
                ? getString(R.string.a2ui_ev_navigating, chargerName(mChosenCharger))
                : getString(R.string.a2ui_ev_from_vehicle);
        addRow(rows, keys, HostRows.SUGGEST, HostRows.card(allowed, limits,
                getResources().getQuantityString(R.plurals.a2ui_ev_suggestion, chargers, pct,
                        chargers),
                Collections.singletonList(line + " · " + getString(
                        R.string.a2ui_ev_chargers_credit)), null,
                getDrawable(R.drawable.a2ui_ic_battery)));
    }

    @Nullable
    private String chargerName(int index) {
        if (index < 0) return null;
        return journeyData(HostJourney.forSurface("ev_charge_coffee"),
                "/chargers/" + index + "/name");
    }

    /** The bundled café when the chosen charger is the one its walk was measured from. */
    @Nullable
    private CoffeeFixture.Pick coffeeNearCharger() {
        return mCoffee != null && mCoffee.isNear(chargerName(mChosenCharger)) ? mCoffee : null;
    }

    /**
     * Why Muse picked the café, with its source: parked at that charger only, a glanceable card
     * under the same TEXT gate, clamp and content cap as every host row.
     */
    private void addCoffeeWhyRow(@NonNull List<CarUiListItem> rows, @NonNull List<String> keys,
            @NonNull Set<ComponentType> allowed, @NonNull A2uiValueLimits limits) {
        if (mShowingAgent || !"ev_charge_coffee".equals(mSurfaceId) || !mAtCharger) return;
        CoffeeFixture.Pick coffee = coffeeNearCharger();
        if (coffee == null) return;
        addRow(rows, keys, HostRows.WHY, HostRows.card(allowed, limits, coffee.whyTitle(),
                coffee.whyBody(), null, getDrawable(R.drawable.a2ui_ic_pickup)));
    }

    static final long ARRIVAL_ARTWORK_DELAY_MS = 1200;

    private boolean parkedNow() {
        CarUxRestrictionsUtil.RestrictionsSnapshot s = mRestrictions.getRestrictionsSnapshot();
        return isParked(s.getSource(), s.getRestrictions());
    }

    /** A copy of {@code tree} whose root children named in {@code first} lead, in that order. */
    @NonNull
    static A2uiComponent withChildrenFirst(@NonNull A2uiComponent tree, @NonNull String... first) {
        A2uiComponent.Builder copy = new A2uiComponent.Builder(tree.getType())
                .copyPropertiesFrom(tree);
        List<A2uiComponent> rest = new ArrayList<>(tree.getChildren());
        for (String id : first) {
            for (A2uiComponent child : tree.getChildren()) {
                if (id.equals(child.getId())) {
                    copy.addChild(child);
                    rest.remove(child);
                }
            }
        }
        for (A2uiComponent child : rest) copy.addChild(child);
        return copy.build();
    }

    /** The lane's surface shown as this screen's brief, or null. */
    @Nullable
    private String embeddedBrief() {
        return mEmbeddedAgentSurface != null && mAgent != null && !mShowingAgent
                && DemoNativeContract.embedsAgentBrief(mSurfaceId) ? mEmbeddedAgentSurface : null;
    }

    /**
     * The agent's brief as a glanceable card: its tree pruned and limited for this reading like
     * any surface, then read as text. No control from it is reachable.
     */
    private void addAgentBriefRow(@NonNull List<CarUiListItem> rows, @NonNull List<String> keys,
            @NonNull Set<ComponentType> allowed, @NonNull A2uiValueLimits limits) {
        String surfaceId = embeddedBrief();
        if (surfaceId == null) return;
        A2uiComponent tree;
        try {
            A2uiSurface surface = mAgent.processor().getSurface(surfaceId);
            tree = surface == null ? null : surface.project(DemoTrace.ROOT_ID);
        } catch (RuntimeException unprojectable) {
            tree = null;
        }
        A2uiComponent pruned = tree == null ? null : A2uiPruner.prune(tree, allowed);
        if (pruned != null) pruned = A2uiPruner.applyLimits(pruned, limits);
        if (pruned == null) return;
        List<String> lines = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        collectBriefLines(pruned, true, lines, sources);
        if (lines.isEmpty()) return;
        String title = lines.remove(0);
        List<String> body = new ArrayList<>(lines.subList(0, Math.min(1, lines.size())));
        body.addAll(sources.subList(0, Math.min(2, sources.size())));
        String thumb = DemoNativeContract.thumbnailFor(mSurfaceId,
                new A2uiComponent.Builder(ComponentType.CARD).setId("brief").build());
        int art = thumb == null || !parkedNow() ? 0 : drawableNamed(thumb, 0);
        addRow(rows, keys, HostRows.AGENT_BRIEF, HostRows.card(allowed, limits, title, body,
                art == 0 ? null : getDrawable(art), getDrawable(R.drawable.a2ui_ic_film)));
    }

    private static void collectBriefLines(@NonNull A2uiComponent node, boolean root,
            @NonNull List<String> lines, @NonNull List<String> sources) {
        String title = node.getTitle();
        if (title != null && !title.trim().isEmpty() && !(root && node.hasChildren())) {
            if (title.startsWith("Source")) {
                // "Source · host" alone: the page title is the link's business, not the card's.
                String[] parts = title.split(" · ");
                String source = parts.length >= 2 ? parts[0] + " · " + parts[1] : title;
                if (!sources.contains(source)) sources.add(source);
            } else {
                lines.add(node.getSubtitle() == null ? title : title + " · " + node.getSubtitle());
            }
        } else if (root && title != null && !title.trim().isEmpty()) {
            lines.add(title);
        }
        for (A2uiComponent child : node.getChildren()) {
            collectBriefLines(child, false, lines, sources);
        }
    }

    /** Registered row art: a thumbnail while IMAGE is allowed, else a glyph while ICON is. */
    private boolean applyRowArt(@NonNull CarUiContentListItem row,
            @NonNull A2uiComponent component, @NonNull Set<ComponentType> allowed) {
        String thumb = DemoNativeContract.thumbnailFor(mSurfaceId, component);
        int art = thumb == null ? 0 : drawableNamed(thumb, 0);
        // Artwork thumbnails are parked-only on top of IMAGE: no image to a moving driver.
        if (art != 0 && allowed.contains(ComponentType.IMAGE) && parkedNow()) {
            row.setIcon(getDrawable(art));
            row.setPrimaryIconType(CarUiContentListItem.IconType.CONTENT);
            return true;
        }
        int glyph = DemoNativeContract.glyphFor(mSurfaceId, component);
        if (glyph != 0 && allowed.contains(ComponentType.ICON)) {
            row.setIcon(getDrawable(glyph));
            row.setPrimaryIconType(CarUiContentListItem.IconType.STANDARD);
            return true;
        }
        return false;
    }

    /** Demo stories ask Muse unless something else was chosen on this lane. */
    private void defaultToMuse() {
        if (mAgent == null || mAgent.provider() == AgentProvider.MUSE) return;
        mAgent.selectProvider(AgentProvider.MUSE);
        if (mProviderItem != null) mProviderItem.setTitle(providerLabel(AgentProvider.MUSE));
    }

    /**
     * While the passenger display plays the Met artwork, the driver gets text only: which work
     * the audio guide is on. Glanceable, no image, no action; audio is allowed in motion.
     */
    private void addGuideRow(@NonNull List<CarUiListItem> rows, @NonNull List<String> keys,
            @NonNull Set<ComponentType> allowed, @NonNull A2uiValueLimits limits) {
        String playing = PassengerDisplay.playing();
        int segment = PassengerDisplay.segment();
        if (!"met_visit".equals(mSurfaceId) || segment < 0
                || !(PassengerMedia.ARTWORK.equals(playing)
                        || PassengerMedia.SLIDESHOW.equals(playing))) {
            return;
        }
        int index = Math.min(segment, MetArtworks.WORKS.size() - 1);
        addRow(rows, keys, HostRows.GUIDE, HostRows.card(allowed, limits,
                getString(R.string.a2ui_met_guide_title),
                Collections.singletonList(getString(R.string.a2ui_met_guide_body,
                        MetArtworks.WORKS.get(index).mTitle, index + 1,
                        MetArtworks.WORKS.size())),
                null, getDrawable(R.drawable.a2ui_ic_guide)));
    }

    private static void addRow(@NonNull List<CarUiListItem> rows, @NonNull List<String> keys,
            @NonNull String key, @Nullable CarUiListItem row) {
        if (row != null) {
            rows.add(row);
            keys.add(key);
        }
    }

    /** A copy of {@code tree} without the component {@code id} and its subtree. */
    @NonNull
    static A2uiComponent withoutComponent(@NonNull A2uiComponent tree, @NonNull String id) {
        A2uiComponent.Builder copy = new A2uiComponent.Builder(tree.getType())
                .copyPropertiesFrom(tree);
        for (A2uiComponent child : tree.getChildren()) {
            if (!id.equals(child.getId())) copy.addChild(withoutComponent(child, id));
        }
        return copy.build();
    }

    /**
     * The confirmation's question, from the action: a host-owned sentence for exact corpus
     * controls, else the control's own title as a question. Never a bare "Confirm this action?".
     */
    @VisibleForTesting
    @NonNull
    String confirmTitle(@NonNull A2uiComponent component) {
        CoffeeFixture.Pick coffee = coffeeFor(component);
        if (coffee != null) return coffee.confirmTitle();
        String exact = mShowingAgent ? null : DemoNativeContract.confirmPrompt(mSurfaceId,
                component);
        if (exact != null) return exact;
        String title = component.getTitle() == null ? "" : component.getTitle().trim();
        return title.isEmpty() ? getString(R.string.a2ui_confirm) + "?"
                : (title.endsWith("?") ? title : title + "?");
    }

    @Nullable
    private String confirmMessage(@NonNull A2uiComponent component) {
        CoffeeFixture.Pick coffee = coffeeFor(component);
        if (coffee != null) return coffee.confirmDetail();
        String exact = mShowingAgent ? null : DemoNativeContract.confirmDetail(mSurfaceId,
                component);
        return exact != null ? exact : component.getSubtitle();
    }

    /** The café for the corpus coffee order, near the chosen charger; else null. */
    @Nullable
    private CoffeeFixture.Pick coffeeFor(@NonNull A2uiComponent component) {
        return !mShowingAgent && DemoNativeContract.isCoffeeOrder(mSurfaceId, component)
                ? coffeeNearCharger() : null;
    }

    /** Hides any card the list cannot show whole. */
    private void hidePartialCards() {
        if (mCardList == null) return;
        int limit = mCardList.getHeight() - mCardList.getPaddingBottom();
        for (int i = 0; i < mCardList.getChildCount(); i++) {
            View child = mCardList.getChildAt(i);
            child.setVisibility(child.getBottom() <= limit ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** The OEM toolbar's title view, found by its text, gets the same long-press. */
    private void attachTitleLongPress(@NonNull View.OnLongClickListener details) {
        java.util.ArrayList<View> found = new java.util.ArrayList<>();
        getWindow().getDecorView().findViewsWithText(found, getTitle(),
                View.FIND_VIEWS_WITH_TEXT);
        for (View view : found) {
            if (view instanceof TextView && view != mSurfaceHeading) {
                view.setOnLongClickListener(details);
            }
        }
    }

    @VisibleForTesting
    @NonNull
    RecyclerView cardListForTesting() {
        return mCardList;
    }

    @VisibleForTesting
    @NonNull
    View demoPillForTesting() {
        return mDemoPill;
    }

    /** The host journey of the corpus surface on screen, or null (agent surfaces have none). */
    @Nullable
    private HostJourney currentJourney() {
        return mShowingAgent ? null : HostJourney.forSurface(mSurfaceId);
    }

    /** A string from the journey trace's data model, or null. */
    @Nullable
    private String journeyData(@Nullable HostJourney journey, @Nullable String pointer) {
        if (journey == null || pointer == null) return null;
        A2uiSurface surface = mProcessor.getSurface(journey.mDataSurface);
        try {
            Object value = surface == null ? null : surface.readDataModel(pointer);
            return value instanceof String ? (String) value : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * Park, drive, park again: the earcons and the arrival moment. Only an authoritative reading
     * counts as either; an untrusted one ends the arrival and is never "parked".
     */
    private void trackJourney(boolean authoritative, boolean parked) {
        boolean journey = currentJourney() != null;
        if (authoritative && !parked) {
            if (Boolean.TRUE.equals(mWasParked) && journey) {
                earcon(DemoAudio.FOCUS);
            }
            mDroveWithOrder |= mOrderPlaced;
            mArrived = false;
        } else if (parked && mDroveWithOrder) {
            mDroveWithOrder = false;
            mArrived = true;
            if (journey) {
                earcon(DemoAudio.ARRIVAL);
            }
            if ("met_visit".equals(mSurfaceId) && !mShowingAgent) {
                // Parked at the door: the artwork, under the same checks as a tap.
                mStripHandler.postDelayed(() -> {
                    if (mArrived && "met_visit".equals(mSurfaceId)) showGallery("gallery");
                }, ARRIVAL_ARTWORK_DELAY_MS);
            }
        } else if (!authoritative) {
            mArrived = false;
        }
        mWasParked = authoritative ? parked : null;
    }

    /** Parked means a trusted reading with no restriction at all. */
    @VisibleForTesting
    static boolean isParked(@NonNull CarUxRestrictionsUtil.RestrictionsSource source,
            @NonNull CarUxRestrictions restrictions) {
        return isAuthoritative(source) && restrictions.getActiveRestrictions()
                == A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE;
    }

    /** A confirmed DEMO purchase becomes the pickup card; the card, not a toast, answers it. */
    private void placeOrder() {
        mOrderSeq++;
        mOrderPlaced = true;
        mDroveWithOrder = false;
        mArrived = false;
        earcon(DemoAudio.CONFIRMED);
        refresh();
    }

    /** A case chosen on the bus: the welcome earcon entering Fifth House, then its own title. */
    private void announce(@Nullable String fromSurface, @NonNull String caseId) {
        if (DemoTrace.SURFACE_ID.equals(caseId)) {
            return;
        }
        HostJourney entering = HostJourney.forSurface(caseId);
        if (entering != null && (fromSurface == null
                || HostJourney.forSurface(fromSurface) != entering)) {
            earcon(DemoAudio.WELCOME);
        }
        A2uiComponent root = currentProjection();
        speak(root == null ? null : root.getTitle());
    }

    /** Sound only between onStart and onStop; the debug bus and agent callbacks outlive that. */
    private void earcon(@NonNull String effectId) {
        if (mStarted && mAudio != null) mAudio.earcon(effectId);
    }

    private void speak(@Nullable CharSequence text) {
        if (mStarted && mAudio != null) mAudio.speak(text);
    }

    /** A turn the lane will never answer (switch, close, unsendable) takes its row and badge. */
    private void dropPendingTurn() {
        if (mAgentThinking) {
            mAgentThinking = false;
            mAgentBadge = null;
        }
    }

    /** Keeps the teaching comparison on the base showcase without squeezing visitor workflows. */
    @VisibleForTesting
    static boolean usesComparisonColumns(@NonNull String surfaceId) {
        return DemoTrace.SURFACE_ID.equals(surfaceId);
    }

    @VisibleForTesting
    @NonNull
    static String surfaceHeading(@NonNull String surfaceId) {
        if ("poi_discovery_order".equals(surfaceId)) {
            return "FIFTH HOUSE · PICKUP";
        }
        if ("big_buck_bunny".equals(surfaceId)) {
            return "FIFTH HOUSE · CITY NOTE";
        }
        if ("met_visit".equals(surfaceId)) {
            return "THE MET · THURSDAY";
        }
        if ("ev_charge_coffee".equals(surfaceId)) {
            return "CHARGE · COFFEE";
        }
        return "A2UI · " + surfaceId.replace('_', ' ').toUpperCase(java.util.Locale.US);
    }

    @NonNull
    private String themedSurfaceHeading(@NonNull String surfaceId) {
        if ("poi_discovery_order".equals(surfaceId)) {
            return getString(R.string.a2ui_brand_pickup_heading);
        }
        if ("big_buck_bunny".equals(surfaceId)) {
            return getString(R.string.a2ui_brand_city_note_heading);
        }
        if ("met_visit".equals(surfaceId)) {
            return getString(R.string.a2ui_met_heading);
        }
        if ("ev_charge_coffee".equals(surfaceId)) {
            return getString(R.string.a2ui_ev_heading);
        }
        return surfaceHeading(surfaceId);
    }

    @VisibleForTesting
    @NonNull
    static String toolbarTitleFor(@NonNull String surfaceId, @NonNull CharSequence defaultTitle) {
        return "poi_discovery_order".equals(surfaceId) || "big_buck_bunny".equals(surfaceId)
                ? "Fifth House · NYC Concierge" : defaultTitle.toString();
    }

    @NonNull
    private String themedToolbarTitle(@NonNull String surfaceId, @NonNull CharSequence defaultTitle) {
        if ("met_visit".equals(surfaceId)) {
            return getString(R.string.a2ui_met_toolbar_title);
        }
        if ("ev_charge_coffee".equals(surfaceId)) {
            return getString(R.string.a2ui_ev_toolbar_title);
        }
        return "poi_discovery_order".equals(surfaceId) || "big_buck_bunny".equals(surfaceId)
                ? getString(R.string.a2ui_brand_toolbar_title) : defaultTitle.toString();
    }

    /** Human-facing provenance summary; exact engineering values stay in the expandable strip. */
    @VisibleForTesting
    @NonNull
    static String compactStatus(@NonNull CarUxRestrictionsUtil.RestrictionsSource source,
            int activeRestrictions) {
        if (source == CarUxRestrictionsUtil.RestrictionsSource.NEVER_DELIVERED) {
            return "SAFE MODE · WAITING";
        }
        if (source == CarUxRestrictionsUtil.RestrictionsSource.DISCONNECTED) {
            return "SAFE MODE · OFFLINE";
        }
        String prefix = source == CarUxRestrictionsUtil.RestrictionsSource.DEMO ? "DEMO" : "LIVE";
        if (activeRestrictions == A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE) {
            return prefix + " · AVAILABLE";
        }
        if (activeRestrictions == A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO) {
            return prefix + " · NO VIDEO";
        }
        if (activeRestrictions == A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_CONTENT) {
            return prefix + " · LIMITED";
        }
        if (activeRestrictions == A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_SETUP) {
            return prefix + " · NO SETUP";
        }
        if (activeRestrictions == A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION) {
            return prefix + " · NO VOICE";
        }
        if (activeRestrictions == A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED) {
            return prefix + " · FULLY RESTRICTED";
        }
        return prefix + " · RESTRICTED";
    }

    private void setDiagnosticsExpanded(boolean expanded) {
        mDiagnosticsExpanded = expanded;
        if (mProviderItem != null) mProviderItem.setVisible(expanded);
        if (mConsoleItem != null) mConsoleItem.setVisible(expanded);
        if (mStatus != null) {
            mStatus.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (mStatusBadge != null) {
            CharSequence current = mStatusBadge.getText();
            mStatusBadge.setContentDescription(current + ". "
                    + (expanded ? "Hide" : "Show") + " technical details.");
        }
        if (mRestrictions != null) {
            refresh();
        }
    }

    /**
     * Uses OEM-native toolbar tabs/search only when the corresponding component survived policy.
     * Labels and destinations are host-owned; agent text never becomes a navigation target.
     */
    private void configureNativeChrome(@Nullable A2uiComponent rendered) {
        if (mToolbar == null) {
            return;
        }
        mToolbar.setTitle(themedToolbarTitle(mSurfaceId, getTitle()));
        NativeChromePlan plan = currentChromePlan(rendered);
        if (plan.mShowBrowseTabs) {
            List<Tab> nativeTabs = new ArrayList<>();
            for (String label : new String[]{"Albums", "Artists", "Playlists"}) {
                nativeTabs.add(Tab.builder().setText(label)
                        .setSelectedListener(ignored -> invokeRendered("tabs", null)).build());
            }
            mToolbar.setTabs(nativeTabs, 0);
        } else {
            mToolbar.setTabs(Collections.emptyList());
        }

        if (mSearchItem != null) {
            mSearchItem.setVisible(plan.mShowSearch);
        }
        if (plan.mShowSearch) {
            mToolbar.setSearchHint(plan.mSearchHint);
        } else {
            mToolbar.setSearchMode(SearchMode.DISABLED);
            mPendingSearchQuery = null;
        }
    }

    /** Enters editing only from the explicit OEM search affordance and after a current-tree check. */
    private void beginNativeSearch() {
        NativeChromePlan current = currentChromePlan(mRenderedRoot);
        if (!current.mShowSearch) {
            mToolbar.setSearchMode(SearchMode.DISABLED);
            Toast.makeText(this, "Search is not available now", Toast.LENGTH_LONG).show();
            return;
        }
        mPendingSearchQuery = null;
        mToolbar.setSearchHint(current.mSearchHint);
        mToolbar.setSearchMode(SearchMode.SEARCH);
    }

    /** Immutable, testable chrome decision; applying it remains an Activity/main-thread concern. */
    @VisibleForTesting
    static final class NativeChromePlan {
        final boolean mShowBrowseTabs;
        final boolean mShowSearch;
        @Nullable final String mSearchHint;

        NativeChromePlan(boolean showBrowseTabs, boolean showSearch,
                @Nullable String searchHint) {
            mShowBrowseTabs = showBrowseTabs;
            mShowSearch = showSearch;
            mSearchHint = searchHint;
        }
    }

    /** Native chrome is keyed to exact corpus cases; an agent surface never earns tabs or search. */
    @NonNull
    private NativeChromePlan currentChromePlan(@Nullable A2uiComponent rendered) {
        return mShowingAgent ? new NativeChromePlan(false, false, null)
                : nativeChromeFor(mSurfaceId, rendered);
    }

    /** Plans chrome from exact host-owned cases and the tree that survived the current UXR. */
    @VisibleForTesting
    @NonNull
    static NativeChromePlan nativeChromeFor(@NonNull String surfaceId,
            @Nullable A2uiComponent rendered) {
        A2uiComponent tabs = findRendered(rendered, "tabs");
        boolean browseTabs = "browse_library".equals(surfaceId) && tabs != null
                && tabs.getType() == ComponentType.TABS;
        A2uiComponent field = findRendered(rendered, "field");
        boolean searchCase = "search_and_filter".equals(surfaceId)
                || "poi_discovery_order".equals(surfaceId)
                || "big_buck_bunny".equals(surfaceId);
        boolean search = searchCase && field != null
                && field.getType() == ComponentType.TEXT_FIELD;
        return new NativeChromePlan(browseTabs, search,
                search ? ("poi_discovery_order".equals(surfaceId)
                        ? "Find a place along the route" : "Search media") : null);
    }

    /** Stores no transcript/query in logs and dispatches only after the toolbar reports completion. */
    private void dispatchPendingSearch() {
        String query = mPendingSearchQuery;
        mPendingSearchQuery = null;
        mToolbar.setSearchMode(SearchMode.DISABLED);
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        A2uiValueLimits limits = limitsFor(mRestrictions.getRestrictionsSource(),
                mRestrictions.getCurrentRestrictions());
        if (query.length() > limits.getMaxStringLength()) {
            Toast.makeText(this, "Search is longer than the vehicle allows", Toast.LENGTH_LONG)
                    .show();
            return;
        }
        invokeRendered("field", query);
    }

    /** Re-resolves against the current visible tree and current display restrictions. */
    private void invokeRendered(@NonNull String componentId, @Nullable Object payload) {
        A2uiComponent component = findRendered(mRenderedRoot, componentId);
        if (component == null || !component.isInteractive()) {
            Toast.makeText(this, "Not available now — conditions changed", Toast.LENGTH_LONG)
                    .show();
            return;
        }
        A2uiActionPolicy.Decision decision;
        try {
            decision = surfaceProcessor().dispatchAction(mSurfaceId, componentId,
                    component.getAction(), payload, component.getActionClass(),
                    mActionBinder.reader());
        } catch (A2uiProtocolException surfaceReplaced) {
            conditionsChanged();
            return;
        }
        if (decision != A2uiActionPolicy.Decision.ALLOW) {
            Toast.makeText(this, A2uiActionBinder.REFUSED_NOTE, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Adds repository-owned demo artwork and exact host interactions after policy has pruned.
     * Artwork is an image, so it follows IMAGE: a Button that survives FULLY_RESTRICTED loses it.
     */
    private void decorateNativeRows(@Nullable A2uiComponent renderedTree,
            @NonNull List<CarUiListItem> rows, @NonNull Set<ComponentType> allowed) {
        boolean images = allowed.contains(ComponentType.IMAGE);
        List<A2uiComponent> components = CarUiRenderer.renderableRows(renderedTree);
        for (int i = 0; i < Math.min(components.size(), rows.size()); i++) {
            if (!(rows.get(i) instanceof CarUiContentListItem)) continue;
            A2uiComponent component = components.get(i);
            CarUiContentListItem row = (CarUiContentListItem) rows.get(i);
            DemoNativeContract.Spec spec = DemoNativeContract.resolve(mSurfaceId, component);
            appendCitations(row, component);
            boolean rowArt = applyRowArt(row, component, allowed);
            if (spec != null && spec.mKind == DemoNativeContract.Kind.TOGGLE) {
                List<com.android.car.ui.CarUiText> body = new ArrayList<>();
                body.add(new com.android.car.ui.CarUiText(getString(mArtworkFilm
                        ? R.string.a2ui_met_artmode_film : R.string.a2ui_met_artmode_slideshow)));
                row.setBody(body);
            }
            if (spec != null) {
                int art = 0;
                switch (spec.mKind) {
                    case ROUTE:
                        art = R.drawable.demo_route_preview;
                        break;
                    case VIDEO:
                    case AUDIO:
                        art = R.drawable.demo_media_poster;
                        break;
                    case IMAGE:
                        art = R.drawable.demo_album_art;
                        break;
                    case WEATHER:
                        if (allowed.contains(ComponentType.ICON)) {
                            row.setIcon(getDrawable("met_visit".equals(mSurfaceId)
                                    ? R.drawable.a2ui_ic_sun : R.drawable.a2ui_ic_umbrella));
                            row.setPrimaryIconType(CarUiContentListItem.IconType.STANDARD);
                        }
                        break;
                    default:
                        break;
                }
                // One guard for every kind of artwork; registered row art wins.
                if (art != 0 && images && !rowArt) {
                    row.setIcon(getDrawable(art));
                    row.setPrimaryIconType(CarUiContentListItem.IconType.CONTENT);
                }
                if (!component.isInteractive() && spec.mKind != DemoNativeContract.Kind.WEATHER) {
                    String componentId = component.getId();
                    row.setOnItemClickedListener(ignored -> openHostOwnedComponent(componentId));
                }
            } else if (component.getType() == ComponentType.IMAGE) {
                row.setIcon(getDrawable(R.drawable.demo_album_art));
                row.setPrimaryIconType(CarUiContentListItem.IconType.CONTENT);
            } else if (component.getType() == ComponentType.ICON) {
                row.setIcon(getDrawable(android.R.drawable.ic_media_play));
                row.setPrimaryIconType(CarUiContentListItem.IconType.STANDARD);
            }
        }
    }

    /**
     * Cited sources that survived the prune, as extra body lines under their card. A limit that
     * cut them (LIMIT_CONTENT's depth) leaves the card with its summary alone.
     */
    private void appendCitations(@NonNull CarUiContentListItem row,
            @NonNull A2uiComponent component) {
        String containerId = DemoNativeContract.citationContainer(mSurfaceId, component);
        A2uiComponent sources = containerId == null ? null
                : findRendered(component, containerId);
        if (sources == null || !sources.hasChildren()) return;
        List<com.android.car.ui.CarUiText> body = new ArrayList<>();
        if (row.getBody() != null) body.addAll(row.getBody());
        for (A2uiComponent source : sources.getChildren()) {
            if (source.getTitle() == null) continue;
            body.add(new com.android.car.ui.CarUiText(source.getSubtitle() == null
                    ? source.getTitle() : source.getTitle() + " \u2014 " + source.getSubtitle()));
        }
        row.setBody(body);
    }

    /** Re-resolves a host interaction against the current frame and current UXR before opening. */
    private void openHostOwnedComponent(@Nullable String componentId) {
        if (componentId == null) return;
        A2uiComponent component = findRendered(mRenderedRoot, componentId);
        CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
                mRestrictions.getRestrictionsSnapshot();
        if (component == null || !allowedFor(snapshot.getSource(), snapshot.getRestrictions())
                .contains(component.getType())) {
            Toast.makeText(this, "Not available now — conditions changed", Toast.LENGTH_LONG)
                    .show();
            return;
        }
        handleNativeDemoAction(componentId, null);
    }

    /** Applies only exact {@link DemoNativeContract} entries; arbitrary agent ids are ignored. */
    private void handleNativeDemoAction(@NonNull String componentId, @Nullable Object payload) {
        A2uiComponent component = findRendered(mRenderedRoot, componentId);
        if (component == null) return;
        DemoNativeContract.Spec spec = DemoNativeContract.resolve(mSurfaceId, component);
        if (spec == null) return;
        if (spec.mKind == DemoNativeContract.Kind.VIDEO) {
            CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
                    mRestrictions.getRestrictionsSnapshot();
            if (!allowedFor(snapshot.getSource(), snapshot.getRestrictions())
                    .contains(ComponentType.VIDEO)) {
                Toast.makeText(this, A2uiActionBinder.REFUSED_NOTE, Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (payload != null) {
            Toast.makeText(this, spec.mTitle + ": " + payload, Toast.LENGTH_SHORT).show();
            return;
        }
        switch (spec.mKind) {
            case CHOICE:
                showChoice(componentId, spec);
                break;
            case SLIDER:
                showSlider(componentId, spec);
                break;
            case TIME:
                showTime(componentId, spec);
                break;
            case BROWSE:
                showBrowse(componentId, spec);
                break;
            case ROUTE:
                showRoute(componentId, spec);
                break;
            case IMAGE:
                showImage(componentId, spec);
                break;
            case VIDEO:
                showVideo(componentId);
                break;
            case GALLERY:
                showGallery(componentId);
                break;
            case CHARGER:
                // Navigation, decided by policy as such: allowed in motion, no dialog.
                mChosenCharger = Integer.parseInt(componentId.substring(1)) - 1;
                Log.i(TAG, "charger chosen: " + componentId);
                refresh();
                break;
            case TOGGLE:
                mArtworkFilm = !mArtworkFilm;
                // An open gallery follows the toggle at once, under the same checks.
                showGallery("ev_charge_coffee".equals(mSurfaceId) ? "tour" : "gallery");
                refresh();
                break;
            case ORDER:
                placeOrder();
                break;
            case AUDIO:
                if ("met_visit".equals(mSurfaceId)) {
                    // Answered by the audio guide, for everyone in the cabin; nothing new is drawn.
                    Log.i(TAG, "audio guide asked; answered by audio only");
                    break;
                }
                // Audio replaces the video; its stage must not stay up, black, over the list.
                if (mVideoView != null) dismissDemoContent();
                if (mDemoMedia != null) mDemoMedia.toggleAudio();
                Toast.makeText(this, "DEMO placeholder audio — play/pause",
                        Toast.LENGTH_SHORT).show();
                break;
            case MODAL:
                trackDialog(componentId, new AlertDialogBuilder(this).setTitle(spec.mTitle)
                        .setMessage("Host-native modal. The separate Buy action still requires "
                                + "policy confirmation.")
                        .setPositiveButton(android.R.string.ok, null).show());
                break;
            default:
                break;
        }
    }

    private void showChoice(String componentId, DemoNativeContract.Spec spec) {
        CharSequence[] choices = spec.mChoices.toArray(new CharSequence[0]);
        trackDialog(componentId, new AlertDialogBuilder(this).setTitle(spec.mTitle)
                .setSingleChoiceItems(choices, spec.mInitial, (dialog, which) -> {
                    dialog.dismiss();
                    invokeRendered(componentId, spec.mChoices.get(which));
                }).show());
    }

    private void showSlider(String componentId, DemoNativeContract.Spec spec) {
        LinearLayout body = dialogBody();
        TextView value = dialogText(String.valueOf(spec.mInitial));
        SeekBar slider = new SeekBar(this);
        slider.setMax(spec.mMaximum - spec.mMinimum);
        slider.setProgress(spec.mInitial - spec.mMinimum);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                    boolean fromUser) { value.setText(String.valueOf(progress + spec.mMinimum)); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        body.addView(value);
        body.addView(slider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(spec.mTitle).setView(body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (ignored, which) ->
                        invokeRendered(componentId, slider.getProgress() + spec.mMinimum)).create();
        dialog.show();
        trackDialog(componentId, dialog);
    }

    private void showTime(String componentId, DemoNativeContract.Spec spec) {
        TimePicker picker = new TimePicker(this);
        picker.setIs24HourView(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(spec.mTitle).setView(picker)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (ignored, which) ->
                        invokeRendered(componentId, String.format(java.util.Locale.US,
                                "%02d:%02d", picker.getHour(), picker.getMinute()))).create();
        dialog.show();
        trackDialog(componentId, dialog);
    }

    private void showBrowse(String componentId, DemoNativeContract.Spec spec) {
        String[] rows;
        if ("places".equals(componentId)) {
            rows = new String[]{"Fifth House Coffee Atelier · FICTIONAL DEMO · 0.4 mi",
                    "Atlantic Avenue Coffee · DEMO · 0.7 mi"};
        } else if ("results".equals(componentId)) {
            rows = new String[]{"Big Buck Bunny · DEMO local decoder fixture"};
        } else if ("list".equals(componentId) && "passenger_video".equals(mSurfaceId)) {
            rows = new String[]{"Episode 1 · 48m", "Episode 2 · 51m"};
        } else {
            rows = new String[]{"Demo album one", "Demo album two", "Demo album three"};
        }
        trackDialog(componentId, new AlertDialogBuilder(this).setTitle(spec.mTitle)
                .setItems(rows, (dialog, which) -> Toast.makeText(this,
                        rows[which] + " — DEMO detail", Toast.LENGTH_SHORT).show()).show());
    }

    private void showRoute(String componentId, DemoNativeContract.Spec spec) {
        LinearLayout body = dialogBody();
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.demo_route_preview);
        image.setAdjustViewBounds(true);
        body.addView(image, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 360));
        body.addView(dialogText("DEMO route preview · synthetic map · no guidance active"));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(spec.mTitle).setView(body)
                .setPositiveButton(android.R.string.ok, null).create();
        dialog.show();
        trackDialog(componentId, dialog);
    }

    private void showImage(String componentId, DemoNativeContract.Spec spec) {
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.demo_album_art);
        image.setAdjustViewBounds(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(spec.mTitle).setView(image)
                .setPositiveButton(android.R.string.ok, null).create();
        dialog.show();
        trackDialog(componentId, dialog);
    }

    /**
     * Parked video, full-bleed over the content. Tracked as host content requiring VIDEO, so
     * refresh() removes the view, and with it the surface, on the reading that forbids it.
     */
    private void showVideo(String componentId) {
        A2uiComponent component = findRendered(mRenderedRoot, componentId);
        showVideoWith(componentId, component == null ? null
                : DemoNativeContract.videoFor(mSurfaceId, component));
    }

    private void showVideoWith(@NonNull String componentId,
            @Nullable DemoNativeContract.Media media) {
        dismissDemoContent();
        VideoView video = new VideoView(this);
        mVideoStage.addView(video, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        mVideoView = video;
        mDemoDialogComponentId = componentId;
        mDemoDialogRequiredType = ComponentType.VIDEO;
        mVideoStage.setVisibility(View.VISIBLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && mVideoBack == null) {
            // Predictive back (targetSdk 36 on API 36+) never calls onBackPressed.
            mVideoBack = this::dismissDemoContent;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, mVideoBack);
        }
        TextView caption = findViewById(R.id.video_caption);
        caption.setText(media == null ? getString(R.string.a2ui_brand_video_caption)
                : media.mCaption);
        caption.setVisibility(caption.getText().length() == 0 ? View.GONE : View.VISIBLE);
        if (mDemoMedia != null) {
            mDemoMedia.playVideo(video, media == null ? R.raw.demo_placeholder_video
                    : rawNamed(media.mRawName, R.raw.demo_placeholder_video));
        }
    }

    /** A bundled raw resource by name (media other agents may not have landed yet), else fallback. */
    int rawNamed(@NonNull String name, int fallback) {
        int id = getResources().getIdentifier(name, "raw", getPackageName());
        return id != 0 ? id : fallback;
    }

    /** Decoding stops first, then the view and its surface leave the window. Idempotent. */
    private void hideVideoStage() {
        VideoView video = mVideoView;
        mVideoView = null;
        if (video != null) {
            if (mDemoMedia != null) mDemoMedia.stopVideo();
            mVideoStage.removeView(video);
        }
        if (mVideoStage != null) mVideoStage.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && mVideoBack != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(mVideoBack);
            mVideoBack = null;
        }
        if (mSlideshow != null) mSlideshow.stop();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && mGalleryBack != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(mGalleryBack);
            mGalleryBack = null;
        }
    }

    @Override
    @SuppressWarnings("deprecation") // the legacy path, still what an API 35 head unit calls
    public void onBackPressed() {
        if (mEndCard != null && mEndCard.getVisibility() == View.VISIBLE) {
            hideEndCard();
            return;
        }
        if (mVideoView != null || (mSlideshow != null && mSlideshow.isShowing())) {
            dismissDemoContent();
            return;
        }
        super.onBackPressed();
    }

    /** The full-bleed video stage, for tests. */
    @VisibleForTesting
    @NonNull
    FrameLayout videoStageForTesting() {
        return mVideoStage;
    }

    @VisibleForTesting
    @Nullable
    VideoView videoViewForTesting() {
        return mVideoView;
    }

    /**
     * Opens the slideshow for a gallery that survived the prune: its surviving Image children with
     * a registered artwork, captioned from the pruned tree. Auto-advancing imagery is moving
     * imagery, so it is tracked as needing VIDEO, like the parked video.
     */
    private void showGallery(@NonNull String componentId) {
        A2uiComponent gallery = findRendered(mLastPrunedTree, componentId);
        CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
                mRestrictions.getRestrictionsSnapshot();
        Set<ComponentType> allowed = allowedFor(snapshot.getSource(),
                snapshot.getRestrictions());
        if (gallery == null || !allowed.contains(ComponentType.VIDEO)
                || !allowed.contains(ComponentType.IMAGE)) {
            Toast.makeText(this, A2uiActionBinder.REFUSED_NOTE, Toast.LENGTH_LONG).show();
            return;
        }
        A2uiComponent film = findRendered(gallery, "film");
        if (mArtworkFilm && film != null && film.getType() == ComponentType.VIDEO) {
            // The film: a Video that survived inside the gallery, under the video rules.
            showVideoWith(componentId, DemoNativeContract.videoFor(mSurfaceId, film));
            return;
        }
        String credit = DemoNativeContract.artworkCredit(mSurfaceId);
        List<ArtworkSlideshow.Slide> slides = new ArrayList<>();
        for (A2uiComponent child : gallery.getChildren()) {
            String name = child.getType() == ComponentType.IMAGE
                    ? DemoNativeContract.artworkFor(mSurfaceId, child) : null;
            int art = name == null ? 0 : drawableNamed(name, 0);
            if (art != 0 && child.getTitle() != null) {
                slides.add(new ArtworkSlideshow.Slide(art, child.getTitle(), child.getSubtitle(),
                        credit));
            }
        }
        dismissDemoContent();
        if (!mSlideshow.show(slides)) return;
        mDemoDialogComponentId = componentId;
        mDemoDialogRequiredType = ComponentType.VIDEO;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && mGalleryBack == null) {
            mGalleryBack = this::dismissDemoContent;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, mGalleryBack);
        }
    }

    /**
     * The visual panel: the journey's arrival hero while parked after a drive, else the first
     * surviving component with registered panel imagery. Nothing unless the reading allows IMAGE.
     */
    private void updateVisualPanel(@Nullable A2uiComponent pruned,
            @NonNull Set<ComponentType> allowed, boolean parked, boolean comparison) {
        int art = 0;
        boolean hero = false;
        CharSequence title = null;
        CharSequence caption = null;
        if (!comparison && !mShowingAgent && allowed.contains(ComponentType.IMAGE)) {
            HostJourney journey = currentJourney();
            A2uiComponent hit = firstPanelComponent(pruned);
            if (journey != null && journey.mHeroArt != 0 && mArrived && parked) {
                art = journey.mHeroArt;
                hero = true;
                title = journeyData(journey, journey.mArrivalTitle);
                caption = journeyData(journey, journey.mArrivalDetail);
            } else if (hit != null) {
                DemoNativeContract.PanelSpec spec = DemoNativeContract.panelFor(mSurfaceId, hit);
                art = drawableNamed(spec.mDrawableName, spec.mFallbackDrawable);
                title = hit.getTitle();
                caption = spec.mCaption != null ? spec.mCaption : hit.getSubtitle();
            }
        }
        boolean show = art != 0;
        mVisualPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            // A map carries baked text (distance, labels) to its edges: fit it whole. Only the
            // arrival hero, a painting, fills the panel.
            mVisualPanelImage.setScaleType(hero ? ImageView.ScaleType.CENTER_CROP
                    : ImageView.ScaleType.FIT_CENTER);
            mVisualPanelImage.setImageResource(art);
            mVisualPanelTitle.setText(title);
            mVisualPanelCaption.setText(caption);
            mVisualPanelCaption.setVisibility(caption == null ? View.GONE : View.VISIBLE);
        } else {
            mVisualPanelImage.setImageDrawable(null);
        }
    }

    @VisibleForTesting
    @NonNull
    ImageView visualPanelImageForTesting() {
        return mVisualPanelImage;
    }

    /** Pre-order, any depth: the first surviving component with registered panel imagery. */
    @Nullable
    private A2uiComponent firstPanelComponent(@Nullable A2uiComponent node) {
        if (node == null) return null;
        if (DemoNativeContract.panelFor(mSurfaceId, node) != null) return node;
        for (A2uiComponent child : node.getChildren()) {
            A2uiComponent found = firstPanelComponent(child);
            if (found != null) return found;
        }
        return null;
    }

    /** A bundled drawable by name (assets other agents may not have landed yet), else fallback. */
    private int drawableNamed(@NonNull String name, int fallback) {
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        return id != 0 ? id : fallback;
    }

    /**
     * Hands a PASSENGER request to the passenger display's activity, which decides with its own
     * display's reading. Without one, the request waits for it to start.
     */
    @VisibleForTesting
    boolean onPassengerRequest(@Nullable String media) {
        boolean taken = PassengerDisplay.request(media);
        if (!taken && PassengerMedia.forName(media) != null) {
            Log.i(TAG, "no passenger surface yet; the request plays when one starts: adb shell am"
                    + " start --display <passenger display id> -n " + getPackageName() + "/"
                    + A2uiPassengerActivity.class.getName());
        }
        return taken;
    }

    /** The closing card, only on a trusted parked reading; refresh() takes it away on any other. */
    @VisibleForTesting
    boolean showEndCard() {
        CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
                mRestrictions.getRestrictionsSnapshot();
        if (!isParked(snapshot.getSource(), snapshot.getRestrictions())) {
            Log.i(TAG, "end card refused: the vehicle is not parked");
            return false;
        }
        dismissDemoContent();
        mEndCard.animate().cancel();
        mEndCard.setVisibility(View.VISIBLE);
        if (animationsEnabled()) {
            mEndCard.setAlpha(0f);
            mEndCard.animate().alpha(1f).setDuration(400).start();
        } else {
            mEndCard.setAlpha(1f);
        }
        return true;
    }

    private void hideEndCard() {
        if (mEndCard == null) return;
        mEndCard.animate().cancel();
        mEndCard.setVisibility(View.GONE);
    }

    private void bindEndCardBullets(@NonNull LinearLayout container) {
        int[] bullets = {R.string.a2ui_end_card_bullet_1, R.string.a2ui_end_card_bullet_2,
                R.string.a2ui_end_card_bullet_3, R.string.a2ui_end_card_bullet_4};
        float density = getResources().getDisplayMetrics().density;
        for (int bullet : bullets) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, Math.round(5 * density), 0, Math.round(5 * density));
            View mark = new View(this);
            mark.setBackgroundColor(getColor(R.color.a2ui_brand_accent));
            LinearLayout.LayoutParams markSize = new LinearLayout.LayoutParams(
                    Math.round(18 * density), Math.round(3 * density));
            markSize.setMarginEnd(Math.round(20 * density));
            line.addView(mark, markSize);
            TextView text = new TextView(this);
            text.setText(bullet);
            text.setTextAppearance(R.style.A2uiBrandBody);
            text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 23);
            line.addView(text);
            container.addView(line);
        }
    }

    @VisibleForTesting
    @NonNull
    View endCardForTesting() {
        return mEndCard;
    }

    @VisibleForTesting
    @NonNull
    ArtworkSlideshow slideshowForTesting() {
        return mSlideshow;
    }

    @VisibleForTesting
    @NonNull
    View visualPanelForTesting() {
        return mVisualPanel;
    }

    private LinearLayout dialogBody() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        return body;
    }

    private TextView dialogText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(18);
        return view;
    }

    private void trackDialog(@NonNull String componentId, @NonNull AlertDialog dialog) {
        hideVideoStage();
        if (mDemoContentDialog != null && mDemoContentDialog != dialog) {
            mDemoContentDialog.dismiss();
        }
        mDemoContentDialog = dialog;
        mDemoDialogComponentId = componentId;
        mDemoDialogRequiredType = null;
    }

    private void dismissDemoContent() {
        AlertDialog dialog = mDemoContentDialog;
        mDemoContentDialog = null;
        mDemoDialogComponentId = null;
        mDemoDialogRequiredType = null;
        if (dialog != null) dialog.dismiss();
        hideVideoStage();
    }

    @Nullable
    static A2uiComponent findRendered(@Nullable A2uiComponent root,
            @NonNull String componentId) {
        if (root == null) {
            return null;
        }
        if (componentId.equals(root.getId())) {
            return root;
        }
        for (A2uiComponent child : root.getChildren()) {
            A2uiComponent found = findRendered(child, componentId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The same provenance rule as Host A, duplicated so {@code :a2ui-model} needs no car-ui-lib
     * type. {@code CarUiRendererTest#provenanceRuleAgreesWithHostA} pins it against Host A's rule
     * restated inline, so it would not notice Host A changing.
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
     * The value limits for a reading, gated on provenance like {@link #allowedFor}: a fail-closed
     * reading's numbers describe no vehicle. Pinned by
     * {@code CarUiRendererTest#limitsFor_agreesWithHostAsRuleAtEveryLevelAndSource}, with the same
     * restated-rule caveat.
     */
    @VisibleForTesting
    @NonNull
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
     * One display's reading as everything a handoff decides with, all four inputs derived from the
     * same {@code (source, restrictions)} pair so they cannot be mispaired. A non-DELIVERED reading
     * fails closed throughout.
     */
    @VisibleForTesting
    @NonNull
    static A2uiHandoff.Endpoint endpointFor(
            @NonNull CarUxRestrictionsUtil.RestrictionsSource source,
            @NonNull CarUxRestrictions restrictions) {
        return A2uiHandoff.Endpoint.of(
                allowedFor(source, restrictions),
                limitsFor(source, restrictions),
                restrictions.getActiveRestrictions(),
                isAuthoritative(source));
    }

    /**
     * Applies a demo override through the production observer pipeline, tagged DEMO.
     * {@code setDemoUxRestrictions} is itself dual-gated against user and non-debuggable builds.
     */
    private void applyDemoOverride(int active) {
        if (active < 0) {
            // Restoring is required: the injected value lives in the shared per-display util, and
            // clearing only the flag would leave it reported as "[live]".
            if (mGenuineRestrictions != null) {
                mRestrictions.setUxRestrictions(mGenuineRestrictions);
                mGenuineRestrictions = null;
                mDemoOverride = null;
                Log.i(TAG, "demo override cleared; restored the last genuine reading");
            } else {
                Log.i(TAG, "demo override remains active; no genuine reading was captured to "
                        + "restore without mislabelling synthetic state as live");
            }
            refresh();
            return;
        }
        if (mGenuineRestrictions == null
                && mRestrictions.getRestrictionsSource()
                        == CarUxRestrictionsUtil.RestrictionsSource.DELIVERED) {
            // Captured once, before the first injection replaces it.
            mGenuineRestrictions = mRestrictions.getCurrentRestrictions();
        }
        mDemoOverride = active;
        CarUxRestrictions uxr = new CarUxRestrictions.Builder(
                active != A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, active, 0)
                // 3 rather than the platform default, so truncation is visible on a surface that
                // only ever has eight rows. A limit larger than the content demonstrates nothing.
                .setMaxCumulativeContentItems(3)
                .build();
        mRestrictions.setDemoUxRestrictions(uxr);
    }

    private void registerDemoReceiverIfDebuggable() {
        if (!debugBusAllowed(getApplicationInfo(), Build.TYPE)) {
            return;
        }
        mDemoReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                applyDemoOverride(intent.getIntExtra(DemoRestrictions.EXTRA_ACTIVE, -1));
            }
        };
        registerReceiver(mDemoReceiver, new IntentFilter(DemoRestrictions.ACTION_SET),
                Context.RECEIVER_EXPORTED);

        // A phrase injector, debuggable builds only: emulator images often lack a recognition
        // service, and this feeds onHeard() exactly as a real result does.
        //
        //   adb shell am broadcast -a art.dart.a2ui.carui.action.SPEAK --es art.dart.a2ui.carui.extra.PHRASE "buy album"
        mSpeakReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onHeard(intent.getStringExtra(DebugBroadcasts.EXTRA_PHRASE));
            }
        };
        registerReceiver(mSpeakReceiver, new IntentFilter(DebugBroadcasts.ACTION_SPEAK),
                Context.RECEIVER_EXPORTED);

        //   adb shell am broadcast -a art.dart.a2ui.carui.action.HANDOFF --ei art.dart.a2ui.carui.extra.TO 2
        //
        // `destActive` also pushes a DEMO reading onto the destination display's own util, so its
        // re-render is observable on an AVD whose display 4 answers 0x1ff forever.
        //
        //   adb shell am broadcast -a art.dart.a2ui.carui.action.HANDOFF --ei art.dart.a2ui.carui.extra.TO 4 --ei art.dart.a2ui.carui.extra.DEST_ACTIVE 0
        mHandoffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                demonstrateHandoff(intent.getIntExtra(DebugBroadcasts.EXTRA_TO, 2));
                // Absent leaves the display alone, >= 0 injects, -1 restores the genuine reading;
                // Integer.MIN_VALUE marks absent.
                int destActive = intent.getIntExtra(DebugBroadcasts.EXTRA_DEST_ACTIVE, Integer.MIN_VALUE);
                if (destActive != Integer.MIN_VALUE) {
                    overrideDestination(destActive);
                }
            }
        };
        registerReceiver(mHandoffReceiver, new IntentFilter(DebugBroadcasts.ACTION_HANDOFF),
                Context.RECEIVER_EXPORTED);

        mCaseReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String from = mShowingAgent ? null : mSurfaceId;
                String caseId = intent.getStringExtra(DebugBroadcasts.EXTRA_CASE_ID);
                if (selectCase(caseId)) {
                    announce(from, caseId);
                }
            }
        };
        registerReceiver(mCaseReceiver, new IntentFilter(DebugBroadcasts.ACTION_CASE),
                Context.RECEIVER_EXPORTED);

        //   adb shell am broadcast --user current -a art.dart.a2ui.carui.action.PASSENGER \
        //       --es art.dart.a2ui.carui.extra.MEDIA video|stop
        mPassengerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onPassengerRequest(intent.getStringExtra(DebugBroadcasts.EXTRA_MEDIA));
            }
        };
        registerReceiver(mPassengerReceiver, new IntentFilter(DebugBroadcasts.ACTION_PASSENGER),
                Context.RECEIVER_EXPORTED);

        //   adb shell am broadcast --user current -a art.dart.a2ui.carui.action.VEHICLE \
        //       --ei art.dart.a2ui.carui.extra.BATTERY_PCT 18      (-1 returns to the vehicle's)
        mVehicleReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int pct = intent.getIntExtra(DebugBroadcasts.EXTRA_BATTERY_PCT, -1);
                mDemoBatteryPct = pct < 0 || pct > 100 ? null : pct;
                refresh();
            }
        };
        registerReceiver(mVehicleReceiver, new IntentFilter(DebugBroadcasts.ACTION_VEHICLE),
                Context.RECEIVER_EXPORTED);

        //   adb shell am broadcast --user current -a art.dart.a2ui.carui.action.END_CARD
        mEndCardReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                showEndCard();
            }
        };
        registerReceiver(mEndCardReceiver, new IntentFilter(DebugBroadcasts.ACTION_END_CARD),
                Context.RECEIVER_EXPORTED);

        //   adb shell am broadcast -a art.dart.a2ui.carui.action.AGENT \
        //       --es art.dart.a2ui.carui.extra.PROVIDER muse --es art.dart.a2ui.carui.extra.ASK "find coffee"
        if (mAgent != null) {
            mAgentReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String provider = intent.getStringExtra(DebugBroadcasts.EXTRA_PROVIDER);
                    if (provider != null) {
                        selectAgentProvider(AgentProvider.fromWireName(provider));
                    }
                    String ask = intent.getStringExtra(DebugBroadcasts.EXTRA_ASK);
                    if (ask != null) {
                        askAgent(ask);
                    }
                }
            };
            registerReceiver(mAgentReceiver, new IntentFilter(DebugBroadcasts.ACTION_AGENT),
                    Context.RECEIVER_EXPORTED);
        }
    }

    @VisibleForTesting
    boolean selectCase(@Nullable String caseId) {
        if (caseId == null || mProcessor.getSurface(caseId) == null) return false;
        try {
            mProcessor.getSurface(caseId).project(DemoTrace.ROOT_ID);
        } catch (RuntimeException invalid) {
            Log.i(TAG, "case selection refused: " + caseId);
            return false;
        }
        mShowingAgent = false;
        mCorpusSurfaceId = caseId;
        mAgentBadge = null;
        mEmbeddedAgentSurface = null;
        String passenger = PassengerDisplay.playing();
        if (PassengerMedia.ARTWORK.equals(passenger) || PassengerMedia.SLIDESHOW.equals(passenger)) {
            PassengerDisplay.request(PassengerDisplay.STOP);
        }
        if (mAgent != null && DemoNativeContract.embedsAgentBrief(caseId) || "ev_charge_coffee"
                .equals(caseId)) {
            defaultToMuse();
        }
        showSurface(caseId);
        return true;
    }

    /** Switches the rendered surface; {@link #mShowingAgent} says which processor owns it. */
    private void showSurface(@NonNull String surfaceId) {
        stopListening();
        dismissDemoContent();
        hideEndCard();
        mPendingSearchQuery = null;
        if (mToolbar != null) {
            mToolbar.setSearchMode(SearchMode.DISABLED);
        }
        mSurfaceId = surfaceId;
        // A commitment belongs to its journey: entering another one starts clean, while leaving
        // to a surface with none keeps it for the way back.
        HostJourney next = mShowingAgent ? null : HostJourney.forSurface(surfaceId);
        if (next != null && next != mJourney) {
            mOrderPlaced = false;
            mDroveWithOrder = false;
            mArrived = false;
        }
        if (next != null) mJourney = next;
        mActionBinder = binderFor(mRestrictions, this);
        mRowEpoch++;
        refresh();
    }

    // Live-agent lane. Debug builds only: every entry point returns false without the lane.

    private final AgentLane.Listener mAgentListener = new AgentLane.Listener() {
        @Override
        public void onPending(@NonNull AgentProvider provider) {
            mAgentBadge = provider.wireName() + " · …";
            mAgentThinking = true;
            refresh();
        }

        @Override
        public void onSurface(@NonNull String surfaceId, @NonNull AgentProvider provider,
                @NonNull String badge) {
            mAgentThinking = false;
            mAgentBadge = badge;
            if (!mShowingAgent && DemoNativeContract.embedsAgentBrief(mSurfaceId)) {
                // The answer becomes this screen's brief; the screen and its controls stay.
                mEmbeddedAgentSurface = surfaceId;
                Log.i(TAG, "agent surface embedded as the brief: " + badge);
                refresh();
                return;
            }
            mShowingAgent = true;
            showSurface(surfaceId);
            Log.i(TAG, "agent surface: " + badge);
        }

        @Override
        public void onAssistantText(@NonNull String text) {
            speak(text);
        }

        @Override
        public void onFailure(@NonNull AgentProvider provider,
                @NonNull AgentTransportException failure) {
            mAgentThinking = false;
            mAgentBadge = provider.wireName() + " · failed";
            // Never on screen: the fixture (or the last good surface) stays, and the log says why.
            Log.i(TAG, "agent turn failed: " + failure.getKind() + "; keeping the current surface");
            refresh();
        }
    };

    @VisibleForTesting
    @NonNull
    static String providerLabel(@NonNull AgentProvider provider) {
        switch (provider) {
            case CHATGPT: return "ChatGPT";
            case GEMINI: return "Gemini";
            case GROK: return "Grok";
            case MUSE: return "Muse";
            case CLAUDE:
            default: return "Claude";
        }
    }

    /** Sends one ask through the lane. The reply renders through refresh() like any surface. */
    @VisibleForTesting
    boolean askAgent(@Nullable String text) {
        if (mAgent == null || text == null || text.trim().isEmpty()) return false;
        // Back to the corpus first: the lane is about to replace the surface on screen.
        if (mShowingAgent) selectCase(mCorpusSurfaceId);
        A2uiMessageProcessor.RestrictionReading reading = readerFor(mRestrictions).currentReading();
        // Parked: the cached, cited lane. Moving: the low-latency one, minimal effort.
        CarUxRestrictionsUtil.RestrictionsSnapshot state = mRestrictions.getRestrictionsSnapshot();
        mAgent.selectProfile(isParked(state.getSource(), state.getRestrictions())
                ? art.dart.a2ui.agent.AgentExecutionProfile.QUALITY_CACHED
                : art.dart.a2ui.agent.AgentExecutionProfile.REALTIME_LOW_LATENCY);
        try {
            mAgent.ask(text.trim(), mRestrictions.getDisplayId(), reading);
        } catch (IllegalArgumentException unencodable) {
            // onPending already ran; nothing will answer it.
            dropPendingTurn();
            refresh();
            Toast.makeText(this, "That request cannot be sent", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    /** The chip's tap. refresh() withholds it unless parked; this refuses a tap that raced that. */
    @VisibleForTesting
    boolean cycleProvider() {
        if (mAgent == null) return false;
        CarUxRestrictionsUtil.RestrictionsSnapshot reading =
                mRestrictions.getRestrictionsSnapshot();
        if (!isParked(reading.getSource(), reading.getRestrictions())) {
            Toast.makeText(this, A2uiActionBinder.REFUSED_NOTE, Toast.LENGTH_LONG).show();
            return false;
        }
        AgentProvider[] all = AgentProvider.values();
        return selectAgentProvider(all[(mAgent.provider().ordinal() + 1) % all.length]);
    }

    /** Switches provider. The session resets the LANE's processor; the corpus is not touched. */
    @VisibleForTesting
    boolean selectAgentProvider(@Nullable AgentProvider provider) {
        if (mAgent == null || provider == null) return false;
        boolean switched = mAgent.provider() != provider;
        boolean reask = mShowingAgent && mAgent.lastAsk() != null;
        if (mShowingAgent) selectCase(mCorpusSurfaceId);
        mAgent.selectProvider(provider);
        // The session drops an in-flight turn only on a real switch; the same provider keeps it.
        if (switched) dropPendingTurn();
        if (mProviderItem != null) {
            mProviderItem.setTitle(providerLabel(provider));
        }
        if (reask) {
            askAgent(mAgent.lastAsk());
        } else {
            refresh();
        }
        return true;
    }

    /** Replaces the lane's gateway transport; the fallback replay wraps it as in production. */
    @VisibleForTesting
    void setAgentTransportForTesting(@NonNull AgentTransport transport) {
        if (mAgent == null) throw new IllegalStateException("no agent lane on this build");
        if (mShowingAgent) selectCase(mCorpusSurfaceId);
        AgentProvider provider = mAgent.provider();
        mAgent.close();
        dropPendingTurn();
        mAgent = new AgentLane(transport, null, getMainExecutor(), mAgentListener);
        mAgent.selectProvider(provider);
        mAgent.processor().setRestrictionReader(readerFor(mRestrictions));
    }

    /** The rows both adapters read. */
    @VisibleForTesting
    @NonNull
    List<CarUiListItem> renderedRowsForTesting() {
        return mItems;
    }

    private void conditionsChanged() {
        Toast.makeText(this, "Not available now — conditions changed", Toast.LENGTH_LONG).show();
    }

    private void showDemoConsole() {
        List<String> labels = new ArrayList<>();
        labels.add(mDiagnosticsExpanded ? "Hide technical details" : "Show technical details");
        labels.add("Live restrictions");
        labels.add("DEMO baseline (0x0)");
        labels.add("DEMO no video (0x10)");
        labels.add("DEMO limit content (0x20)");
        labels.add("DEMO no setup (0x40)");
        labels.add("DEMO no voice (0x100)");
        labels.add("DEMO fully restricted (0x1ff)");
        labels.add("Inject voice: buy album");
        labels.add("Inject voice: play");
        labels.add("Choose handoff display…");
        for (UseCaseCorpus.UseCase useCase : UseCaseCorpus.all()) {
            labels.add("Case: " + useCase.getId());
        }
        new AlertDialogBuilder(this)
                .setTitle("A2UI demo controls")
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    if (which == 0) {
                        setDiagnosticsExpanded(!mDiagnosticsExpanded);
                        return;
                    }
                    int selection = which - 1;
                    final int[] masks = {-1, 0,
                            A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO,
                            A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_CONTENT,
                            A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_SETUP,
                            A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION,
                            A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED};
                    if (selection < masks.length) {
                        sendBroadcast(new Intent(DemoRestrictions.ACTION_SET)
                                .putExtra(DemoRestrictions.EXTRA_ACTIVE, masks[selection]));
                    } else if (selection == 7) {
                        onHeard("buy album");
                    } else if (selection == 8) {
                        onHeard("play");
                    } else if (selection == 9) {
                        showHandoffTargets();
                    } else {
                        UseCaseCorpus.UseCase selected = UseCaseCorpus.all().get(selection - 10);
                        sendBroadcast(new Intent(DebugBroadcasts.ACTION_CASE)
                                .putExtra(DebugBroadcasts.EXTRA_CASE_ID, selected.getId()));
                    }
                })
                .show();
    }

    private void showHandoffTargets() {
        android.view.Display[] displays = getSystemService(
                android.hardware.display.DisplayManager.class).getDisplays();
        CharSequence[] labels = new CharSequence[displays.length];
        for (int i = 0; i < displays.length; i++) {
            labels[i] = displays[i].getDisplayId() + ": " + displays[i].getName();
        }
        new AlertDialogBuilder(this).setTitle("Handoff destination")
                .setItems(labels, (dialog, which) -> demonstrateHandoff(
                        displays[which].getDisplayId()))
                .show();
    }

    @VisibleForTesting
    static boolean debugBusAllowed(@NonNull ApplicationInfo info, @NonNull String buildType) {
        return (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && !"user".equals(buildType);
    }

    /**
     * Re-projects the current surface for another display via {@link A2uiHandoff}, re-decides its
     * actions there, and logs what changed: a negative {@code net} (components added, which
     * forwarding cannot produce) and {@code redecided} (controls whose permission changed, such as
     * the glanceable "Buy album" Button).
     */
    @VisibleForTesting
    void demonstrateHandoff(int destinationDisplayId) {
        // Logged: a handoff that silently lands on its own display looks like one that worked.
        StringBuilder visible = new StringBuilder();
        android.hardware.display.DisplayManager dm =
                getSystemService(android.hardware.display.DisplayManager.class);
        for (android.view.Display d : dm.getDisplays()) {
            visible.append(d.getDisplayId()).append(':').append(d.getName()).append(' ');
        }
        Log.i(TAG, "displays this app can see: " + visible);

        CarUxRestrictionsUtil destination =
                CarUxRestrictionsUtil.getInstanceForDisplay(this, destinationDisplayId);
        if (destination.getDisplayId() != destinationDisplayId) {
            Log.w(TAG, "requested display " + destinationDisplayId + " is not reachable from this "
                    + "app; fell back to " + destination.getDisplayId()
                    + ". The handoff below is therefore display-to-itself.");
        }
        CarUxRestrictionsUtil.RestrictionsSnapshot destSnapshot =
                destination.getRestrictionsSnapshot();
        CarUxRestrictions destReading = destSnapshot.getRestrictions();
        CarUxRestrictionsUtil.RestrictionsSource destSource = destSnapshot.getSource();
        CarUxRestrictionsUtil.RestrictionsSnapshot srcSnapshot =
                mRestrictions.getRestrictionsSnapshot();
        CarUxRestrictions srcReading = srcSnapshot.getRestrictions();
        CarUxRestrictionsUtil.RestrictionsSource srcSource = srcSnapshot.getSource();

        A2uiComponent projected = currentProjection();
        if (projected == null) {
            Log.w(TAG, "nothing to hand off: surface " + mSurfaceId + " does not project");
            return;
        }
        A2uiHandoff.Result r = A2uiHandoff.reproject(projected,
                endpointFor(srcSource, srcReading),
                endpointFor(destSource, destReading));

        StringBuilder redecided = new StringBuilder();
        for (String id : r.redecided()) {
            redecided.append(' ').append(id).append(": ")
                    .append(r.decisionsAtSource().get(id)).append(" -> ")
                    .append(r.decisionsAtDestination().get(id)).append(';');
        }

        Log.i(TAG, "handoff " + mRestrictions.getDisplayId() + " -> "
                + destination.getDisplayId()
                + " destSource=" + destSource
                + " destActive=0x" + Integer.toHexString(destReading.getActiveRestrictions())
                + " net=" + r.netComponentsRemoved()
                + " (negative means the destination shows MORE — only re-projection can)"
                + " redecided=" + r.redecided().size() + " [" + redecided.toString().trim() + "]"
                + " destDecisions=" + r.decisionsAtDestination());

        // Only the destination is passed. The tree, the limits, the binder and the UI context are
        // all derived from it inside, so there is no second argument that could be the source's.
        showOnDestination(destination);

        Toast.makeText(this,
                "Handoff to display " + destination.getDisplayId() + ": net "
                        + r.netComponentsRemoved() + " components, "
                        + r.redecided().size() + " action(s) re-decided",
                Toast.LENGTH_LONG).show();
    }

    /**
     * Draws the handed-off surface on the destination display, with the destination's tree
     * ({@code r.atDestination()}), its draw-time decisions and its touch-time decision
     * ({@code binderFor(destination)}, see {@link A2uiDisplayBinder}). A {@link Presentation}, not a
     * second Activity, so one process keeps one copy of the protocol state.
     */
    private void showOnDestination(@NonNull CarUxRestrictionsUtil destination) {
        dismissHandoffPresentation();
        android.view.Display display = getSystemService(
                android.hardware.display.DisplayManager.class)
                .getDisplay(destination.getDisplayId());
        if (display == null) {
            Log.w(TAG, "destination display " + destination.getDisplayId()
                    + " vanished before it could be drawn to; nothing was rendered there.");
            return;
        }
        mHandoffDestination = destination;

        try {
            Presentation presentation = new Presentation(this, display);

            // car-ui-lib's LayoutInflater factory on the presentation's own inflater: CarUiInstaller
            // installs it only on Activities, and without it the first row bind throws
            // ClassNotFoundException for CarUiTextView. Set only if nothing claimed the slot, as
            // car-ui-lib's own secondary windows do.
            LayoutInflater presentationInflater =
                    LayoutInflater.from(presentation.getContext());
            if (presentationInflater.getFactory2() == null) {
                presentationInflater.setFactory2(new CarUiLayoutInflaterFactory());
            }

            presentation.setContentView(R.layout.a2ui_handoff_presentation);
            ((TextView) presentation.findViewById(R.id.handoff_label)).setText(
                    "handed-off from display " + mRestrictions.getDisplayId()
                            + " — decided by display " + destination.getDisplayId());

            // Created, not inflated: the CarUiRecyclerView tag needs an Activity's inflater factory.
            CarUiRecyclerView list = CarUiRecyclerView.create(presentation.getContext());
            list.setLayoutManager(new LinearLayoutManager(presentation.getContext()));
            mHandoffAdapter = new CarUiListItemAdapter(mHandoffItems);
            list.setAdapter(mHandoffAdapter);
            ((android.widget.FrameLayout) presentation.findViewById(R.id.handoff_list_container))
                    .addView(list.getView());

            // Registered before show(), so a presentation the system rejects or tears down still
            // releases. Identity-checked: dismiss() is async, so a superseded presentation's late
            // callback must not tear down its replacement.
            final Presentation shown = presentation;
            presentation.setOnDismissListener(dialog -> {
                if (shown != mHandoffPresentation) {
                    Log.i(TAG, "ignoring dismissal of a superseded handoff presentation");
                    return;
                }
                Log.i(TAG, "handoff presentation dismissed; releasing the destination listener");
                releaseHandoffState();
            });
            presentation.show();
            mHandoffPresentation = presentation;
            // The Presentation's own Context, so the destination's dialogs and toasts land there.
            refreshHandoff(presentation.getContext());
            registerHandoffListener(presentation.getContext());
            Log.i(TAG, "rendered " + mHandoffItems.size() + " row(s) on display "
                    + destination.getDisplayId() + ", bound to that display's restrictions");
            return;
        } catch (android.view.WindowManager.InvalidDisplayException e) {
            // Loud: a handoff that renders nowhere looks like one that worked. The display exists;
            // it refuses app windows.
            boolean presentable = false;
            for (android.view.Display d : getSystemService(
                    android.hardware.display.DisplayManager.class)
                    .getDisplays(android.hardware.display.DisplayManager
                            .DISPLAY_CATEGORY_PRESENTATION)) {
                presentable |= d.getDisplayId() == destination.getDisplayId();
            }
            // Decided and reported anyway, so the enforcement evidence survives an AVD that refuses
            // the window; the listener is registered here too so the refresh stays observable.
            refreshHandoff(this);
            registerHandoffListener(this);
            Log.w(TAG, "could not present on display " + destination.getDisplayId()
                    + " (presentation-capable=" + presentable + "). The surface was decided FOR"
                    + " that display — see the enforcement line above — but is not DRAWN there.", e);
        }
    }

    /**
     * Re-runs the destination's whole pipeline against its current reading: at handoff and on
     * every destination restriction change, like display 0's {@code refresh()}, so the surface is
     * never frozen at transfer time.
     */
    private void refreshHandoff(@NonNull Context uiContext) {
        CarUxRestrictionsUtil destination = mHandoffDestination;
        if (destination == null) {
            return;
        }
        DisplayScope scope = scopeFor(destination);
        Rendered rendered = renderFor(currentProjection(), scope, surfaceProcessor(), mSurfaceId,
                outcomeOn(destination, uiContext));

        mHandoffItems.clear();
        mHandoffItems.addAll(rendered.mRows);
        if (mHandoffAdapter != null) {
            mHandoffAdapter.notifyDataSetChanged();
        }
        logEnforcement(destination, rendered.mTree, rendered.mRows);
    }

    /**
     * Pushes a DEMO reading onto the destination display's own util, so its refresh can be watched
     * without claiming the reading came from car service.
     */
    @VisibleForTesting
    void overrideDestination(int active) {
        CarUxRestrictionsUtil destination = mHandoffDestination;
        if (destination == null) {
            Log.w(TAG, "destActive given but no handoff destination is set; ignored");
            return;
        }

        if (active < 0) {
            CarUxRestrictions saved = sGenuineDestinationRestrictions.get(
                    destination.getDisplayId());
            if (saved != null) {
                destination.setUxRestrictions(saved);
                sGenuineDestinationRestrictions.remove(destination.getDisplayId());
                Log.i(TAG, "destination override cleared; restored the last genuine reading for "
                        + "display " + destination.getDisplayId());
            } else {
                Log.i(TAG, "destination override cleared; no genuine reading had been captured"
                        + " for display " + destination.getDisplayId());
            }
            return;
        }

        // Captured once, before the first injection, so clearing DEMO restores an honest pair.
        if (sGenuineDestinationRestrictions.get(destination.getDisplayId()) == null
                && destination.getRestrictionsSource()
                        == CarUxRestrictionsUtil.RestrictionsSource.DELIVERED) {
            sGenuineDestinationRestrictions.put(
                    destination.getDisplayId(), destination.getCurrentRestrictions());
        }
        Log.i(TAG, "pushing SYNTHETIC active=0x" + Integer.toHexString(active) + " onto display "
                + destination.getDisplayId() + "; the enforcement line below is the REFRESH."
                + " Clear it with --ei art.dart.a2ui.carui.extra.DEST_ACTIVE -1.");
        destination.setDemoUxRestrictions(new CarUxRestrictions.Builder(
                active != A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, active, 0)
                .setMaxCumulativeContentItems(3)
                .build());
    }

    /** Watches the destination's own per-display restrictions while its surface is up. */
    private void registerHandoffListener(@NonNull Context uiContext) {
        unregisterHandoffListener();
        CarUxRestrictionsUtil destination = mHandoffDestination;
        if (destination == null) {
            return;
        }
        mHandoffUxrListener = ignored -> runOnMain(() -> refreshHandoff(uiContext));
        destination.register(mHandoffUxrListener);
        Log.i(TAG, "watching display " + destination.getDisplayId()
                + " for restriction changes while its surface is handed over");
    }

    private void unregisterHandoffListener() {
        if (mHandoffUxrListener != null && mHandoffDestination != null) {
            mHandoffDestination.unregister(mHandoffUxrListener);
        }
        mHandoffUxrListener = null;
    }

    /**
     * Reports what the destination's binder did to the rows (a null click listener means
     * withheld), not what the decision map said. Useful even when the Presentation cannot be
     * shown.
     */
    private void logEnforcement(@NonNull CarUxRestrictionsUtil destination,
            @Nullable A2uiComponent atDestination, @NonNull List<CarUiListItem> rows) {
        // Paired with their components: "no listener" is enforcement only for a control that had
        // an action. renderableRows() is the list render() walked, so the pairing cannot drift.
        List<A2uiComponent> components = CarUiRenderer.renderableRows(atDestination);
        StringBuilder offered = new StringBuilder();
        StringBuilder refused = new StringBuilder();
        StringBuilder inert = new StringBuilder();
        int paired = Math.min(components.size(), rows.size());
        for (int i = 0; i < paired; i++) {
            if (!(rows.get(i) instanceof CarUiContentListItem)) {
                continue;
            }
            CarUiContentListItem item = (CarUiContentListItem) rows.get(i);
            A2uiComponent component = components.get(i);
            CharSequence title = item.getTitle() == null ? "?" : item.getTitle().getPreferredText();
            StringBuilder bucket = !component.isInteractive() ? inert
                    : item.getOnClickListener() == null ? refused : offered;
            bucket.append(" \"").append(title).append('"');
        }
        Log.i(TAG, "enforcement on display " + destination.getDisplayId()
                + ": REFUSED (interactive, listener withheld) [" + refused.toString().trim()
                + " ]  offered [" + offered.toString().trim()
                + " ]  inert, no action [" + inert.toString().trim() + " ]"
                + (components.size() == rows.size() ? ""
                        : "  WARNING: " + components.size() + " components vs " + rows.size()
                                + " rows — the pairing above is truncated and unreliable"));
    }

    private void dismissHandoffPresentation() {
        Presentation presentation = mHandoffPresentation;
        if (presentation != null) {
            // Triggers the dismiss listener below, which does the releasing. Called anyway
            // afterwards, because the presentation may never have been shown at all.
            presentation.dismiss();
        }
        releaseHandoffState();
    }

    /**
     * Drops everything the handoff holds. Idempotent; also reached when the system dismisses the
     * Presentation. Unregister before clearing {@code mHandoffDestination}, which the unregister
     * needs.
     */
    private void releaseHandoffState() {
        unregisterHandoffListener();
        mHandoffPresentation = null;
        mHandoffDestination = null;
        mHandoffAdapter = null;
        mHandoffItems.clear();
        // sGenuineDestinationRestrictions is deliberately not cleared (see its declaration).
    }
}
