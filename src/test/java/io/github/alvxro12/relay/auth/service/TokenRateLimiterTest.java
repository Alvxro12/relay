package io.github.alvxro12.relay.auth.service;

import io.github.alvxro12.relay.auth.RateLimitExceededException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unitario y con reloj controlado: el comportamiento que importa acá es cómo se
 * comporta la ventana en el tiempo y cuánto crece el mapa, y las dos cosas son
 * imposibles de probar en serio contra el reloj del sistema.
 */
class TokenRateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-24T12:00:00Z"));
    private final TokenRateLimiter limiter = new TokenRateLimiter(clock);

    @Test
    void allowsFiveAttemptsPerMinuteAndRejectsTheSixth() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            int current = attempt;
            assertThatCode(() -> limiter.checkAttempt("cliente", "203.0.113.1"))
                    .as("intento %d", current)
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.checkAttempt("cliente", "203.0.113.1"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void theSixthAttemptReportsHowLongToWait() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            limiter.checkAttempt("cliente", "203.0.113.1");
        }
        clock.advance(Duration.ofSeconds(20));

        assertThatThrownBy(() -> limiter.checkAttempt("cliente", "203.0.113.1"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> assertThat(((RateLimitExceededException) ex).getRetryAfterSeconds())
                        .isBetween(1L, 40L));
    }

    /** Pasada la ventana se vuelve a empezar de cero, no se queda bloqueado para siempre. */
    @Test
    void startsANewWindowOnceTheOldOneExpires() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            limiter.checkAttempt("cliente", "203.0.113.1");
        }
        assertThatThrownBy(() -> limiter.checkAttempt("cliente", "203.0.113.1"))
                .isInstanceOf(RateLimitExceededException.class);

        clock.advance(WINDOW.plusSeconds(1));

        assertThatCode(() -> limiter.checkAttempt("cliente", "203.0.113.1"))
                .doesNotThrowAnyException();
    }

    /** Las dos dimensiones son independientes: otra IP no hereda el bloqueo de la primera. */
    @Test
    void limitsPerIpAndPerClientIdSeparately() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            limiter.checkAttempt("cliente-a", "203.0.113.1");
        }

        // Misma IP, otro clientId: la IP ya se pasó, así que sigue bloqueado.
        assertThatThrownBy(() -> limiter.checkAttempt("cliente-b", "203.0.113.1"))
                .isInstanceOf(RateLimitExceededException.class);

        // Otra IP, el mismo clientId que ya gastó sus cinco: bloqueado por clientId.
        assertThatThrownBy(() -> limiter.checkAttempt("cliente-a", "198.51.100.7"))
                .isInstanceOf(RateLimitExceededException.class);

        // Otra IP y otro clientId: nada que ver, pasa.
        assertThatCode(() -> limiter.checkAttempt("cliente-c", "198.51.100.8"))
                .doesNotThrowAnyException();
    }

    /**
     * El mapa no crece para siempre. Sin la purga, cada IP y cada clientId vistos
     * alguna vez se quedarían en memoria hasta reiniciar el proceso, y el rate limiter
     * —que existe para frenar un ataque— sería él mismo el vector de agotamiento.
     */
    @Test
    void purgeRemovesExpiredWindows() {
        for (int i = 0; i < 50; i++) {
            limiter.checkAttempt("cliente-" + i, "198.51.100." + i);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(100);   // 50 clientIds + 50 IPs

        // Todavía dentro de la ventana: no se purga nada, o el límite no serviría.
        clock.advance(Duration.ofSeconds(30));
        limiter.purgeExpiredWindows();
        assertThat(limiter.trackedKeys()).isEqualTo(100);

        clock.advance(WINDOW);
        limiter.purgeExpiredWindows();
        assertThat(limiter.trackedKeys()).isZero();
    }

    /** Reloj que solo avanza cuando el test se lo pide. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
