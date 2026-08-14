package io.github.alvxro12.relay.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String PAYMENTS_EXCHANGE = "relay.payments";
    public static final String CHARGE_QUEUE = "payment.charge.queue";
    public static final String CHARGE_ROUTING_KEY = "payment.charge.requested";

    public static final String PAYMENTS_DLX = "relay.payments.dlx";
    public static final String CHARGE_DLQ = "payment.charge.queue.dlq";

    // maxRetries es la cantidad de reintentos DESPUÉS del intento inicial,
    // por lo tanto 2 = 1 intento inicial + 2 reintentos = 3 intentos totales.
    private static final int LISTENER_MAX_RETRIES_AFTER_FIRST_ATTEMPT = 2;

    @Bean
    public TopicExchange paymentsExchange() {
        return new TopicExchange(PAYMENTS_EXCHANGE);
    }

    @Bean
    public Queue chargeQueue() {
        return QueueBuilder.durable(CHARGE_QUEUE)
                .withArgument("x-dead-letter-exchange", PAYMENTS_DLX)
                .build();
    }

    @Bean
    public Binding chargeBinding(Queue chargeQueue, TopicExchange paymentsExchange) {
        return BindingBuilder.bind(chargeQueue).to(paymentsExchange).with(CHARGE_ROUTING_KEY);
    }

    @Bean
    public FanoutExchange paymentsDlx() {
        return new FanoutExchange(PAYMENTS_DLX);
    }

    @Bean
    public Queue chargeDlq() {
        return new Queue(CHARGE_DLQ, true);
    }

    @Bean
    public Binding chargeDlqBinding(Queue chargeDlq, FanoutExchange paymentsDlx) {
        return BindingBuilder.bind(chargeDlq).to(paymentsDlx);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        // Tras agotar los reintentos en memoria, el recoverer por defecto
        // rechaza sin reencolar; junto con x-dead-letter-exchange en la cola,
        // RabbitMQ lo enruta solo a la DLQ en vez de reintentar para siempre.
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(
                RetryInterceptorBuilder.stateless()
                        .maxRetries(LISTENER_MAX_RETRIES_AFTER_FIRST_ATTEMPT)
                        .recoverer(new RejectAndDontRequeueRecoverer())
                        .build()
        );
        return factory;
    }
}