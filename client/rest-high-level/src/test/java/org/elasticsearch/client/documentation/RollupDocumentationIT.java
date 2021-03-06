/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.client.documentation;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.LatchedActionListener;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.ESRestHighLevelClientTestCase;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.RollupClient;
import org.elasticsearch.client.rollup.DeleteRollupJobRequest;
import org.elasticsearch.client.rollup.DeleteRollupJobResponse;
import org.elasticsearch.client.rollup.GetRollupCapsRequest;
import org.elasticsearch.client.rollup.GetRollupCapsResponse;
import org.elasticsearch.client.rollup.GetRollupJobRequest;
import org.elasticsearch.client.rollup.GetRollupJobResponse;
import org.elasticsearch.client.rollup.GetRollupJobResponse.JobWrapper;
import org.elasticsearch.client.rollup.GetRollupJobResponse.RollupIndexerJobStats;
import org.elasticsearch.client.rollup.GetRollupJobResponse.RollupJobStatus;
import org.elasticsearch.client.rollup.PutRollupJobRequest;
import org.elasticsearch.client.rollup.PutRollupJobResponse;
import org.elasticsearch.client.rollup.RollableIndexCaps;
import org.elasticsearch.client.rollup.RollupJobCaps;
import org.elasticsearch.client.rollup.StartRollupJobRequest;
import org.elasticsearch.client.rollup.StartRollupJobResponse;
import org.elasticsearch.client.rollup.job.config.DateHistogramGroupConfig;
import org.elasticsearch.client.rollup.job.config.GroupConfig;
import org.elasticsearch.client.rollup.job.config.HistogramGroupConfig;
import org.elasticsearch.client.rollup.job.config.MetricConfig;
import org.elasticsearch.client.rollup.job.config.RollupJobConfig;
import org.elasticsearch.client.rollup.job.config.TermsGroupConfig;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.junit.After;
import org.junit.Before;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isOneOf;

public class RollupDocumentationIT extends ESRestHighLevelClientTestCase {

    @Before
    public void setUpDocs() throws IOException {
        final BulkRequest bulkRequest = new BulkRequest();
        bulkRequest.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
        for (int i = 0; i < 50; i++) {
            final IndexRequest indexRequest = new IndexRequest("docs", "doc");
            indexRequest.source(jsonBuilder()
                .startObject()
                .field("timestamp", String.format(Locale.ROOT, "2018-01-01T00:%02d:00Z", i))
                .field("hostname", 0)
                .field("datacenter", 0)
                .field("temperature", 0)
                .field("voltage", 0)
                .field("load", 0)
                .field("net_in", 0)
                .field("net_out", 0)
                .endObject());
            bulkRequest.add(indexRequest);
        }
        BulkResponse bulkResponse = highLevelClient().bulk(bulkRequest, RequestOptions.DEFAULT);
        assertEquals(RestStatus.OK, bulkResponse.status());
        assertFalse(bulkResponse.hasFailures());

        RefreshResponse refreshResponse = highLevelClient().indices().refresh(new RefreshRequest("docs"), RequestOptions.DEFAULT);
        assertEquals(0, refreshResponse.getFailedShards());
    }

