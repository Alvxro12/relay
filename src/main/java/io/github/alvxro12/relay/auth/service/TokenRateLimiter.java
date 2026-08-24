package io.github.alvxro12.relay.auth.service;

import io.github.alvxro12.relay.auth.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Límite de intentos del endpoint de token: 5 por minuto por IP y 5 por minuto por
 * clientId, en ventana fija.
 *
 * <p>Hace falta porque verificar un secret cuesta un BCrypt cost 12 (~250 ms de CPU
 * nuestra por intento): sin límite, unas pocas conexiones haciendo fuerza bruta
 * saturan el servicio aunque nunca acierten un secret.
 *
 * <p><b>Es por instancia, no distribuido.</b> Con N instancias detrás de un balanceador
 * el límite efectivo es 5·N por minuto. Alcanza para lo que este límite tiene que
 * frenar —fuerza bruta y agotamiento de CPU— y evita meter Redis en el camino crítico
 * de la autenticación; un límite estricto necesitaría almacenamiento compartido.
 */
@Component
public class TokenRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenRateLimiter.class);

    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();

    /**
     * Techo de claves seguidas a la vez. El mapa no crece sin control por dos razones
     * que se refuerzan: la purga programada saca todas las ventanas vencidas una vez
     * por minuto, y el chequeo de IP corre primero y corta, así que una IP sola puede
     * insertar como mucho su propia clave y cinco de clientId antes de quedar frenada.
     * Llenar el mapa exige tantas IPs distintas como claves. Este techo es la última
     * red por si aun así se llega: fuerza una purga inmediata.
     */
    private static final int MAX_TRACKED_KEYS = 100_000;

    /** Prefijos para que un clientId no pueda colisionar con una IP. */
    private static final String IP_PREFIX = "ip:";
    private static final String CLIENT_PREFIX = "cid:";

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private final Clock clock;

    public TokenRateLimiter() {
        this(Clock.systemUTC());
    }

    /**
     * Constructor de los tests. Sin un reloj inyectable, probar que la ventana vence y
     * que la purga limpia habria que hacerlo esperando un minuto real, y un test que
     * duerme un minuto es un test que nadie corre.
     */
    TokenRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Cuenta un intento contra las dos dimensiones y falla si alguna se pasó.
     *
     * <p>La IP se mira primero y corta antes de tocar la clave del clientId: además de
     * ser lo que acota el crecimiento del mapa, evita que quien inunda con clientIds
     * inventados consuma memoria por cada uno.
     *
     * <p>Los intentos exitosos también cuentan. Un cliente legítimo necesita un token
     * cada 15 minutos, así que 5 por minuto le sobra, y contar solo los fallidos
     * dejaría abierta la emisión masiva de tokens con credenciales robadas.
     */
    public void checkAttempt(String clientId, String remoteAddress) {
        if (windows.size() >= MAX_TRACKED_KEYS) {
            purgeExpiredWindows();
        }

        long now = clock.millis();

        Window ipWindow = register(IP_PREFIX + nullSafe(remoteAddress), now);
        if (ipWindow.count() > MAX_ATTEMPTS) {
            reject("ip", remoteAddress, ipWindow, now);
        }

        Window clientWindow = register(CLIENT_PREFIX + nullSafe(clientId), now);
        if (clientWindow.count() > MAX_ATTEMPTS) {
            reject("clientId", clientId, clientWindow, now);
        }
    }

    /**
     * compute() y no get()/put(): la función corre bajo el lock del bin, así que leer
     * la ventana, decidir si venció y escribir el conteo nuevo es atómico. Con
     * get()/put() dos hilos que llegan juntos leen el mismo valor y uno pisa al otro,
     * y el límite se afloja justo cuando hay concurrencia, que es cuando importa.
     */
    private Window register(String key, long now) {
        return windows.compute(key, (k, previous) ->
                previous == null || now - previous.startMillis() >= WINDOW_MILLIS
                        ? new Window(now, 1)
                        : new Window(previous.startMillis(), previous.count() + 1));
    }

    private void reject(String dimension, String value, Window window, long now) {
        long retryAfter = Math.max(1, (window.startMillis() + WINDOW_MILLIS - now + 999) / 1000);
        // clientId e IP no son secretos y son lo único con lo que se investiga un
        // ataque de fuerza bruta. El secret nunca llega hasta acá.
        log.warn("Rate limit del endpoint de token superado por {}={} ({} intentos en la ventana)",
                dimension, value, window.count());
        throw new RateLimitExceededException(retryAfter);
    }

    /**
     * Saca las ventanas vencidas. Una ventana vencida no aporta nada: el próximo
     * intento con esa clave la reemplaza igual.
     */
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    void purgeExpiredWindows() {
        long cutoff = clock.millis() - WINDOW_MILLIS;
        int before = windows.size();
        windows.entrySet().removeIf(entry -> entry.getValue().startMillis() <= cutoff);
        if (log.isDebugEnabled() && before != windows.size()) {
            log.debug("Rate limiter: {} ventanas vencidas purgadas, quedan {}", before - windows.size(), windows.size());
        }
    }

    /** Visible para los tests: cuántas claves se están siguiendo ahora mismo. */
    int trackedKeys() {
        return windows.size();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** Inmutable a propósito: es lo que permite que compute() la reemplace entera. */
    private record Window(long startMillis, int count) {
    }
}
