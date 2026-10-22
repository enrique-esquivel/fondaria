package dev.fondaria.contribution.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.common.security.CallerIdentity;
import dev.fondaria.common.security.CallerIdentityParser;
import dev.fondaria.common.security.IdentityContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extracts {@link CallerIdentity} from request authorization headers and binds it to {@link
 * IdentityContext} for the lifecycle of the request in {@code contribution-api}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IdentityContextFilter extends OncePerRequestFilter {

  private final ObjectMapper objectMapper;

  public IdentityContextFilter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      String authHeader = request.getHeader("Authorization");
      Optional<CallerIdentity> caller =
          CallerIdentityParser.parseBearerHeader(authHeader, objectMapper);
      caller.ifPresent(IdentityContext::set);
      dev.fondaria.common.security.AuthorisationEnforcer.enforce(
          caller, request.getRequestURI(), request.getMethod());
      filterChain.doFilter(request, response);
    } catch (dev.fondaria.common.security.AuthenticationRequiredException e) {
      sendProblem(
          response,
          jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED,
          "Unauthorized",
          e.getMessage(),
          "https://fondaria.example/problems/unauthorized");
    } catch (dev.fondaria.common.security.AccessDeniedProblemException e) {
      sendProblem(
          response,
          jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN,
          "Forbidden",
          e.detail(),
          "https://fondaria.example/problems/forbidden");
    } finally {
      IdentityContext.clear();
    }
  }

  private void sendProblem(
      HttpServletResponse response, int status, String title, String detail, String type)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    org.springframework.http.ProblemDetail pd =
        org.springframework.http.ProblemDetail.forStatus(status);
    pd.setType(java.net.URI.create(type));
    pd.setTitle(title);
    pd.setDetail(detail);
    response.getWriter().write(objectMapper.writeValueAsString(pd));
  }
}
