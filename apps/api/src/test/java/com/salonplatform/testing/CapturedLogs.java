package com.salonplatform.testing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/**
 * The log lines one logger writes while a test runs, each with the logging context (MDC) and the
 * key-value pairs it was written with: what the API's JSON lines are made of. The developer tests'
 * own log configuration stays quiet; this lowers one logger to the level asked for, and puts it
 * back on close.
 */
public final class CapturedLogs implements AutoCloseable {

    private final Logger logger;

    private final Level previous;

    private final Appender appender = new Appender();

    private CapturedLogs(String loggerName, Level level) {
        this.logger = (Logger) LoggerFactory.getLogger(loggerName);
        this.previous = logger.getLevel();
        logger.setLevel(level);
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
    }

    /** Everything at INFO and above under this logger name. */
    public static CapturedLogs of(String loggerName) {
        return new CapturedLogs(loggerName, Level.INFO);
    }

    /** Every application logger: {@code com.salonplatform}. */
    public static CapturedLogs ofApplication() {
        return of("com.salonplatform");
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.events);
    }

    public List<ILoggingEvent> events(Predicate<ILoggingEvent> matching) {
        return appender.events.stream().filter(matching).toList();
    }

    /** The value of a key-value pair the line was written with, or null. */
    public static Object keyValue(ILoggingEvent event, String key) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs == null) {
            return null;
        }
        return pairs.stream()
                .filter(pair -> pair.key.equals(key))
                .map(pair -> pair.value)
                .findFirst()
                .orElse(null);
    }

    public static Map<String, String> context(ILoggingEvent event) {
        return event.getMDCPropertyMap();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previous);
    }

    private static final class Appender extends AppenderBase<ILoggingEvent> {

        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            // The logging context is read when the line is written, not later on the test's thread.
            event.prepareForDeferredProcessing();
            events.add(event);
        }
    }
}
