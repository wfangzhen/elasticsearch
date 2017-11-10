/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.restart;

import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.Version;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.Booleans;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.test.StreamsUtils;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.xpack.common.text.TextTemplate;
import org.elasticsearch.xpack.monitoring.exporter.MonitoringTemplateUtils;
import org.elasticsearch.xpack.security.SecurityClusterClientYamlTestCase;
import org.elasticsearch.xpack.security.support.IndexLifecycleManager;
import org.elasticsearch.xpack.test.rest.XPackRestTestCase;
import org.elasticsearch.xpack.watcher.actions.logging.LoggingAction;
import org.elasticsearch.xpack.watcher.client.WatchSourceBuilder;
import org.elasticsearch.xpack.watcher.condition.AlwaysCondition;
import org.elasticsearch.xpack.watcher.support.xcontent.ObjectPath;
import org.elasticsearch.xpack.watcher.trigger.schedule.IntervalSchedule;
import org.elasticsearch.xpack.watcher.trigger.schedule.ScheduleTrigger;
import org.junit.Before;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

public class FullClusterRestartIT extends ESRestTestCase {
    private final boolean runningAgainstOldCluster = Booleans.parseBoolean(System.getProperty("tests.is_old_cluster"));
    private final Version oldClusterVersion = Version.fromString(System.getProperty("tests.old_cluster_version"));

    @Before
    public void waitForSecuritySetup() throws Exception {
        SecurityClusterClientYamlTestCase.waitForSecurity();
    }

    @Before
    public void waitForMlTemplates() throws Exception {
        XPackRestTestCase.waitForMlTemplates();
    }

    @Override
    protected boolean preserveIndicesUponCompletion() {
        return true;
    }

    @Override
    protected boolean preserveSnapshotsUponCompletion() {
        return true;
    }

    @Override
    protected boolean preserveReposUponCompletion() {
        return true;
    }

    @Override
    protected boolean preserveTemplatesUponCompletion() {
        return true;
    }

    @Override
    protected Settings restClientSettings() {
        String token = "Basic " + Base64.getEncoder().encodeToString("test_user:x-pack-test-password".getBytes(StandardCharsets.UTF_8));
        return Settings.builder()
                .put(ThreadContext.PREFIX + ".Authorization", token)
                // we increase the timeout here to 90 seconds to handle long waits for a green
                // cluster health. the waits for green need to be longer than a minute to
                // account for delayed shards
                .put(ESRestTestCase.CLIENT_RETRY_TIMEOUT, "90s")
                .put(ESRestTestCase.CLIENT_SOCKET_TIMEOUT, "90s")
                .build();
    }

    /**
     * Tests that a single document survives. Super basic smoke test.
     */
    public void testSingleDoc() throws IOException {
        String docLocation = "/testsingledoc/doc/1";
        String doc = "{\"test\": \"test\"}";

        if (runningAgainstOldCluster) {
            client().performRequest("PUT", docLocation, singletonMap("refresh", "true"),
                    new StringEntity(doc, ContentType.APPLICATION_JSON));
        }

        assertThat(toStr(client().performRequest("GET", docLocation)), containsString(doc));
    }

    @SuppressWarnings("unchecked")
    public void testSecurityNativeRealm() throws Exception {
        if (runningAgainstOldCluster) {
            createUser("preupgrade_user");
            createRole("preupgrade_role");
        } else {
            waitForYellow(".security");
            Response settingsResponse = client().performRequest("GET", "/.security/_settings/index.format");
            Map<String, Object> settingsResponseMap = toMap(settingsResponse);
            logger.info("settings response map {}", settingsResponseMap);
            final boolean needsUpgrade;
            final String concreteSecurityIndex;
            if (settingsResponseMap.isEmpty()) {
                needsUpgrade = true;
                concreteSecurityIndex = ".security";
            } else {
                concreteSecurityIndex = settingsResponseMap.keySet().iterator().next();
                Map<String, Object> indexSettingsMap =
                        (Map<String, Object>) settingsResponseMap.get(concreteSecurityIndex);
                Map<String, Object> settingsMap = (Map<String, Object>) indexSettingsMap.get("settings");
                logger.info("settings map {}", settingsMap);
                if (settingsMap.containsKey("index")) {
                    int format = Integer.parseInt(String.valueOf(((Map<String, Object>)settingsMap.get("index")).get("format")));
                    needsUpgrade = format == IndexLifecycleManager.INTERNAL_INDEX_FORMAT ? false : true;
                } else {
                    needsUpgrade = true;
                }
            }

            if (needsUpgrade) {
                logger.info("upgrading security index {}", concreteSecurityIndex);
                // without upgrade, an error should be thrown
                try {
                    createUser("postupgrade_user");
                    fail("should not be able to add a user when upgrade hasn't taken place");
                } catch (ResponseException e) {
                    assertThat(e.getMessage(), containsString("Security index is not on the current version - " +
                            "the native realm will not be operational until the upgrade API is run on the security index"));
                }
                // run upgrade API
                Response upgradeResponse = client().performRequest("POST", "_xpack/migration/upgrade/" + concreteSecurityIndex);
                logger.info("upgrade response:\n{}", toStr(upgradeResponse));
            }

            // create additional user and role
            createUser("postupgrade_user");
            createRole("postupgrade_role");
        }

        assertUserInfo("preupgrade_user");
        assertRoleInfo("preupgrade_role");
        if (!runningAgainstOldCluster) {
            assertUserInfo("postupgrade_user");
            assertRoleInfo("postupgrade_role");
        }
    }

