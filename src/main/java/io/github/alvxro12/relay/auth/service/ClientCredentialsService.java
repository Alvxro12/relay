package io.github.alvxro12.relay.auth.service;

import io.github.alvxro12.relay.auth.InvalidClientException;
import io.github.alvxro12.relay.auth.Merchant;
import io.github.alvxro12.relay.auth.MerchantRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Verifica las credenciales de cliente del flujo client_credentials.
 *
 * Todo el método está escrito para que los tres modos de falla —clientId que no
 * existe, secret incorrecto, merchant DISABLED— sean indistinguibles: mismo tipo
 * de excepción, misma respuesta, y aproximadamente el mismo tiempo.
 */
@Service
public class ClientCredentialsService {

    private final MerchantRepository merchants;
    private final PasswordEncoder passwordEncoder;

    /**
     * Hash señuelo contra el que se compara cuando el clientId no existe. Sin él,
     * un clientId desconocido saltearía el BCrypt y respondería en microsegundos
     * mientras uno existente tarda ~200 ms: la diferencia es medible desde afuera
     * y convierte el endpoint en un oráculo de qué clientIds están registrados.
     *
     * Se genera en el arranque sobre un valor aleatorio: tiene el mismo cost que
     * los hashes reales (lo produce el mismo encoder) y nadie conoce su preimagen.
     */
    private final String decoyHash;

    public ClientCredentialsService(MerchantRepository merchants, PasswordEncoder passwordEncoder) {
        this.merchants = merchants;
        this.passwordEncoder = passwordEncoder;
        this.decoyHash = passwordEncoder.encode(randomDecoySecret());
    }

    /**
     * @return el merchant autenticado y activo
     * @throws InvalidClientException si las credenciales no sirven, por el motivo que sea
     */
    @Transactional(readOnly = true)
    public Merchant authenticate(String clientId, String clientSecret) {
        // Cadena vacía en vez de saltear la consulta: un clientId nulo tiene que
        // pagar el mismo viaje a la DB que uno cualquiera.
        Optional<Merchant> found = merchants.findByClientId(clientId == null ? "" : clientId);

        String hash = found.map(Merchant::getClientSecretHash).orElse(decoyHash);

        // Las tres condiciones se evalúan siempre y recién después se decide, para
        // que ninguna rama se saltee el BCrypt, que es lo que domina el tiempo.
        boolean secretMatches = passwordEncoder.matches(clientSecret == null ? "" : clientSecret, hash);
        boolean exists = found.isPresent();
        boolean active = found.map(Merchant::isActive).orElse(false);

        if (!exists || !secretMatches || !active) {
            throw new InvalidClientException();
        }

        return found.get();
    }

    private static String randomDecoySecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
