package com.cafe.common.security;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents the gateway's trusted-identity headers (plan section 5) as OpenAPI "apiKey"
 * security schemes, so Swagger UI's Authorize dialog can attach them to "Try it out" calls.
 * These headers are read directly off the raw request by HeaderAuthenticationFilter, not
 * bound as Spring MVC method parameters, so springdoc can't otherwise discover them - and
 * there is no JWT/Bearer token to enter here at all, since only the gateway ever verifies
 * the token; a service reached directly (bypassing the gateway, as this Swagger UI does)
 * trusts these three headers outright instead.
 *
 * Put on each service's *Application class alongside @OpenAPIDefinition(security = {...}).
 */
@SecuritySchemes({
        @SecurityScheme(
                name = HeaderAuthenticationFilter.HEADER_USER_ID,
                type = SecuritySchemeType.APIKEY,
                in = SecuritySchemeIn.HEADER,
                description = "Trusted numeric user id - normally set by the gateway after verifying the JWT"
        ),
        @SecurityScheme(
                name = HeaderAuthenticationFilter.HEADER_USERNAME,
                type = SecuritySchemeType.APIKEY,
                in = SecuritySchemeIn.HEADER,
                description = "Trusted username - normally set by the gateway after verifying the JWT"
        ),
        @SecurityScheme(
                name = HeaderAuthenticationFilter.HEADER_USER_ROLE,
                type = SecuritySchemeType.APIKEY,
                in = SecuritySchemeIn.HEADER,
                description = "Trusted role (ADMIN or CASHIER) - normally set by the gateway after verifying the JWT"
        )
})
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TrustedHeaderAuth {
}
