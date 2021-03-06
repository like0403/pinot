/**
 * Copyright (C) 2014-2016 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.controller.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.apache.helix.HelixAdmin;
import org.apache.helix.ZNRecord;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.model.IdealState;
import org.apache.helix.store.zk.ZkHelixPropertyStore;
import org.joda.time.Duration;
import org.joda.time.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.linkedin.pinot.common.config.AbstractTableConfig;
import com.linkedin.pinot.common.config.TableNameBuilder;
import com.linkedin.pinot.common.metadata.ZKMetadataProvider;
import com.linkedin.pinot.common.metadata.segment.OfflineSegmentZKMetadata;
import com.linkedin.pinot.common.metadata.segment.RealtimeSegmentZKMetadata;
import com.linkedin.pinot.common.metadata.stream.KafkaStreamMetadata;
import com.linkedin.pinot.common.metrics.ValidationMetrics;
import com.linkedin.pinot.common.segment.SegmentMetadata;
import com.linkedin.pinot.common.utils.CommonConstants.Helix.TableType;
import com.linkedin.pinot.common.utils.HLCSegmentName;
import com.linkedin.pinot.common.utils.LLCSegmentName;
import com.linkedin.pinot.common.utils.SegmentName;
import com.linkedin.pinot.common.utils.helix.HelixHelper;
import com.linkedin.pinot.common.utils.time.TimeUtils;
import com.linkedin.pinot.controller.ControllerConf;
import com.linkedin.pinot.controller.helix.core.PinotHelixResourceManager;
import com.linkedin.pinot.controller.helix.core.PinotHelixSegmentOnlineOfflineStateModelGenerator;
import com.linkedin.pinot.controller.helix.core.realtime.PinotLLCRealtimeSegmentManager;
import com.linkedin.pinot.core.segment.index.SegmentMetadataImpl;


/**
 * Manages the segment validation metrics, to ensure that all offline segments are contiguous (no missing segments) and
 * that the offline push delay isn't too high.
 *
 * Dec 10, 2014
*/

public class ValidationManager {
  private static final Logger LOGGER = LoggerFactory.getLogger(ValidationManager.class);
  private final ValidationMetrics _validationMetrics;
  private final ScheduledExecutorService _executorService;
  private final PinotHelixResourceManager _pinotHelixResourceManager;
  private final long _validationIntervalSeconds;
  private final boolean _autoCreateOnError;
  private final PinotLLCRealtimeSegmentManager _llcRealtimeSegmentManager;

