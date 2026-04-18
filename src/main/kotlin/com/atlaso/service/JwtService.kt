package com.atlaso.service

import com.atlaso.domain.user.User
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Date

@Service
class JwtService(@Value("\${atlaso.auth.jwt-secret}") private val secret: String) {

    private val signer: MACSigner by lazy {
        val keyBytes = secret.toByteArray(Charsets.UTF_8)
        require(keyBytes.size >= 32) { "JWT_SECRET must be at least 32 characters" }
        MACSigner(keyBytes)
    }

    fun generateToken(user: User): String {
        val now = System.currentTimeMillis()
        val claims = JWTClaimsSet.Builder()
            .subject(user.id.toString())
            .claim("email", user.email)
            .claim("name", user.name)
            .issueTime(Date(now))
            .expirationTime(Date(now + 7L * 24 * 60 * 60 * 1000))
            .build()
        val signed = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
        signed.sign(signer)
        return signed.serialize()
    }
}
