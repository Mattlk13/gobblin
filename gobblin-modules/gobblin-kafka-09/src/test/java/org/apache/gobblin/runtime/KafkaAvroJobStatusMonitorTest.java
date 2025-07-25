/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gobblin.runtime;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.mockito.Mockito;
import static org.mockito.ArgumentMatchers.any;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

import kafka.consumer.ConsumerIterator;
import kafka.message.MessageAndMetadata;
import lombok.Getter;

import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.configuration.ErrorPatternProfile;
import org.apache.gobblin.configuration.State;
import org.apache.gobblin.kafka.KafkaTestBase;
import org.apache.gobblin.kafka.client.DecodeableKafkaRecord;
import org.apache.gobblin.kafka.client.Kafka09ConsumerClient;
import org.apache.gobblin.metastore.InMemoryErrorPatternStore;
import org.apache.gobblin.metastore.StateStore;
import org.apache.gobblin.metrics.FlowStatus;
import org.apache.gobblin.metrics.GaaSFlowObservabilityEvent;
import org.apache.gobblin.metrics.GaaSJobObservabilityEvent;
import org.apache.gobblin.metrics.GobblinTrackingEvent;
import org.apache.gobblin.metrics.JobStatus;
import org.apache.gobblin.metrics.MetricContext;
import org.apache.gobblin.metrics.event.TimingEvent;
import org.apache.gobblin.metrics.kafka.KafkaAvroEventKeyValueReporter;
import org.apache.gobblin.metrics.kafka.KafkaEventReporter;
import org.apache.gobblin.metrics.kafka.KafkaKeyValueProducerPusher;
import org.apache.gobblin.metrics.kafka.Pusher;
import org.apache.gobblin.runtime.troubleshooter.InMemoryMultiContextIssueRepository;
import org.apache.gobblin.runtime.troubleshooter.IssueTestDataProvider;
import org.apache.gobblin.runtime.troubleshooter.JobIssueEventHandler;
import org.apache.gobblin.runtime.troubleshooter.MultiContextIssueRepository;
import org.apache.gobblin.runtime.troubleshooter.TroubleshooterException;
import org.apache.gobblin.service.ExecutionStatus;
import org.apache.gobblin.service.ServiceConfigKeys;
import org.apache.gobblin.service.modules.orchestration.DagActionStore;
import org.apache.gobblin.service.modules.orchestration.DagManagementStateStore;
import org.apache.gobblin.service.monitoring.GaaSJobObservabilityEventProducer;
import org.apache.gobblin.service.monitoring.JobStatusRetriever;
import org.apache.gobblin.service.monitoring.KafkaAvroJobStatusMonitor;
import org.apache.gobblin.service.monitoring.KafkaJobStatusMonitor;
import org.apache.gobblin.service.monitoring.MockGaaSJobObservabilityEventProducer;
import org.apache.gobblin.service.monitoring.NoopGaaSJobObservabilityEventProducer;
import org.apache.gobblin.util.ConfigUtils;

import static org.apache.gobblin.util.retry.RetryerFactory.RETRY_MULTIPLIER;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;


public class KafkaAvroJobStatusMonitorTest {
  public static final String TOPIC = KafkaAvroJobStatusMonitorTest.class.getSimpleName();

  private KafkaTestBase kafkaTestHelper;
  private final String flowGroup = "myFlowGroup";
  private final String flowName = "myFlowName";
  private final String jobGroup = "myJobGroup";
  private final String jobName = "myJobName";
  private final long flowExecutionId = 1234L;
  private final String stateStoreDir = "/tmp/jobStatusMonitor/statestore";
  private MetricContext context;
  private KafkaAvroEventKeyValueReporter.Builder<?> builder;
  DagActionStore.DagAction enforceJobStartDeadlineDagAction;
  private JobIssueEventHandler eventHandler;
  private ErrorClassifier errorClassifier;

