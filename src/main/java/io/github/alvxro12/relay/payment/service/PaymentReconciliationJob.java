package io.github.alvxro12.relay.payment.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * El temporizador de la reconciliación, y nada más. Toda la lógica está en
 * {@link StalePaymentResolver}.
 *
 * <p>Están separados porque son dos cosas con dueños distintos. La resolución es una
 * operación que se invoca —un test la corre cuando quiere, un operador podría dispararla a
 * mano—, mientras que esto de acá es una decisión de despliegue: cada cuánto, y si corre.
 *
 * <p><b>Y porque en los tests tiene que poder no existir.</b> Desde que la reconciliación
 * escribe estado, un job andando de fondo mutaría las filas que un test de integración está
 * mirando, y el fallo aparecería como un test intermitente que culpa a otra cosa. Alargar el
 * intervalo no alcanza: si la suite tarda más que el intervalo, el job dispara igual. Por eso
 * el bean entero se apaga con {@code relay.reconciliation.scheduler.enabled=false}, que es lo
 * que hace surefire (ver pom.xml), y los tests llaman al resolver directo.
 *
 * <p>La condición es {@code matchIfMissing = true}: el default es que corra. Un entorno que
 * se olvide de la propiedad tiene que quedar con la reconciliación encendida, no apagada —al
 * revés que el sandbox, donde el default seguro es el que no hace nada.
 */
@Component
@ConditionalOnProperty(
        name = "relay.reconciliation.scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PaymentReconciliationJob {

    private final StalePaymentResolver resolver;

    public PaymentReconciliationJob(StalePaymentResolver resolver) {
        this.resolver = resolver;
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void reconcile() {
        resolver.reconcileStalePayments();
    }
}
