package edu.iitm.instichain.auth;

import edu.iitm.instichain.config.InstiChainProperties;
import java.util.Properties;
import jakarta.mail.MessagingException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

@Service
public class MailService {
    private final InstiChainProperties properties;

    public MailService(InstiChainProperties properties) {
        this.properties = properties;
    }

    public void sendVerification(String email, String token, boolean login) {
        InstiChainProperties.Smtp smtp = properties.smtp();
        JavaMailSenderImpl sender = sender();
        String url = properties.publicAppUrl().replaceAll("/+$", "")
                + "/verify-email#token=" + token;
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(smtp.from());
        message.setTo(email);
        message.setSubject(login ? "Sign in to InstiChain" : "Verify your InstiChain email");
        message.setText((login ? "Sign in to InstiChain:" : "Confirm your institute email address:")
                + "\n\n" + url + "\n\nThis link expires in "
                + properties.verificationTtlMinutes()
                + " minutes and works once.\nIf you did not request this email, you can ignore it.\n");
        sender.send(message);
    }

    public void checkConnection() throws MessagingException {
        sender().testConnection();
    }

    public void sendDeliveryCheck(String email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.smtp().from());
        message.setTo(email);
        message.setSubject("InstiChain delivery check");
        message.setText("InstiChain SMTP delivery check. No account or permission changes were made.");
        sender().send(message);
    }

    private JavaMailSenderImpl sender() {
        InstiChainProperties.Smtp smtp = properties.smtp();
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        sender.setUsername(blankToNull(smtp.username()));
        sender.setPassword(blankToNull(smtp.password()));
        Properties javaMail = sender.getJavaMailProperties();
        javaMail.put("mail.smtp.connectiontimeout", "10000");
        javaMail.put("mail.smtp.timeout", "10000");
        javaMail.put("mail.smtp.writetimeout", "10000");
        if ("starttls".equals(smtp.security())) {
            javaMail.put("mail.smtp.starttls.enable", "true");
            javaMail.put("mail.smtp.starttls.required", "true");
        } else if ("ssl".equals(smtp.security())) {
            sender.setProtocol("smtps");
            javaMail.put("mail.smtps.ssl.enable", "true");
        } else if (!"plain".equals(smtp.security())) {
            throw new IllegalStateException("SMTP security must be plain, starttls, or ssl");
        }
        return sender;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
