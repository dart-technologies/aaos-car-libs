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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Application;
import android.car.drivingstate.CarUxRestrictions;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.widget.TextView;

import com.android.car.ui.core.CarUiInstaller;
import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.recyclerview.CarUiListItem;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.agent.AgentExecutionProfile;
import art.dart.a2ui.agent.AgentProvider;
import art.dart.a2ui.agent.AgentTransport;
import art.dart.a2ui.agent.AgentTransportException;
import art.dart.a2ui.demo.DemoTrace;
import art.dart.a2ui.policy.A2uiActionBinder;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.protocol.A2uiMessageProcessor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowTextToSpeech;
import org.robolectric.shadows.ShadowToast;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Host B's live-agent lane: debug-only, provider switches confined to the lane, and agent surfaces
 * pruned by exactly the path corpus surfaces take.
 */
@RunWith(RobolectricTestRunner.class)
public class A2uiAgentLaneTest {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    /** Root Column over glanceable and non-glanceable children, as a gateway would send it. */
    private static final String MESSAGES = "[{\"version\":\"v0.9\",\"createSurface\":"
            + "{\"surfaceId\":\"coffee\",\"catalogId\":\"automotive\"}},{\"version\":\"v0.9\","
            + "\"updateComponents\":{\"surfaceId\":\"coffee\",\"components\":["
            + "{\"id\":\"root\",\"component\":\"Column\",\"children\":[\"name\",\"clip\","
            + "\"field\",\"order\",\"play\"]},"
            + "{\"id\":\"name\",\"component\":\"Text\",\"title\":\"DEMO cafe\"},"
            + "{\"id\":\"clip\",\"component\":\"Video\",\"title\":\"Tour\"},"
            + "{\"id\":\"field\",\"component\":\"TextField\",\"title\":\"Search\","
            + "\"action\":\"search\"},"
            + "{\"id\":\"order\",\"component\":\"Button\",\"title\":\"Order ahead\","
            + "\"action\":\"purchase\"},"
            + "{\"id\":\"play\",\"component\":\"Button\",\"title\":\"Play\",\"action\":\"play\"}"
            + "]}}]";

    private Application mApp;
    private int mOriginalFlags;

    @Before
    public void setUp() {
        CarUiInstaller.register(RuntimeEnvironment.getApplication());
        CarUxRestrictionsUtil.clearInstancesForTesting();
        A2uiCarUiActivity.clearGenuineDestinationRestrictionsForTesting();
        mApp = RuntimeEnvironment.getApplication();
        mOriginalFlags = mApp.getApplicationInfo().flags;
        mApp.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
    }

    @After
    public void tearDown() {
        shadowOf(Looper.getMainLooper()).idle();
        mApp.getApplicationInfo().flags = mOriginalFlags;
    }

    @Test
    public void theLaneAndItsExtrasDoNotExistOnANonDebuggableBuild() {
        mApp.getApplicationInfo().flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
        A2uiCarUiActivity release =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create().start().get();

        assertThat(release.mAgent).isNull();
        assertThat(release.mProviderItem).isNull();
        assertThat(agentReceivers()).isEmpty();
        assertThat(release.askAgent("find coffee")).isFalse();
        assertThat(release.cycleProvider()).isFalse();
        assertThat(release.selectAgentProvider(AgentProvider.MUSE)).isFalse();
        mApp.sendBroadcast(new Intent(DebugBroadcasts.ACTION_AGENT)
                .putExtra(DebugBroadcasts.EXTRA_PROVIDER, "muse")
                .putExtra(DebugBroadcasts.EXTRA_ASK, "find coffee"));
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(release.mShowingAgent).isFalse();

        // Anti-vacuity: the same build, debuggable, has all three.
        mApp.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        A2uiCarUiActivity debug =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create().start().get();
        assertThat(debug.mAgent).isNotNull();
        assertThat(debug.mProviderItem).isNotNull();
        assertThat(agentReceivers()).isNotEmpty();
    }

    @Test
    public void theProviderAndAskExtrasDriveTheLane() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        FakeGateway gateway = attach(activity);

