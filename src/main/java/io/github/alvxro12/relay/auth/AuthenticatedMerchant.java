package io.github.alvxro12.relay.auth;

import java.util.UUID;

/**
 * El merchant detrás del request en curso. Es el principal que queda en el
 * SecurityContext y la <b>única</b> fuente del merchantId: ningún endpoint lo
 * recibe por header, path ni body.
 *
 * Se arma en el filtro a partir del claim {@code sub} del token y de la fila de la
 * DB, no del token solo: el token dice quién dice ser, la DB dice si todavía puede.
 */
public record AuthenticatedMerchant(UUID merchantId, String clientId) {

    public static AuthenticatedMerchant of(Merchant merchant) {
        return new AuthenticatedMerchant(merchant.getId(), merchant.getClientId());
    }
}
