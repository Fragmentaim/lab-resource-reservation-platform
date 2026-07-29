package com.fragment.labbooking.knowledge.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface MinioService {

    String uploadFile(String bucket, String objectName, MultipartFile file);

    String uploadFile(String bucket, String objectName, InputStream inputStream, long size, String contentType);

    String presignedGetUrl(String bucket, String objectName, int expirySeconds);

    void deleteFile(String bucket, String objectName);
}
