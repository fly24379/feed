package com.example.feed.service;

import com.example.feed.api.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class VerificationCodeSender {
    private static final Logger log = LoggerFactory.getLogger(VerificationCodeSender.class);

    private final RestClient restClient;
    private final String emailWebhookUrl;
    private final String smsWebhookUrl;
    private final String webhookToken;
    private final boolean logCode;

    public VerificationCodeSender(RestClient.Builder builder,
                                  @Value("${feed.security.verification.email-webhook-url:}") String emailWebhookUrl,
                                  @Value("${feed.security.verification.sms-webhook-url:}") String smsWebhookUrl,
                                  @Value("${feed.security.verification.webhook-token:}") String webhookToken,
                                  @Value("${feed.security.verification.log-code:false}") boolean logCode) {
        this.restClient = builder.build();
        this.emailWebhookUrl = emailWebhookUrl;
        this.smsWebhookUrl = smsWebhookUrl;
        this.webhookToken = webhookToken;
        this.logCode = logCode;
    }

    public void send(String channel, String target, String code, String purpose) {
        String url = "EMAIL".equals(channel) ? emailWebhookUrl : smsWebhookUrl;
        if (url != null && !url.isBlank()) {
            try {
                RestClient.RequestBodySpec request = restClient.post().uri(url)
                        .contentType(MediaType.APPLICATION_JSON);
                if (webhookToken != null && !webhookToken.isBlank()) {
                    request.header("Authorization", "Bearer " + webhookToken);
                }
                request.body(Map.of("channel", channel, "target", target,
                        "code", code, "purpose", purpose)).retrieve().toBodilessEntity();
                return;
            } catch (RuntimeException exception) {
                log.warn("Verification delivery failed for channel={} target={}", channel, mask(target), exception);
                throw new BadRequestException("验证码发送失败，请稍后重试");
            }
        }
        if (!logCode) {
            throw new BadRequestException("验证码发送服务尚未配置");
        }
        log.info("Development verification code: channel={} target={} purpose={} code={}",
                channel, mask(target), purpose, code);
    }

    private String mask(String target) {
        if (target == null || target.length() < 4) {
            return "***";
        }
        return target.substring(0, 2) + "***" + target.substring(target.length() - 2);
    }
}