    public void testCreateRollupJob() throws Exception {
        RestHighLevelClient client = highLevelClient();

        final String indexPattern = "docs";
        final String rollupIndex = "rollup";
        final String cron = "*/1 * * * * ?";
        final int pageSize = 100;
        final TimeValue timeout = null;

        //tag::x-pack-rollup-put-rollup-job-group-config
        DateHistogramGroupConfig dateHistogram =
            new DateHistogramGroupConfig("timestamp", DateHistogramInterval.HOUR, new DateHistogramInterval("7d"), "UTC"); // <1>
        TermsGroupConfig terms = new TermsGroupConfig("hostname", "datacenter"); // <2>
        HistogramGroupConfig histogram = new HistogramGroupConfig(5L, "load", "net_in", "net_out"); // <3>

        GroupConfig groups = new GroupConfig(dateHistogram, histogram, terms); // <4>
        //end::x-pack-rollup-put-rollup-job-group-config

        //tag::x-pack-rollup-put-rollup-job-metrics-config
        List<MetricConfig> metrics = new ArrayList<>(); // <1>
        metrics.add(new MetricConfig("temperature", Arrays.asList("min", "max", "sum"))); // <2>
        metrics.add(new MetricConfig("voltage", Arrays.asList("avg", "value_count"))); // <3>
        //end::x-pack-rollup-put-rollup-job-metrics-config
        {
            String id = "job_1";

            //tag::x-pack-rollup-put-rollup-job-config
            RollupJobConfig config = new RollupJobConfig(id, // <1>
                indexPattern,  // <2>
                rollupIndex,  // <3>
                cron,  // <4>
                pageSize,  // <5>
                groups,  // <6>
                metrics,  // <7>
                timeout);  // <8>
            //end::x-pack-rollup-put-rollup-job-config

            //tag::x-pack-rollup-put-rollup-job-request
            PutRollupJobRequest request = new PutRollupJobRequest(config); // <1>
            //end::x-pack-rollup-put-rollup-job-request

            //tag::x-pack-rollup-put-rollup-job-execute
            PutRollupJobResponse response = client.rollup().putRollupJob(request, RequestOptions.DEFAULT);
            //end::x-pack-rollup-put-rollup-job-execute

            //tag::x-pack-rollup-put-rollup-job-response
            boolean acknowledged = response.isAcknowledged(); // <1>
            //end::x-pack-rollup-put-rollup-job-response
            assertTrue(acknowledged);
        }
        {
            String id = "job_2";
            RollupJobConfig config = new RollupJobConfig(id, indexPattern, rollupIndex, cron, pageSize, groups, metrics, timeout);
            PutRollupJobRequest request = new PutRollupJobRequest(config);
            // tag::x-pack-rollup-put-rollup-job-execute-listener
            ActionListener<PutRollupJobResponse> listener = new ActionListener<PutRollupJobResponse>() {
                @Override
                public void onResponse(PutRollupJobResponse response) {
                    // <1>
                }

                @Override
                public void onFailure(Exception e) {
                    // <2>
                }
            };
            // end::x-pack-rollup-put-rollup-job-execute-listener

            // Replace the empty listener by a blocking listener in test
            final CountDownLatch latch = new CountDownLatch(1);
            listener = new LatchedActionListener<>(listener, latch);

            // tag::x-pack-rollup-put-rollup-job-execute-async
            client.rollup().putRollupJobAsync(request, RequestOptions.DEFAULT, listener); // <1>
            // end::x-pack-rollup-put-rollup-job-execute-async

            assertTrue(latch.await(30L, TimeUnit.SECONDS));
        }
    }

    @SuppressWarnings("unused")
    public void testGetRollupJob() throws Exception {
        testCreateRollupJob();
        RestHighLevelClient client = highLevelClient();


        // tag::x-pack-rollup-get-rollup-job-request
        GetRollupJobRequest getAll = new GetRollupJobRequest();        // <1>
        GetRollupJobRequest getJob = new GetRollupJobRequest("job_1"); // <2>
        // end::x-pack-rollup-get-rollup-job-request

        // tag::x-pack-rollup-get-rollup-job-execute
        GetRollupJobResponse response = client.rollup().getRollupJob(getJob, RequestOptions.DEFAULT);
        // end::x-pack-rollup-get-rollup-job-execute

        // tag::x-pack-rollup-get-rollup-job-response
        assertThat(response.getJobs(), hasSize(1));
        JobWrapper job = response.getJobs().get(0); // <1>
        RollupJobConfig config = job.getJob();
        RollupJobStatus status = job.getStatus();
        RollupIndexerJobStats stats = job.getStats();
        // end::x-pack-rollup-get-rollup-job-response
        assertNotNull(config);
        assertNotNull(status);
        assertNotNull(status);

        // tag::x-pack-rollup-get-rollup-job-execute-listener
        ActionListener<GetRollupJobResponse> listener = new ActionListener<GetRollupJobResponse>() {
            @Override
            public void onResponse(GetRollupJobResponse response) {
                // <1>
            }

            @Override
            public void onFailure(Exception e) {
                // <2>
            }
        };
        // end::x-pack-rollup-get-rollup-job-execute-listener

        // Replace the empty listener by a blocking listener in test
        final CountDownLatch latch = new CountDownLatch(1);
        listener = new LatchedActionListener<>(listener, latch);

        // tag::x-pack-rollup-get-rollup-job-execute-async
        client.rollup().getRollupJobAsync(getJob, RequestOptions.DEFAULT, listener); // <1>
        // end::x-pack-rollup-get-rollup-job-execute-async

        assertTrue(latch.await(30L, TimeUnit.SECONDS));
    }


