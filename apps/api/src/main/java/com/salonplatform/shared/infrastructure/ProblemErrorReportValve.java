package com.salonplatform.shared.infrastructure;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.salonplatform.shared.api.ErrorCode;
import com.salonplatform.shared.api.RequestTrace;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.ActionCode;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

/**
 * The server rejects some requests itself, before the application sees them: a path that is not
 * validly encoded, a header it cannot read. Its own answer is an HTML page. This answers them in
 * the shape of every other error instead: problem details with a stable {@code code} and a
 * {@code traceId}, and writes the request's one log line, which no filter wrote.
 *
 * <p>Tomcat creates it by name, so it has a public constructor and uses nothing from Spring's
 * context.
 */
public class ProblemErrorReportValve extends ErrorReportValve {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public ProblemErrorReportValve() {
        setShowReport(false);
        setShowServerInfo(false);
    }

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int status = response.getStatus();
        // Nothing to do unless this is an error nobody has answered yet.
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        String traceId = RequestTrace.of(request);
        if (request.getAttribute(RequestLog.LOGGED) == null) {
            // Refused before the application saw it, so no filter wrote the request's line.
            logRequest(request, status, traceId);
        }
        AtomicBoolean ioAllowed = new AtomicBoolean(false);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, ioAllowed);
        if (!ioAllowed.get()) {
            return;
        }
        try {
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("utf-8");
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write(JSON.writeValueAsString(body(HttpStatusCode.valueOf(status), traceId)));
                response.finishResponse();
            }
        }
        catch (IOException | IllegalStateException ex) {
            // The client has gone, or the response can no longer be written. There is nobody to tell.
        }
    }

    private static void logRequest(Request request, int status, String traceId) {
        long started = request.getCoyoteRequest().getStartTimeNanos();
        long duration = started > 0 ? Math.max(0, System.nanoTime() - started) : 0;
        String path = request.getRequestURI();
        MDC.put(RequestLog.TRACE_ID, traceId);
        try {
            RequestLog.write(request.getMethod() == null ? "-" : request.getMethod(), path == null ? "-" : path, status,
                    duration);
        }
        finally {
            MDC.remove(RequestLog.TRACE_ID);
        }
    }

    private static Map<String, Object> body(HttpStatusCode status, String traceId) {
        ErrorCode errorCode = ErrorCode.forStatus(status);
        HttpStatus known = HttpStatus.resolve(status.value());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", known != null ? known.getReasonPhrase() : "Error");
        body.put("status", status.value());
        body.put("detail", errorCode.detail());
        body.put("code", errorCode.code());
        body.put("traceId", traceId);
        return body;
    }
}
