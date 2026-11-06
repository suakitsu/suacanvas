package com.openimage.video;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "openimage.video")
public class VideoProperties {
    private boolean mockEnabled = false;
    private boolean paidProviderEnabled = false;
    private boolean agnesEnabled = false;
    private String apimartApiKey = "";
    private String agnesApiKey = "";
    private String hfsyApiKey = "";
    private String configEncryptionKey = "";
    private String publicBaseUrl = "";
    private String webhookSecret = "";
    private int timeoutMinutes = 30;
    private int workerBatchSize = 20;
}
