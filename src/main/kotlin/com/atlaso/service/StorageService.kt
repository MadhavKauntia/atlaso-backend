package com.atlaso.service

import java.io.InputStream

interface StorageService {
    fun store(key: String, inputStream: InputStream, contentType: String): String
    fun load(key: String): ByteArray
    fun getAccessUrl(key: String, contentType: String): String
    fun getUploadUrl(key: String, contentType: String, contentLength: Long): String
    fun delete(key: String)

    /** True if an object exists at [key] (HEAD). Used to verify a client-declared upload landed. */
    fun exists(key: String): Boolean
}
