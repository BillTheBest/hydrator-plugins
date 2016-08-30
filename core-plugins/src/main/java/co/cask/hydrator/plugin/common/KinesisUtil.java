/*
 * Copyright © 2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.hydrator.plugin.common;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.kinesis.AmazonKinesisClient;
import com.amazonaws.services.kinesis.model.CreateStreamRequest;
import com.amazonaws.services.kinesis.model.DescribeStreamRequest;
import com.amazonaws.services.kinesis.model.StreamStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Utility methods to help create and manage Kinesis Streams
 */
public final class KinesisUtil {

  private static final Logger LOG = LoggerFactory.getLogger(KinesisUtil.class);
  private static final long TIMEOUT = 3 * 60 * 1000;
  private static final long SLEEP_INTERVAL = 1000 * 10;

  private KinesisUtil() {
  }

  /**
   * Creates an Amazon Kinesis stream if it does not exist and waits for it to become available
   *
   * @param kinesisClient The {@link AmazonKinesisClient} with Amazon Kinesis read and write privileges
   * @param streamName The Amazon Kinesis stream name to create
   * @param shardCount The shard count to create the stream with
   * @throws IllegalStateException Invalid Amazon Kinesis stream state
   * @throws IllegalStateException Stream does not become active before the timeout
   */
  public static void createAndWaitForStream(AmazonKinesisClient kinesisClient, String streamName, int shardCount) {
    if (streamExists(kinesisClient, streamName)) {
      StreamStatus streamStatus = getStreamState(kinesisClient, streamName);
      switch (streamStatus) {
        case DELETING:
          waitForStream(kinesisClient, streamName, null);
          if (streamExists(kinesisClient, streamName)) {
            throw new IllegalStateException(String.format("Timed out waiting for stream %s to delete", streamName));
          }
          createStream(streamName, shardCount, kinesisClient);
          break;
        case ACTIVE:
          LOG.info("Stream {} already exists", streamName);
          return;
        case CREATING:
          LOG.info("Stream {} is being created", streamName);
          break;
        case UPDATING:
          LOG.info("Stream {} is being updated", streamName);
          return;
        default:
          throw new IllegalStateException(String.format("Illegal stream state: %s", streamStatus));
      }
    } else {
      createStream(streamName, shardCount, kinesisClient);
    }
    waitForStream(kinesisClient, streamName, StreamStatus.ACTIVE);
    if (!(getStreamState(kinesisClient, streamName) == StreamStatus.ACTIVE)) {
      throw new IllegalStateException(String.format("Stream %s did not become active in %d", streamName, TIMEOUT));
    }
  }

  private static void waitForStream(AmazonKinesisClient kinesisClient, String streamName,
                                    @Nullable StreamStatus expectedStatus) {
    long waitTime = System.currentTimeMillis() + TIMEOUT;
    while (System.currentTimeMillis() < waitTime) {
      try {
        LOG.debug("Deleting Stream {} ", streamName);
        TimeUnit.MILLISECONDS.sleep(SLEEP_INTERVAL);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      StreamStatus streamState;
      try {
        streamState = getStreamState(kinesisClient, streamName);
      } catch (IllegalStateException e) {
        streamState = null;
      }
      if (expectedStatus == streamState) {
        return;
      }
    }
  }

  private static void createStream(String streamName, int shardCount, AmazonKinesisClient kinesisClient) {
    CreateStreamRequest createStreamRequest = new CreateStreamRequest();
    createStreamRequest.setStreamName(streamName);
    createStreamRequest.setShardCount(shardCount);
    kinesisClient.createStream(createStreamRequest);
    LOG.info("Stream {} is being created", streamName);
  }

  /**
   * Return the state of a Amazon Kinesis stream.
   *
   * @param kinesisClient The {@link AmazonKinesisClient} with Amazon Kinesis read privileges
   * @param streamName The Amazon Kinesis stream to get the state of
   * @return String representation of the Stream state
   */
  private static StreamStatus getStreamState(AmazonKinesisClient kinesisClient, String streamName) {
    DescribeStreamRequest describeStreamRequest = new DescribeStreamRequest();
    describeStreamRequest.setStreamName(streamName);
    try {
      String status = kinesisClient.describeStream(describeStreamRequest).getStreamDescription().getStreamStatus();
      return StreamStatus.fromValue(status);
    } catch (AmazonServiceException e) {
      throw new IllegalStateException(String.format("State of the stream %s could not be found", streamName), e);
    }
  }

  /**
   * Helper method to determine if an Amazon Kinesis stream exists.
   *
   * @param kinesisClient The {@link AmazonKinesisClient} with Amazon Kinesis read privileges
   * @param streamName    The Amazon Kinesis stream to check for
   * @return true if the Amazon Kinesis stream exists, otherwise return false
   */
  private static boolean streamExists(AmazonKinesisClient kinesisClient, String streamName) {
    try {
      getStreamState(kinesisClient, streamName);
      return true;
    } catch (IllegalStateException e) {
      return false;
    }
  }
}