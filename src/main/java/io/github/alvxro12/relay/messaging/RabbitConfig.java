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

    public static final String WEBHOOK_QUEUE = "webhook.processing.queue";
    public static final String WEBHOOK_ROUTING_KEY = "webhook.received";

    public static final String PAYMENTS_DLX = "relay.payments.dlx";
    public static final String CHARGE_DLQ = "payment.charge.queue.dlq";

    public static final String WEBHOOKS_DLX = "relay.webhooks.dlx";
    public static final String WEBHOOK_DLQ = "webhook.processing.queue.dlq";

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
    public Queue webhookQueue() {
        return QueueBuilder.durable(WEBHOOK_QUEUE)
                .withArgument("x-dead-letter-exchange", WEBHOOKS_DLX)
                .build();
    }

    @Bean
    public Binding webhookBinding(Queue webhookQueue, TopicExchange paymentsExchange) {
        return BindingBuilder.bind(webhookQueue).to(paymentsExchange).with(WEBHOOK_ROUTING_KEY);
    }

    // DLX propio en vez de reusar relay.payments.dlx. La razón no es técnica sino
    // operativa: un dead-letter de charge significa plata que no se cobró y uno de
    // webhook significa estado que no se sincronizó. Son incidentes de naturaleza
    // distinta, y quien opera el sistema necesita poder distinguirlos —revisar,
    // alertar y drenar cada uno por separado— en vez de encontrarlos mezclados.
    // (Además, relay.payments.dlx es fanout: bindear una segunda DLQ ahí le
    // entregaría a cada cola TODOS los mensajes muertos, no solo los suyos.)
    @Bean
    public FanoutExchange webhooksDlx() {
        return new FanoutExchange(WEBHOOKS_DLX);
    }

    @Bean
    public Queue webhookDlq() {
        return new Queue(WEBHOOK_DLQ, true);
    }

    @Bean
    public Binding webhookDlqBinding(Queue webhookDlq, FanoutExchange webhooksDlx) {
        return BindingBuilder.bind(webhookDlq).to(webhooksDlx);
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

    @Bean
    public SimpleRabbitListenerContainerFactory webhookListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter,
            WebhookProcessingRecoverer webhookProcessingRecoverer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(
                RetryInterceptorBuilder.stateless()
                        .maxRetries(LISTENER_MAX_RETRIES_AFTER_FIRST_ATTEMPT)
                        // Con backoff, a diferencia de charge. Acá el fallo esperable es
                        // que el webhook del proveedor llegue antes de que el consumer de
                        // charge haya commiteado el providerTransactionId; reintentar al
                        // instante quemaría los tres intentos dentro de esa misma ventana
                        // y mandaría a la DLQ un evento que era perfectamente procesable.
                        .backOffOptions(500, 2.0, 4000)
                        // Recoverer propio: además de rechazar sin reencolar, deja el
                        // WebhookEvent en FAILED antes de que el mensaje caiga en la DLQ.
                        .recoverer(webhookProcessingRecoverer)
                        .build()
        );
        return factory;
    }
}