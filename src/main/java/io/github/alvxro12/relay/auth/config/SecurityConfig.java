package io.github.alvxro12.relay.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cadena de filtros. En esta fase todavía deja pasar todo: acá solo se emite el
 * token, y quien lo valida —el filtro que lee el header Authorization— llega en la
 * fase C, que reemplaza el {@code permitAll()} de abajo por el cierre real.
 *
 * La cadena existe igual desde ahora porque incorporar spring-boot-starter-security
 * sin declarar una SecurityFilterChain propia activaría la de Spring Boot, que pone
 * HTTP Basic sobre todo y dejaría la API existente respondiendo 401.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Sin CSRF: no hay cookies ni sesión, la credencial viaja en un header
                // que un formulario de otro origen no puede setear. El token CSRF
                // protege sesiones basadas en cookie, que es lo que acá no existe.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /**
     * Cost 12: ~250 ms por verificación en hardware de hoy. Es deliberadamente lento
     * —hace caro el ataque por diccionario contra un hash filtrado— y es la razón por
     * la que el endpoint de token tiene rate limit: cada intento cuesta CPU nuestra.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
