package com.atlaso.controller

import com.atlaso.controller.dto.ErrorResponse
import com.atlaso.service.BookNotFoundException
import com.atlaso.service.FreePreviewQuotaExceededException
import com.atlaso.service.GuestTokenException
import com.atlaso.service.InsufficientPhotosException
import com.atlaso.service.InvalidGoogleTokenException
import com.atlaso.service.NoPhotosAvailableException
import com.atlaso.service.PageNotFoundException
import com.atlaso.service.PhotoNotFoundException
import com.atlaso.service.TripNotFoundException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(TripNotFoundException::class)
    fun handleTripNotFound(ex: TripNotFoundException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        return buildResponse(HttpStatus.NOT_FOUND, ex.message, request)
    }

    @ExceptionHandler(PhotoNotFoundException::class)
    fun handlePhotoNotFound(ex: PhotoNotFoundException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        return buildResponse(HttpStatus.NOT_FOUND, ex.message, request)
    }

    @ExceptionHandler(BookNotFoundException::class)
    fun handleBookNotFound(ex: BookNotFoundException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        return buildResponse(HttpStatus.NOT_FOUND, ex.message, request)
    }

    @ExceptionHandler(PageNotFoundException::class)
    fun handlePageNotFound(ex: PageNotFoundException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        return buildResponse(HttpStatus.NOT_FOUND, ex.message, request)
    }

    @ExceptionHandler(NoPhotosAvailableException::class)
    fun handleNoPhotos(ex: NoPhotosAvailableException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.message, request)
    }

    // 409 when a layout switch needs more spare photos than the trip has left. The internal message
    // carries counts, so we send a fixed, actionable message the client can show verbatim.
    @ExceptionHandler(InsufficientPhotosException::class)
    fun handleInsufficientPhotos(ex: InsufficientPhotosException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        return buildResponse(HttpStatus.CONFLICT, "Not enough spare photos to switch to this layout. Pick a layout with fewer photos.", request)
    }

    // 402 so the client can distinguish "out of free previews" from other errors and prompt an order.
    // The internal ex.message carries the user id, so we send a fixed user-facing message instead.
    @ExceptionHandler(FreePreviewQuotaExceededException::class)
    fun handleFreePreviewQuota(ex: FreePreviewQuotaExceededException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        return buildResponse(HttpStatus.PAYMENT_REQUIRED, "You've used all your free book previews. Place an order to create more.", request)
    }

    @ExceptionHandler(InvalidGoogleTokenException::class)
    fun handleInvalidGoogleToken(ex: InvalidGoogleTokenException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.message, request)
    }

    @ExceptionHandler(GuestTokenException::class)
    fun handleGuestToken(ex: GuestTokenException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        return buildResponse(HttpStatus.FORBIDDEN, ex.message, request)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.message, request)
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSize(ex: MaxUploadSizeExceededException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        return buildResponse(HttpStatus.PAYLOAD_TOO_LARGE, "File size exceeds maximum allowed size", request)
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneral(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        logger.error("Unhandled exception", ex)
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request)
    }

    private fun buildResponse(
        status: HttpStatus,
        message: String?,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        // Surface handled client errors so they're visible in Axiom (5xx already logged at
        // ERROR by handleGeneral). Without this, every 400/401/404 vanishes silently.
        if (status.is4xxClientError) {
            logger.warn("{} {} -> {} {}", request.method, request.requestURI, status.value(), message)
        }
        val error = ErrorResponse(
            status = status.value(),
            error = status.reasonPhrase,
            message = message,
            path = request.requestURI
        )
        return ResponseEntity.status(status).body(error)
    }
}
