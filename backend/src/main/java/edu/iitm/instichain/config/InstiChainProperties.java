package edu.iitm.instichain.config;

import java.nio.file.Path;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "instichain")
@Validated
public record InstiChainProperties(
        @NotBlank String appName,
        @NotBlank String publicAppUrl,
        @Min(1) @Max(60) int verificationTtlMinutes,
        @Min(1) @Max(365) int sessionTtlDays,
        @Valid Smtp smtp,
        @Valid Fabric fabric,
        Path adminSigningKeyFile,
        String driveTestAccessToken,
        String driveTestFileId) {

    public record Smtp(@NotBlank String host, @Min(1) @Max(65535) int port,
                       @NotBlank String security, String username,
                       String password, @NotBlank String from) {}

    public record Fabric(@NotBlank String channelName, @NotBlank String chaincodeName,
                         @NotBlank String mspId,
                         Path cryptoPath, Path keyDirectory, Path certDirectory,
                         Path tlsCertPath, @NotBlank String peerEndpoint,
                         @NotBlank String peerHostAlias) {}
}