        mApp.sendBroadcast(new Intent(DebugBroadcasts.ACTION_AGENT)
                .putExtra(DebugBroadcasts.EXTRA_PROVIDER, "muse")
                .putExtra(DebugBroadcasts.EXTRA_ASK, "find coffee"));
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(activity.mAgent.provider()).isEqualTo(AgentProvider.MUSE);
        assertThat(activity.mProviderItem.getTitle().toString()).isEqualTo("Muse");
        assertThat(gateway.request(0)).contains("\"provider\":\"muse\"");

        gateway.respond(0);
        assertThat(activity.mShowingAgent).isTrue();
        assertThat(activity.mAgentBadge).matches("muse · \\d+ ms");
    }

    @Test
    public void anAgentReplyIsSpokenAndASilentOneIsNot() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        FakeGateway gateway = attach(activity);

        activity.askAgent("find coffee");
        gateway.respond(0);
        // TTS is created on first speech, so a silent reply leaves no engine at all.
        assertThat(ShadowTextToSpeech.getLastTextToSpeechInstance()).isNull();

        activity.askAgent("find coffee");
        gateway.respond(1, null, "Two cafes on your route");
        TextToSpeech tts = ShadowTextToSpeech.getLastTextToSpeechInstance();
        assertThat(tts).isNotNull();
        shadowOf(tts).getOnInitListener().onInit(TextToSpeech.SUCCESS);
        assertThat(shadowOf(tts).getLastSpokenText()).isEqualTo("Two cafes on your route");
    }

    @Test
    public void switchingProvidersNeverTouchesTheCorpusOrTheHostSink() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        FakeGateway gateway = attach(activity);
        Set<String> corpus = new HashSet<>(activity.mProcessor.getSurfaces().keySet());
        assertThat(corpus).containsAtLeast(DemoTrace.SURFACE_ID, "now_playing", "big_buck_bunny");
        activity.askAgent("find coffee");
        gateway.respond(0);
        assertThat(activity.mShowingAgent).isTrue();
        assertThat(activity.mAgent.processor()).isNotSameInstanceAs(activity.mProcessor);

        for (int i = 0; i < AgentProvider.values().length; i++) {
            assertThat(activity.cycleProvider()).isTrue();
        }

        assertThat(activity.mAgent.provider()).isEqualTo(AgentProvider.CLAUDE);
        assertThat(activity.mProcessor.getSurfaces().keySet()).containsExactlyElementsIn(corpus);
        assertThat(activity.mShowingAgent).isFalse();
        assertThat(activity.mAgent.processor().getSurfaces()).isEmpty();
        // The host's own sink still answers a corpus action: send-to-driver opens its picker. Had
        // the lane's registry replaced it, this unknown action id would be refused silently.
        activity.mProcessor.confirmAction("handoff_to_driver", "send", "demo.handoff_to_driver",
                null, null, reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog picker = ShadowAlertDialog.getLatestAlertDialog();
        assertThat(picker).isNotNull();
        TextView title = picker.findViewById(com.android.car.ui.R.id.car_ui_alert_title);
        assertThat(title.getText().toString()).isEqualTo("Handoff destination");
    }

    @Test
    public void agentSurfacesArePrunedExactlyLikeCorpusSurfaces() {
        A2uiCarUiActivity activity =
                started(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED);
        FakeGateway gateway = attach(activity);
        activity.askAgent("find coffee");
        assertThat(gateway.request(0)).contains("\"activeRestrictions\":511");
        gateway.respond(0);

        assertThat(activity.mShowingAgent).isTrue();
        assertThat(titles(activity)).containsAtLeast("DEMO cafe", "Order ahead", "Play");
        assertThat(titles(activity)).containsNoneOf("Tour", "Search");
        assertThat(row(activity, "Order ahead").getOnClickListener()).isNull();
        assertThat(row(activity, "Play").getOnClickListener()).isNotNull();

        // Parked, the same agent surface shows what the restriction withheld.
        CarUxRestrictionsUtil.getInstance(activity)
                .setUxRestrictions(reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(titles(activity)).containsAtLeast("Tour", "Search");
        assertThat(row(activity, "Order ahead").getOnClickListener()).isNotNull();
    }

    @Test
    public void anUndeliveredReadingFailsClosedForAgentSurfaces() {
        A2uiCarUiActivity activity =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create().start().get();
        FakeGateway gateway = attach(activity);
        activity.askAgent("find coffee");
        assertThat(gateway.request(0)).contains("\"trustworthy\":false");
        gateway.respond(0);

        assertThat(activity.mShowingAgent).isTrue();
        assertThat(titles(activity)).contains("DEMO cafe");
        assertThat(titles(activity)).containsNoneOf("Tour", "Search");
        assertThat(row(activity, "Order ahead").getOnClickListener()).isNull();
    }

    @Test
    public void aRowFromAReplacedAgentSurfaceRefusesInsteadOfCrashing() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        FakeGateway gateway = attach(activity);
        activity.askAgent("find coffee");
        gateway.respond(0);
        CarUiContentListItem stale = row(activity, "Play");

        activity.askAgent("something else");
        stale.getOnClickListener().onClick(stale);

        assertThat(String.valueOf(ShadowToast.getTextOfLatestToast()))
                .contains("conditions changed");
    }

    @Test
    public void aFailedTurnReplaysTheLastGoodResponseToTheSameAsk() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        FakeGateway gateway = attach(activity);
        activity.askAgent("find coffee");
        gateway.respond(0);
        activity.askAgent("find coffee");
        assertThat(gateway.request(1)).isEqualTo(gateway.request(0));

        gateway.fail(1);
        assertThat(activity.mShowingAgent).isTrue();
        assertThat(activity.mAgentBadge).isEqualTo("claude · fallback");

        activity.askAgent("never answered");
        gateway.fail(2);
        assertThat(activity.mShowingAgent).isFalse();
        assertThat(activity.mAgentBadge).isEqualTo("claude · failed");
    }

    /** B3: a thinking row stands in for streaming, and every way a turn ends takes it away. */
    @Test
    public void aPendingTurnShowsThinking_andResponseFallbackAndErrorEachClearIt() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        FakeGateway gateway = attach(activity);

        activity.askAgent("find coffee");
        assertThat(thinking(activity)).isTrue();
        assertThat(activity.mAgentBadge).isEqualTo("claude · …");
        gateway.respond(0);
        assertThat(thinking(activity)).isFalse();
        assertThat(activity.mShowingAgent).isTrue();

        activity.askAgent("find coffee");
        assertThat(thinking(activity)).isTrue();
        gateway.fail(1);
        assertThat(activity.mAgentBadge).isEqualTo("claude · fallback");
        assertThat(thinking(activity)).isFalse();

        activity.askAgent("never answered");
        assertThat(thinking(activity)).isTrue();
        gateway.fail(2);
        assertThat(activity.mAgentBadge).isEqualTo("claude · failed");
        assertThat(thinking(activity)).isFalse();
    }

    /** A switch drops the in-flight turn; its row and its "…" badge must not outlive it. */
    @Test
    public void switchingProviderMidTurnClearsThePendingRowAndBadge() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        FakeGateway gateway = attach(activity);
        activity.askAgent("find coffee");
        assertThat(thinking(activity)).isTrue();

        assertThat(activity.cycleProvider()).isTrue();

        assertThat(thinking(activity)).isFalse();
        assertThat(activity.mAgentBadge).isNull();
        TextView badge = activity.findViewById(R.id.status_badge);
        assertThat(badge.getText().toString()).doesNotContain("…");
        // The dropped turn's late answer changes nothing.
        gateway.respond(0);
        assertThat(activity.mShowingAgent).isFalse();
        assertThat(thinking(activity)).isFalse();
    }

    /** Re-selecting the same provider keeps the turn, so its row and badge stay too. */
    @Test
    public void reselectingTheSameProviderKeepsThePendingTurn() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        FakeGateway gateway = attach(activity);
        activity.askAgent("find coffee");

        assertThat(activity.selectAgentProvider(activity.mAgent.provider())).isTrue();

        assertThat(thinking(activity)).isTrue();
        assertThat(activity.mAgentBadge).endsWith("…");
        gateway.respond(0);
        assertThat(activity.mShowingAgent).isTrue();
        assertThat(thinking(activity)).isFalse();
    }

    /** The chip re-asks and replaces the screen, so it is gated like Speak: parked and trusted. */
    @Test
    public void theProviderChipIsWithheldWhileMovingOrUntrusted() {
        A2uiCarUiActivity activity =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create().start().get();
        FakeGateway gateway = attach(activity);
        assertThat(activity.mProviderItem.isEnabled()).isFalse();

        CarUxRestrictionsUtil restrictions = CarUxRestrictionsUtil.getInstance(activity);
        restrictions.setUxRestrictions(reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
        assertThat(activity.mProviderItem.isEnabled()).isTrue();
        activity.askAgent("find coffee");
        gateway.respond(0);
        assertThat(activity.mShowingAgent).isTrue();

        restrictions.setUxRestrictions(
                reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED));
        assertThat(activity.mProviderItem.isEnabled()).isFalse();
        assertThat(activity.cycleProvider()).isFalse();
        assertThat(activity.mAgent.provider()).isEqualTo(AgentProvider.CLAUDE);
        assertThat(gateway.mRequests).hasSize(1);
        assertThat(activity.mShowingAgent).isTrue();
        assertThat(String.valueOf(ShadowToast.getTextOfLatestToast()))
                .isEqualTo(A2uiActionBinder.REFUSED_NOTE);
    }

    @Test
    public void theBadgeNamesACachedOrFallbackAnswerInsteadOfALatency() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        FakeGateway gateway = attach(activity);
        activity.askAgent("find coffee");
        gateway.respond(0, "cache");
        assertThat(activity.mAgentBadge).isEqualTo("claude · cached");
        activity.askAgent("find tea");
        gateway.respond(1, "fallback");
        assertThat(activity.mAgentBadge).isEqualTo("claude · fallback");
        activity.askAgent("find juice");
        gateway.respond(2, "live");
        assertThat(activity.mAgentBadge).matches("claude · \\d+ ms");
    }

    @Test
    public void actionResultsCarryTheRealOutcome() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        FakeGateway gateway = attach(activity);
        activity.askAgent("find coffee");
        gateway.respond(0);

        CarUiContentListItem play = row(activity, "Play");
        play.getOnClickListener().onClick(play);
        assertThat(gateway.lastActionResult()).contains("\"actionId\":\"play\"");
        assertThat(gateway.lastActionResult()).contains("\"outcome\":\"ALLOWED\"");

        CarUiContentListItem order = row(activity, "Order ahead");
        order.getOnClickListener().onClick(order);
        shadowOf(Looper.getMainLooper()).idle();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(gateway.lastActionResult()).contains("\"actionId\":\"purchase\"");
        assertThat(gateway.lastActionResult()).contains("\"outcome\":\"CONFIRMED\"");

        // The voice path reports through the same lane.
        activity.onHeard("play");
        assertThat(gateway.actionResults()).isEqualTo(3);
        assertThat(gateway.lastActionResult()).contains("\"outcome\":\"ALLOWED\"");
        activity.onHeard("order ahead");
        shadowOf(Looper.getMainLooper()).idle();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(gateway.actionResults()).isEqualTo(4);
        assertThat(gateway.lastActionResult()).contains("\"outcome\":\"CONFIRMED\"");

        // Drawn while parked, pressed once moving: refused, and reported as refused.
        CarUxRestrictionsUtil.getInstance(activity).setUxRestrictions(
                reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED));
        shadowOf(Looper.getMainLooper()).idle();
        order.getOnClickListener().onClick(order);
        assertThat(gateway.lastActionResult()).contains("\"outcome\":\"REFUSED\"");
    }

    @Test
    public void internetAndTheNetworkConfigAreDebugOnly() throws Exception {
        String release = System.getProperty("a2ui.releaseManifest");
        String debug = System.getProperty("a2ui.debugManifest");
        assumeTrue("set for testDebugUnitTest by a2ui-host-carui/build.gradle",
                release != null && debug != null);
        Element releaseApp = application(release);
        assertThat(permissions(release)).doesNotContain(Manifest.permission.INTERNET);
        assertThat(releaseApp.hasAttributeNS(ANDROID_NS, "networkSecurityConfig")).isFalse();
        // Anti-vacuity: the debug variant merges both, and Robolectric runs that variant.
        assertThat(permissions(debug)).contains(Manifest.permission.INTERNET);
        assertThat(application(debug).getAttributeNS(ANDROID_NS, "networkSecurityConfig"))
                .startsWith("@xml/a2ui_network_security_config");
        PackageInfo info = mApp.getPackageManager()
                .getPackageInfo(mApp.getPackageName(), PackageManager.GET_PERMISSIONS);
        assertThat(Arrays.asList(info.requestedPermissions))
                .contains(Manifest.permission.INTERNET);
    }

    @Test
    public void badgeLabelsTurnIdsAndTheActionVocabulary() throws Exception {
        assertThat(AgentLane.badge(AgentProvider.MUSE, 812, "live", false))
                .isEqualTo("muse · 812 ms");
        assertThat(AgentLane.badge(AgentProvider.MUSE, 812, null, false))
                .isEqualTo("muse · 812 ms");
        assertThat(AgentLane.badge(AgentProvider.GROK, 3, "cache", false))
                .isEqualTo("grok · cached");
        assertThat(AgentLane.badge(AgentProvider.GROK, 2600, "fallback", false))
                .isEqualTo("grok · fallback");
        assertThat(AgentLane.badge(AgentProvider.GROK, 40, "live", true))
                .isEqualTo("grok · fallback");
        Set<String> labels = new HashSet<>();
        for (AgentProvider provider : AgentProvider.values()) {
            labels.add(A2uiCarUiActivity.providerLabel(provider));
        }
        assertThat(labels).hasSize(AgentProvider.values().length);
        String id = AgentLane.turnId(AgentProvider.CLAUDE,
                AgentExecutionProfile.REALTIME_LOW_LATENCY, "find coffee");
        assertThat(id).matches("ask-[0-9a-f]{24}");
        assertThat(AgentLane.turnId(AgentProvider.MUSE,
                AgentExecutionProfile.REALTIME_LOW_LATENCY, "find coffee")).isNotEqualTo(id);
        // The trusted ids are exactly the vocabulary the gateway constrains vendors to.
        Matcher list = Pattern.compile("ACTIONS = \\[([^\\]]*)\\]")
                .matcher(repoFile("tools/a2ui-gateway/a2ui_gateway/contract.py"));
        assertThat(list.find()).isTrue();
        List<String> gateway = new ArrayList<>();
        Matcher quoted = Pattern.compile("\"([a-z._:-]+)\"").matcher(list.group(1));
        while (quoted.find()) gateway.add(quoted.group(1));
        assertThat(AgentLane.ACTIONS).containsExactlyElementsIn(gateway).inOrder();
    }

    // ---------------------------------------------------------------------------------------

    private static A2uiCarUiActivity started(int mask) {
        ActivityController<A2uiCarUiActivity> controller =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create();
        CarUxRestrictionsUtil.getInstance(controller.get()).setUxRestrictions(reading(mask));
        return controller.start().get();
    }

    private static FakeGateway attach(A2uiCarUiActivity activity) {
        FakeGateway gateway = new FakeGateway();
        activity.setAgentTransportForTesting(gateway);
        return gateway;
    }

    private static CarUxRestrictions reading(int mask) {
        return new CarUxRestrictions.Builder(mask != 0, mask, 0).build();
    }

    private static A2uiMessageProcessor.RestrictionReader reader(int mask) {
        return new A2uiMessageProcessor.RestrictionReader() {
            @Override public int activeRestrictions() { return mask; }
            @Override public boolean isTrustworthy() { return true; }
        };
    }

    private List<String> agentReceivers() {
        List<String> found = new ArrayList<>();
        for (ShadowApplication.Wrapper wrapper : shadowOf(mApp).getRegisteredReceivers()) {
            IntentFilter filter = wrapper.getIntentFilter();
            if (filter != null && filter.hasAction(DebugBroadcasts.ACTION_AGENT)) {
                found.add(DebugBroadcasts.ACTION_AGENT);
            }
        }
        return found;
    }

    private static List<String> titles(A2uiCarUiActivity activity) {
        List<String> titles = new ArrayList<>();
        for (CarUiListItem row : activity.renderedRowsForTesting()) {
            if (row instanceof CarUiContentListItem
                    && ((CarUiContentListItem) row).getTitle() != null) {
                titles.add(((CarUiContentListItem) row).getTitle().getPreferredText().toString());
            }
        }
        return titles;
    }

    /** The thinking row leads the list exactly while a turn is pending. */
    private static boolean thinking(A2uiCarUiActivity activity) {
        boolean row = titles(activity).contains(activity.getString(R.string.a2ui_brand_thinking_title));
        assertThat(row).isEqualTo(activity.mAgentThinking);
        return row;
    }

    private static CarUiContentListItem row(A2uiCarUiActivity activity, String title) {
        for (CarUiListItem row : activity.renderedRowsForTesting()) {
            if (row instanceof CarUiContentListItem
                    && ((CarUiContentListItem) row).getTitle() != null
                    && title.contentEquals(
                            ((CarUiContentListItem) row).getTitle().getPreferredText())) {
                return (CarUiContentListItem) row;
            }
        }
        throw new AssertionError("no row titled " + title + " in " + titles(activity));
    }

    private static Element application(String manifest) throws Exception {
        return (Element) parse(manifest).getElementsByTagName("application").item(0);
    }

    private static List<String> permissions(String manifest) throws Exception {
        List<String> names = new ArrayList<>();
        NodeList nodes = parse(manifest).getElementsByTagName("uses-permission");
        for (int i = 0; i < nodes.getLength(); i++) {
            names.add(((Element) nodes.item(i)).getAttributeNS(ANDROID_NS, "name"));
        }
        return names;
    }

    private static Document parse(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new File(path));
    }

    private static String repoFile(String relative) throws Exception {
        File dir = new File(".").getCanonicalFile();
        while (dir != null && !new File(dir, relative).isFile()) {
            dir = dir.getParentFile();
        }
        assertThat(dir).isNotNull();
        return new String(Files.readAllBytes(new File(dir, relative).toPath()),
                StandardCharsets.UTF_8);
    }

    /** Records requests and answers each with a well-formed envelope bound to it. */
    private static final class FakeGateway implements AgentTransport {
        private static final Pattern TURN = Pattern.compile("\"turnId\":\"([^\"]+)\"");
        private static final Pattern PROVIDER = Pattern.compile("\"provider\":\"([^\"]+)\"");
        final List<byte[]> mRequests = new ArrayList<>();
        final List<Callback> mCallbacks = new ArrayList<>();

        @Override
        public void send(byte[] request, Executor executor, Callback callback) {
            mRequests.add(request.clone());
            mCallbacks.add(callback);
        }

        @Override
        public void close() {
        }

        String request(int index) {
            return new String(mRequests.get(index), StandardCharsets.UTF_8);
        }

        String lastActionResult() {
            for (int i = mRequests.size() - 1; i >= 0; i--) {
                if (request(i).contains("\"type\":\"actionResult\"")) return request(i);
            }
            throw new AssertionError("no action result was sent");
        }

        int actionResults() {
            int count = 0;
            for (int i = 0; i < mRequests.size(); i++) {
                if (request(i).contains("\"type\":\"actionResult\"")) count++;
            }
            return count;
        }

        void respond(int index) {
            respond(index, null);
        }

        /** Answers as the gateway would, optionally saying the output came from its cache. */
        void respond(int index, String source) {
            respond(index, source, null);
        }

        void respond(int index, String source, String assistantText) {
            Matcher turn = TURN.matcher(request(index));
            Matcher provider = PROVIDER.matcher(request(index));
            assertThat(turn.find() && provider.find()).isTrue();
            String body = "{\"schema\":\"a2ui.gateway.v1\",\"provider\":\"" + provider.group(1)
                    + "\",\"sessionId\":\"" + AgentLane.SESSION_ID + "\",\"turnId\":\""
                    + turn.group(1) + "\"" + (source == null ? "" : ",\"source\":\"" + source + "\"")
                    + (assistantText == null ? ""
                            : ",\"assistantText\":\"" + assistantText + "\"")
                    + ",\"messages\":" + MESSAGES + "}";
            mCallbacks.get(index).onResponse(body.getBytes(StandardCharsets.UTF_8));
            shadowOf(Looper.getMainLooper()).idle();
        }

        void fail(int index) {
            mCallbacks.get(index).onFailure(new AgentTransportException(
                    AgentTransportException.Kind.NETWORK, "offline"));
            shadowOf(Looper.getMainLooper()).idle();
        }
    }
}
