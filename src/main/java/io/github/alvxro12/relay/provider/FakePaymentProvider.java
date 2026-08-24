package io.github.alvxro12.relay.provider;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Proveedor simulado. Es el que corre en modo sandbox.
 *
 * <p>Lo que lo hace útil no es que devuelva resultados variados, sino que <b>lleva registro
 * de lo que realmente pasó</b>. Un simulador que solo devuelve un enum no puede representar
 * el único caso que importa: el cobro salió bien y la respuesta se perdió. Acá ese caso es
 * un registro escrito más una excepción lanzada, y {@link #getPaymentStatus} lo cuenta.
 *
 * <p>De ahí que TIMEOUT y LOST_RESPONSE sean <b>indistinguibles desde Relay</b> —misma
 * excepción, mismo estado final— y distintos del lado del proveedor. Esa asimetría es todo
 * el problema que la reconciliación tiene que resolver; sin ella no hay nada que descubrir.
 */
@Component
public class FakePaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(FakePaymentProvider.class);

    /**
     * Techo de cobros recordados. El mapa vive lo que vive el proceso y en un sandbox de
     * larga vida crecería sin parar, así que descarta los más viejos por orden de inserción.
     * Un cobro tan viejo ya fue reconciliado o abandonado hace rato.
     */
    private static final int MAX_TRACKED_CHARGES = 10_000;

    private final SandboxProperties sandbox;

    /** Lo que "pasó" del lado del proveedor, por paymentId. */
    private final Map<UUID, ChargeRecord> charges = Collections.synchronizedMap(
            new LinkedHashMap<>(512, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, ChargeRecord> eldest) {
                    return size() > MAX_TRACKED_CHARGES;
                }
            });

    /**
     * Seam de test: se ejecuta dentro del cobro, con el PROCESSING ya commiteado y ninguna
     * transacción abierta. Sirve para frenar el cobro en vuelo y mirar desde afuera qué ve
     * el resto del sistema mientras tanto. Es de un solo uso.
     *
     * <p>Se queda como setter y no se expresa como escenario a propósito: no es un
     * comportamiento del proveedor sino una pausa en el tiempo, y eso no se puede pedir
     * desde el cuerpo de un request.
     */
    private volatile Runnable duringNextCharge;

    private final AtomicInteger chargeCount = new AtomicInteger();

    public FakePaymentProvider(SandboxProperties sandbox) {
        this.sandbox = sandbox;
    }

    @PostConstruct
    void warnIfSandboxEnabled() {
        if (sandbox.enabled()) {
            log.warn("MODO SANDBOX ACTIVO: una referencia que empiece con {} fuerza el resultado "
                    + "del cobro. Esto no puede estar encendido en producción.", SandboxScenario.PREFIX);
        }
    }

    /**
     * Id de transacción que le corresponde a un pago. Determinístico a propósito: un
     * proveedor real devuelve siempre el mismo id para el mismo cobro, y además deja que un
     * test lo calcule en vez de tener que forzarlo con un setter antes de cobrar.
     */
    public static String transactionIdFor(UUID paymentId) {
        return "fake_txn_" + paymentId;
    }

    public void onNextCharge(Runnable hook) {
        this.duringNextCharge = hook;
    }

    /**
     * Cuenta cuántas veces se llamó al proveedor. Es lo que distingue "el pago quedó en el
     * estado correcto" de "se cobró una sola vez": el estado final se ve igual si el cobro
     * salió una vez o tres.
     */
    public int chargeCount() {
        return chargeCount.get();
    }

    public void resetChargeCount() {
        chargeCount.set(0);
    }

    @Override
    public ChargeResult charge(ChargeRequest request) {
        chargeCount.incrementAndGet();

        Runnable hook = this.duringNextCharge;
        this.duringNextCharge = null;
        if (hook != null) {
            hook.run();
        }

        SandboxScenario scenario = sandbox.enabled()
                ? SandboxScenario.from(request.externalReference())
                : SandboxScenario.SUCCESS;

        UUID paymentId = request.paymentId();
        String transactionId = transactionIdFor(paymentId);

        return switch (scenario) {
            case SUCCESS -> {
                record(paymentId, ProviderPaymentStatus.SUCCEEDED, transactionId);
                yield new ChargeResult(ChargeStatus.SUCCESS, transactionId);
            }

            case FAILED -> {
                // Rechazado también es un cobro que ocurrió: el proveedor lo tiene
                // registrado y lo informa igual si se le pregunta.
                record(paymentId, ProviderPaymentStatus.FAILED, transactionId);
                yield new ChargeResult(ChargeStatus.DECLINED, transactionId);
            }

            case AWAITING_CONFIRMATION -> {
                record(paymentId, ProviderPaymentStatus.PENDING, transactionId);
                yield new ChargeResult(ChargeStatus.ACCEPTED, transactionId);
            }

            // No se registra nada: la llamada nunca llegó. Es lo que después hace que la
            // consulta devuelva NOT_FOUND, la única respuesta que autoriza a dar el pago
            // por fallido.
            case TIMEOUT -> throw new PaymentProviderTimeoutException("Payment provider timed out");

            case LOST_RESPONSE -> {
                // El orden es el punto: primero se registra el cobro, después se corta la
                // comunicación. Desde Relay esto es idéntico a un TIMEOUT; del lado del
                // proveedor hay plata movida.
                record(paymentId, ProviderPaymentStatus.SUCCEEDED, transactionId);
                throw new PaymentProviderTimeoutException(
                        "Payment provider timed out after the charge was applied");
            }

            case UNKNOWN -> {
                // Sin transactionId: el proveedor no llegó a asignarle uno, y es parte de
                // por qué no sabe. Un pago así no tiene con qué correlacionarse después.
                record(paymentId, ProviderPaymentStatus.UNKNOWN, null);
                yield new ChargeResult(ChargeStatus.SERVER_ERROR, null);
            }
        };
    }

    @Override
    public ProviderStatusResult getPaymentStatus(UUID paymentId) {
        ChargeRecord charge = paymentId == null ? null : charges.get(paymentId);

        if (charge == null) {
            return new ProviderStatusResult(ProviderPaymentStatus.NOT_FOUND, null);
        }
        return new ProviderStatusResult(charge.status(), charge.providerTransactionId());
    }

    private void record(UUID paymentId, ProviderPaymentStatus status, String transactionId) {
        if (paymentId == null) {
            // Un cobro sin clave no se puede consultar después. No debería pasar: el
            // paymentId sale del evento y siempre está.
            log.warn("Cobro sin paymentId: no va a poder consultarse su estado");
            return;
        }
        charges.put(paymentId, new ChargeRecord(status, transactionId));
    }

    /** Lo que el proveedor recuerda de un cobro. */
    private record ChargeRecord(ProviderPaymentStatus status, String providerTransactionId) {
    }
}
