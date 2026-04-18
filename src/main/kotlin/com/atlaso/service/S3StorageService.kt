package com.atlaso.service

import com.atlaso.config.S3StorageConfig
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.io.InputStream
import java.time.Duration

@Service
@ConditionalOnProperty(name = ["atlaso.storage.type"], havingValue = "s3")
class S3StorageService(
    private val config: S3StorageConfig
) : StorageService {

    private val logger = LoggerFactory.getLogger(S3StorageService::class.java)

    private val region = Region.of(config.region)

    private val s3 = S3Client.builder()
        .region(region)
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build()

    private val presigner = S3Presigner.builder()
        .region(region)
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build()

    override fun store(key: String, inputStream: InputStream, contentType: String): String {
        val bytes = inputStream.readBytes()
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(config.bucketName)
                .key(key)
                .contentType(contentType)
                .contentLength(bytes.size.toLong())
                .build(),
            RequestBody.fromBytes(bytes)
        )
        logger.info("Stored s3://{}/{}", config.bucketName, key)
        return key
    }

    override fun load(key: String): ByteArray {
        return s3.getObjectAsBytes(
            GetObjectRequest.builder()
                .bucket(config.bucketName)
                .key(key)
                .build()
        ).asByteArray()
    }

    override fun getAccessUrl(key: String, contentType: String): String {
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofHours(1))
            .getObjectRequest(
                GetObjectRequest.builder()
                    .bucket(config.bucketName)
                    .key(key)
                    .build()
            )
            .build()
        return presigner.presignGetObject(presignRequest).url().toString()
    }

    override fun getUploadUrl(key: String, contentType: String): String {
        val putRequest = PutObjectRequest.builder()
            .bucket(config.bucketName)
            .key(key)
            .contentType(contentType)
            .build()
        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofHours(1))
            .putObjectRequest(putRequest)
            .build()
        return presigner.presignPutObject(presignRequest).url().toString()
    }

    override fun delete(key: String) {
        s3.deleteObject(
            DeleteObjectRequest.builder()
                .bucket(config.bucketName)
                .key(key)
                .build()
        )
        logger.info("Deleted s3://{}/{}", config.bucketName, key)
    }
}