    @SuppressWarnings("unchecked")
    public void testMonitoring() throws Exception {
        waitForYellow(".monitoring-es-*");

        if (runningAgainstOldCluster == false) {
            waitForMonitoringTemplates();
        }

        // ensure that monitoring [re]starts and creates the core monitoring document, cluster_stats, for the current cluster
        final Map<String, Object> response = toMap(client().performRequest("GET", "/"));
        final Map<String, Object> version = (Map<String, Object>) response.get("version");
        final String expectedVersion = (String) version.get("number");

        waitForClusterStats(expectedVersion);
    }

    public void testWatcher() throws Exception {
        if (runningAgainstOldCluster) {
            logger.info("Adding a watch on old cluster");
            client().performRequest("PUT", "_xpack/watcher/watch/bwc_watch", emptyMap(),
                    new StringEntity(loadWatch("simple-watch.json"), ContentType.APPLICATION_JSON));

            logger.info("Adding a watch with \"fun\" throttle periods on old cluster");
            client().performRequest("PUT", "_xpack/watcher/watch/bwc_throttle_period", emptyMap(),
                    new StringEntity(loadWatch("throttle-period-watch.json"), ContentType.APPLICATION_JSON));

            logger.info("Adding a watch with \"fun\" read timeout on old cluster");
            client().performRequest("PUT", "_xpack/watcher/watch/bwc_funny_timeout", emptyMap(),
                    new StringEntity(loadWatch("funny-timeout-watch.json"), ContentType.APPLICATION_JSON));

            logger.info("Waiting for watch results index to fill up...");
            waitForYellow(".watches,bwc_watch_index,.watcher-history*");
            waitForHits("bwc_watch_index", 2);
            waitForHits(".watcher-history*", 2);
            logger.info("Done creating watcher-related indices");
        } else {
            logger.info("testing against {}", oldClusterVersion);
            waitForYellow(".watches,bwc_watch_index,.watcher-history*");

            logger.info("checking if the upgrade procedure on the new cluster is required");
            Map<String, Object> response = toMap(client().performRequest("GET", "/_xpack/migration/assistance"));
            logger.info(response);

            @SuppressWarnings("unchecked") Map<String, Object> indices = (Map<String, Object>) response.get("indices");
            if (indices.containsKey(".watches") || indices.containsKey(".triggered_watches")) {
                logger.info("checking if upgrade procedure is required for watcher");
                assertThat(indices.entrySet().size(), greaterThanOrEqualTo(1));
                assertThat(indices.get(".watches"), notNullValue());
                @SuppressWarnings("unchecked") Map<String, Object> index = (Map<String, Object>) indices.get(".watches");
                assertThat(index.get("action_required"), equalTo("upgrade"));
                String watchIndexUpgradeRequired = index.get("action_required").toString();

                assertThat(indices.entrySet().size(), greaterThanOrEqualTo(1));
                assertThat(indices.get(".triggered_watches"), notNullValue());
                @SuppressWarnings("unchecked") Map<String, Object> triggeredWatchIndex =
                        (Map<String, Object>) indices.get(".triggered_watches");
                assertThat(triggeredWatchIndex.get("action_required"), equalTo("upgrade"));
                String triggeredWatchIndexUpgradeRequired = index.get("action_required").toString();

                logger.info("starting upgrade procedure on the new cluster");

                Map<String, String> params = Collections.singletonMap("error_trace", "true");
                if ("upgrade".equals(watchIndexUpgradeRequired)) {
                    Map<String, Object> upgradeResponse =
                            toMap(client().performRequest("POST", "_xpack/migration/upgrade/.watches", params));
                    assertThat(upgradeResponse.get("timed_out"), equalTo(Boolean.FALSE));
                    // we posted 3 watches, but monitoring can post a few more
                    assertThat((int) upgradeResponse.get("total"), greaterThanOrEqualTo(3));
                }
                if ("upgrade".equals(triggeredWatchIndexUpgradeRequired)) {
                    Map<String, Object> upgradeResponse =
                            toMap(client().performRequest("POST", "_xpack/migration/upgrade/.triggered_watches", params));
                    assertThat(upgradeResponse.get("timed_out"), equalTo(Boolean.FALSE));
                }

                logger.info("checking that upgrade procedure on the new cluster is no longer required");
                Map<String, Object> responseAfter = toMap(client().performRequest("GET", "/_xpack/migration/assistance"));
                logger.info("checking upgrade procedure required after upgrade: [{}]", responseAfter);
                @SuppressWarnings("unchecked") Map<String, Object> indicesAfter = (Map<String, Object>) responseAfter.get("indices");
                assertThat(indicesAfter, not(hasKey(".watches")));
                assertThat(indicesAfter, not(hasKey(".triggered_watches")));
            } else {
                logger.info("upgrade procedure is not required for watcher");
            }

            // Wait for watcher to actually start....
            Map<String, Object> startWatchResponse = toMap(client().performRequest("POST", "_xpack/watcher/_start"));
            assertThat(startWatchResponse.get("acknowledged"), equalTo(Boolean.TRUE));
            assertBusy(() -> {
                Map<String, Object> statsWatchResponse = toMap(client().performRequest("GET", "_xpack/watcher/stats"));
                @SuppressWarnings("unchecked")
                List<Object> states = ((List<Object>) statsWatchResponse.get("stats"))
                        .stream().map(o -> ((Map<String, Object>) o).get("watcher_state")).collect(Collectors.toList());
                assertThat(states, everyItem(is("started")));
            });

            try {
                assertOldTemplatesAreDeleted();
                assertWatchIndexContentsWork();
                assertBasicWatchInteractions();
            } finally {
                /* Shut down watcher after every test because watcher can be a bit finicky about shutting down when the node shuts
                 * down. This makes super sure it shuts down *and* causes the test to fail in a sensible spot if it doesn't shut down.
                 */
                Map<String, Object> stopWatchResponse = toMap(client().performRequest("POST", "_xpack/watcher/_stop"));
                assertThat(stopWatchResponse.get("acknowledged"), equalTo(Boolean.TRUE));
                assertBusy(() -> {
                    Map<String, Object> statsStoppedWatchResponse = toMap(client().performRequest("GET", "_xpack/watcher/stats"));
                    @SuppressWarnings("unchecked")
                    List<Object> states = ((List<Object>) statsStoppedWatchResponse.get("stats"))
                            .stream().map(o -> ((Map<String, Object>) o).get("watcher_state")).collect(Collectors.toList());
                    assertThat(states, everyItem(is("stopped")));
                });
            }
        }
    }

