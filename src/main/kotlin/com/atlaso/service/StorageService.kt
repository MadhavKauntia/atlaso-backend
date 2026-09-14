package com.atlaso.service

import java.io.InputStream

/** Object metadata from a HEAD, used to verify a client-declared upload actually landed. */
data class ObjectHead(val contentLength: Long, val contentType: String?)

interface StorageService {
    fun store(key: String, inputStream: InputStream, contentType: String): String
    fun load(key: String): ByteArray
    fun getAccessUrl(key: String, contentType: String): String
    fun getUploadUrl(key: String, contentType: String, contentLength: Long): String
    fun delete(key: String)

    /** HEAD metadata for the object at [key], or null if it doesn't exist. */
    fun head(key: String): ObjectHead?
}
