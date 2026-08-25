package io.github.alvxro12.relay.shared.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Borra credenciales de una línea de log ya formateada.
 *
 * <p>El código de Relay no loguea ni el header Authorization ni el clientSecret, y esa
 * es la barrera principal. Esto es la de atrás: cubre lo que loguee una librería de
 * tercero y el {@code log.debug} distraído que alguien escriba en seis meses.
 *
 * <p>Se aplica sobre el mensaje formateado y no dentro del patrón de logback: la
 * versión en XML necesitaba {@code %replace} anidados, que el tokenizer de logback no
 * parsea —falla al arrancar—, y un regex escrito adentro de un atributo XML no hay
 * forma de testearlo por separado.
 */
public final class CredentialMasker {

    private static final String MASK = "***";

    /** Fin de un valor: comilla, coma, punto y coma, llave, corchete o espacio. */
    private static final String VALUE = "[^\\s\"',;}\\]]+";

    /**
     * {@code Authorization: <esquema> <credencial>} en cualquiera de sus formas: header
     * HTTP crudo, clave de un mapa, campo de un JSON. El esquema es opcional porque hay
     * quien loguea el valor pelado.
     */
    private static final Pattern AUTHORIZATION_HEADER =
            Pattern.compile("(?i)(authorization\"?\\s*[:=]\\s*\"?)(\\w+\\s+)?" + VALUE);

    /** El token suelto, sin el nombre del header al lado. */
    private static final Pattern BEARER_TOKEN =
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9\\-._~+/=]+");

    /** {@code clientSecret=...}, {@code "client_secret": "..."} y variantes. */
    private static final Pattern CLIENT_SECRET =
            Pattern.compile("(?i)(client[_-]?secret\"?\\s*[:=]\\s*\"?)" + VALUE);

    private CredentialMasker() {
    }

    public static String mask(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        String masked = replace(AUTHORIZATION_HEADER, message, "$1$2" + MASK);
        masked = replace(BEARER_TOKEN, masked, "$1" + MASK);
        masked = replace(CLIENT_SECRET, masked, "$1" + MASK);
        return masked;
    }

    /**
     * find() antes de reemplazar: replaceAll() recorre la cadena entera y construye una
     * nueva siempre, y el 99,9% de las líneas de log no tiene nada que enmascarar. Esto
     * corre en el camino de todos los logs de la aplicación.
     */
    private static String replace(Pattern pattern, String input, String replacement) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.reset().replaceAll(replacement) : input;
    }
}
