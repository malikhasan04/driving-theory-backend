package com.drivingtheory.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class CloudinaryService {

    private final Cloudinary cloudinary;
    private final String folder;
    private final int imageWidth;
    private final int imageHeight;

    public CloudinaryService(
            @Value("${cloudinary.cloud-name}") String cloudName,
            @Value("${cloudinary.api-key}")    String apiKey,
            @Value("${cloudinary.api-secret}") String apiSecret,
            @Value("${cloudinary.folder}")     String folder,
            @Value("${cloudinary.image-width}") int imageWidth,
            @Value("${cloudinary.image-height}") int imageHeight) {

        this.cloudinary = new Cloudinary(ObjectUtils.asMap(
                "cloud_name", cloudName,
                "api_key",    apiKey,
                "api_secret", apiSecret,
                "secure",     true));
        this.folder      = folder;
        this.imageWidth  = imageWidth;
        this.imageHeight = imageHeight;
    }

    @SuppressWarnings("unchecked")
    public UploadResult uploadImage(BufferedImage image, String questionRef) throws IOException {
        byte[] bytes    = toBytes(image);
        String publicId = folder + "/" + questionRef + "_" + UUID.randomUUID().toString().substring(0, 8);

        // Upload without transformation — let Cloudinary store the original
        // Transformation via URL is more reliable than upload-time transformation
        Map<String, Object> result = cloudinary.uploader().upload(bytes, ObjectUtils.asMap(
                "public_id",     publicId,
                "resource_type", "image",
                "overwrite",     true));

        String secureUrl        = (String) result.get("secure_url");
        String returnedPublicId = (String) result.get("public_id");
        log.info("Uploaded sign image: {}", secureUrl);
        return new UploadResult(returnedPublicId, secureUrl);
    }

    public void deleteImage(String publicId) {
        try {
            cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
            log.info("Deleted Cloudinary image: {}", publicId);
        } catch (IOException e) {
            log.warn("Failed to delete Cloudinary image {}: {}", publicId, e.getMessage());
        }
    }

    private byte[] toBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    public record UploadResult(String publicId, String secureUrl) {}
}