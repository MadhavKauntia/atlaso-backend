package com.atlaso.controller

import com.atlaso.controller.dto.AuthResponse
import com.atlaso.controller.dto.GoogleAuthRequest
import com.atlaso.controller.dto.UserDto
import com.atlaso.service.GoogleTokenVerifier
import com.atlaso.service.JwtService
import com.atlaso.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val googleTokenVerifier: GoogleTokenVerifier,
    private val userService: UserService,
    private val jwtService: JwtService
) {

    @PostMapping("/google")
    fun googleAuth(@RequestBody request: GoogleAuthRequest): ResponseEntity<AuthResponse> {
        val claims = googleTokenVerifier.verify(request.idToken)
        val user = userService.findOrCreate(
            googleSub = claims.sub,
            email = claims.email,
            name = claims.name,
            pictureUrl = claims.pictureUrl
        )
        val token = jwtService.generateToken(user)
        return ResponseEntity.ok(AuthResponse(token = token, user = UserDto.from(user)))
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<UserDto> {
        val user = userService.getById(UUID.fromString(jwt.subject))
        return ResponseEntity.ok(UserDto.from(user))
    }
}
