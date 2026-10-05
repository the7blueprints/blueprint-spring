package com.open.spring.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseCookie;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import jakarta.servlet.DispatcherType;

/*
 * MvcSecurityConfig.java
 * 
 * MVC Security Configuration - handles web pages and form-based login
 * 
 * Key Points:
 * - Order(2): Processed AFTER the API security chain (Order 1)
 * - Matches: All requests not handled by API chain (/**)
 * - Authentication: Traditional form login with sessions
 * - Login: /login page, redirects to /mvc/person/read on success
 * - Logout: Deletes session cookie, redirects to homepage
 * 
 * Access Levels:
 * - permitAll(): /login, /mvc/person/create, /mvc/person/reset
 * - authenticated(): Most /mvc/** endpoints
 * - ROLE_ADMIN: /mvc/person/delete, /mvc/extract, /mvc/import
 * - ROLE_TEACHER/STUDENT: /mvc/synergy/** endpoints
 * 
 * For API security (JWT-based), see SecurityConfig.java
 */

@Configuration
public class MvcSecurityConfig {

    // Cookie attributes live in CookieFactory -- see the comment there on why
    // set and delete must be built from the same place.

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    @Autowired
    private CookieFactory cookieFactory;

    @Autowired
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    // Tracks every live MVC HttpSession by principal so a password reset can force-expire
    // whatever session(s) that uid currently holds -- previously a known gap (the JWT path
    // was covered via tokenVersion, but this form-login/session path was not). Registering
    // HttpSessionEventPublisher is required for the registry to actually see session
    // creation/destruction events.
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public ServletListenerRegistrationBean<HttpSessionEventPublisher> httpSessionEventPublisher() {
        return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());
    }

    /**
     * MVC security: form login, session-based.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain mvcSecurityFilterChain(HttpSecurity http) throws Exception {

        http
            // Everything that is NOT handled by the API chain
            .securityMatcher("/**")
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                // maximumSessions(-1) means no cap is enforced -- this exists purely to get
                // every session registered in sessionRegistry() so it can be force-expired
                // elsewhere (PersonViewController, after a password reset), not to limit
                // concurrent logins.
                .sessionConcurrency(concurrency -> concurrency
                    .sessionRegistry(sessionRegistry())
                    .maximumSessions(-1)))
            .authorizeHttpRequests(auth -> auth
                // A container ERROR dispatch re-enters this chain with the URI rewritten
                // to /error, which no longer matches the API chain's securityMatcher. Without
                // this, every /api/** failure (500, 404, or a sendError from a filter) was
                // re-authorized here as an anonymous request and answered with a 302 to the
                // HTML login page -- the API request never saw its own error.
                //
                // This permits the DISPATCH TYPE, not a URL: a direct GET /error from outside
                // arrives as a REQUEST dispatch and is still covered by the rules below.
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                .requestMatchers("/mvc/person/search/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/mvc/person/create").permitAll()
                .requestMatchers(HttpMethod.POST, "/mvc/person/create").permitAll()
                .requestMatchers(HttpMethod.GET, "/mvc/person/reset").permitAll()
                .requestMatchers(HttpMethod.GET, "/mvc/person/reset/check").permitAll()
                .requestMatchers(HttpMethod.POST, "/mvc/person/reset/start").permitAll()
                .requestMatchers(HttpMethod.POST, "/mvc/person/reset/check").permitAll()
                .requestMatchers(HttpMethod.POST, "/mvc/person/reset/oauth/verify").permitAll()
                .requestMatchers(HttpMethod.POST, "/mvc/person/reset/oauth/complete").permitAll()
                .requestMatchers("/mvc/person/read/**").authenticated()
                .requestMatchers("/mvc/person/cookie-clicker").authenticated()
                .requestMatchers(HttpMethod.GET,"/mvc/person/update/user").authenticated()
                .requestMatchers(HttpMethod.GET,"/mvc/person/update/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST,"/mvc/person/update").authenticated()
                .requestMatchers(HttpMethod.POST,"/mvc/person/update/role").hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST,"/mvc/person/update/roles").hasAuthority("ROLE_ADMIN")
                .requestMatchers("/mvc/person/delete/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers("/mvc/bathroom/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/login").permitAll()
                .requestMatchers("/mvc/synergy/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/mvc/synergy/gradebook").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN", "ROLE_STUDENT")
                .requestMatchers(HttpMethod.GET, "/mvc/synergy/view-grade-requests").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                .requestMatchers(HttpMethod.GET, "/mvc/assignments/tracker").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                .requestMatchers(HttpMethod.GET, "/mvc/teamteach/teachergrading").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                .requestMatchers(HttpMethod.GET,"/mvc/train/**").authenticated()
                .requestMatchers(HttpMethod.GET,"/mvc/extract/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST,"/mvc/extract/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST,"/mvc/import/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers("/mvc/grades/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers("/mvc/assignments/read").hasAnyAuthority("ROLE_ADMIN", "ROLE_TEACHER")
                .requestMatchers("/mvc/bank/read").hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.OPTIONS, "/ws-chat/**").permitAll()
                .requestMatchers("/mvc/progress/read").hasAnyAuthority("ROLE_ADMIN", "ROLE_TEACHER")
                .requestMatchers("/mvc/cs-pathway/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_TEACHER")
                .requestMatchers("/ws-chat/**").permitAll()
                .requestMatchers("/run/**").permitAll()  // Java runner endpoints - public access
                .anyRequest().authenticated()
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(
                    new ApiAwareAuthenticationEntryPoint("/login", jwtAuthenticationEntryPoint)))
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler((request, response, authentication) -> {
                    if (authentication == null || !authentication.isAuthenticated()) {
                        response.sendRedirect("/login?error");
                        return;
                    }

                    UserDetails userDetails = (UserDetails) authentication.getPrincipal();
                    List<String> roles = authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toList());

                    String token = jwtTokenUtil.generateToken(userDetails, roles);
                    if (token == null) {
                        response.sendError(500, "Token generation failed");
                        return;
                    }

                    // Built by CookieFactory so this cookie and the one logout deletes
                    // always carry the same name, domain and path.
                    ResponseCookie jwtCookie = cookieFactory.jwtCookie(token);

                    response.addHeader(HttpHeaders.SET_COOKIE, jwtCookie.toString());
                    response.sendRedirect("/mvc/person/read");
                }))
            .logout(logout -> logout
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .logoutSuccessHandler((request, response, authentication) -> {
                    // Previously these were built inline without the domain that login sets,
                    // so the browser kept the domain-scoped JWT and logout never took effect.
                    ResponseCookie sessionCookie = cookieFactory.expiredSessionCookie();
                    ResponseCookie jwtCookie = cookieFactory.expiredJwtCookie();
                    response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie.toString());
                    response.addHeader(HttpHeaders.SET_COOKIE, jwtCookie.toString());
                    // Also clears any pre-CookieFactory JWT cookie shape a browser might
                    // still be holding (host-only, or Domain=localhost from local dev), so
                    // logout actually logs out old sessions instead of leaving a zombie cookie.
                    response.addHeader(HttpHeaders.SET_COOKIE, cookieFactory.expiredJwtHostOnlyCookie().toString());
                    response.addHeader(HttpHeaders.SET_COOKIE, cookieFactory.expiredJwtLegacyLocalhostCookie().toString());
                    response.sendRedirect("/login?logout");
                }));

        return http.build();
    }

    @Bean(name = "mvcEndpointRolePolicy")
    public Map<String, String> mvcEndpointRolePolicy() {
        Map<String, String> policy = new LinkedHashMap<>();
        policy.put("GET/POST /login", "permitAll");
        policy.put("GET/POST /mvc/person/create", "permitAll");
        policy.put("GET /mvc/person/reset", "permitAll");
        policy.put("GET /mvc/person/reset/check", "permitAll");
        policy.put("POST /mvc/person/reset/start", "permitAll");
        policy.put("POST /mvc/person/reset/check", "permitAll");
        policy.put("POST /mvc/person/reset/oauth/verify", "permitAll");
        policy.put("POST /mvc/person/reset/oauth/complete", "permitAll");
        policy.put("GET /mvc/person/update/user", "authenticated");
        policy.put("POST /mvc/person/update", "authenticated (+ controller ownership checks)");
        policy.put("POST /mvc/person/update/role", "ROLE_ADMIN");
        policy.put("POST /mvc/person/update/roles", "ROLE_ADMIN");
        policy.put("/mvc/person/delete/**", "ROLE_ADMIN");
        return Map.copyOf(policy);
    }
}
