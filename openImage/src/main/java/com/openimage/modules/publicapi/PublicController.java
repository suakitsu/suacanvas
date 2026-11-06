package com.openimage.modules.publicapi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.common.ApiResponse;
import com.openimage.domain.entity.SystemSettingEntity;
import com.openimage.domain.mapper.SystemSettingMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/public")
public class PublicController {
    private final SystemSettingMapper settings;
    private final ObjectMapper json;

    public PublicController(SystemSettingMapper settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @GetMapping("/ping")
    public ApiResponse<Map<String, String>> ping() {
        return ApiResponse.ok(Map.of("service", "picpilot", "status", "ok"));
    }

    @GetMapping("/benefit-config")
    public ApiResponse<Map<String, Object>> benefitConfig() {
        return ApiResponse.ok(benefitConfigValue());
    }

    private Map<String, Object> benefitConfigValue() {
        SystemSettingEntity setting = settings.selectOne(new LambdaQueryWrapper<SystemSettingEntity>()
            .eq(SystemSettingEntity::getDeleted, 0)
            .eq(SystemSettingEntity::getSettingKey, "benefit_onboarding"));
        if (setting == null || setting.getSettingValue() == null || setting.getSettingValue().isBlank()) {
            return defaultBenefitConfig();
        }
        try {
            Map<String, Object> value = json.readValue(setting.getSettingValue(), new TypeReference<>() {});
            Map<String, Object> merged = defaultBenefitConfig();
            merged.putAll(value);
            return merged;
        } catch (Exception ignored) {
            return defaultBenefitConfig();
        }
    }

    private Map<String, Object> defaultBenefitConfig() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("enabled", true);
        row.put("title", "新人积分兑换码");
        row.put("description", "加入 QQ 群或微信群，联系管理员领取兑换码；到积分页兑换后即可用积分体验生成流程。");
        row.put("qq_group", "619616946");
        row.put("qq_link", "https://qm.qq.com/q/Hks6HegucA");
        row.put("wechat_group", "");
        row.put("qq_qr_url", "");
        row.put("wechat_qr_url", "");
        row.put("redeem_tutorial", "1. 加入社群 2. 联系管理员领取兑换码 3. 到积分充值页兑换积分 4. 回到工作台生成图片");
        row.put("free_highlight_label", "兑换码会直接兑换成积分，生成仍按积分扣费");
        return row;
    }
}