    @SuppressWarnings("unused")
    public void testStartRollupJob() throws Exception {
        testCreateRollupJob();
        RestHighLevelClient client = highLevelClient();

        String id = "job_1";
        // tag::rollup-start-job-request
        StartRollupJobRequest request = new StartRollupJobRequest(id); // <1>
        // end::rollup-start-job-request


        try {
            // tag::rollup-start-job-execute
            RollupClient rc = client.rollup();
            StartRollupJobResponse response = rc.startRollupJob(request, RequestOptions.DEFAULT);
            // end::rollup-start-job-execute

            // tag::rollup-start-job-response
            response.isAcknowledged(); // <1>
            // end::rollup-start-job-response
        } catch (Exception e) {
            // Swallow any exception, this test does not test actually cancelling.
        }

        // tag::rollup-start-job-execute-listener
        ActionListener<StartRollupJobResponse> listener = new ActionListener<StartRollupJobResponse>() {
            @Override
            public void onResponse(StartRollupJobResponse response) {
                 // <1>
            }

            @Override
            public void onFailure(Exception e) {
                // <2>
            }
        };
        // end::rollup-start-job-execute-listener

        final CountDownLatch latch = new CountDownLatch(1);
        listener = new LatchedActionListener<>(listener, latch);

        // tag::rollup-start-job-execute-async
        RollupClient rc = client.rollup();
        rc.startRollupJobAsync(request, RequestOptions.DEFAULT, listener); // <1>
        // end::rollup-start-job-execute-async

        assertTrue(latch.await(30L, TimeUnit.SECONDS));

        // stop job so it can correctly be deleted by the test teardown
        // TODO Replace this with the Rollup Stop Job API
        Response stoptResponse = client().performRequest(new Request("POST", "/_xpack/rollup/job/" + id + "/_stop"));
        assertEquals(RestStatus.OK.getStatus(), stoptResponse.getStatusLine().getStatusCode());
    }

