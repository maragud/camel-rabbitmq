/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.rabbitmq;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;

import com.rabbitmq.client.*;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import org.apache.camel.*;
import org.apache.camel.support.ServiceSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RabbitConsumer extends ServiceSupport {
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final RabbitMQConsumer consumer;
    private Channel channel;
    private String tag;
    /** Consumer tag for this consumer. */
    private volatile boolean stopping;

    private final Semaphore lock = new Semaphore(1);

    /**
     * Constructs a new instance and records its association to the passed-in
     * channel.
     */
    RabbitConsumer(RabbitMQConsumer consumer) {
        // super(channel);
        this.consumer = consumer;
        try {
            Connection conn = consumer.getConnection();
            this.channel = openChannel(conn);
        } catch (IOException | TimeoutException e) {
            log.warn("Unable to open channel for RabbitMQConsumer. Continuing and will try again", e);
        }
    }

    private void handleDelivery(String consumerTag, Delivery message) {
        doHandleDelivery(tag, message.getEnvelope(), message.getProperties(), message.getBody());
    }

    public void doHandleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
        Exchange exchange = consumer.getEndpoint().createRabbitExchange(envelope, properties, body);
        consumer.getEndpoint().getMessageConverter().mergeAmqpProperties(exchange, properties);

        boolean sendReply = properties.getReplyTo() != null;
        if (sendReply && !exchange.getPattern().isOutCapable()) {
            log.debug("In an inOut capable route");
            exchange.setPattern(ExchangePattern.InOut);
        }

        log.trace("Created exchange [exchange={}]", exchange);
        long deliveryTag = envelope.getDeliveryTag();
        try {
            consumer.getAsyncProcessor().process(exchange, doneSync -> asyncCallback(doneSync, properties, exchange, sendReply, deliveryTag));
        } catch (Exception e) {
            exchange.setException(e);
        }
    }

    private void asyncCallback(boolean doneSync, AMQP.BasicProperties properties, Exchange exchange, boolean sendReply, long deliveryTag) {
        // obtain the message after processing
        Message msg;
        if (exchange.hasOut()) {
            msg = exchange.getOut();
        } else {
            msg = exchange.getIn();
        }

        if (exchange.getException() != null) {
            consumer.getExceptionHandler().handleException("Error processing exchange", exchange, exchange.getException());
        }

        if (!exchange.isFailed()) {
            // processing success
            if (sendReply && exchange.getPattern().isOutCapable()) {
                try {
                    consumer.getEndpoint().publishExchangeToChannel(exchange, channel, properties.getReplyTo());
                } catch (RuntimeCamelException e) {
                    // set the exception on the exchange so it can send the
                    // exception back to the producer
                    exchange.setException(e);
                    consumer.getExceptionHandler().handleException("Error processing exchange", exchange, e);
                } catch (IOException e) {
                    exchange.setException(e);
                }
            }
            if (!consumer.getEndpoint().isAutoAck()) {
                log.trace("Acknowledging receipt [delivery_tag={}]", deliveryTag);
                try {
                    channel.basicAck(deliveryTag, false);
                } catch (IOException e) {
                    exchange.setException(e);
                }
            }
        }
        // The exchange could have failed when sending the above message
        if (exchange.isFailed()) {
            if (consumer.getEndpoint().isTransferException() && exchange.getPattern().isOutCapable()) {
                // the inOut exchange failed so put the exception in the body
                // and send back
                msg.setBody(exchange.getException());
                exchange.setOut(msg);
                exchange.getOut().setHeader(RabbitMQConstants.CORRELATIONID, exchange.getIn().getHeader(RabbitMQConstants.CORRELATIONID));
                try {
                    consumer.getEndpoint().publishExchangeToChannel(exchange, channel, properties.getReplyTo());
                } catch (RuntimeCamelException e) {
                    consumer.getExceptionHandler().handleException("Error processing exchange", exchange, e);
                } catch (IOException e) {
                    exchange.setException(e);
                }

                if (!consumer.getEndpoint().isAutoAck()) {
                    log.trace("Acknowledging receipt when transferring exception [delivery_tag={}]", deliveryTag);
                    try {
                        channel.basicAck(deliveryTag, false);
                    } catch (IOException e) {
                        exchange.setException(e);
                    }
                }
            } else {
                boolean isRequeueHeaderSet = msg.getHeader(RabbitMQConstants.REQUEUE, false, boolean.class);
                // processing failed, then reject and handle the exception
                if (deliveryTag != 0 && !consumer.getEndpoint().isAutoAck()) {
                    log.trace("Rejecting receipt [delivery_tag={}] with requeue={}", deliveryTag, isRequeueHeaderSet);
                    try {
                        channel.basicReject(deliveryTag, isRequeueHeaderSet);
                    } catch (IOException e) {
                        exchange.setException(e);
                    }
                }
            }
        }
    }

    @Override
    protected void doStart() throws Exception {
        if (channel == null) {
            throw new IOException("The RabbitMQ channel is not open");
        }
        tag = channel.basicConsume(consumer.getEndpoint().getQueue(), consumer.getEndpoint().isAutoAck(), "",
                false, consumer.getEndpoint().isExclusiveConsumer(), null,
                this::handleDelivery, this::handleCancel, this::handleShutdownSignal);
    }

    @Override
    protected void doStop() throws Exception {
        if (channel == null) {
            return;
        }
        if (tag != null && isChannelOpen()) {
            channel.basicCancel(tag);
        }
        try {
            lock.acquire();
            if (isChannelOpen()) {
                channel.close();
            }
        } catch (TimeoutException e) {
            log.error("Timeout occured");
            throw e;
        } catch (InterruptedException e1) {
            log.error("Thread Interrupted!");
        } finally {
            lock.release();
        }
    }

    /**
     * No-op implementation of {@link Consumer#handleCancel(String)}
     *
     * @param consumerTag
     *            the defined consumer tag (client- or server-generated)
     */
    public void handleCancel(String consumerTag) throws IOException {
        log.debug("Received cancel signal on the rabbitMQ channel.");

        try {
            channel.basicCancel(tag);
        } catch (Exception e) {
            //no-op
        }

        this.consumer.getEndpoint().declareExchangeAndQueue(channel);

        try {
            this.start();
        } catch (Exception e) {
            throw new IOException("Error starting consumer", e);
        }
    }

    /**
     * No-op implementation of {@link Consumer#handleShutdownSignal}.
     */
    public void handleShutdownSignal(String consumerTag, ShutdownSignalException sig) {
        log.info("Received shutdown signal on the rabbitMQ channel");

        // Check if the consumer closed the connection or something else
        if (!sig.isInitiatedByApplication()) {
            // Something else closed the connection so reconnect
            boolean connected = false;
            while (!connected && !isStopping()) {
                try {
                    reconnect();
                    connected = true;
                } catch (Exception e) {
                    log.warn("Unable to obtain a RabbitMQ channel. Will try again. Caused by: {}. Stacktrace logged at DEBUG logging level.", e.getMessage());
                    // include stacktrace in DEBUG logging
                    log.debug(e.getMessage(), e);

                    Integer networkRecoveryInterval = consumer.getEndpoint().getNetworkRecoveryInterval();
                    final long connectionRetryInterval = networkRecoveryInterval != null && networkRecoveryInterval > 0
                            ? networkRecoveryInterval : 100L;
                    try {
                        Thread.sleep(connectionRetryInterval);
                    } catch (InterruptedException e1) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    /**
     * If the RabbitMQ connection is good this returns without changing
     * anything. If the connection is down it will attempt to reconnect
     */
    public void reconnect() throws Exception {
        if (isChannelOpen()) {
            // ensure we are started
            start();
            // The connection is good, so nothing to do
            return;
        } else if (channel != null && !channel.isOpen() && isAutomaticRecoveryEnabled()) {
            // Still need to wait for channel to re-open
            throw new IOException("Waiting for channel to re-open.");
        } else if (channel == null || !isAutomaticRecoveryEnabled()) {
            log.info("Attempting to open a new rabbitMQ channel");
            Connection conn = consumer.getConnection();
            channel = openChannel(conn);
            // Register the channel to the tag
            start();
        }
    }

    private boolean isAutomaticRecoveryEnabled() {
        return this.consumer.getEndpoint().getAutomaticRecoveryEnabled() != null
            && this.consumer.getEndpoint().getAutomaticRecoveryEnabled();
    }

    private boolean isChannelOpen() {
        return channel != null && channel.isOpen();
    }

    /**
     * Open channel
     */
    private Channel openChannel(Connection conn) throws IOException {
        log.trace("Creating channel...");
        Channel channel = conn.createChannel();
        log.debug("Created channel: {}", channel);
        // setup the basicQos
        if (consumer.getEndpoint().isPrefetchEnabled()) {
            channel.basicQos(consumer.getEndpoint().getPrefetchSize(), consumer.getEndpoint().getPrefetchCount(),
                    consumer.getEndpoint().isPrefetchGlobal());
        }

        // This really only needs to be called on the first consumer or on
        // reconnections.
        if (consumer.getEndpoint().isDeclare()) {
            consumer.getEndpoint().declareExchangeAndQueue(channel);
        }
        return channel;
    }

}
