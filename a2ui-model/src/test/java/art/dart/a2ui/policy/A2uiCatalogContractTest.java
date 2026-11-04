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
package art.dart.a2ui.policy;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import art.dart.a2ui.model.ComponentType;

import org.junit.BeforeClass;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pins {@link A2uiRestrictionPolicy} against the published conformance suite, read from the repo
 * rather than a copied resource: the YAML's hand-written allowed sets must match the derivation.
 */
public class A2uiCatalogContractTest {

    /** Case name -> the components that case allows. */
    private static Map<String, Set<String>> sCases;
    /** Case name -> component keys in the expected pruned catalog. */
    private static Map<String, Set<String>> sExpectedCases;

    @BeforeClass
    @SuppressWarnings("unchecked")
    public static void loadConformanceSuite() throws IOException {
        File yaml = locateConformanceYaml();
        assertWithMessage(
                "conformance suite not found; looked upward from " + new File(".").getAbsolutePath())
                .that(yaml).isNotNull();

        sCases = new LinkedHashMap<>();
        sExpectedCases = new LinkedHashMap<>();
        try (FileInputStream in = new FileInputStream(yaml)) {
            Iterable<Object> documents = new Yaml().loadAll(in);
            for (Object document : documents) {
                if (!(document instanceof List)) {
                    continue;
                }
                for (Object element : (List<Object>) document) {
                    Map<String, Object> testCase = (Map<String, Object>) element;
                    Map<String, Object> args = (Map<String, Object>) testCase.get("args");
                    if (args == null || !args.containsKey("allowed_components")) {
                        continue;
                    }
                    List<String> allowed = (List<String>) args.get("allowed_components");
                    String name = (String) testCase.get("name");
                    sCases.put(name, new HashSet<>(allowed));
                    Map<String, Object> expect = (Map<String, Object>) testCase.get("expect");
                    Map<String, Object> schema = expect == null ? null
                            : (Map<String, Object>) expect.get("catalogSchema");
                    Map<String, Object> components = schema == null ? null
                            : (Map<String, Object>) schema.get("components");
                    assertWithMessage("case %s has no expected catalog components", name)
                            .that(components).isNotNull();
                    sExpectedCases.put(name, new HashSet<>(components.keySet()));
                }
            }
        }
        assertWithMessage("no prune cases parsed out of the conformance suite")
                .that(sCases).isNotEmpty();
    }

    /** Walks up from the working directory to the repo's conformance suite. */
    private static File locateConformanceYaml() throws IOException {
        File dir = new File(".").getCanonicalFile();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParentFile()) {
            File candidate = new File(dir, "a2ui-automotive/conformance/automotive.yaml");
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    // The catalog: the same eighteen names on both sides.

    @Test
    public void enumCoversExactlyTheCatalogTheSuiteDeclares() {
        Set<String> fromEnum = new TreeSet<>();
        for (ComponentType type : ComponentType.values()) {
            fromEnum.add(type.catalogName());
        }
        // The baseline case is by definition the whole catalog.
        Set<String> fromSuite = new TreeSet<>(
                requireCase("test_automotive_baseline_allows_full_catalog"));

        assertThat(fromEnum).isEqualTo(fromSuite);
    }

    @Test
    public void everyNameInEverySuiteCaseResolves() {
        for (Map.Entry<String, Set<String>> entry : sCases.entrySet()) {
            for (String name : entry.getValue()) {
                assertWithMessage("case %s names a component the enum does not have: %s",
                        entry.getKey(), name)
                        .that(ComponentType.fromCatalogName(name)).isNotNull();
            }
        }
    }

    @Test
    public void eachFixturesExpectedCatalogMatchesItsAllowedInput() {
        assertThat(sExpectedCases.keySet()).isEqualTo(sCases.keySet());
        for (String name : sCases.keySet()) {
            assertWithMessage("fixture input and expected catalog disagree for %s", name)
                    .that(sExpectedCases.get(name)).isEqualTo(sCases.get(name));
        }
    }

    // The derivation: each restriction level must produce the set the suite declares.

    @Test
    public void baseline_matchesTheSuite() {
        assertDerivationMatches("test_automotive_baseline_allows_full_catalog",
                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
    }

    @Test
    public void noVideo_matchesTheSuite() {
        assertDerivationMatches("test_automotive_no_video_drops_video_only",
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO);
    }

    @Test
    public void noKeyboard_matchesTheSuite() {
        assertDerivationMatches("test_automotive_no_keyboard_drops_text_entry",
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_KEYBOARD);
    }

    @Test
    public void noFiltering_matchesTheSuite() {
        assertDerivationMatches("test_automotive_no_filtering_drops_search_affordances",
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_FILTERING);
    }

    @Test
    public void noSetup_matchesTheSuite() {
        assertDerivationMatches("test_automotive_no_setup_drops_configuration_components",
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_SETUP);
    }

    @Test
    public void limitContent_matchesTheSuite() {
        assertDerivationMatches("test_automotive_limit_content_drops_deep_navigation",
                A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_CONTENT);
    }

    @Test
    public void fullyRestricted_matchesTheSuite() {
        assertDerivationMatches("test_automotive_fully_restricted_prunes_to_glanceable_set",
                A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    /**
     * The suite's fail-closed case and the policy's fail-closed answer must agree. Noticing an
     * unknown state needs provenance:
     * {@code A2uiShowcaseScreenTest#neverDelivered_failsClosed_evenWhenTheBitsSayBaseline}.
     */
    @Test
    public void drivingStateUnknown_matchesTheSuitesFailClosedSet() {
        assertThat(namesOf(A2uiRestrictionPolicy.failClosedComponents()))
                .isEqualTo(new TreeSet<>(
                        requireCase("test_automotive_driving_state_unknown_fails_closed")));
    }

    private void assertDerivationMatches(String caseName, int restrictions) {
        assertWithMessage("derived allowed_components for %s (0x%s)",
                caseName, Integer.toHexString(restrictions))
                .that(namesOf(A2uiRestrictionPolicy.allowedComponents(restrictions)))
                .isEqualTo(new TreeSet<>(requireCase(caseName)));
    }

    private Set<String> requireCase(String name) {
        Set<String> allowed = sCases.get(name);
        assertWithMessage("conformance case missing: %s (have: %s)", name,
                new ArrayList<>(sCases.keySet()))
                .that(allowed).isNotNull();
        return allowed;
    }

    private static Set<String> namesOf(Set<ComponentType> types) {
        Set<String> names = new TreeSet<>();
        for (ComponentType type : types) {
            names.add(type.catalogName());
        }
        return names;
    }
}
