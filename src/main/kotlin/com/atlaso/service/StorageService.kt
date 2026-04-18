package com.atlaso.service

import java.io.InputStream

interface StorageService {
    fun store(key: String, inputStream: InputStream, contentType: String): String
    fun load(key: String): ByteArray
    fun getAccessUrl(key: String, contentType: String): String
    fun delete(key: String)
}