    @SuppressWarnings("unused")
    public void testGetRollupCaps() throws Exception {
        RestHighLevelClient client = highLevelClient();

        DateHistogramGroupConfig dateHistogram =
            new DateHistogramGroupConfig("timestamp", DateHistogramInterval.HOUR, new DateHistogramInterval("7d"), "UTC"); // <1>
        TermsGroupConfig terms = new TermsGroupConfig("hostname", "datacenter");
        HistogramGroupConfig histogram = new HistogramGroupConfig(5L, "load", "net_in", "net_out");
        GroupConfig groups = new GroupConfig(dateHistogram, histogram, terms);
        List<MetricConfig> metrics = new ArrayList<>(); // <1>
        metrics.add(new MetricConfig("temperature", Arrays.asList("min", "max", "sum")));
        metrics.add(new MetricConfig("voltage", Arrays.asList("avg", "value_count")));

        //tag::x-pack-rollup-get-rollup-caps-setup
        final String indexPattern = "docs";
        final String rollupIndexName = "rollup";
        final String cron = "*/1 * * * * ?";
        final int pageSize = 100;
        final TimeValue timeout = null;

        String id = "job_1";
        RollupJobConfig config = new RollupJobConfig(id, indexPattern, rollupIndexName, cron,
            pageSize, groups, metrics, timeout);

        PutRollupJobRequest request = new PutRollupJobRequest(config);
        PutRollupJobResponse response = client.rollup().putRollupJob(request, RequestOptions.DEFAULT);

        boolean acknowledged = response.isAcknowledged();
        //end::x-pack-rollup-get-rollup-caps-setup
        assertTrue(acknowledged);

        ClusterHealthRequest healthRequest = new ClusterHealthRequest(config.getRollupIndex()).waitForYellowStatus();
        ClusterHealthResponse healthResponse = client.cluster().health(healthRequest, RequestOptions.DEFAULT);
        assertFalse(healthResponse.isTimedOut());
        assertThat(healthResponse.getStatus(), isOneOf(ClusterHealthStatus.YELLOW, ClusterHealthStatus.GREEN));

        // Now that the job is created, we should have a rollup index with metadata.
        // We can test out the caps API now.

        //tag::x-pack-rollup-get-rollup-caps-request
        GetRollupCapsRequest getRollupCapsRequest = new GetRollupCapsRequest("docs");
        //end::x-pack-rollup-get-rollup-caps-request

        //tag::x-pack-rollup-get-rollup-caps-execute
        GetRollupCapsResponse capsResponse = client.rollup().getRollupCapabilities(getRollupCapsRequest, RequestOptions.DEFAULT);
        //end::x-pack-rollup-get-rollup-caps-execute

        //tag::x-pack-rollup-get-rollup-caps-response
        Map<String, RollableIndexCaps> rolledPatterns = capsResponse.getJobs();

        RollableIndexCaps docsPattern = rolledPatterns.get("docs");

        // indexName will be "docs" in this case... the index pattern that we rolled up
        String indexName = docsPattern.getIndexName();

        // Each index pattern can have multiple jobs that rolled it up, so `getJobCaps()`
        // returns a list of jobs that rolled up the pattern
        List<RollupJobCaps> rollupJobs = docsPattern.getJobCaps();
        RollupJobCaps jobCaps = rollupJobs.get(0);

        // jobID is the identifier we used when we created the job (e.g. `job1`)
        String jobID = jobCaps.getJobID();

        // rollupIndex is the location that the job stored it's rollup docs (e.g. `rollup`)
        String rollupIndex = jobCaps.getRollupIndex();

        // indexPattern is the same as the indexName that we retrieved earlier, redundant info
        assert jobCaps.getIndexPattern().equals(indexName);

        // Finally, fieldCaps are the capabilities of individual fields in the config
        // The key is the field name, and the value is a RollupFieldCaps object which
        // provides more info.
        Map<String, RollupJobCaps.RollupFieldCaps> fieldCaps = jobCaps.getFieldCaps();

        // If we retrieve the "timestamp" field, it returns a list of maps.  Each list
        // item represents a different aggregation that can be run against the "timestamp"
        // field, and any additional details specific to that agg (interval, etc)
        List<Map<String, Object>> timestampCaps = fieldCaps.get("timestamp").getAggs();
        assert timestampCaps.get(0).toString().equals("{agg=date_histogram, delay=7d, interval=1h, time_zone=UTC}");

        // In contrast to the timestamp field, the temperature field has multiple aggs configured
        List<Map<String, Object>> temperatureCaps = fieldCaps.get("temperature").getAggs();
        assert temperatureCaps.toString().equals("[{agg=min}, {agg=max}, {agg=sum}]");
        //end::x-pack-rollup-get-rollup-caps-response

        assertThat(indexName, equalTo("docs"));
        assertThat(jobID, equalTo("job_1"));
        assertThat(rollupIndex, equalTo("rollup"));
        assertThat(fieldCaps.size(), equalTo(8));

        // tag::x-pack-rollup-get-rollup-caps-execute-listener
        ActionListener<GetRollupCapsResponse> listener = new ActionListener<GetRollupCapsResponse>() {
            @Override
            public void onResponse(GetRollupCapsResponse response) {

                // <1>
            }

            @Override
            public void onFailure(Exception e) {
                // <2>
            }
        };
        // end::x-pack-rollup-get-rollup-caps-execute-listener

        // Replace the empty listener by a blocking listener in test
        final CountDownLatch latch = new CountDownLatch(1);
        listener = new LatchedActionListener<>(listener, latch);

        // tag::x-pack-rollup-get-rollup-caps-execute-async
        client.rollup().getRollupCapabilitiesAsync(getRollupCapsRequest, RequestOptions.DEFAULT, listener); // <1>
        // end::x-pack-rollup-get-rollup-caps-execute-async

        assertTrue(latch.await(30L, TimeUnit.SECONDS));
    }