  /**
   * Constructs the validation manager.
   * @param validationMetrics The validation metrics utility used to publish the metrics.
   * @param pinotHelixResourceManager The resource manager used to interact with Helix
   * @param config
   * @param llcRealtimeSegmentManager
   */
  public ValidationManager(ValidationMetrics validationMetrics, PinotHelixResourceManager pinotHelixResourceManager,
      ControllerConf config, PinotLLCRealtimeSegmentManager llcRealtimeSegmentManager) {
    _validationMetrics = validationMetrics;
    _pinotHelixResourceManager = pinotHelixResourceManager;
    _validationIntervalSeconds = config.getValidationControllerFrequencyInSeconds();
    _autoCreateOnError = true;
    _llcRealtimeSegmentManager = llcRealtimeSegmentManager;

    _executorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
      @Override
      public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setName("PinotValidationManagerExecutorService");
        return thread;
      }
    });
  }

  /**
   * Starts the validation manager.
   */
  public void start() {
    LOGGER.info("Starting validation manager");

    // Set up an executor that executes validation tasks periodically
    _executorService.scheduleWithFixedDelay(new Runnable() {
      @Override
      public void run() {
        try {
          runValidation();
        } catch (Exception e) {
          LOGGER.warn("Caught exception while running validation", e);
        }
      }
    }, 120, _validationIntervalSeconds, TimeUnit.SECONDS);
  }

  /**
   * Stops the validation manager.
   */
  public void stop() {
    // Shut down the executor
    _executorService.shutdown();
  }

  /**
   * Runs a validation pass over the currently loaded tables.
   */
  public void runValidation() {
    if (!_pinotHelixResourceManager.isLeader()) {
      LOGGER.info("Skipping validation, not leader!");
      return;
    }

    LOGGER.info("Starting validation");
    // Fetch the list of tables
    List<String> allTableNames = _pinotHelixResourceManager.getAllPinotTableNames();
    ZkHelixPropertyStore<ZNRecord> propertyStore = _pinotHelixResourceManager.getPropertyStore();
    HelixAdmin helixAdmin = _pinotHelixResourceManager.getHelixAdmin();
    String clusterName = _pinotHelixResourceManager.getHelixClusterName();
    IdealState brokerIdealState = HelixHelper.getBrokerIdealStates(helixAdmin, clusterName);
    for (String tableName : allTableNames) {
      List<SegmentMetadata> segmentMetadataList = new ArrayList<SegmentMetadata>();

      TableType tableType = TableNameBuilder.getTableTypeFromTableName(tableName);
      AbstractTableConfig tableConfig = null;
      // For each table, fetch the metadata for all its segments
      if (tableType.equals(TableType.OFFLINE)) {
        validateOfflineSegmentPush(propertyStore, tableName, segmentMetadataList);
        try {
          tableConfig = _pinotHelixResourceManager.getOfflineTableConfig(tableName);
          rebuildBrokerResourceWhenBrokerAdded(tableConfig, brokerIdealState);
        } catch (Exception e) {
          LOGGER.warn("Cannot get offline tableconfig for {}", tableName);
        }
      } else if (tableType.equals(TableType.REALTIME)) {
        LOGGER.info("Starting to validate table {}", tableName);
        List<RealtimeSegmentZKMetadata> realtimeSegmentZKMetadatas = ZKMetadataProvider.getRealtimeSegmentZKMetadataListForTable(propertyStore, tableName);
        boolean countHLCSegments = true;  // false if this table has ONLY LLC segments (i.e. fully migrated)
        KafkaStreamMetadata streamMetadata = null;
        try {
          tableConfig = _pinotHelixResourceManager.getRealtimeTableConfig(tableName);
          rebuildBrokerResourceWhenBrokerAdded(tableConfig, brokerIdealState);
          streamMetadata = new KafkaStreamMetadata(tableConfig.getIndexingConfig().getStreamConfigs());
          if (streamMetadata.hasSimpleKafkaConsumerType() && !streamMetadata.hasHighLevelKafkaConsumerType()) {
            countHLCSegments = false;
          }
          for (RealtimeSegmentZKMetadata realtimeSegmentZKMetadata : realtimeSegmentZKMetadatas) {
            SegmentMetadata segmentMetadata = new SegmentMetadataImpl(realtimeSegmentZKMetadata);
            segmentMetadataList.add(segmentMetadata);
          }
          // Update the gauge to contain the total document count in the segments
          _validationMetrics.updateTotalDocumentsGauge(tableName, computeRealtimeTotalDocumentInSegments(segmentMetadataList,
              countHLCSegments));
          if (streamMetadata.hasSimpleKafkaConsumerType()) {
            validateLLCSegments(tableName, tableConfig);
          }
        } catch (Exception e) {
          if (tableConfig == null) {
            LOGGER.warn("Cannot get realtime tableconfig for {}", tableName);
          } else if (streamMetadata == null) {
            LOGGER.warn("Cannot get streamconfig for {}", tableName);
          } else {
            LOGGER.error("Exception while validating table {}", tableName, e);
          }
        }
      } else {
        LOGGER.warn("Ignoring table type {} for table {}", tableType, tableName);
      }
    }
    LOGGER.info("Validation completed");
  }

  // If we add a new broker, we want to rebuild the broker resource.
  void rebuildBrokerResourceWhenBrokerAdded(AbstractTableConfig tableConfig, IdealState brokerIdealState) {
    String brokerTenantName = tableConfig.getTenantConfig().getBroker();
    String tableName = tableConfig.getTableName();

    Set<String> brokerTenantInstances = _pinotHelixResourceManager.getAllInstancesForBrokerTenant(brokerTenantName);
    Set<String> idealStateBrokerInstances = brokerIdealState.getInstanceSet(tableName);

    if(!idealStateBrokerInstances.equals(brokerTenantInstances)) {
      LOGGER.info("Rebuilding broker resource for table {}", tableName);
      _pinotHelixResourceManager.rebuildBrokerResourceFromHelixTags(tableName);
    } else {
      LOGGER.info("Broker resource is not rebuilt for table {}", tableName);
    }
  }

  // For LLC segments, validate that there is at least one segment in CONSUMING state for every partition.
  void validateLLCSegments(final String realtimeTableName, AbstractTableConfig tableConfig) {
    LOGGER.info("Validating LLC Segments for {}", realtimeTableName);
    Map<String, String> streamConfigs = tableConfig.getIndexingConfig().getStreamConfigs();
    ZNRecord partitionAssignment = _llcRealtimeSegmentManager.getKafkaPartitionAssignment(realtimeTableName);
    if (partitionAssignment == null) {
      LOGGER.warn("No partition assignment found for table {}", realtimeTableName);
      return;
    }
    Map<String, List<String>> partitionToHostsMap = partitionAssignment.getListFields();
    // Keep a set of kafka partitions, and remove the partition when we find a segment in CONSUMING state in
    // that partition.
    Set<Integer> nonConsumingKafkaPartitions = new HashSet<>(partitionToHostsMap.size());
    for (String partitionStr : partitionToHostsMap.keySet()) {
      nonConsumingKafkaPartitions.add(Integer.valueOf(partitionStr));
    }

    IdealState idealState =
        HelixHelper.getTableIdealState(_pinotHelixResourceManager.getHelixZkManager(), realtimeTableName);
    if (!idealState.isEnabled()) {
      // No validation to be done.
      LOGGER.info("Skipping validation for {} since it is disabled", realtimeTableName);
      return;
    }
    // Walk through all segments in the idealState, looking for one instance that is in CONSUMING state. If we find one
    // remove the kafka partition that the segment belongs to, from the kafka partition set.
    // Make sure that there are at least some LLC segments in place. If there are no LLC segments, it is possible
    // that this table is in the process of being disabled for LLC
    Set<String> segmentIds = idealState.getPartitionSet();
    List<String> llcSegments = new ArrayList<>(segmentIds.size());
    for (String segmentId : segmentIds) {
      if (SegmentName.isLowLevelConsumerSegmentName(segmentId)) {
        llcSegments.add(segmentId);
        Map<String, String> stateMap = idealState.getInstanceStateMap(segmentId);
        Iterator<String> iterator = stateMap.values().iterator();
        // If there is at least one instance in CONSUMING state, we are good.
        boolean foundConsuming = false;
        while (iterator.hasNext() && !foundConsuming) {
          String stateString = iterator.next();
          if (stateString.equals(PinotHelixSegmentOnlineOfflineStateModelGenerator.CONSUMING_STATE)) {
            LOGGER.info("Found CONSUMING segment {}", segmentId);
            foundConsuming = true;
          }
        }
        if (foundConsuming) {
          LLCSegmentName llcSegmentName = new LLCSegmentName(segmentId);
          nonConsumingKafkaPartitions.remove(llcSegmentName.getPartitionId());
        }
      }
     }

    // Kafka partition set now has all the partitions that do not have any segments in CONSUMING state.
    if (!llcSegments.isEmpty()) {
      // Raise the metric only if there is at least one llc segment in the idealstate.
      _validationMetrics.updateNumNonConsumingPartitionsMetric(realtimeTableName, nonConsumingKafkaPartitions.size());
      // Recreate a segment for the partitions that are missing one.
      for (Integer kafkaPartition : nonConsumingKafkaPartitions) {
        LOGGER.warn("Table {}, kafka partition {} has no segments in CONSUMING state (out of {} llc segments)",
            realtimeTableName, kafkaPartition, llcSegments.size());
      }
      if (_autoCreateOnError) {
        _llcRealtimeSegmentManager.createConsumingSegment(realtimeTableName, nonConsumingKafkaPartitions, llcSegments,
            tableConfig);
      }
    }
    // Make this call after other validations (so that we verify that we are consistent against the existing partition
    // assignment). This call may end up changing the kafka partition assignment for the table.
    _llcRealtimeSegmentManager.updateKafkaPartitionsIfNecessary(realtimeTableName, tableConfig);
  }

  // For offline segment pushes, validate that there are no missing segments, and update metrics
  private void validateOfflineSegmentPush(ZkHelixPropertyStore<ZNRecord> propertyStore, String tableName,
      List<SegmentMetadata> segmentMetadataList) {
    List<OfflineSegmentZKMetadata> offlineSegmentZKMetadatas = ZKMetadataProvider
        .getOfflineSegmentZKMetadataListForTable(propertyStore, tableName);
    for (OfflineSegmentZKMetadata offlineSegmentZKMetadata : offlineSegmentZKMetadatas) {
      SegmentMetadata segmentMetadata = new SegmentMetadataImpl(offlineSegmentZKMetadata);
      segmentMetadataList.add(segmentMetadata);
    }

    // Calculate missing segments only for offline tables
    int missingSegmentCount = 0;

    // Compute the missing segments if there are at least two
    if (2 < segmentMetadataList.size()) {
      List<Interval> segmentIntervals = new ArrayList<Interval>();
      for (SegmentMetadata segmentMetadata : segmentMetadataList) {
        Interval timeInterval = segmentMetadata.getTimeInterval();
        if (timeInterval != null && TimeUtils.timeValueInValidRange(timeInterval.getStartMillis()) && TimeUtils
                .timeValueInValidRange(timeInterval.getEndMillis())) {
          segmentIntervals.add(timeInterval);
        }
      }

      List<Interval> missingIntervals = computeMissingIntervals(segmentIntervals, segmentMetadataList.get(0).getTimeGranularity());
      missingSegmentCount = missingIntervals.size();

      for (Interval missingInterval : missingIntervals) {
        LOGGER.warn("Missing data in table {} for time interval {}", tableName, missingInterval);
      }
    }

    // Update the gauge that contains the number of missing segments
    _validationMetrics.updateMissingSegmentsGauge(tableName, missingSegmentCount);

    // Compute the max segment end time and max segment push time
    long maxSegmentEndTime = Long.MIN_VALUE;
    long maxSegmentPushTime = Long.MIN_VALUE;

    for (SegmentMetadata segmentMetadata : segmentMetadataList) {
      Interval segmentInterval = segmentMetadata.getTimeInterval();

      if (segmentInterval != null && maxSegmentEndTime < segmentInterval.getEndMillis()) {
        maxSegmentEndTime = segmentInterval.getEndMillis();
      }

      long segmentPushTime = segmentMetadata.getPushTime();
      long segmentRefreshTime = segmentMetadata.getRefreshTime();
      long segmentUpdateTime = Math.max(segmentPushTime, segmentRefreshTime);

      if (maxSegmentPushTime < segmentUpdateTime) {
        maxSegmentPushTime = segmentUpdateTime;
      }
    }

    // Update the gauges that contain the delay between the current time and last segment end time
    _validationMetrics.updateOfflineSegmentDelayGauge(tableName, maxSegmentEndTime);
    _validationMetrics.updateLastPushTimeGauge(tableName, maxSegmentPushTime);
    // Update the gauge to contain the total document count in the segments
    _validationMetrics.updateTotalDocumentsGauge(tableName, computeOfflineTotalDocumentInSegments(segmentMetadataList));
    // Update the gauge to contain the total number of segments for this table
    _validationMetrics.updateSegmentCountGauge(tableName, segmentMetadataList.size());
  }

  public static long computeOfflineTotalDocumentInSegments(List<SegmentMetadata> segmentMetadataList)  {
    long totalDocumentCount = 0;

    for (SegmentMetadata segmentMetadata : segmentMetadataList) {
      totalDocumentCount += segmentMetadata.getTotalRawDocs();
    }
    return totalDocumentCount;
  }

  public static long computeRealtimeTotalDocumentInSegments(List<SegmentMetadata> segmentMetadataList,
      boolean countHLCSegments)  {
    long totalDocumentCount = 0;

    String groupId = "";

    for (SegmentMetadata segmentMetadata : segmentMetadataList) {
      String segmentName = segmentMetadata.getName();
      if (SegmentName.isHighLevelConsumerSegmentName(segmentName)) {
        if (countHLCSegments) {
          HLCSegmentName hlcSegmentName = new HLCSegmentName(segmentName);
          String segmentGroupIdName = hlcSegmentName.getGroupId();

          if (groupId.isEmpty()) {
            groupId = segmentGroupIdName;
          }
          // Discard all segments with different groupids as they are replicas
          if (groupId.equals(segmentGroupIdName) && segmentMetadata.getTotalRawDocs() >= 0) {
            totalDocumentCount += segmentMetadata.getTotalRawDocs();
          }
        }
      } else {
        // Low level segments
        if (!countHLCSegments) {
          totalDocumentCount += segmentMetadata.getTotalRawDocs();
        }
      }
    }
    return totalDocumentCount;
  }

  /**
   * Computes a list of missing intervals, given a list of existing intervals and the expected frequency of the
   * intervals.
   *
   * @param segmentIntervals The list of existing intervals
   * @param frequency The expected interval frequency
   * @return The list of missing intervals
   */
  public static List<Interval> computeMissingIntervals(List<Interval> segmentIntervals, Duration frequency) {
    // Sanity check for freuency
    if (frequency == null) {
      return Collections.emptyList();
    }

    // Default segment granularity to day level if its small than hours.
    if (frequency.getMillis() < Duration.standardHours(1).getMillis()) {
      frequency = Duration.standardDays(1);
    }

    // If there are less than two segments, none can be missing
    if (segmentIntervals.size() < 2) {
      return Collections.emptyList();
    }

    // Sort the intervals by ascending starting time
    List<Interval> sortedSegmentIntervals = new ArrayList<Interval>(segmentIntervals);
    Collections.sort(sortedSegmentIntervals, new Comparator<Interval>() {
      @Override
      public int compare(Interval first, Interval second) {
        if (first.getStartMillis() < second.getStartMillis())
          return -1;
        else if (second.getStartMillis() < first.getStartMillis())
          return 1;
        return 0;
      }
    });

    // Find the minimum starting time and maximum ending time
    final long startTime = sortedSegmentIntervals.get(0).getStartMillis();
    long endTime = Long.MIN_VALUE;
    for (Interval sortedSegmentInterval : sortedSegmentIntervals) {
      if (endTime < sortedSegmentInterval.getEndMillis()) {
        endTime = sortedSegmentInterval.getEndMillis();
      }
    }

    final long frequencyMillis = frequency.getMillis();
    int lastEndIntervalCount = 0;
    List<Interval> missingIntervals = new ArrayList<Interval>(10);
    for (Interval segmentInterval : sortedSegmentIntervals) {
      int startIntervalCount = (int) ((segmentInterval.getStartMillis() - startTime) / frequencyMillis);
      int endIntervalCount = (int) ((segmentInterval.getEndMillis() - startTime) / frequencyMillis);

      // If there is at least one complete missing interval between the end of the previous interval and the start of
      // the current interval, then mark the missing interval(s) as missing
      if (lastEndIntervalCount < startIntervalCount - 1) {
        for (int missingIntervalIndex = lastEndIntervalCount + 1; missingIntervalIndex < startIntervalCount; ++missingIntervalIndex) {
          missingIntervals.add(new Interval(startTime + frequencyMillis * missingIntervalIndex, startTime + frequencyMillis * (missingIntervalIndex + 1) - 1));
        }
      }

      lastEndIntervalCount = Math.max(lastEndIntervalCount, endIntervalCount);
    }

    return missingIntervals;
  }

  /**
   * Counts the number of missing segments, given their start times and their expected frequency.
   *
   * @param sortedStartTimes Start times for the segments, sorted in ascending order.
   * @param frequency The expected segment frequency (ie. daily, hourly, etc.)
   */
  public static int countMissingSegments(long[] sortedStartTimes, TimeUnit frequency) {
    // If there are less than two segments, none can be missing
    if (sortedStartTimes.length < 2) {
      return 0;
    }

    final long frequencyMillis = frequency.toMillis(1);
    final long halfFrequencyMillis = frequencyMillis / 2;
    final long firstStartTime = sortedStartTimes[0];
    final long lastStartTime = sortedStartTimes[sortedStartTimes.length - 1];
    final int expectedSegmentCount = (int) ((lastStartTime + halfFrequencyMillis - firstStartTime) / frequencyMillis);

    int missingSegments = 0;
    int currentIndex = 1;
    for (int expectedIntervalCount = 1; expectedIntervalCount <= expectedSegmentCount;) {
      // Count the number of complete intervals that are found
      final int intervalCount =
          (int) ((sortedStartTimes[currentIndex] + halfFrequencyMillis - firstStartTime) / frequencyMillis);

      // Does this segment have the expected interval count?
      if (intervalCount == expectedIntervalCount) {
        // Yes, advance both the current index and expected interval count
        ++expectedIntervalCount;
        ++currentIndex;
      } else {
        if (intervalCount < expectedIntervalCount) {
          // Duplicate segment, just advance the index
          ++currentIndex;
        } else {
          // Missing segment(s), advance the index, increment the number of missing segments by the number of missing
          // intervals and set the expected interval to the following one
          missingSegments += intervalCount - expectedIntervalCount;
          expectedIntervalCount = intervalCount + 1;
          ++currentIndex;
        }
      }
    }

    return missingSegments;
  }
}