    private String loadWatch(String watch) throws IOException {
        return StreamsUtils.copyToStringFromClasspath("/org/elasticsearch/xpack/restart/" + watch);
    }

    @SuppressWarnings("unchecked")
    private void assertOldTemplatesAreDeleted() throws IOException {
        Map<String, Object> templates = toMap(client().performRequest("GET", "/_template"));
        assertThat(templates.keySet(), not(hasItems(is("watches"), startsWith("watch-history"), is("triggered_watches"))));
    }

    @SuppressWarnings("unchecked")
    private void assertWatchIndexContentsWork() throws Exception {
        // Fetch a basic watch
        Map<String, Object> bwcWatch = toMap(client().performRequest("GET", "_xpack/watcher/watch/bwc_watch"));

        logger.error("-----> {}", bwcWatch);

        assertThat(bwcWatch.get("found"), equalTo(true));
        Map<String, Object> source = (Map<String, Object>) bwcWatch.get("watch");
        assertEquals(1000, source.get("throttle_period_in_millis"));
        int timeout = (int) timeValueSeconds(100).millis();
        assertThat(ObjectPath.eval("input.search.timeout_in_millis", source), equalTo(timeout));
        assertThat(ObjectPath.eval("actions.index_payload.transform.search.timeout_in_millis", source), equalTo(timeout));
        assertThat(ObjectPath.eval("actions.index_payload.index.index", source), equalTo("bwc_watch_index"));
        assertThat(ObjectPath.eval("actions.index_payload.index.doc_type", source), equalTo("bwc_watch_type"));
        assertThat(ObjectPath.eval("actions.index_payload.index.timeout_in_millis", source), equalTo(timeout));

        // Fetch a watch with "fun" throttle periods
        bwcWatch = toMap(client().performRequest("GET", "_xpack/watcher/watch/bwc_throttle_period"));
        assertThat(bwcWatch.get("found"), equalTo(true));
        source = (Map<String, Object>) bwcWatch.get("watch");
        assertEquals(timeout, source.get("throttle_period_in_millis"));
        assertThat(ObjectPath.eval("actions.index_payload.throttle_period_in_millis", source), equalTo(timeout));

        /*
         * Fetch a watch with a funny timeout to verify loading fractional time
         * values.
         */
        bwcWatch = toMap(client().performRequest("GET", "_xpack/watcher/watch/bwc_funny_timeout"));
        assertThat(bwcWatch.get("found"), equalTo(true));
        source = (Map<String, Object>) bwcWatch.get("watch");


        Map<String, Object> attachments = ObjectPath.eval("actions.work.email.attachments", source);
        Map<String, Object> attachment = (Map<String, Object>) attachments.get("test_report.pdf");
        Map<String, Object>  request =  ObjectPath.eval("http.request", attachment);
        assertEquals(timeout, request.get("read_timeout_millis"));
        assertEquals("https", request.get("scheme"));
        assertEquals("example.com", request.get("host"));
        assertEquals("{{ctx.metadata.report_url}}", request.get("path"));
        assertEquals(8443, request.get("port"));
        Map<?, ?> basic = ObjectPath.eval("auth.basic", request);
        assertThat(basic, hasEntry("username", "Aladdin"));
        // password doesn't come back because it is hidden
        assertThat(basic, not(hasKey("password")));

        Map<String, Object> history = toMap(client().performRequest("GET", ".watcher-history*/_search"));
        Map<String, Object> hits = (Map<String, Object>) history.get("hits");
        assertThat((int) (hits.get("total")), greaterThanOrEqualTo(2));
    }

