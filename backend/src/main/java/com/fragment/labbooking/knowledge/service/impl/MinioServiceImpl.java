package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.service.MinioService;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

@Service
@Slf4j
public class MinioServiceImpl implements MinioService {

    private final MinioClient minioClient;

    public MinioServiceImpl(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    @Override
    public String uploadFile(String bucket, String objectName, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        try (InputStream inputStream = file.getInputStream()) {
            return uploadFile(bucket, objectName, inputStream, file.getSize(), file.getContentType());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to upload multipart file to MinIO: {}", e.getMessage());
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }
    }

    @Override
    public String uploadFile(String bucket, String objectName, InputStream inputStream, long size, String contentType) {
        try {
            ensureBucket(bucket);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(inputStream, size, -1)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .build());
            return objectName;
        } catch (Exception e) {
            log.error("Failed to upload stream to MinIO: {}", e.getMessage());
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }
    }

    @Override
    public String uploadFile(String bucket, String objectName, byte[] bytes, String contentType) {
        byte[] payload = bytes == null ? new byte[0] : bytes;
        return uploadFile(bucket, objectName, new ByteArrayInputStream(payload), payload.length, contentType);
    }

    @Override
    public byte[] downloadFile(String bucket, String objectName) {
        try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectName)
                .build())) {
            return inputStream.readAllBytes();
        } catch (Exception e) {
            log.error("Failed to download file from MinIO: {}", e.getMessage());
            throw new BusinessException("文件下载失败: " + e.getMessage());
        }
    }

    @Override
    public String presignedGetUrl(String bucket, String objectName, int expirySeconds) {
        try {
            ensureBucket(bucket);
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectName)
                    .expiry(Math.max(60, expirySeconds))
                    .build());
        } catch (Exception e) {
            log.error("Failed to generate MinIO presigned URL: {}", e.getMessage());
            throw new BusinessException("生成文件下载地址失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteFile(String bucket, String objectName) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to delete file from MinIO: {}", e.getMessage());
        }
    }

    private void ensureBucket(String bucket) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                .bucket(bucket)
                .build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder()
                    .bucket(bucket)
                    .build());
        }
    }
}
