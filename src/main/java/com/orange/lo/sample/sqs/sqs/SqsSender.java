/** 
* Copyright (c) Orange, Inc. and its affiliates. All Rights Reserved. 
* 
* This source code is licensed under the MIT license found in the 
* LICENSE file in the root directory of this source tree. 
*/

package com.orange.lo.sample.sqs.sqs;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry;
import com.orange.lo.sample.sqs.utils.Counters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Random;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@EnableScheduling
@Component
public class SqsSender {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final SqsProperties sqsProperties;
    private final AmazonSQS sqs;
    private final Counters counters;
    private final ThreadPoolExecutor tpe;
    private final Random random;

    public SqsSender(AmazonSQS amazonSQ, SqsProperties sqsProperties, ThreadPoolExecutor tpe, Counters counters) {
        this.sqs = amazonSQ;
        this.sqsProperties = sqsProperties;
        this.random = new Random();
        this.tpe = tpe;
        this.counters = counters;
    }

    public void send(List<String> message) {
        try {
            tpe.submit(() -> {
                counters.evtAttemptCount().increment();
                send(message, 0);
            });
        } catch (RejectedExecutionException rejected) {
            counters.evtRejected().increment();
            LOG.error("Too many tasks in queue, rejecting: {}", message.hashCode());
        }
    }

    //send batches
    private void send(List<String> batch, int attemptCount) {
        if (attemptCount > 0)
            LOG.info("Retrying to send ({}): {}", attemptCount, batch.hashCode());

        AtomicInteger index = new AtomicInteger();
        try {//send a batch
            List<SendMessageBatchRequestEntry> entries = batch.stream()
                    .map(m -> toSendMessageBatchRequestEntry(index.getAndIncrement(), m, random.nextDouble()))
                    .collect(Collectors.toList());

            SendMessageBatchRequest sendBatchRequest = new SendMessageBatchRequest()
                    .withQueueUrl(sqsProperties.getQueueUrl())
                    .withEntries(entries);
            sqs.sendMessageBatch(sendBatchRequest);
            counters.evtSuccess().increment(sendBatchRequest.getEntries().size());
        } catch (final AmazonServiceException ase) {
            StringBuilder sb = new StringBuilder("Caught an AmazonServiceException, which means ")
                    .append("your request made it to Amazon SQS, but was ")
                    .append("rejected with an error response for some reason.\n")
                    .append("Error Message:    {}\n")
                    .append("HTTP Status Code: {} \n")
                    .append("AWS Error Code:   {}\n")
                    .append("Error Type:       {}\n")
                    .append("Request ID:       {}");
            LOG.error(sb.toString(), ase.getMessage(), ase.getStatusCode(), ase.getErrorCode(), ase.getErrorType(),
                    ase.getRequestId());
        } catch (final AmazonClientException ace) {
            StringBuilder sb = new StringBuilder("Caught an AmazonClientException, which means ")
                    .append("the client encountered a serious internal problem while ")
                    .append("trying to communicate with Amazon SQS, such as not ")
                    .append("being able to access the network.\n")
                    .append("Error Message: {}");
            LOG.error(sb.toString(), ace.getMessage());
        }
    }

    private SendMessageBatchRequestEntry toSendMessageBatchRequestEntry(int id, String message, double deduplicationId) {
        return new SendMessageBatchRequestEntry(String.valueOf(id), message)
                .withMessageGroupId(sqsProperties.getMessageGroupId())
                .withMessageDeduplicationId(Double.toString(deduplicationId));
    }

    @Scheduled(fixedRate = 30000)
    public void reportExecutorData() {
        LOG.info("Pool size: {}, active threads: {}, tasks in queue: {}", tpe.getPoolSize(), tpe.getActiveCount(), tpe.getQueue().size());
    }
}