    private void assertBasicWatchInteractions() throws Exception {

        String watch = new WatchSourceBuilder()
                .condition(AlwaysCondition.INSTANCE)
                .trigger(ScheduleTrigger.builder(new IntervalSchedule(IntervalSchedule.Interval.seconds(1))))
                .addAction("awesome", LoggingAction.builder(new TextTemplate("test"))).buildAsBytes(XContentType.JSON).utf8ToString();
        Map<String, Object> put = toMap(client().performRequest("PUT", "_xpack/watcher/watch/new_watch", emptyMap(),
                new StringEntity(watch, ContentType.APPLICATION_JSON)));

        logger.info(put);

        assertThat(put.get("created"), equalTo(true));
        assertThat(put.get("_version"), equalTo(1));

        put = toMap(client().performRequest("PUT", "_xpack/watcher/watch/new_watch", emptyMap(),
                new StringEntity(watch, ContentType.APPLICATION_JSON)));
        assertThat(put.get("created"), equalTo(false));
        assertThat(put.get("_version"), equalTo(2));

        Map<String, Object> get = toMap(client().performRequest("GET", "_xpack/watcher/watch/new_watch"));
        assertThat(get.get("found"), equalTo(true));
        @SuppressWarnings("unchecked") Map<?, ?> source = (Map<String, Object>) get.get("watch");
        Map<String, Object>  logging = ObjectPath.eval("actions.awesome.logging", source);
        assertEquals("info", logging.get("level"));
        assertEquals("test", logging.get("text"));
    }

    private void waitForYellow(String indexName) throws IOException {
        Map<String, String> params = new HashMap<>();
        params.put("wait_for_status", "yellow");
        params.put("timeout", "30s");
        Map<String, Object> response = toMap(client().performRequest("GET", "/_cluster/health/" + indexName, params));
        assertThat(response.get("timed_out"), equalTo(Boolean.FALSE));
    }

