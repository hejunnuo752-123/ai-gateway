package com.aigateway.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AiPlatform {
    private Long id;
    private String name;
    private String description;
    private String url;
    private String category;
    private Integer sortOrder = 0;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
