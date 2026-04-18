package com.atlaso.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Service

class InvalidGoogleTokenException(cause: Throwable? = null) :
    RuntimeException("Invalid Google ID token", cause)

@Service
class GoogleTokenVerifier(
    @Value("\${atlaso.auth.google-client-id}") private val googleClientId: String
) {
    data class GoogleClaims(val sub: String, val email: String, val name: String, val pictureUrl: String?)

    private val decoder by lazy {
        val d = NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build()
        d.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtTimestampValidator(),
                JwtClaimValidator<String>(JwtClaimNames.ISS) { iss ->
                    iss == "accounts.google.com" || iss == "https://accounts.google.com"
                },
                JwtClaimValidator<List<String>>(JwtClaimNames.AUD) { aud ->
                    aud != null && googleClientId in aud
                }
            )
        )
        d
    }

    fun verify(idToken: String): GoogleClaims {
        val jwt = try {
            decoder.decode(idToken)
        } catch (e: JwtException) {
            throw InvalidGoogleTokenException(e)
        }
        val sub = jwt.subject ?: throw InvalidGoogleTokenException()
        val email = jwt.getClaimAsString("email") ?: throw InvalidGoogleTokenException()
        val name = jwt.getClaimAsString("name") ?: email
        val picture = jwt.getClaimAsString("picture")
        return GoogleClaims(sub = sub, email = email, name = name, pictureUrl = picture)
    }
}
