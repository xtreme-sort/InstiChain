package edu.iitm.instichain;

import edu.iitm.instichain.config.InstiChainProperties;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(InstiChainProperties.class)
public class InstiChainApplication {
    public static void main(String[] args) {
        useUtc();
        SpringApplication.run(InstiChainApplication.class, args);
    }

    /**
     * The PostgreSQL driver sends the JVM's zone when it connects, and newer JDKs report
     * legacy aliases such as Asia/Calcutta that PostgreSQL rejects. Every stored time is
     * TIMESTAMPTZ and every Java time carries an offset, so UTC changes no behaviour.
     */
    public static void useUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
}