    @SuppressWarnings("unchecked")
    private void waitForHits(String indexName, int expectedHits) throws Exception {
        assertBusy(() -> {
            Map<String, Object> response = toMap(client().performRequest("GET", "/" + indexName + "/_search", singletonMap("size", "0")));
            Map<String, Object> hits = (Map<String, Object>) response.get("hits");
            int total = (int) hits.get("total");
            assertThat(total, greaterThanOrEqualTo(expectedHits));
        }, 30, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private void waitForMonitoringTemplates() throws Exception {
        assertBusy(() -> {
            final Map<String, Object> templates = toMap(client().performRequest("GET", "/_template/.monitoring-*"));

            // in earlier versions, we published legacy templates in addition to the current ones to support transitioning
            assertThat(templates.size(), greaterThanOrEqualTo(MonitoringTemplateUtils.TEMPLATE_IDS.length));

            // every template should be updated to whatever the current version is
            for (final String templateId : MonitoringTemplateUtils.TEMPLATE_IDS) {
                final String templateName = MonitoringTemplateUtils.templateName(templateId);
                final Map<String, Object> template = (Map<String, Object>) templates.get(templateName);

                assertThat(template.get("version"), is(MonitoringTemplateUtils.LAST_UPDATED_VERSION));
            }
        }, 30, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private void waitForClusterStats(final String expectedVersion) throws Exception {
        assertBusy(() -> {
            final Map<String, String> params = new HashMap<>(3);
            params.put("q", "type:cluster_stats _type:cluster_stats");
            params.put("size", "1");
            params.put("sort", "timestamp:desc");

            final Map<String, Object> response = toMap(client().performRequest("GET", "/.monitoring-es-*/_search", params));
            final Map<String, Object> hits = (Map<String, Object>) response.get("hits");

            assertThat("No cluster_stats documents found.", (int)hits.get("total"), greaterThanOrEqualTo(1));

            final Map<String, Object> hit = (Map<String, Object>) ((List<Object>) hits.get("hits")).get(0);
            final Map<String, Object> source = (Map<String, Object>) hit.get("_source");
            assertThat(source.get("version"), is(expectedVersion));
        }, 30, TimeUnit.SECONDS);
    }

    static Map<String, Object> toMap(Response response) throws IOException {
        return toMap(EntityUtils.toString(response.getEntity()));
    }

    static Map<String, Object> toMap(String response) throws IOException {
        return XContentHelper.convertToMap(JsonXContent.jsonXContent, response, false);
    }

    static String toStr(Response response) throws IOException {
        return EntityUtils.toString(response.getEntity());
    }

    private void createUser(final String id) throws Exception {
        final String userJson =
            "{\n" +
            "   \"password\" : \"j@rV1s\",\n" +
            "   \"roles\" : [ \"admin\", \"other_role1\" ],\n" +
            "   \"full_name\" : \"" + randomAlphaOfLength(5) + "\",\n" +
            "   \"email\" : \"" + id + "@example.com\",\n" +
            "   \"enabled\": true\n" +
            "}";

        client().performRequest("PUT", "/_xpack/security/user/" + id, emptyMap(),
            new StringEntity(userJson, ContentType.APPLICATION_JSON));
    }

    private void createRole(final String id) throws Exception {
        final String roleJson =
            "{\n" +
            "  \"run_as\": [ \"abc\" ],\n" +
            "  \"cluster\": [ \"monitor\" ],\n" +
            "  \"indices\": [\n" +
            "    {\n" +
            "      \"names\": [ \"events-*\" ],\n" +
            "      \"privileges\": [ \"read\" ],\n" +
            "      \"field_security\" : {\n" +
            "        \"grant\" : [ \"category\", \"@timestamp\", \"message\" ]\n" +
            "      },\n" +
            "      \"query\": \"{\\\"match\\\": {\\\"category\\\": \\\"click\\\"}}\"\n" +
            "    }\n" +
            "  ]\n" +
            "}";

        client().performRequest("PUT", "/_xpack/security/role/" + id, emptyMap(),
            new StringEntity(roleJson, ContentType.APPLICATION_JSON));
    }

    private void assertUserInfo(final String user) throws Exception {
        Map<String, Object> response = toMap(client().performRequest("GET", "/_xpack/security/user/" + user));
        @SuppressWarnings("unchecked") Map<String, Object> userInfo = (Map<String, Object>) response.get(user);
        assertEquals(user + "@example.com", userInfo.get("email"));
        assertNotNull(userInfo.get("full_name"));
        assertNotNull(userInfo.get("roles"));
    }

    private void assertRoleInfo(final String role) throws Exception {
        @SuppressWarnings("unchecked") Map<String, Object> response = (Map<String, Object>)
                toMap(client().performRequest("GET", "/_xpack/security/role/" + role)).get(role);
        assertNotNull(response.get("run_as"));
        assertNotNull(response.get("cluster"));
        assertNotNull(response.get("indices"));
    }
}
