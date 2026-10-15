package ai.intentoms.monitoring.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default e-mail channel: writes the message to the log (the alert row is the durable record). */
public class LoggingEmailSender implements EmailSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("ALERT EMAIL to={} subject=\"{}\" body=\"{}\"", to, subject, body);
    }
}
