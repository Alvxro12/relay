package io.github.alvxro12.relay.auth.controller;

import io.github.alvxro12.relay.auth.Merchant;
import io.github.alvxro12.relay.auth.dto.TokenRequest;
import io.github.alvxro12.relay.auth.dto.TokenResponse;
import io.github.alvxro12.relay.auth.service.ClientCredentialsService;
import io.github.alvxro12.relay.auth.service.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Emisión de tokens (client_credentials). Es el único endpoint público de la API:
 * todo lo demás exige el token que sale de acá.
 *
 * No hay login, ni refresh, ni sesión. Cuando el token expira, el cliente vuelve a
 * llamar acá con las mismas credenciales.
 */
@RestController
@RequestMapping("/v1/oauth")
public class TokenController {

    private static final Logger log = LoggerFactory.getLogger(TokenController.class);

    private final ClientCredentialsService clientCredentials;
    private final JwtService jwtService;

    public TokenController(ClientCredentialsService clientCredentials, JwtService jwtService) {
        this.clientCredentials = clientCredentials;
        this.jwtService = jwtService;
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenResponse> token(@RequestBody TokenRequest request) {
        Merchant merchant = clientCredentials.authenticate(request.clientId(), request.clientSecret());
        JwtService.IssuedToken issued = jwtService.issue(merchant.getId());

        // clientId no es secreto y es lo que hace auditable quién pidió el token.
        // El secret y el token no se loguean nunca, ni siquiera truncados.
        log.info("Token emitido para clientId={} merchantId={}", merchant.getClientId(), merchant.getId());

        return ResponseEntity.ok()
                // Un token en un caché intermedio es un token robado. RFC 6749 §5.1.
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(new TokenResponse(issued.token(), "Bearer", issued.expiresInSeconds()));
    }
}
