package io.github.alvxro12.relay.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    /**
     * Búsqueda de la emisión del token. Devuelve el merchant sin filtrar por estado
     * a propósito: si filtrara, un merchant DISABLED sería indistinguible de uno
     * inexistente para el que llama, pero también nos impediría comparar el secret
     * igual en los dos casos, que es lo que mantiene constante el tiempo de respuesta.
     */
    Optional<Merchant> findByClientId(String clientId);
}
