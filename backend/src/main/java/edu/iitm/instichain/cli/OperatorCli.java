package edu.iitm.instichain.cli;

import edu.iitm.instichain.InstiChainApplication;
import edu.iitm.instichain.auth.MailService;
import edu.iitm.instichain.common.Ed25519;
import edu.iitm.instichain.config.InstiChainProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Set;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;

public final class OperatorCli {
    private OperatorCli() {}

    public static void main(String[] args) {
        try {
            if (args.length == 2 && "keygen".equals(args[0])) {
                keygen(Path.of(args[1]));
                return;
            }
            if (args.length == 3 && "admin".equals(args[0])) {
                try (ConfigurableApplicationContext context = applicationContext()) {
                    UUIDPrinter.print(context.getBean(AdminBootstrapService.class)
                            .bootstrap(args[1], args[2]));
                }
                return;
            }
            if ((args.length == 1 || args.length == 2) && "smtp".equals(args[0])) {
                try (ConfigurableApplicationContext context = preflightContext()) {
                    System.out.println(context.getBean(IntegrationPreflightService.class)
                            .smtp(args.length == 2 ? args[1] : null));
                }
                return;
            }
            if (args.length == 1 && "drive".equals(args[0])) {
                try (ConfigurableApplicationContext context = preflightContext()) {
                    System.out.println(context.getBean(IntegrationPreflightService.class).drive());
                }
                return;
            }
            usage();
            System.exit(2);
        } catch (Exception error) {
            System.err.println(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            System.exit(1);
        }
    }

    private static void keygen(Path output) throws Exception {
        if (Files.exists(output)) {
            throw new IllegalArgumentException("Refusing to overwrite existing key: " + output);
        }
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path parent = output.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(output, pem, StandardCharsets.US_ASCII);
        try {
            Files.setPosixFilePermissions(output, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // The key reader still validates permissions on POSIX filesystems.
        }
        System.out.println("Signing key created: " + output.toAbsolutePath());
        System.out.println("Public key (base64): "
                + Base64.getEncoder().encodeToString(Ed25519.rawPublic(pair.getPrivate())));
    }

    private static void usage() {
        System.err.println("Usage: OperatorCli keygen <output.pem>");
        System.err.println("   or: OperatorCli admin <institute-email> <verification-reference>");
        System.err.println("   or: OperatorCli smtp [institute-recipient]");
        System.err.println("   or: OperatorCli drive");
    }

    private static ConfigurableApplicationContext applicationContext() {
        return new SpringApplicationBuilder(InstiChainApplication.class)
                .web(WebApplicationType.NONE).run();
    }

    private static ConfigurableApplicationContext preflightContext() {
        return new SpringApplicationBuilder(PreflightConfiguration.class)
                .profiles("operator-preflight")
                .web(WebApplicationType.NONE).run();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InstiChainProperties.class)
    @Profile("operator-preflight")
    static class PreflightConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        MailService mailService(InstiChainProperties properties) {
            return new MailService(properties);
        }

        @Bean
        IntegrationPreflightService integrationPreflightService(
                MailService mail, InstiChainProperties properties, ObjectMapper json) {
            return new IntegrationPreflightService(mail, properties, json);
        }
    }

    private static final class UUIDPrinter {
        private static void print(java.util.UUID id) {
            System.out.println("Administrator ready: " + id);
        }
    }
}