  @BeforeClass
  public void setUp() throws Exception {
    cleanUpDir();
    kafkaTestHelper = new KafkaTestBase();
    kafkaTestHelper.startServers();
    kafkaTestHelper.provisionTopic(TOPIC);

    // Create KeyValueProducerPusher instance.
    Pusher<Pair<byte[], byte[]>> pusher = new KafkaKeyValueProducerPusher<>("localhost:dummy", TOPIC,
        Optional.of(ConfigFactory.parseMap(ImmutableMap.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:" + this.kafkaTestHelper.getKafkaServerPort()))));

    //Create an event reporter instance
    context = MetricContext.builder("context").build();
    builder = KafkaAvroEventKeyValueReporter.Factory.forContext(context);
    builder = builder.withKafkaPusher(pusher).withKeys(Lists.newArrayList(TimingEvent.FlowEventConstants.FLOW_NAME_FIELD,
        TimingEvent.FlowEventConstants.FLOW_GROUP_FIELD, TimingEvent.FlowEventConstants.FLOW_EXECUTION_ID_FIELD));

    this.enforceJobStartDeadlineDagAction = new DagActionStore.DagAction(this.flowGroup, this.flowName, this.flowExecutionId, this.jobName,
        DagActionStore.DagActionType.ENFORCE_JOB_START_DEADLINE);
  }

  @BeforeMethod
  public void setUpMethod() {
    eventHandler = Mockito.mock(JobIssueEventHandler.class);
    errorClassifier = Mockito.mock(ErrorClassifier.class);
  }

  @Test
  public void testProcessMessageForSuccessfulFlow() throws IOException, ReflectiveOperationException {
    DagManagementStateStore dagManagementStateStore = mock(DagManagementStateStore.class);
    KafkaEventReporter kafkaReporter = builder.build("localhost:0000", "topic1");

    //Submit GobblinTrackingEvents to Kafka
    ImmutableList.of(
        createFlowCompiledEvent(),
        createJobOrchestratedEvent(1, 2),
        createJobStartEvent(),
        createJobSucceededEvent(),
        createDummyEvent(), // note position
        createJobStartEvent()
    ).forEach(event -> {
      context.submitEvent(event);
      kafkaReporter.report();
    });

    try {
      Thread.sleep(1000);
    } catch(InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    MockKafkaAvroJobStatusMonitor jobStatusMonitor = createMockKafkaAvroJobStatusMonitor(new AtomicBoolean(false), ConfigFactory.empty(),
        new NoopGaaSJobObservabilityEventProducer(), dagManagementStateStore);
    jobStatusMonitor.buildMetricsContextAndMetrics();

    Iterator<DecodeableKafkaRecord<byte[], byte[]>> recordIterator = Iterators.transform(
        this.kafkaTestHelper.getIteratorForTopic(TOPIC),
        this::convertMessageAndMetadataToDecodableKafkaRecord);

    State state = getNextJobStatusState(jobStatusMonitor, recordIterator, "NA", "NA");
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.COMPILED.name());
    Mockito.verify(dagManagementStateStore, Mockito.never()).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.never()).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.ORCHESTRATED.name());
    Mockito.verify(dagManagementStateStore, Mockito.never()).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.never()).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.RUNNING.name());
    Mockito.verify(dagManagementStateStore, Mockito.never()).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.COMPLETE.name());
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).addJobDagAction(any(), any(),
        anyLong(), any(), eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    // (per above, is a 'dummy' event)
    Assert.assertNull(jobStatusMonitor.parseJobStatus(
        jobStatusMonitor.deserializeEvent(recordIterator.next())));

    // Check that state didn't get set to running since it was already complete
    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.COMPLETE.name());

    jobStatusMonitor.shutDown();
  }

  /**
   * Functional test for ErrorClassifier integration with KafkaJobStatusMonitor:
   * Tests that when a job fails, the monitor properly invokes ErrorClassifier to classify issues
   * and logs the final classified error through the JobIssueEventHandler.
   */
  @Test (dependsOnMethods = "testObservabilityEventFlowFailed")
  public void testErrorClassifierFinalIssueSystemInfra()
      throws IOException, ReflectiveOperationException, TroubleshooterException {
    DagManagementStateStore dagManagementStateStore = mock(DagManagementStateStore.class);
    Config config = ConfigFactory.empty().withValue(ServiceConfigKeys.ERROR_CLASSIFICATION_ENABLED_KEY, ConfigValueFactory.fromAnyRef(Boolean.TRUE));
    List<ErrorPatternProfile> errorPatternProfiles = IssueTestDataProvider.getSortedPatterns();

    InMemoryErrorPatternStore store = new InMemoryErrorPatternStore(config);
    store.upsertCategory(IssueTestDataProvider.TEST_CATEGORIES);
    store.upsertPatterns(errorPatternProfiles);
    store.setDefaultCategory(IssueTestDataProvider.TEST_DEFAULT_ERROR_CATEGORY);

    this.errorClassifier =  new ErrorClassifier(store, config);
    KafkaEventReporter kafkaReporter = builder.build("localhost:0000", "topic2");

    //Submit GobblinTrackingEvents to Kafka
    ImmutableList.of(
        createJobFailedEvent()
    ).forEach(event -> {
      context.submitEvent(event);
      kafkaReporter.report();
    });

    //Set issues for error classification
    Mockito.doReturn(IssueTestDataProvider.testSystemInfraCategoryIssues()).when(eventHandler).getErrorListForClassification(Mockito.any());

    try {
      Thread.sleep(1000L);
    } catch(InterruptedException ex) {
      Thread.currentThread().interrupt();
    }

    MockKafkaAvroJobStatusMonitor jobStatusMonitor = createMockKafkaAvroJobStatusMonitor(new AtomicBoolean(false),
        config, new NoopGaaSJobObservabilityEventProducer(), dagManagementStateStore);
    jobStatusMonitor.buildMetricsContextAndMetrics();

    ConsumerIterator<byte[], byte[]> iterator = this.kafkaTestHelper.getIteratorForTopic(TOPIC);
    MessageAndMetadata<byte[], byte[]> messageAndMetadata = iterator.next();
    jobStatusMonitor.processMessage(convertMessageAndMetadataToDecodableKafkaRecord(messageAndMetadata));

    // Verify that the JobIssueEventHandler was called with the final classified error
    Mockito.verify(eventHandler, Mockito.times(1))
        .logFinalError(Mockito.argThat(issue ->
                issue.getSummary().contains("SYSTEM INFRA")),
            Mockito.eq(this.flowName),
            Mockito.eq(this.flowGroup),
            Mockito.eq(String.valueOf(this.flowExecutionId)),
            Mockito.eq(this.jobName));

    jobStatusMonitor.shutDown();
  }

  /**
   * Test for verifying that error classifier module is not called when it is disabled.
   */
  @Test (dependsOnMethods = "testErrorClassifierFinalIssueSystemInfra")
  public void testDisabledErrorClassification()
      throws IOException, ReflectiveOperationException, TroubleshooterException {
    DagManagementStateStore dagManagementStateStore = mock(DagManagementStateStore.class);
    Config config = ConfigFactory.empty().withValue(ServiceConfigKeys.ERROR_CLASSIFICATION_ENABLED_KEY, ConfigValueFactory.fromAnyRef(Boolean.FALSE));
    List<ErrorPatternProfile> errorPatternProfiles = IssueTestDataProvider.getSortedPatterns();

    InMemoryErrorPatternStore store = new InMemoryErrorPatternStore(config);
    store.upsertCategory(IssueTestDataProvider.TEST_CATEGORIES);
    store.upsertPatterns(errorPatternProfiles);
    store.setDefaultCategory(IssueTestDataProvider.TEST_DEFAULT_ERROR_CATEGORY);

    this.errorClassifier =  new ErrorClassifier(store, config);
    KafkaEventReporter kafkaReporter = builder.build("localhost:0000", "topic2");

    //Submit GobblinTrackingEvents to Kafka
    ImmutableList.of(
        createJobFailedEvent()
    ).forEach(event -> {
      context.submitEvent(event);
      kafkaReporter.report();
    });

    //Set issues for error classification
    Mockito.doReturn(IssueTestDataProvider.testSystemInfraCategoryIssues()).when(eventHandler).getErrorListForClassification(Mockito.any());

    try {
      Thread.sleep(1000L);
    } catch(InterruptedException ex) {
      Thread.currentThread().interrupt();
    }

    MockKafkaAvroJobStatusMonitor jobStatusMonitor = createMockKafkaAvroJobStatusMonitor(new AtomicBoolean(false),
        config, new NoopGaaSJobObservabilityEventProducer(), dagManagementStateStore);
    jobStatusMonitor.buildMetricsContextAndMetrics();

    ConsumerIterator<byte[], byte[]> iterator = this.kafkaTestHelper.getIteratorForTopic(TOPIC);
    MessageAndMetadata<byte[], byte[]> messageAndMetadata = iterator.next();
    jobStatusMonitor.processMessage(convertMessageAndMetadataToDecodableKafkaRecord(messageAndMetadata));

    // Verify that the JobIssueEventHandler was not called for error classification
    Mockito.verify(eventHandler, Mockito.times(0))
        .logFinalError(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    Mockito.verify(eventHandler, Mockito.times(0))
        .getErrorListForClassification(Mockito.any());

    jobStatusMonitor.shutDown();
  }

  @Test (dependsOnMethods = "testProcessMessageForSuccessfulFlow")
  public void testProcessMessageForFailedFlow() throws IOException, ReflectiveOperationException {
    DagManagementStateStore dagManagementStateStore = mock(DagManagementStateStore.class);
    KafkaEventReporter kafkaReporter = builder.build("localhost:0000", "topic2");

    //Submit GobblinTrackingEvents to Kafka
    ImmutableList.of(
        createFlowCompiledEvent(),
        createJobOrchestratedEvent(1, 2),
        createJobStartEvent(),
        createJobFailedEvent(),
        // Mimic retrying - job orchestration
        // set maximum attempt to 2, and current attempt to 2
        createJobOrchestratedEvent(2, 2),
        // Mimic retrying - job start (current attempt = 2)
        createJobStartEvent(),
        // Mimic retrying - job failed again (current attempt = 2)
        createJobFailedEvent()
    ).forEach(event -> {
      context.submitEvent(event);
      kafkaReporter.report();
    });

    try {
      Thread.sleep(1000L);
    } catch(InterruptedException ex) {
      Thread.currentThread().interrupt();
    }

    MockKafkaAvroJobStatusMonitor jobStatusMonitor = createMockKafkaAvroJobStatusMonitor(new AtomicBoolean(false),
        ConfigFactory.empty(), new NoopGaaSJobObservabilityEventProducer(), dagManagementStateStore);
    jobStatusMonitor.buildMetricsContextAndMetrics();

    ConsumerIterator<byte[], byte[]> iterator = this.kafkaTestHelper.getIteratorForTopic(TOPIC);

    MessageAndMetadata<byte[], byte[]> messageAndMetadata = iterator.next();
    // Verify undecodeable message is skipped
    byte[] undecodeableMessage = Arrays.copyOf(messageAndMetadata.message(),
        messageAndMetadata.message().length - 1);
    ConsumerRecord<byte[], byte[]> undecodeableRecord = new ConsumerRecord<>(TOPIC, messageAndMetadata.partition(),
        messageAndMetadata.offset(), messageAndMetadata.key(), undecodeableMessage);
    Assert.assertEquals(jobStatusMonitor.getMessageParseFailures().getCount(), 0L);
    jobStatusMonitor.processMessage(new Kafka09ConsumerClient.Kafka09ConsumerRecord<>(undecodeableRecord));
    Assert.assertEquals(jobStatusMonitor.getMessageParseFailures().getCount(), 1L);
    // Re-test when properly encoded, as expected for a normal event
    jobStatusMonitor.processMessage(convertMessageAndMetadataToDecodableKafkaRecord(messageAndMetadata));

    StateStore<State> stateStore = jobStatusMonitor.getStateStore();
    String storeName = KafkaJobStatusMonitor.jobStatusStoreName(flowGroup, flowName);
    String tableName = KafkaJobStatusMonitor.jobStatusTableName(this.flowExecutionId, "NA", "NA");
    List<State> stateList  = stateStore.getAll(storeName, tableName);
    Assert.assertEquals(stateList.size(), 1);
    State state = stateList.get(0);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.COMPILED.name());

    Iterator<DecodeableKafkaRecord<byte[], byte[]>> recordIterator = Iterators.transform(
        iterator,
        this::convertMessageAndMetadataToDecodableKafkaRecord);

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.ORCHESTRATED.name());
    Mockito.verify(dagManagementStateStore, Mockito.never()).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.never()).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.RUNNING.name());
    Mockito.verify(dagManagementStateStore, Mockito.never()).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);

    //Because the maximum attempt is set to 2, so the state is set to PENDING_RETRY after the first failure
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.PENDING_RETRY.name());
    Assert.assertEquals(state.getProp(TimingEvent.FlowEventConstants.SHOULD_RETRY_FIELD), Boolean.toString(true));
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    //Job orchestrated for retrying
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.ORCHESTRATED.name());
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    //Because the maximum attempt is set to 2, so the state is set to PENDING_RETRY after the first failure
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.RUNNING.name());
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.times(2)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    //Because the maximum attempt is set to 2, so the state is set to Failed after trying twice
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.FAILED.name());
    Assert.assertEquals(state.getProp(TimingEvent.FlowEventConstants.SHOULD_RETRY_FIELD), Boolean.toString(false));
    Mockito.verify(dagManagementStateStore, Mockito.times(2)).addJobDagAction(any(), any(),
        anyLong(), any(), eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.times(2)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    jobStatusMonitor.shutDown();
  }

  @Test (dependsOnMethods = "testProcessMessageForFailedFlow")
  public void testProcessMessageForSkippedFlow() throws IOException, ReflectiveOperationException {
    DagManagementStateStore dagManagementStateStore = mock(DagManagementStateStore.class);
    KafkaEventReporter kafkaReporter = builder.build("localhost:0000", "topic2");

    //Submit GobblinTrackingEvents to Kafka
    ImmutableList.of(
        createFlowCompiledEvent(),
        createJobOrchestratedEvent(1, 2),
        createJobSkippedEvent()
    ).forEach(event -> {
      context.submitEvent(event);
      kafkaReporter.report();
    });

    try {
      Thread.sleep(1000);
    } catch(InterruptedException ex) {
      Thread.currentThread().interrupt();
    }

    MockKafkaAvroJobStatusMonitor jobStatusMonitor = createMockKafkaAvroJobStatusMonitor(new AtomicBoolean(false), ConfigFactory.empty(),
        new NoopGaaSJobObservabilityEventProducer(), dagManagementStateStore);
    jobStatusMonitor.buildMetricsContextAndMetrics();

    ConsumerIterator<byte[], byte[]> iterator = this.kafkaTestHelper.getIteratorForTopic(TOPIC);

    MessageAndMetadata<byte[], byte[]> messageAndMetadata = iterator.next();
    // Verify undecodeable message is skipped
    byte[] undecodeableMessage = Arrays.copyOf(messageAndMetadata.message(),
        messageAndMetadata.message().length - 1);
    ConsumerRecord<byte[], byte[]> undecodeableRecord = new ConsumerRecord<>(TOPIC, messageAndMetadata.partition(),
        messageAndMetadata.offset(), messageAndMetadata.key(), undecodeableMessage);
    Assert.assertEquals(jobStatusMonitor.getMessageParseFailures().getCount(), 0L);
    jobStatusMonitor.processMessage(new Kafka09ConsumerClient.Kafka09ConsumerRecord<>(undecodeableRecord));
    Assert.assertEquals(jobStatusMonitor.getMessageParseFailures().getCount(), 1L);
    // Re-test when properly encoded, as expected for a normal event
    jobStatusMonitor.processMessage(convertMessageAndMetadataToDecodableKafkaRecord(messageAndMetadata));

    StateStore<State> stateStore = jobStatusMonitor.getStateStore();
    String storeName = KafkaJobStatusMonitor.jobStatusStoreName(flowGroup, flowName);
    String tableName = KafkaJobStatusMonitor.jobStatusTableName(this.flowExecutionId, "NA", "NA");
    List<State> stateList  = stateStore.getAll(storeName, tableName);
    Assert.assertEquals(stateList.size(), 1);
    State state = stateList.get(0);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.COMPILED.name());

    Iterator<DecodeableKafkaRecord<byte[], byte[]>> recordIterator = Iterators.transform(
        iterator,
        this::convertMessageAndMetadataToDecodableKafkaRecord);

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.ORCHESTRATED.name());
    Mockito.verify(dagManagementStateStore, Mockito.never()).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.never()).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.CANCELLED.name());
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).addJobDagAction(any(), any(),
        anyLong(), any(), eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));
    jobStatusMonitor.shutDown();
  }

  @Test (dependsOnMethods = "testProcessMessageForSkippedFlow")
  public void testProcessingRetriedForApparentlyTransientErrors() throws IOException, ReflectiveOperationException {
    DagManagementStateStore dagManagementStateStore = mock(DagManagementStateStore.class);
    KafkaEventReporter kafkaReporter = builder.build("localhost:0000", "topic3");

    //Submit GobblinTrackingEvents to Kafka
    ImmutableList.of(
        createFlowCompiledEvent(),
        createJobOrchestratedEvent(1, 2)
    ).forEach(event -> {
      context.submitEvent(event);
      kafkaReporter.report();
    });

    try {
      Thread.sleep(1000L);
    } catch(InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    int minNumFakeExceptionsExpected = 10;
    AtomicBoolean shouldThrowFakeExceptionInParseJobStatusToggle = new AtomicBoolean(false);
    Config conf = ConfigFactory.empty().withValue(
        KafkaJobStatusMonitor.JOB_STATUS_MONITOR_PREFIX + "." + RETRY_MULTIPLIER, ConfigValueFactory.fromAnyRef(TimeUnit.MILLISECONDS.toMillis(1L)));
    MockKafkaAvroJobStatusMonitor jobStatusMonitor = createMockKafkaAvroJobStatusMonitor(shouldThrowFakeExceptionInParseJobStatusToggle, conf,
        new NoopGaaSJobObservabilityEventProducer(), dagManagementStateStore);

    jobStatusMonitor.buildMetricsContextAndMetrics();
    Iterator<DecodeableKafkaRecord<byte[], byte[]>> recordIterator = Iterators.transform(
        this.kafkaTestHelper.getIteratorForTopic(TOPIC),
        this::convertMessageAndMetadataToDecodableKafkaRecord);

    State state = getNextJobStatusState(jobStatusMonitor, recordIterator, "NA", "NA");;
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.COMPILED.name());

    shouldThrowFakeExceptionInParseJobStatusToggle.set(true);
    // since `processMessage` hereafter effectively hangs, launch eventual re-toggling before calling again
    ScheduledExecutorService toggleManagementExecutor = Executors.newScheduledThreadPool(2);
    toggleManagementExecutor.scheduleAtFixedRate(() -> {
      if (jobStatusMonitor.getNumFakeExceptionsFromParseJobStatus().intValue() > minNumFakeExceptionsExpected) { // curtail faking: simulate resolution
        shouldThrowFakeExceptionInParseJobStatusToggle.set(false);
      }
    }, 2, 2, TimeUnit.SECONDS);
    Thread mainThread = Thread.currentThread();
    // guardrail against excessive retries (befitting this unit test):
    toggleManagementExecutor.scheduleAtFixedRate(mainThread::interrupt, 20, 5, TimeUnit.SECONDS);

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);

    Assert.assertTrue(jobStatusMonitor.getNumFakeExceptionsFromParseJobStatus().intValue() > minNumFakeExceptionsExpected,
        String.format("processMessage returned with only %d (faked) exceptions",
            jobStatusMonitor.getNumFakeExceptionsFromParseJobStatus().intValue()));

    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.ORCHESTRATED.name());
    Mockito.verify(dagManagementStateStore, Mockito.never()).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.never()).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    toggleManagementExecutor.shutdownNow();
    jobStatusMonitor.shutDown();
  }

  @Test (dependsOnMethods = "testProcessingRetriedForApparentlyTransientErrors")
  public void testProcessMessageForCancelledAndKilledEvent() throws IOException, ReflectiveOperationException {
    DagManagementStateStore dagManagementStateStore = mock(DagManagementStateStore.class);
    KafkaEventReporter kafkaReporter = builder.build("localhost:0000", "topic4");

    //Submit GobblinTrackingEvents to Kafka
    ImmutableList.of(
        createFlowCompiledEvent(),
        createJobOrchestratedEvent(1, 4),
        createJobSLAKilledEvent(),
        createJobOrchestratedEvent(2, 4),
        createJobStartSLAKilledEvent(),
        // Verify that kill event will not retry
        createJobOrchestratedEvent(3, 4),
        createJobCancelledEvent()
    ).forEach(event -> {
      context.submitEvent(event);
      kafkaReporter.report();
    });

    try {
      Thread.sleep(1000);
    } catch(InterruptedException ex) {
      Thread.currentThread().interrupt();
    }

    MockKafkaAvroJobStatusMonitor jobStatusMonitor = createMockKafkaAvroJobStatusMonitor(new AtomicBoolean(false),
        ConfigFactory.empty(), new NoopGaaSJobObservabilityEventProducer(), dagManagementStateStore);
    jobStatusMonitor.buildMetricsContextAndMetrics();
    Iterator<DecodeableKafkaRecord<byte[], byte[]>> recordIterator = Iterators.transform(
        this.kafkaTestHelper.getIteratorForTopic(TOPIC),
        this::convertMessageAndMetadataToDecodableKafkaRecord);

    State state = getNextJobStatusState(jobStatusMonitor, recordIterator, "NA", "NA");
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.COMPILED.name());
    Mockito.verify(dagManagementStateStore, Mockito.never()).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.never()).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.ORCHESTRATED.name());
    Mockito.verify(dagManagementStateStore, Mockito.never()).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.never()).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.PENDING_RETRY.name());
    Assert.assertEquals(state.getProp(TimingEvent.FlowEventConstants.SHOULD_RETRY_FIELD), Boolean.toString(true));
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    // this is a cancelled job, so its JobStartDeadlineDagAction should get removed
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    //Job orchestrated for retrying
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.ORCHESTRATED.name());
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.PENDING_RETRY.name());
    // second pending retry, creates a second reevaluate dag proc
    Mockito.verify(dagManagementStateStore, Mockito.times(2)).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    // cancelled again, so JobStartDeadlineDagAction is removed again
    Mockito.verify(dagManagementStateStore, Mockito.times(2)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    //Job orchestrated for retrying
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.ORCHESTRATED.name());
    Mockito.verify(dagManagementStateStore, Mockito.times(2)).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.times(2)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    // Received kill flow event, should not retry the flow even though there is 1 pending attempt left
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.CANCELLED.name());
    Assert.assertEquals(state.getProp(TimingEvent.FlowEventConstants.SHOULD_RETRY_FIELD), Boolean.toString(false));
    Mockito.verify(dagManagementStateStore, Mockito.times(3)).addJobDagAction(any(), any(),
        anyLong(), any(), eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.times(3)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    jobStatusMonitor.shutDown();
  }

  @Test (dependsOnMethods = "testProcessingRetriedForApparentlyTransientErrors")
  public void testProcessMessageForFlowPendingResume() throws IOException, ReflectiveOperationException {
    DagManagementStateStore dagManagementStateStore = mock(DagManagementStateStore.class);
    KafkaEventReporter kafkaReporter = builder.build("localhost:0000", "topic4");

    //Submit GobblinTrackingEvents to Kafka
    ImmutableList.of(
        createFlowCompiledEvent(),
        createJobOrchestratedEvent(1, 2),
        createJobCancelledEvent(),
        createFlowPendingResumeEvent(),
        createJobOrchestratedEvent(2, 2),
        createJobStartEvent(),
        createJobSucceededEvent()
    ).forEach(event -> {
      context.submitEvent(event);
      kafkaReporter.report();
    });

    try {
      Thread.sleep(1000);
    } catch(InterruptedException ex) {
      Thread.currentThread().interrupt();
    }

    MockKafkaAvroJobStatusMonitor jobStatusMonitor = createMockKafkaAvroJobStatusMonitor(new AtomicBoolean(false),
        ConfigFactory.empty(), new NoopGaaSJobObservabilityEventProducer(), dagManagementStateStore);
    jobStatusMonitor.buildMetricsContextAndMetrics();
    Iterator<DecodeableKafkaRecord<byte[], byte[]>> recordIterator = Iterators.transform(
        this.kafkaTestHelper.getIteratorForTopic(TOPIC),
        this::convertMessageAndMetadataToDecodableKafkaRecord);

    State state = getNextJobStatusState(jobStatusMonitor, recordIterator, "NA", "NA");
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.COMPILED.name());
    Mockito.verify(dagManagementStateStore, Mockito.never()).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.never()).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.ORCHESTRATED.name());
    Mockito.verify(dagManagementStateStore, Mockito.never()).addJobDagAction(any(), any(), anyLong(), any(),
        eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.never()).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.CANCELLED.name());
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).addJobDagAction(any(), any(),
        anyLong(), any(), eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    // Job for flow pending resume status after it was cancelled or failed
    state = getNextJobStatusState(jobStatusMonitor, recordIterator, "NA", "NA");
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.PENDING_RESUME.name());
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).addJobDagAction(any(), any(),
        anyLong(), any(), eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    //Job orchestrated for retrying
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.ORCHESTRATED.name());
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).addJobDagAction(any(), any(),
        anyLong(), any(), eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.RUNNING.name());
    Mockito.verify(dagManagementStateStore, Mockito.times(1)).addJobDagAction(any(), any(),
        anyLong(), any(), eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.times(2)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.COMPLETE.name());
    Mockito.verify(dagManagementStateStore, Mockito.times(2)).addJobDagAction(any(), any(),
        anyLong(), any(), eq(DagActionStore.DagActionType.REEVALUATE));
    Mockito.verify(dagManagementStateStore, Mockito.times(2)).deleteDagAction(eq(this.enforceJobStartDeadlineDagAction));

    jobStatusMonitor.shutDown();
  }

  @Test (dependsOnMethods = "testProcessMessageForCancelledAndKilledEvent")
  public void testProcessProgressingMessageWhenNoPreviousStatus() throws IOException, ReflectiveOperationException {
    KafkaEventReporter kafkaReporter = builder.build("localhost:0000", "topic5");

    //Submit GobblinTrackingEvents to Kafka
    ImmutableList.of(
        createGTE(TimingEvent.JOB_COMPLETION_PERCENTAGE, new HashMap<>())
    ).forEach(event -> {
      context.submitEvent(event);
      kafkaReporter.report();
    });

    try {
      Thread.sleep(1000);
    } catch(InterruptedException ex) {
      Thread.currentThread().interrupt();
    }

    MockKafkaAvroJobStatusMonitor jobStatusMonitor = createMockKafkaAvroJobStatusMonitor(new AtomicBoolean(false), ConfigFactory.empty(),
        new NoopGaaSJobObservabilityEventProducer(), mock(DagManagementStateStore.class));
    jobStatusMonitor.buildMetricsContextAndMetrics();
    Iterator<DecodeableKafkaRecord<byte[], byte[]>> recordIterator = Iterators.transform(
        this.kafkaTestHelper.getIteratorForTopic(TOPIC),
        this::convertMessageAndMetadataToDecodableKafkaRecord);

    State state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    // Verify we are able to process it without NPE
    Assert.assertNull(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD));
  }

  @Test (dependsOnMethods = "testProcessProgressingMessageWhenNoPreviousStatus")
  public void testJobMonitorCreatesGaaSObservabilityEvent() throws IOException, ReflectiveOperationException {
    KafkaEventReporter kafkaReporter = builder.build("localhost:0000", "topic6");

    //Submit GobblinTrackingEvents to Kafka
    ImmutableList.of(
        createFlowCompiledEvent(),
        createWorkUnitTimingEvent(),
        createJobSucceededEvent()
    ).forEach(event -> {
      context.submitEvent(event);
      kafkaReporter.report();
    });
    try {
      Thread.sleep(1000);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    MultiContextIssueRepository issueRepository = new InMemoryMultiContextIssueRepository();
    MockGaaSJobObservabilityEventProducer mockEventProducer = new MockGaaSJobObservabilityEventProducer(ConfigUtils.configToState(ConfigFactory.empty()),
        issueRepository, false);
    MockKafkaAvroJobStatusMonitor jobStatusMonitor = createMockKafkaAvroJobStatusMonitor(new AtomicBoolean(false),
        ConfigFactory.empty(), mockEventProducer, mock(DagManagementStateStore.class));
    jobStatusMonitor.buildMetricsContextAndMetrics();
    Iterator<DecodeableKafkaRecord<byte[], byte[]>> recordIterator = Iterators.transform(
        this.kafkaTestHelper.getIteratorForTopic(TOPIC),
        this::convertMessageAndMetadataToDecodableKafkaRecord);

    State state = getNextJobStatusState(jobStatusMonitor, recordIterator, "NA", "NA");
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.COMPILED.name());

    getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.COMPLETE.name());

    // Only the COMPLETE event should create a GaaSJobObservabilityEvent
    List<GaaSJobObservabilityEvent> emittedEvents = mockEventProducer.getTestEmittedJobEvents();
    Iterator<GaaSJobObservabilityEvent> iterator = emittedEvents.iterator();
    GaaSJobObservabilityEvent event1 = iterator.next();
    Assert.assertEquals(event1.getJobStatus(), JobStatus.SUCCEEDED);
    Assert.assertEquals(event1.getFlowName(), this.flowName);
    Assert.assertEquals(event1.getFlowGroup(), this.flowGroup);
    Assert.assertEquals(event1.getJobPlanningStartTimestamp(), Long.valueOf(2));
    Assert.assertEquals(event1.getJobPlanningEndTimestamp(), Long.valueOf(3));
    jobStatusMonitor.shutDown();
  }

  @Test (dependsOnMethods = "testJobMonitorCreatesGaaSObservabilityEvent")
  public void testObservabilityEventSingleEmission() throws IOException, ReflectiveOperationException {
    KafkaEventReporter kafkaReporter = builder.build("localhost:0000", "topic7");

    //Submit GobblinTrackingEvents to Kafka
    ImmutableList.of(
        createFlowCompiledEvent(),
        createJobCancelledEvent(),
        createJobSucceededEvent() // This event should be ignored
    ).forEach(event -> {
      context.submitEvent(event);
      kafkaReporter.report();
    });
    try {
      Thread.sleep(1000);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    MultiContextIssueRepository issueRepository = new InMemoryMultiContextIssueRepository();
    MockGaaSJobObservabilityEventProducer mockEventProducer = new MockGaaSJobObservabilityEventProducer(ConfigUtils.configToState(ConfigFactory.empty()),
        issueRepository, false);
    MockKafkaAvroJobStatusMonitor jobStatusMonitor = createMockKafkaAvroJobStatusMonitor(new AtomicBoolean(false),
        ConfigFactory.empty(), mockEventProducer, mock(DagManagementStateStore.class));
    jobStatusMonitor.buildMetricsContextAndMetrics();
    Iterator<DecodeableKafkaRecord<byte[], byte[]>> recordIterator = Iterators.transform(
        this.kafkaTestHelper.getIteratorForTopic(TOPIC),
        this::convertMessageAndMetadataToDecodableKafkaRecord);

    State state = getNextJobStatusState(jobStatusMonitor, recordIterator, "NA", "NA");
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.COMPILED.name());

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.CANCELLED.name());

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.CANCELLED.name());

    // Only the COMPLETE event should create a GaaSJobObservabilityEvent
    List<GaaSJobObservabilityEvent> emittedEvents = mockEventProducer.getTestEmittedJobEvents();
    Assert.assertEquals(emittedEvents.size(), 1);
    Iterator<GaaSJobObservabilityEvent> iterator = emittedEvents.iterator();
    GaaSJobObservabilityEvent event1 = iterator.next();
    Assert.assertEquals(event1.getJobStatus(), JobStatus.CANCELLED);
    Assert.assertEquals(event1.getFlowName(), this.flowName);
    Assert.assertEquals(event1.getFlowGroup(), this.flowGroup);

    jobStatusMonitor.shutDown();
  }

  @Test (dependsOnMethods = "testObservabilityEventSingleEmission")
  public void testObservabilityEventFlowLevel() throws IOException, ReflectiveOperationException {
    KafkaEventReporter kafkaReporter = builder.build("localhost:0000", "topic7");

    //Submit GobblinTrackingEvents to Kafka
    ImmutableList.of(
        createFlowCompiledEvent(),
        createJobSucceededEvent(),
        createFlowSucceededEvent()
    ).forEach(event -> {
      context.submitEvent(event);
      kafkaReporter.report();
    });
    try {
      Thread.sleep(1000);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    MultiContextIssueRepository issueRepository = new InMemoryMultiContextIssueRepository();
    State producerState = new State();
    producerState.setProp(GaaSJobObservabilityEventProducer.EMIT_FLOW_OBSERVABILITY_EVENT, "true");
    MockGaaSJobObservabilityEventProducer mockEventProducer = new MockGaaSJobObservabilityEventProducer(producerState,
        issueRepository, false);
    MockKafkaAvroJobStatusMonitor jobStatusMonitor = createMockKafkaAvroJobStatusMonitor(new AtomicBoolean(false), ConfigFactory.empty(),
        mockEventProducer, mock(DagManagementStateStore.class));
    jobStatusMonitor.buildMetricsContextAndMetrics();
    Iterator<DecodeableKafkaRecord<byte[], byte[]>> recordIterator = Iterators.transform(
        this.kafkaTestHelper.getIteratorForTopic(TOPIC),
        this::convertMessageAndMetadataToDecodableKafkaRecord);

    State state = getNextJobStatusState(jobStatusMonitor, recordIterator, "NA", "NA");
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.COMPILED.name());

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.COMPLETE.name());

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, "NA", "NA");
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.COMPLETE.name());

    // Only the COMPLETE event should create a GaaSJobObservabilityEvent
    List<GaaSJobObservabilityEvent> emittedEvents = mockEventProducer.getTestEmittedJobEvents();
    Assert.assertEquals(emittedEvents.size(), 1);
    Iterator<GaaSJobObservabilityEvent> iterator = emittedEvents.iterator();
    GaaSJobObservabilityEvent event1 = iterator.next();
    Assert.assertEquals(event1.getJobStatus(), JobStatus.SUCCEEDED);
    Assert.assertEquals(event1.getFlowName(), this.flowName);
    Assert.assertEquals(event1.getFlowGroup(), this.flowGroup);

    // Only the COMPLETE event should create a GaaSFlowObservabilityEvent
    List<GaaSFlowObservabilityEvent> emittedFlowEvents = mockEventProducer.getTestEmittedFlowEvents();
    Assert.assertEquals(emittedFlowEvents.size(), 1);
    Iterator<GaaSFlowObservabilityEvent> flowIterator = emittedFlowEvents.iterator();
    GaaSFlowObservabilityEvent flowEvent = flowIterator.next();
    Assert.assertEquals(flowEvent.getFlowStatus(), FlowStatus.SUCCEEDED);
    Assert.assertEquals(flowEvent.getFlowName(), this.flowName);
    Assert.assertEquals(flowEvent.getFlowGroup(), this.flowGroup);

    jobStatusMonitor.shutDown();
  }

  @Test (dependsOnMethods = "testObservabilityEventFlowLevel")
  public void testObservabilityEventFlowFailed() throws IOException, ReflectiveOperationException {
    KafkaEventReporter kafkaReporter = builder.build("localhost:0000", "topic7");

    //Submit GobblinTrackingEvents to Kafka
    ImmutableList.of(
        createFlowCompiledEvent(),
        createJobFailedEvent(),
        createFlowFailedEvent()
    ).forEach(event -> {
      context.submitEvent(event);
      kafkaReporter.report();
    });
    try {
      Thread.sleep(1000);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    MultiContextIssueRepository issueRepository = new InMemoryMultiContextIssueRepository();
    State producerState = new State();
    producerState.setProp(GaaSJobObservabilityEventProducer.EMIT_FLOW_OBSERVABILITY_EVENT, "true");
    MockGaaSJobObservabilityEventProducer mockEventProducer = new MockGaaSJobObservabilityEventProducer(producerState,
        issueRepository, false);
    MockKafkaAvroJobStatusMonitor jobStatusMonitor = createMockKafkaAvroJobStatusMonitor(new AtomicBoolean(false), ConfigFactory.empty(),
        mockEventProducer, mock(DagManagementStateStore.class));
    jobStatusMonitor.buildMetricsContextAndMetrics();
    Iterator<DecodeableKafkaRecord<byte[], byte[]>> recordIterator = Iterators.transform(
        this.kafkaTestHelper.getIteratorForTopic(TOPIC),
        this::convertMessageAndMetadataToDecodableKafkaRecord);

    State state = getNextJobStatusState(jobStatusMonitor, recordIterator, "NA", "NA");
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.COMPILED.name());

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, this.jobGroup, this.jobName);
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.FAILED.name());

    state = getNextJobStatusState(jobStatusMonitor, recordIterator, "NA", "NA");
    Assert.assertEquals(state.getProp(JobStatusRetriever.EVENT_NAME_FIELD), ExecutionStatus.FAILED.name());

    // Only the FAILED event should create a GaaSJobObservabilityEvent
    List<GaaSJobObservabilityEvent> emittedEvents = mockEventProducer.getTestEmittedJobEvents();
    Assert.assertEquals(emittedEvents.size(), 1);
    Iterator<GaaSJobObservabilityEvent> iterator = emittedEvents.iterator();
    GaaSJobObservabilityEvent event1 = iterator.next();
    Assert.assertEquals(event1.getJobStatus(), JobStatus.EXECUTION_FAILURE);
    Assert.assertEquals(event1.getFlowName(), this.flowName);
    Assert.assertEquals(event1.getFlowGroup(), this.flowGroup);

    // Only the COMPLETE event should create a GaaSFlowObservabilityEvent
    List<GaaSFlowObservabilityEvent> emittedFlowEvents = mockEventProducer.getTestEmittedFlowEvents();
    Assert.assertEquals(emittedFlowEvents.size(), 1);
    Iterator<GaaSFlowObservabilityEvent> flowIterator = emittedFlowEvents.iterator();
    GaaSFlowObservabilityEvent flowEvent = flowIterator.next();
    Assert.assertEquals(flowEvent.getFlowStatus(), FlowStatus.EXECUTION_FAILURE);
    Assert.assertEquals(flowEvent.getFlowName(), this.flowName);
    Assert.assertEquals(flowEvent.getFlowGroup(), this.flowGroup);

    jobStatusMonitor.shutDown();
  }

  private State getNextJobStatusState(MockKafkaAvroJobStatusMonitor jobStatusMonitor, Iterator<DecodeableKafkaRecord<byte[], byte[]>> recordIterator,
      String jobGroup, String jobName) throws IOException {
    jobStatusMonitor.processMessage(recordIterator.next());
    StateStore<State> stateStore = jobStatusMonitor.getStateStore();
    String storeName = KafkaJobStatusMonitor.jobStatusStoreName(flowGroup, flowName);
    String tableName = KafkaJobStatusMonitor.jobStatusTableName(this.flowExecutionId, jobGroup, jobName);
    List<State> stateList  = stateStore.getAll(storeName, tableName);
    Assert.assertEquals(stateList.size(), 1);
    return stateList.get(0);
  }

  private GobblinTrackingEvent createFlowCompiledEvent() {
    // Shouldn't have job properties in the GTE for FLOW_COMPILED events so that it gets marked as "NA"
    GobblinTrackingEvent event = createGTE(TimingEvent.FlowTimings.FLOW_COMPILED, Maps.newHashMap());
    event.getMetadata().remove(TimingEvent.FlowEventConstants.JOB_NAME_FIELD);
    event.getMetadata().remove(TimingEvent.FlowEventConstants.JOB_GROUP_FIELD);
    event.getMetadata().remove(TimingEvent.FlowEventConstants.JOB_EXECUTION_ID_FIELD);
    event.getMetadata().remove(TimingEvent.METADATA_MESSAGE);
    return event;
  }

  /**
   * Create a Job Orchestrated Event with a configurable currentAttempt
   * @param currentAttempt specify the number of attempts for the JobOrchestration event
   * @param maxAttempt the maximum number of retries for the event
   * @return the {@link GobblinTrackingEvent}
   */
  private GobblinTrackingEvent createJobOrchestratedEvent(int currentAttempt, int maxAttempt) {
    Map<String, String> metadata = Maps.newHashMap();
    metadata.put(TimingEvent.FlowEventConstants.MAX_ATTEMPTS_FIELD, String.valueOf(maxAttempt));
    metadata.put(TimingEvent.FlowEventConstants.CURRENT_ATTEMPTS_FIELD, String.valueOf(currentAttempt));
    metadata.put(TimingEvent.FlowEventConstants.SHOULD_RETRY_FIELD, Boolean.toString(false));
    return createGTE(TimingEvent.LauncherTimings.JOB_ORCHESTRATED, metadata);
  }

  private GobblinTrackingEvent createJobStartEvent() {
    return createGTE(TimingEvent.LauncherTimings.JOB_START, Maps.newHashMap());
  }

  private GobblinTrackingEvent createJobSkippedEvent() {
    return createGTE(TimingEvent.JOB_SKIPPED_TIME, Maps.newHashMap());
  }

  private GobblinTrackingEvent createJobSucceededEvent() {
    return createGTE(TimingEvent.LauncherTimings.JOB_SUCCEEDED, Maps.newHashMap());
  }

  private GobblinTrackingEvent createFlowSucceededEvent() {
    GobblinTrackingEvent event = createGTE(TimingEvent.FlowTimings.FLOW_SUCCEEDED, Maps.newHashMap());
    // Remove jobname and jobgroup as flow level events should not have them, get populated with "NA"
    event.getMetadata().remove(TimingEvent.FlowEventConstants.JOB_NAME_FIELD);
    event.getMetadata().remove(TimingEvent.FlowEventConstants.JOB_GROUP_FIELD);
    return event;
  }

  private GobblinTrackingEvent createFlowFailedEvent() {
    GobblinTrackingEvent event = createGTE(TimingEvent.FlowTimings.FLOW_FAILED, Maps.newHashMap());
    // Remove jobname and jobgroup as flow level events should not have them, get populated with "NA"
    event.getMetadata().remove(TimingEvent.FlowEventConstants.JOB_NAME_FIELD);
    event.getMetadata().remove(TimingEvent.FlowEventConstants.JOB_GROUP_FIELD);
    return event;
  }

  private GobblinTrackingEvent createJobFailedEvent() {
    return createGTE(TimingEvent.LauncherTimings.JOB_FAILED, Maps.newHashMap());
  }

  private GobblinTrackingEvent createJobCancelledEvent() {
    return createGTE(TimingEvent.FlowTimings.FLOW_CANCELLED, Maps.newHashMap());
  }

  private GobblinTrackingEvent createJobSLAKilledEvent() {
    return createGTE(TimingEvent.FlowTimings.FLOW_RUN_DEADLINE_EXCEEDED, Maps.newHashMap());
  }

  private GobblinTrackingEvent createJobStartSLAKilledEvent() {
    return createGTE(TimingEvent.FlowTimings.FLOW_START_DEADLINE_EXCEEDED, Maps.newHashMap());
  }

  private GobblinTrackingEvent createFlowPendingResumeEvent() {
    GobblinTrackingEvent event = createGTE(TimingEvent.FlowTimings.FLOW_PENDING_RESUME, Maps.newHashMap());
    event.getMetadata().remove(TimingEvent.FlowEventConstants.JOB_NAME_FIELD);
    event.getMetadata().remove(TimingEvent.FlowEventConstants.JOB_GROUP_FIELD);
    return event;
  }

  private GobblinTrackingEvent createWorkUnitTimingEvent() {
    Map<String, String> metadata = Maps.newHashMap();
    metadata.put(TimingEvent.METADATA_START_TIME, "2");
    metadata.put(TimingEvent.METADATA_END_TIME, "3");
    return createGTE(TimingEvent.LauncherTimings.WORK_UNITS_CREATION, metadata);
  }


  private GobblinTrackingEvent createGTE(String eventName, Map<String, String> customMetadata) {
    String namespace = "org.apache.gobblin.metrics";
    Long timestamp = System.currentTimeMillis();
    Map<String, String> metadata = Maps.newHashMap();
    metadata.put(TimingEvent.FlowEventConstants.FLOW_GROUP_FIELD, this.flowGroup);
    metadata.put(TimingEvent.FlowEventConstants.FLOW_NAME_FIELD, this.flowName);
    metadata.put(TimingEvent.FlowEventConstants.FLOW_EXECUTION_ID_FIELD, String.valueOf(this.flowExecutionId));
    metadata.put(TimingEvent.FlowEventConstants.JOB_NAME_FIELD, this.jobName);
    metadata.put(TimingEvent.FlowEventConstants.JOB_GROUP_FIELD, this.jobGroup);
    String jobExecutionId = "1111";
    metadata.put(TimingEvent.FlowEventConstants.JOB_EXECUTION_ID_FIELD, jobExecutionId);
    String message = "https://myServer:8143/1234/1111";
    metadata.put(TimingEvent.METADATA_MESSAGE, message);
    metadata.put(TimingEvent.METADATA_START_TIME, "7");
    metadata.put(TimingEvent.METADATA_END_TIME, "8");
    metadata.putAll(customMetadata);
    return new GobblinTrackingEvent(timestamp, namespace, eventName, metadata);
  }

  MockKafkaAvroJobStatusMonitor createMockKafkaAvroJobStatusMonitor(AtomicBoolean shouldThrowFakeExceptionInParseJobStatusToggle, Config additionalConfig,
      GaaSJobObservabilityEventProducer eventProducer, DagManagementStateStore dagManagementStateStore)
      throws IOException, ReflectiveOperationException {
    Config config = ConfigFactory.empty().withValue(ConfigurationKeys.KAFKA_BROKERS, ConfigValueFactory.fromAnyRef("localhost:0000"))
        .withValue(Kafka09ConsumerClient.GOBBLIN_CONFIG_VALUE_DESERIALIZER_CLASS_KEY, ConfigValueFactory.fromAnyRef("org.apache.kafka.common.serialization.ByteArrayDeserializer"))
        .withValue(ConfigurationKeys.STATE_STORE_ROOT_DIR_KEY, ConfigValueFactory.fromAnyRef(stateStoreDir))
        .withValue("zookeeper.connect", ConfigValueFactory.fromAnyRef("localhost:2121"))
        .withFallback(additionalConfig);
    return new MockKafkaAvroJobStatusMonitor("test", config, 1, shouldThrowFakeExceptionInParseJobStatusToggle,
        eventProducer, dagManagementStateStore, errorClassifier, eventHandler);
  }
  /**
   *   Create a dummy event to test if it is filtered out by the consumer.
   */
  private GobblinTrackingEvent createDummyEvent() {
    String namespace = "org.apache.gobblin.metrics";
    Long timestamp = System.currentTimeMillis();
    String name = "dummy";
    Map<String, String> metadata = Maps.newHashMap();
    metadata.put("k1", "v1");
    metadata.put("k2", "v2");

    return new GobblinTrackingEvent(timestamp, namespace, name, metadata);
  }

  private DecodeableKafkaRecord<byte[], byte[]> convertMessageAndMetadataToDecodableKafkaRecord(MessageAndMetadata<byte[], byte[]> messageAndMetadata) {
    ConsumerRecord<byte[], byte[]> consumerRecord = new ConsumerRecord<>(TOPIC, messageAndMetadata.partition(), messageAndMetadata.offset(), messageAndMetadata.key(), messageAndMetadata.message());
    return new Kafka09ConsumerClient.Kafka09ConsumerRecord<>(consumerRecord);
  }

  private void cleanUpDir() throws Exception {
    File specStoreDir = new File(this.stateStoreDir);
    if (specStoreDir.exists()) {
      FileUtils.deleteDirectory(specStoreDir);
    }
  }

  @AfterMethod
  public void cleanUpStateStore() {
    try {
      cleanUpDir();
    } catch(Exception e) {
      System.err.println("Failed to clean up the state store.");
    }
  }

  @AfterClass
  public void tearDown() {
    try {
      this.kafkaTestHelper.close();
    } catch(Exception e) {
      System.err.println("Failed to close Kafka server.");
    }
  }

  static class MockKafkaAvroJobStatusMonitor extends KafkaAvroJobStatusMonitor {
    private final AtomicBoolean shouldThrowFakeExceptionInParseJobStatus;
    @Getter
    private volatile AtomicInteger numFakeExceptionsFromParseJobStatus = new AtomicInteger(0);

    /**
     * @param shouldThrowFakeExceptionInParseJobStatusToggle - pass (and retain) to dial whether `parseJobStatus` throws
     */
    public MockKafkaAvroJobStatusMonitor(String topic, Config config, int numThreads,
        AtomicBoolean shouldThrowFakeExceptionInParseJobStatusToggle, GaaSJobObservabilityEventProducer producer,
        DagManagementStateStore dagManagementStateStore, ErrorClassifier errorClassifier, JobIssueEventHandler eventHandler)
        throws IOException, ReflectiveOperationException {
      super(topic, config, numThreads, eventHandler, producer, dagManagementStateStore, errorClassifier);
      shouldThrowFakeExceptionInParseJobStatus = shouldThrowFakeExceptionInParseJobStatusToggle;
    }

    @Override
    protected void processMessage(DecodeableKafkaRecord<byte[], byte[]> record) {
      super.processMessage(record);
    }

    @Override
    protected void buildMetricsContextAndMetrics() {
      super.buildMetricsContextAndMetrics();
    }

    /**
     * Overridden to stub potential exception within core processing of `processMessage` (specifically retried portion).
     * Although truly plausible (IO)Exceptions would originate from `KafkaJobStatusMonitor.updateJobStatus`,
     * that is `static`, so unavailable for override.  The approach here is a pragmatic compromise, being simpler than
     * the alternative of writing a mock `StateStore.Factory` that the `KafkaJobStatusMonitor` ctor could reflect,
     * instantiate, and finally create a mock `StateStore` from.
     */
    @Override
    public org.apache.gobblin.configuration.State parseJobStatus(GobblinTrackingEvent event) {
      if (shouldThrowFakeExceptionInParseJobStatus.get()) {
        int n = numFakeExceptionsFromParseJobStatus.incrementAndGet();
        throw new RuntimeException(String.format("BOOM! Failure [%d] w/ event at %d", n, event.getTimestamp()));
      } else {
        return super.parseJobStatus(event);
      }
    }
  }
}