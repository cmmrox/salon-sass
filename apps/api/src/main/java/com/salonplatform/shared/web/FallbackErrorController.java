package com.salonplatform.shared.web;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers the errors that never reach a controller (the server rejecting a request, a failure in a
 * filter) in the same shape as every other error, instead of the framework's default error body.
 */
@RestController
@Hidden
class FallbackErrorController implements ErrorController {

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        HttpStatusCode status = statusOf(request);
        ProblemDetail problem = Problems.traced(Problems.of(status), RequestTrace.of(request));
        if (request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String path) {
            problem.setInstance(URI.create(path));
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static HttpStatusCode statusOf(HttpServletRequest request) {
        if (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer status
                && status >= 400
                && status <= 599) {
            return HttpStatusCode.valueOf(status);
        }
        // Requested directly, not through an error dispatch: there is nothing here to find.
        return HttpStatus.NOT_FOUND;
    }
}
