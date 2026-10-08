package com.open.spring.security;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpServletResponse;


/*
 * THIS FILE IS IMPORTANT
 * 
 * API Security Configuration
 * 
 * This file configures security for all API endpoints (/api/**) and the JWT authentication endpoint (/authenticate).
 * It uses JWT token-based authentication with stateless sessions.
 * 
 * Key Configuration:
 * - Order(1): This filter chain is processed FIRST before MvcSecurityConfig
 * - Security Matcher: Only handles requests to /api/** and /authenticate
 * - Authentication: Uses JWT tokens via JwtRequestFilter
 * - CSRF: Disabled (standard for stateless JWT APIs)
 * - CORS: Enabled with custom headers for cross-origin requests
 * - Rate Limiting: Applied via RateLimitFilter to prevent abuse
 * 
 * Endpoint Access Levels:
 * - permitAll(): Anyone can access (e.g., /authenticate, /api/person/create)
 * - hasAnyAuthority(...): Requires one of the specified roles (e.g., /api/people/**)
 * - hasAuthority("ROLE_ADMIN"): Requires admin role (e.g., DELETE /api/person/**)
 * - hasAnyAuthority(...): Requires one of the specified roles (e.g., /api/synergy/**)
 * 
 * IMPORTANT: 
 * - Keep authentication + account creation endpoints permitAll()
 * - For MVC endpoint security (form-based login), see MvcSecurityConfig.java
 * 
 * Filter Chain Order:
 * 1. RateLimitFilter - Rate limiting
 * 2. JwtRequestFilter - JWT token validation
 * 3. Standard Spring Security filters
 */

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @Autowired
    private JwtRequestFilter jwtRequestFilter;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {

        http
            .securityMatcher(new OrRequestMatcher(
                new RegexRequestMatcher("^/api(?:/.*)?$", null),
                new RegexRequestMatcher("^/authenticate$", null)
            ))
                
                .cors(Customizer.withDefaults())

                // JWT related configuration
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // STATELESS stops Spring storing a SecurityContext, but it does not stop the
                // request cache from creating a session to remember "where the user was going".
                // On an API chain there is nowhere to go back to, so disable it outright.
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth

                        // ========== AUTHENTICATION & USER MANAGEMENT ==========
                        // Public endpoint - no authentication required, supports user login
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**", "/authenticate", "/run/**").permitAll()  // Allow only relevant CORS preflight requests
                        .requestMatchers("/authenticate").permitAll()
                        .requestMatchers(HttpMethod.POST, "/authenticate").permitAll() // allow POST on auth
                        // Admin-only writes are listed FIRST: rules are first-match-wins, and a
                        // method-less permitAll on /api/person/create would otherwise also permit
                        // DELETE and PUT on that path, shadowing the ROLE_ADMIN rules below.
                        .requestMatchers(HttpMethod.DELETE, "/api/person/**").hasAuthority("ROLE_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/person/**").hasAuthority("ROLE_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/person/create").permitAll()
                        // Public grades submission - no authentication required
                        .requestMatchers(HttpMethod.POST, "/api/grades").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/grades/").permitAll()
                        // updated so that it takes auth to actually hit endpoint
                        .requestMatchers("/api/scraper/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        // ← GIST CREATION ENDPOINTS - PUBLIC (NO AUTH)
                        .requestMatchers(HttpMethod.POST, "/api/grades/create-gist").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/grades/create-gist/").permitAll()
                        // ← GIST READ - PUBLIC (mirrors create-gist; a gist URL is
                        //    already shareable, so reading one adds no exposure)
                        .requestMatchers(HttpMethod.GET, "/api/grades/read-gist/**").permitAll()
                        // ← MAKE DEBUGGER - PUBLIC (NO AUTH)
                        .requestMatchers("/api/make/**").permitAll()
                        // (DELETE/PUT /api/person/** are declared above, ahead of the create permit.)
                        .requestMatchers(HttpMethod.GET, "/api/person/uid/**").permitAll()
                        // Face biometric data:
                        //   GET /faces  - teacher/admin only (scanner feed)
                        //   POST /register - any authenticated user (self-service face enrollment)
                        .requestMatchers(HttpMethod.POST, "/api/face/register/public", "/api/face/register/public/").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/face/faces").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/face/register").hasAnyAuthority("ROLE_USER", "ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
                        .requestMatchers("/api/face/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")

                        // All other /api/person/** and /api/people/** operations handled by default rule
                        // ======================================================

                        // ========== API ENDPOINTS (REQUIRE AT LEAST USER) ==========
                        // Previously public endpoints now require authenticated roles
                        .requestMatchers("/api/jokes/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        // Pause Menu APIs should be public
                        .requestMatchers("/api/pausemenu/**").permitAll()
                        // Leaderboard should be public - displays scores without authentication
                        .requestMatchers("/api/leaderboard/**").permitAll()
                        // Frontend calls gamer score endpoint; make it public
                        .requestMatchers("/api/gamer/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        // ==========================================
                        .requestMatchers("/api/exports/**").hasAuthority("ROLE_ADMIN")
                        .requestMatchers("/api/imports/**").hasAuthority("ROLE_ADMIN")
                        
                        .requestMatchers("/api/content/**").hasAnyAuthority("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
                        .requestMatchers("/api/collections/**").hasAnyAuthority("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
                        .requestMatchers("/api/events/**").hasAnyAuthority("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
                        // ========== SYNERGY (ROLE-BASED ACCESS, Legacy system) ==========
                        // Specific endpoint with student/teacher/admin access
                        .requestMatchers(HttpMethod.POST, "/api/synergy/grades/requests").hasAnyAuthority("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/synergy/saigai/").hasAnyAuthority("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
                        // Teacher and admin access for other POST operations
                        .requestMatchers(HttpMethod.POST, "/api/synergy/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                        // AI preferences endpoints require authenticated roles
                        .requestMatchers(HttpMethod.POST, "/api/upai").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        .requestMatchers(HttpMethod.GET, "/api/upai/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        // ← GEMINI FRQ GRADING - authenticated role access
                        .requestMatchers(HttpMethod.POST, "/api/gemini-frq/grade").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        .requestMatchers(HttpMethod.POST, "/api/gemini-frq/grade/").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        .requestMatchers(HttpMethod.GET, "/api/gemini-frq/grade/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        // Admin access for certificates + quests
                        .requestMatchers(HttpMethod.POST, "/api/quests/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/quests/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/quests/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                        

                        .requestMatchers(HttpMethod.POST, "/api/certificates/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/certificates/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/certificates/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/user-certificates/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/user-certificates/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                        // =================================================

                        // ========== LEGACY API ENDPOINTS (NOW AUTHENTICATED) ==========
                        // These endpoints now require authenticated roles
                        .requestMatchers("/api/analytics/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        .requestMatchers("/api/grades**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        .requestMatchers("/api/plant/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        .requestMatchers("/api/groups/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        .requestMatchers("/api/grade-prediction/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        .requestMatchers("/api/admin-evaluation/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        .requestMatchers("/api/grades/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        .requestMatchers("/api/progress/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        .requestMatchers("/api/calendar/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        // Chat APIs - require authentication
                        .requestMatchers("/api/chat/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        // Email send API - require authentication (sends via Gmail SMTP)
                        .requestMatchers("/api/email/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        .requestMatchers("/api/files/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        // Sprint dates - GET requires authenticated roles
                        .requestMatchers(HttpMethod.GET, "/api/sprint-dates/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        // User preferences - requires authentication (handled by default rule)
                        // ================================================================================

                        // ========== CHALLENGE SUBMISSION ==========
                        // Code runner challenge submissions - requires authentication
                        .requestMatchers(HttpMethod.POST, "/api/challenge-submission/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        // ==========================================

                        // ========== ASSIGNMENT CREATOR SYNC ==========
                        // auto-create is how Pages frontmatter reaches Spring. The trusted Pages bot
                        // is provisioned with ROLE_ASSIGNMENT_SYNC and nothing else, so it is listed
                        // here to reach this one endpoint; the default /api/** rule below still keeps
                        // it out of everything else. ROLE_ASSIGNMENT_SYNC is never granted to students
                        // or teachers - only that bot account may set assignment creators.
                        .requestMatchers(HttpMethod.POST, "/api/assignments/auto-create")
                            .hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT", "ROLE_ASSIGNMENT_SYNC")
                        // =============================================

                        // ========== ASSIGNMENT SUBMISSION ==========
                        // Assignment text/link submissions - public (student identity passed in payload)
                        .requestMatchers(HttpMethod.POST, "/api/submissions/**").permitAll()
                        // Assignment file uploads - public (student identity passed in multipart fields)
                        .requestMatchers(HttpMethod.POST, "/api/assignment-submissions/upload").permitAll()
                        // ==========================================

                        // ========== CS PATHWAY SETUP REPORT ==========
                        // A student's Terminal has no JWT cookie, so the report upload is public and
                        // is tied to a student by the short-lived pairing code in its path. The
                        // pairing-code rule comes FIRST (first match wins) so only signed-in users get codes.
                        .requestMatchers(HttpMethod.POST, "/api/cs-pathway/setup-report/pairing-code").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        .requestMatchers(HttpMethod.POST, "/api/cs-pathway/setup-report/review/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_TEACHER")
                        .requestMatchers(HttpMethod.POST, "/api/cs-pathway/setup-report/*").permitAll()
                        // =============================================

                        // ========== OCS ANALYTICS ==========
                        // OCS Analytics endpoints - require authentication to associate data with user
                        .requestMatchers("/api/ocs-analytics/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        // ===================================

                        // ========== DEFAULT: ALL OTHER API ENDPOINTS ==========
                        // Secure by default - any endpoint not explicitly listed above requires authentication
                        .requestMatchers("/api/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT")
                        // ======================================================
                       
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json");
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"You do not have permission to access this resource.\"}");
                            response.getWriter().flush();
                        }))


                // Session related configuration
                .addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, JwtRequestFilter.class);

        return http.build();
    }

    @Bean(name = "apiEndpointRolePolicy")
    public Map<String, String> apiEndpointRolePolicy() {
        Map<String, String> policy = new LinkedHashMap<>();
        policy.put("/authenticate", "permitAll");
        policy.put("POST /authenticate", "permitAll");
        policy.put("/api/person/create", "permitAll");
        policy.put("/api/person/create/", "permitAll");
        policy.put("POST /api/person/create", "permitAll");
        policy.put("POST /api/grades", "permitAll");
        policy.put("POST /api/grades/", "permitAll");
        policy.put("DELETE /api/person/**", "ROLE_ADMIN");
        policy.put("PUT /api/person/**", "ROLE_ADMIN");
        policy.put("GET /api/person/uid/**", "permitAll");
        // Face biometric endpoints
        policy.put("POST /api/face/register/public", "permitAll");
        policy.put("GET /api/face/faces", "ROLE_TEACHER|ROLE_ADMIN");
        policy.put("POST /api/face/register", "ROLE_USER|ROLE_STUDENT|ROLE_TEACHER|ROLE_ADMIN");
        policy.put("/api/face/**", "ROLE_TEACHER|ROLE_ADMIN");
        policy.put("POST /api/assignment-submissions/upload", "permitAll");
        policy.put("POST /api/assignments/auto-create", "ROLE_USER|ROLE_ADMIN|ROLE_TEACHER|ROLE_STUDENT|ROLE_ASSIGNMENT_SYNC");
        policy.put("POST /api/cs-pathway/setup-report/pairing-code", "ROLE_USER|ROLE_ADMIN|ROLE_TEACHER|ROLE_STUDENT");
        policy.put("POST /api/cs-pathway/setup-report/review/**", "ROLE_ADMIN|ROLE_TEACHER");
        policy.put("POST /api/cs-pathway/setup-report/*", "permitAll");
        policy.put("/api/pausemenu/**", "permitAll");
        policy.put("/api/leaderboard/**", "permitAll");
        policy.put("/api/exports/**", "ROLE_ADMIN");
        policy.put("/api/imports/**", "ROLE_ADMIN");
        policy.put("/api/**", "ROLE_USER|ROLE_ADMIN|ROLE_TEACHER|ROLE_STUDENT");
        return Map.copyOf(policy);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowCredentials(true);
        configuration.addAllowedOriginPattern("https://*.opencodingsociety.com");
        configuration.addAllowedOriginPattern("https://open-coding-society.github.io");
        configuration.addAllowedOriginPattern("http://127.0.0.1:4500");
        configuration.addAllowedOriginPattern("http://127.0.0.1:4599");
        configuration.addAllowedOriginPattern("http://127.0.0.1:4600");
        configuration.addAllowedOriginPattern("http://127.0.0.1:8585");
        configuration.addAllowedOriginPattern("http://127.0.0.1:4000");
        configuration.addAllowedOriginPattern("http://localhost:4000");
        configuration.addAllowedOriginPattern("http://localhost:4500");
        configuration.addAllowedOriginPattern("http://localhost:4599");
        configuration.addAllowedOriginPattern("http://localhost:4600");
        configuration.addAllowedOriginPattern("http://localhost:8585");
        configuration.addAllowedOriginPattern("https://opencodingsociety.com");
        configuration.addAllowedOriginPattern("http://opencodingsociety.com");
        configuration.addAllowedOriginPattern("https://pages.opencodingsociety.com");
        configuration.addAllowedOriginPattern("https://spring.opencodingsociety.com");
        configuration.addAllowedHeader("*");
        configuration.addAllowedMethod("*");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

}