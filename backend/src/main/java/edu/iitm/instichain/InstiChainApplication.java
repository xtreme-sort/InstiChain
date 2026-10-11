package edu.iitm.instichain;

import edu.iitm.instichain.config.InstiChainProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(InstiChainProperties.class)
public class InstiChainApplication {
    public static void main(String[] args) {
        SpringApplication.run(InstiChainApplication.class, args);
    }
}
