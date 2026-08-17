package com.unisage.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Retired: this class used to serve uploaded files statically at /uploads/**
 * from local disk (file.upload-dir). File storage now goes through MinIO —
 * see FileService / FileServiceImpl and DocumentServiceImpl. Access is
 * served via time-limited presigned URLs instead of a static route, so
 * there is nothing left to register here. Safe to delete this file entirely.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
}
