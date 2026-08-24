package io.github.alvxro12.relay.auth;

import io.github.alvxro12.relay.auth.service.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Base de los tests que pasan por la API autenticada.
 *
 * <p>Lo importante acá es el {@code .apply(springSecurity())}: sin eso, MockMvc arma
 * la cadena del DispatcherServlet pero <b>no</b> la de Spring Security, así que el
 * filtro nunca corre y todos los tests de autenticación pasarían contra un sistema
 * completamente abierto. Es exactamente el tipo de test que da verde sin probar nada.
 */
@SpringBootTest
public abstract class AuthTestSupport {

    /**
     * Una IP distinta por request. El rate limiter es un singleton compartido por todo
     * el contexto de Spring: con la IP fija de MockMvc, el sexto request del sexto test
     * daría 429 y los tests se volverían dependientes del orden en que corren.
     */
    private static final AtomicInteger IP_COUNTER = new AtomicInteger();

    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    @Autowired
    protected WebApplicationContext webApplicationContext;

    @Autowired
    protected MerchantRepository merchantRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected JwtService jwtService;

    @Value("${relay.auth.jwt.secret}")
    private String jwtSecret;

    @Value("${relay.auth.jwt.issuer}")
    protected String issuer;

    @Value("${relay.auth.jwt.audience}")
    protected String audience;

    protected MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    // --- merchants -------------------------------------------------------------

    protected Merchant activeMerchant(String clientSecret) {
        return merchant(MerchantStatus.ACTIVE, clientSecret);
    }

    protected Merchant disabledMerchant(String clientSecret) {
        return merchant(MerchantStatus.DISABLED, clientSecret);
    }

    protected Merchant merchant(MerchantStatus status, String clientSecret) {
        Merchant merchant = new Merchant();
        merchant.setName("test-" + UUID.randomUUID());
        merchant.setClientId("mch_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        merchant.setClientSecretHash(passwordEncoder.encode(clientSecret));
        merchant.setStatus(status);
        return merchantRepository.saveAndFlush(merchant);
    }

    // --- tokens ----------------------------------------------------------------

    /** El token que emitiría el endpoint. */
    protected String validTokenFor(Merchant merchant) {
        return jwtService.issue(merchant.getId()).token();
    }

    /** Bien firmado, con iss y aud correctos, pero vencido hace una hora. */
    protected String expiredTokenFor(Merchant merchant) {
        Instant past = Instant.now().minusSeconds(3600);
        return baseBuilder(merchant.getId(), past, past.plusSeconds(900))
                .signWith(signingKey(), Jwts.SIG.HS256)
                .compact();
    }

    /** Todo correcto salvo la clave: la firma no verifica contra la nuestra. */
    protected String tokenSignedWithAnotherKey(Merchant merchant) {
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        Instant now = Instant.now();
        return baseBuilder(merchant.getId(), now, now.plusSeconds(900))
                .signWith(Keys.hmacShaKeyFor(otherKey), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * JWT sin firmar: header {@code {"alg":"none"}} y firma vacía. Es el ataque clásico
     * contra un parser que le cree al header del token en vez de fijar el algoritmo.
     */
    protected String algNoneTokenFor(Merchant merchant) {
        Instant now = Instant.now();
        return baseBuilder(merchant.getId(), now, now.plusSeconds(900)).compact();
    }

    /**
     * Confusión de algoritmo de verdad: HS512 firmado con <b>nuestra misma clave</b>,
     * todo lo demás correcto.
     *
     * <p>Existe porque {@code alg: none} no alcanza para probar el requisito: jjwt
     * rechaza los JWT no firmados por su cuenta, así que ese test daría verde aunque el
     * parser no exigiera nada. Este token, en cambio, lo acepta cualquier parser que se
     * conforme con "algún HMAC" —{@code verifyWith(key)} a secas lo hace— y solo lo
     * rechaza uno que compare el header contra HS256.
     *
     * <p>Se arma a mano y no con jjwt porque jjwt se niega a firmar HS512 con una clave
     * de 256 bits, que es justamente la situación que se quiere reproducir.
     */
    protected String tokenSignedWithHs512(Merchant merchant) throws Exception {
        Instant now = Instant.now();

        String header = base64Url("{\"alg\":\"HS512\",\"typ\":\"JWT\"}");
        String payload = base64Url("{"
                + "\"sub\":\"" + merchant.getId() + "\","
                + "\"iss\":\"" + issuer + "\","
                + "\"aud\":\"" + audience + "\","
                + "\"iat\":" + now.getEpochSecond() + ","
                + "\"exp\":" + now.plusSeconds(900).getEpochSecond() + ","
                + "\"jti\":\"" + UUID.randomUUID() + "\""
                + "}");

        String signingInput = header + "." + payload;

        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(decodedSecret(), "HmacSHA512"));
        String signature = BASE64_URL.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));

        return signingInput + "." + signature;
    }

    /** Bien firmado y vigente, pero emitido para otro destinatario. */
    protected String tokenForAnotherAudience(Merchant merchant) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(merchant.getId().toString())
                .issuer(issuer)
                .audience().add("otro-servicio").and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .id(UUID.randomUUID().toString())
                .signWith(signingKey(), Jwts.SIG.HS256)
                .compact();
    }

    private io.jsonwebtoken.JwtBuilder baseBuilder(UUID merchantId, Instant issuedAt, Instant expiration) {
        return Jwts.builder()
                .subject(merchantId.toString())
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiration))
                .id(UUID.randomUUID().toString());
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(decodedSecret());
    }

    private byte[] decodedSecret() {
        return Base64.getDecoder().decode(jwtSecret.trim());
    }

    private static String base64Url(String json) {
        return BASE64_URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    // --- request helpers -------------------------------------------------------

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    /** IP distinta por request, para que el rate limiter no acople tests entre sí. */
    protected static RequestPostProcessor uniqueRemoteAddress() {
        int n = IP_COUNTER.incrementAndGet();
        return remoteAddress("10." + ((n >> 16) & 0xFF) + "." + ((n >> 8) & 0xFF) + "." + (n & 0xFF));
    }

    protected static RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    protected static MockHttpServletRequestBuilder fromNewAddress(MockHttpServletRequestBuilder builder) {
        return builder.with(uniqueRemoteAddress());
    }
}
