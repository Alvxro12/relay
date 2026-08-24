package io.github.alvxro12.relay.auth.tool;

import io.github.alvxro12.relay.auth.Merchant;
import io.github.alvxro12.relay.auth.MerchantRepository;
import io.github.alvxro12.relay.auth.MerchantStatus;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Alta de merchants. Es un runner bajo el perfil {@code provision} y no un endpoint
 * de administración: crear un merchant es una operación de operador con acceso al
 * servidor, no algo que deba estar expuesto en la API —un endpoint así sería el
 * blanco más valioso del sistema y necesitaría su propio esquema de autorización—.
 *
 * Uso (ver docs/gate-1-auth.md):
 *   ./mvnw spring-boot:run -Dspring-boot.run.profiles=provision \
 *     -Dspring-boot.run.arguments=--relay.provision.merchant-name=Acme
 *
 * El clientSecret se imprime una sola vez. No se persiste en claro y no hay forma de
 * recuperarlo: si se pierde, se da de alta un merchant nuevo.
 */
@Component
@Profile("provision")
public class MerchantProvisioningRunner implements CommandLineRunner {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final MerchantRepository merchants;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationContext context;

    public MerchantProvisioningRunner(MerchantRepository merchants,
                                      PasswordEncoder passwordEncoder,
                                      ApplicationContext context) {
        this.merchants = merchants;
        this.passwordEncoder = passwordEncoder;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        String name = context.getEnvironment()
                .getProperty("relay.provision.merchant-name", "")
                .trim();

        if (name.isBlank()) {
            System.err.println("Falta --relay.provision.merchant-name=<nombre>");
            SpringApplication.exit(context, () -> 2);
            return;
        }

        String clientId = "mch_" + BASE64_URL.encodeToString(randomBytes(12));
        String clientSecret = BASE64_URL.encodeToString(randomBytes(32));   // 256 bits

        Merchant merchant = new Merchant();
        merchant.setName(name);
        merchant.setClientId(clientId);
        merchant.setClientSecretHash(passwordEncoder.encode(clientSecret));
        merchant.setStatus(MerchantStatus.ACTIVE);
        merchants.save(merchant);

        // System.out y no el logger: esto es la salida de una herramienta de línea de
        // comandos, y el secret no debe terminar en un archivo de log ni en un agregador.
        System.out.println();
        System.out.println("=== Merchant creado ===");
        System.out.println("  merchantId   : " + merchant.getId());
        System.out.println("  name         : " + merchant.getName());
        System.out.println("  clientId     : " + clientId);
        System.out.println("  clientSecret : " + clientSecret);
        System.out.println();
        System.out.println("  Guardá el clientSecret ahora. No se puede recuperar.");
        System.out.println();

        SpringApplication.exit(context, () -> 0);
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
