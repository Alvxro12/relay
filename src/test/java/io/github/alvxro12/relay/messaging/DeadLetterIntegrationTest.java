package io.github.alvxro12.relay.messaging;

import io.github.alvxro12.relay.payment.ChargeRequestedEvent;
import io.github.alvxro12.relay.payment.PaymentEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Qué pasa con un mensaje que no se puede procesar nunca.
 *
 * <p>Son dos preguntas distintas y por eso son dos tests. Una es de enrutamiento —¿el
 * mensaje termina en un lugar visible o se pierde?— y se responde mirando la DLQ real del
 * flujo real. La otra es de presupuesto —¿cuántas veces se intentó antes de rendirse?— y no
 * se puede responder mirando la DLQ, porque el reintento del listener es <b>en memoria</b>:
 * el broker entrega una sola vez y el interceptor invoca al listener varias dentro de esa
 * entrega. Desde afuera las tres invocaciones son invisibles; hay que contarlas donde
 * ocurren.
 */
@SpringBootTest
class DeadLetterIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /**
     * Tres, escrito a mano y no leído de {@code LISTENER_MAX_RETRIES_AFTER_FIRST_ATTEMPT}.
     *
     * <p>Es el punto entero del test. Si acá dijera {@code 1 + LISTENER_MAX_RETRIES...},
     * mover la constante movería también la expectativa y el test seguiría verde diga lo que
     * diga la configuración: sería una tautología con forma de test. Escrito literal, subir
     * la constante a 3 lo pone rojo con "esperaba 3, fueron 4" y bajarla a 1 lo pone rojo con
     * "esperaba 3, fueron 2".
     *
     * <p>Y no se confía en el javadoc de {@code maxRetries} —que dice {@code total attempts =
     * 1 initial attempt + maxRetries}— sino que se cuenta contra el broker de verdad: la
     * distinción entre {@code maxRetries} y {@code maxAttempts} es exactamente el tipo de
     * cosa que cambia de semántica entre versiones sin que nada se rompa a la vista.
     */
    private static final int EXPECTED_TOTAL_ATTEMPTS = 3;

    @Autowired
    private PaymentEventPublisher paymentEventPublisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    @Qualifier("rabbitListenerContainerFactory")
    private SimpleRabbitListenerContainerFactory chargeListenerContainerFactory;

    /**
     * Un evento de cobro cuyo pago no existe. El fallo es permanente por construcción: el
     * {@code claim} lo busca, no lo encuentra y lanza, y como lanza <b>antes</b> de tocar
     * ninguna fila, cada reintento repite exactamente lo mismo. Es el caso que de verdad
     * agota el presupuesto: los fallos que sí cambian estado —el crash después de cobrar,
     * por ejemplo— se ackean en el segundo intento porque el pago ya no está en PENDING.
     *
     * <p>Lo que se afirma es que el mensaje termina en un lugar del que alguien se puede
     * enterar, en vez de reencolarse para siempre o desaparecer.
     */
    @Test
    void aChargeEventThatCanNeverSucceed_endsUpInTheDeadLetterQueue() {
        UUID paymentThatDoesNotExist = UUID.randomUUID();

        paymentEventPublisher.publishChargeRequested(new ChargeRequestedEvent(
                paymentThatDoesNotExist, UUID.randomUUID(), 1000L, "USD", "dlq-test"));

        Message dead = awaitMessageInDlq(RabbitConfig.CHARGE_DLQ, paymentThatDoesNotExist);

        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8))
                .as("el mensaje que llegó a la DLQ es el nuestro, entero")
                .contains(paymentThatDoesNotExist.toString());

        // x-death es lo que hace investigable un mensaje muerto: de qué cola vino y por qué
        // salió. Sin esto, la DLQ es una bolsa de JSON sin procedencia.
        Object xDeath = dead.getMessageProperties().getHeaders().get("x-death");
        assertThat(xDeath).as("RabbitMQ dejó el rastro de por qué murió").isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> death = ((List<Map<String, Object>>) xDeath).getFirst();
        assertThat(death.get("queue")).isEqualTo(RabbitConfig.CHARGE_QUEUE);
        assertThat(death.get("reason"))
                .as("rejected, no expired: lo rechazó el consumer, no venció un TTL")
                .isEqualTo("rejected");
    }

    /**
     * Cuántas veces se invoca al listener antes de rendirse.
     *
     * <p>Se cuenta sobre una cola temporal propia y no sobre {@code payment.charge.queue}, y
     * la razón es que no hay forma de contar las invocaciones del listener real: los
     * reintentos son en memoria, no dejan rastro en el broker ni en la base, y un
     * {@code @MockitoSpyBean} para espiarlo entraría en la clave del cache de contextos —
     * levantaría un segundo {@code ApplicationContext} cuyos {@code @RabbitListener}
     * competirían por estas mismas colas.
     *
     * <p>Lo que sí es real es todo lo demás: la factory es <b>el bean de producción</b>, con
     * su advice chain, su recoverer y su {@code defaultRequeueRejected}, y el mensaje va y
     * vuelve por el broker de verdad. Lo único de mentira es el listener, que existe para
     * fallar y llevar la cuenta. El container y las colas se crean y se destruyen dentro del
     * test, sin definir beans, así que el contexto compartido queda intacto.
     */
    @Test
    void theRetryBudgetIsThreeAttemptsInTotal() {
        String suffix = UUID.randomUUID().toString();
        String queueName = "relay.test.retry-budget." + suffix;
        String dlxName = "relay.test.retry-budget.dlx." + suffix;
        String dlqName = queueName + ".dlq";

        AtomicInteger attempts = new AtomicInteger();
        MessageListenerContainer container = null;

        try {
            FanoutExchange dlx = new FanoutExchange(dlxName, false, true);
            Queue dlq = new Queue(dlqName, false, false, true);
            Queue queue = QueueBuilder.nonDurable(queueName)
                    .autoDelete()
                    .withArgument("x-dead-letter-exchange", dlxName)
                    .build();

            amqpAdmin.declareExchange(dlx);
            amqpAdmin.declareQueue(dlq);
            amqpAdmin.declareQueue(queue);
            amqpAdmin.declareBinding(BindingBuilder.bind(dlq).to(dlx));

            SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
            endpoint.setId("retry-budget-" + suffix);
            endpoint.setQueueNames(queueName);
            endpoint.setMessageListener((MessageListener) message -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("este listener existe para fallar siempre");
            });

            container = chargeListenerContainerFactory.createListenerContainer(endpoint);
            container.start();

            rabbitTemplate.convertAndSend(queueName, "un mensaje que no se va a poder procesar");

            // Se espera a que el mensaje aparezca en la DLQ y recién ahí se cuenta: es la
            // señal de que el interceptor terminó de reintentar. Contar antes mediría una
            // carrera.
            await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                    assertThat(rabbitTemplate.receive(dlqName)).isNotNull());

            assertThat(attempts.get())
                    .as("un intento inicial más dos reintentos, y ni uno más")
                    .isEqualTo(EXPECTED_TOTAL_ATTEMPTS);

        } finally {
            if (container != null) {
                container.stop();
            }
            // Las colas son autoDelete y el exchange también, pero el borrado explícito no
            // depende de que el container se haya desconectado a tiempo.
            amqpAdmin.deleteQueue(queueName);
            amqpAdmin.deleteQueue(dlqName);
            amqpAdmin.deleteExchange(dlxName);
        }
    }

    /**
     * Saca mensajes de la DLQ hasta encontrar el que le importa a este test.
     *
     * <p>La DLQ es compartida y no tiene consumer, así que puede tener mensajes viejos de
     * otras corridas. Se descartan: buscar el propio por contenido es lo que hace que este
     * test no dependa de que la cola esté vacía al empezar.
     */
    private Message awaitMessageInDlq(String dlqName, UUID paymentId) {
        Message[] found = new Message[1];
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Message message = rabbitTemplate.receive(dlqName);
            while (message != null) {
                if (new String(message.getBody(), StandardCharsets.UTF_8).contains(paymentId.toString())) {
                    found[0] = message;
                    return;
                }
                message = rabbitTemplate.receive(dlqName);
            }
            assertThat(found[0]).as("todavía no llegó a la DLQ").isNotNull();
        });
        return found[0];
    }
}
