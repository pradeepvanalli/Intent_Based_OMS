package ai.intentoms.monitoring.alert;

/**
 * SPI for the e-mail alert channel (FR-MON-06). The default {@link LoggingEmailSender} only logs; deployments provide a
 * {@code @Bean} backed by their mail provider, which replaces the default ({@code @ConditionalOnMissingBean}).
 */
public interface EmailSender {
    void send(String to, String subject, String body);
}
