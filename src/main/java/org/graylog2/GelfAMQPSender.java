package org.graylog2;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.UUID;

public class GelfAMQPSender implements GelfSender {
    
    private volatile boolean shutdown = false;

    private final ConnectionFactory factory;
    private Connection connection;
    private Channel channel;

    private final String exchangeName;
    private final String queueName;
    private final String routingKey;
    private final int maxRetries;
    private final String channelMutex = "channelMutex";

    public GelfAMQPSender(String host, String exchangeName, String routingKey, int maxRetries) throws IOException, URISyntaxException, NoSuchAlgorithmException, KeyManagementException {
        factory = new ConnectionFactory();
        factory.setUri(host);

        this.exchangeName = exchangeName;
        this.queueName = exchangeName;
        this.routingKey = routingKey;
        this.maxRetries = maxRetries;
    }

    public GelfAMQPSender(String host, String exchangeName, String queueName, String routingKey, int maxRetries) throws IOException, URISyntaxException, NoSuchAlgorithmException, KeyManagementException {
        factory = new ConnectionFactory();
        factory.setUri(host);

        this.exchangeName = exchangeName;
        this.queueName = queueName;
        this.routingKey = routingKey;
        this.maxRetries = maxRetries;
    }

    public GelfSenderResult sendMessage(GelfMessage message) {
        if (shutdown || !message.isValid()) {
            return GelfSenderResult.MESSAGE_NOT_VALID_OR_SHUTTING_DOWN;
        }

        // set unique id to identify duplicates after connection failure
        String uuid = UUID.randomUUID().toString();
        String messageid = "gelf" + message.getHost() + message.getFacility() + message.getTimestamp() + uuid;

        int tries = 0;
        Exception lastException = null;
        do {
            try {
                // establish the connection the first time
                if (channel == null) {
                    synchronized(channelMutex) {
                        if (channel == null) {
                            connection = factory.newConnection();
                            channel = connection.createChannel();
                            channel.confirmSelect();
                            channel.queueBind( queueName, exchangeName, routingKey );
                        }
                    }
                }

                BasicProperties.Builder propertiesBuilder = new BasicProperties.Builder();
                propertiesBuilder.contentType("application/json; charset=utf-8");
                propertiesBuilder.contentEncoding("gzip");
                propertiesBuilder.messageId(messageid);
                propertiesBuilder.timestamp(new Date(message.getJavaTimestamp()));
                BasicProperties properties = propertiesBuilder.build();

                channel.basicPublish( "", queueName, properties, message.toAMQPBuffer().array());
                channel.waitForConfirms();

                return GelfSenderResult.OK;
            } catch (Exception e) {
                channel = null;
                tries++;
                lastException = e;
            }
        } while (tries <= maxRetries || maxRetries < 0);

        return new GelfSenderResult(GelfSenderResult.ERROR_CODE, lastException);
    }

    public void close() {
        shutdown = true;
        try {
            channel.queueUnbind( queueName, exchangeName, routingKey );
        } catch (Exception e) {
        }
        try {
            channel.close();
        } catch (Exception e) {
        }
        try {
            connection.close();
        } catch (Exception e) {
        }
    }
}
