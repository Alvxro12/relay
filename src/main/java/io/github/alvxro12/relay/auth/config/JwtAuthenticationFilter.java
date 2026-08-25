package io.github.alvxro12.relay.auth.config;

import io.github.alvxro12.relay.auth.AuthenticatedMerchant;
import io.github.alvxro12.relay.auth.InvalidTokenException;
import io.github.alvxro12.relay.auth.Merchant;
import io.github.alvxro12.relay.auth.MerchantRepository;
import io.github.alvxro12.relay.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Traduce el header Authorization: Bearer &lt;jwt&gt; en un principal en el
 * SecurityContext. Es el único punto del sistema donde nace un merchantId.
 *
 * <p>OncePerRequestFilter y no Filter a secas: un forward interno (por ejemplo hacia
 * /error) vuelve a pasar por la cadena, y revalidar el token en cada pasada sería
 * trabajo repetido y un segundo lugar donde el resultado podría diferir.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final MerchantRepository merchants;
    private final AuthenticationEntryPoint entryPoint;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   MerchantRepository merchants,
                                   AuthenticationEntryPoint entryPoint) {
        this.jwtService = jwtService;
        this.merchants = merchants;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(AUTHORIZATION_HEADER);

        // Sin credencial no se rechaza acá: se sigue sin autenticar y decide el
        // AuthorizationFilter según la regla de la ruta. Cortar acá rompería
        // /v1/oauth/token y el webhook, que son públicos a propósito.
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            UUID merchantId = jwtService.parseMerchantId(header.substring(BEARER_PREFIX.length()).trim());

            // El token es la afirmación; la fila es el hecho. Se relee en cada request
            // porque el token vive hasta 15 minutos y el merchant puede haber quedado
            // DISABLED en el medio: sin este chequeo, deshabilitarlo no surtiría efecto
            // hasta que expire el último token emitido.
            Merchant merchant = merchants.findById(merchantId)
                    .filter(Merchant::isActive)
                    .orElseThrow(() -> new InvalidTokenException("merchant inexistente o no ACTIVE"));

            authenticate(request, merchant);

        } catch (InvalidTokenException e) {
            // Se limpia antes de responder: si algo dejó un principal en el hilo, no
            // debe sobrevivir a un token rechazado.
            SecurityContextHolder.clearContext();
            // DEBUG y sin el token: el motivo sirve para diagnosticar, el token es una
            // credencial viva y no va a ningún log.
            log.debug("Token rechazado ({} {}): {}", request.getMethod(), request.getRequestURI(), e.getMessage());
            entryPoint.commence(request, response, new BadCredentialsException("invalid_token"));
            return;   // la cadena se corta acá: la respuesta ya está escrita
        }

        // El SecurityContext no se limpia acá: de eso se encarga el
        // SecurityContextHolderFilter, que envuelve toda la cadena en un try/finally.
        // Duplicarlo taparía el bug si algún día ese filtro dejara de estar.
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, Merchant merchant) {
        AuthenticatedMerchant principal = AuthenticatedMerchant.of(merchant);

        // Sin authorities: no hay roles ni scopes en este diseño. La única decisión de
        // autorización es "está autenticado o no"; el resto del aislamiento lo hace el
        // filtro por merchantId en cada consulta, no una regla declarativa.
        // Credenciales en null a propósito: el token ya se validó y no hay razón para
        // dejarlo colgando del contexto durante todo el request.
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        // createEmptyContext + setContext y no getContext().setAuthentication(...):
        // el segundo muta el contexto que la estrategia ya tenía asociado al hilo, y
        // esta es la forma que recomienda Spring Security para evitar carreras.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
