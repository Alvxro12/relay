package io.github.alvxro12.relay.auth.config;

import io.github.alvxro12.relay.auth.MerchantRepository;
import io.github.alvxro12.relay.auth.service.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * La cadena de filtros. Todo lo que no esté explícitamente abierto abajo exige un
 * token válido.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   MerchantRepository merchants) throws Exception {

        AuthenticationEntryPoint entryPoint = new JwtAuthenticationEntryPoint();

        return http
                // Sin CSRF: el token viaja en un header que un formulario de otro origen
                // no puede setear, y no hay cookie de sesión que el navegador adjunte
                // solo. El token CSRF protege sesiones basadas en cookie, que acá no hay.
                .csrf(csrf -> csrf.disable())

                // STATELESS: ni se lee ni se crea HttpSession, y el SecurityContext no
                // sobrevive al request. Es lo que hace que el token sea la única
                // credencial posible: con sesión, el primer request autenticado dejaría
                // una cookie que valdría después de que el token expire, y el TTL de 15
                // minutos —que es el único mecanismo de revocación que hay— dejaría de
                // significar nada.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Nada de esto aplica a una API m2m, y dejarlo activo agrega superficie:
                // formLogin monta /login, httpBasic acepta credenciales por header.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .anonymous(anonymous -> anonymous.disable())

                .exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint))

                .authorizeHttpRequests(auth -> auth
                        // Público por definición: es donde se consigue el token.
                        .requestMatchers(HttpMethod.POST, "/v1/oauth/token").permitAll()

                        // Público para esta cadena, no sin autenticar: el webhook lo
                        // llama el proveedor, que no tiene credenciales de merchant, y se
                        // autentica con el HMAC del cuerpo (WebhookSignatureVerifier).
                        // Exigirle un JWT sería pedirle una credencial que no puede tener.
                        .requestMatchers(HttpMethod.POST, "/webhooks/provider").permitAll()

                        // Default deny. Va último y es anyRequest() y no una lista de
                        // rutas: un endpoint nuevo queda cerrado por omisión, que es el
                        // lado correcto del error. Con una allowlist, olvidarse de
                        // agregarlo lo dejaría abierto.
                        .anyRequest().authenticated())

                // Antes de UsernamePasswordAuthenticationFilter, o sea antes del
                // AuthorizationFilter, que es el que decide si el request pasa. Si
                // estuviera después, el AuthorizationFilter evaluaría anyRequest()
                // .authenticated() sobre un contexto todavía vacío y devolvería 401
                // siempre: el filtro autenticaría a destiempo y nunca serviría de nada.
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtService, merchants, entryPoint),
                        UsernamePasswordAuthenticationFilter.class)

                .build();
    }

    /**
     * Cost 12: ~250 ms por verificación en hardware de hoy. Es deliberadamente lento
     * —hace caro el ataque por diccionario contra un hash filtrado— y es la razón por
     * la que el endpoint de token necesita rate limit: cada intento cuesta CPU nuestra.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