    @After
    public void wipeRollup() throws Exception {
        // TODO move this to ESRestTestCase
        deleteRollupJobs();
        waitForPendingRollupTasks();
    }

    private void deleteRollupJobs() throws Exception {
        Response response = adminClient().performRequest(new Request("GET", "/_xpack/rollup/job/_all"));
        Map<String, Object> jobs = entityAsMap(response);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jobConfigs =
                (List<Map<String, Object>>) XContentMapValues.extractValue("jobs", jobs);

        if (jobConfigs == null) {
            return;
        }

        for (Map<String, Object> jobConfig : jobConfigs) {
            @SuppressWarnings("unchecked")
            String jobId = (String) ((Map<String, Object>) jobConfig.get("config")).get("id");
            Request request = new Request("DELETE", "/_xpack/rollup/job/" + jobId);
            request.addParameter("ignore", "404"); // Ignore 404s because they imply someone was racing us to delete this
            adminClient().performRequest(request);
        }
    }

    private void waitForPendingRollupTasks() throws Exception {
        assertBusy(() -> {
            try {
                Request request = new Request("GET", "/_cat/tasks");
                request.addParameter("detailed", "true");
                Response response = adminClient().performRequest(request);

                try (BufferedReader responseReader = new BufferedReader(
                        new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))) {
                    int activeTasks = 0;
                    String line;
                    StringBuilder tasksListString = new StringBuilder();
                    while ((line = responseReader.readLine()) != null) {

                        // We only care about Rollup jobs, otherwise this fails too easily due to unrelated tasks
                        if (line.startsWith("xpack/rollup/job") == true) {
                            activeTasks++;
                            tasksListString.append(line).append('\n');
                        }
                    }
                    assertEquals(activeTasks + " active tasks found:\n" + tasksListString, 0, activeTasks);
                }
            } catch (IOException e) {
                // Throw an assertion error so we retry
                throw new AssertionError("Error getting active tasks list", e);
            }
        });
    }

    @SuppressWarnings("unused")
    public void testDeleteRollupJob() throws Exception {
        RestHighLevelClient client = highLevelClient();

        String id = "job_2";

        // tag::rollup-delete-job-request
        DeleteRollupJobRequest request = new DeleteRollupJobRequest(id); // <1>
        // end::rollup-delete-job-request
        try {
            // tag::rollup-delete-job-execute
            DeleteRollupJobResponse response = client.rollup().deleteRollupJob(request, RequestOptions.DEFAULT);
            // end::rollup-delete-job-execute

            // tag::rollup-delete-job-response
            response.isAcknowledged(); // <1>
            // end::rollup-delete-job-response
        } catch (Exception e) {
            // Swallow any exception, this test does not test actually cancelling.
        }

        // tag::rollup-delete-job-execute-listener
        ActionListener<DeleteRollupJobResponse> listener = new ActionListener<DeleteRollupJobResponse>() {
            @Override
            public void onResponse(DeleteRollupJobResponse response) {
                boolean acknowledged = response.isAcknowledged(); // <1>
            }

            @Override
            public void onFailure(Exception e) {
                // <2>
            }
        };
        // end::rollup-delete-job-execute-listener

        // Replace the empty listener by a blocking listener in test
        final CountDownLatch latch = new CountDownLatch(1);
        listener = new LatchedActionListener<>(listener, latch);

        // tag::rollup-delete-job-execute-async
        client.rollup().deleteRollupJobAsync(request, RequestOptions.DEFAULT, listener); // <1>
        // end::rollup-delete-job-execute-async

        assertTrue(latch.await(30L, TimeUnit.SECONDS));
    }
}