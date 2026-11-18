package com.xxxfhf.meal.common;

import com.xxxfhf.meal.auth.WxApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用户自由输入文本的内容安全检测。
 *
 * <p>只接微信 {@code msg_sec_check}，不自建敏感词库：本店是熟人封闭群，自建词库误伤率高
 * （生僻字、方言、菜品名都会被误杀），维护成本还比调接口大。微信接口免费、按 openid 判定，
 * 够用。
 *
 * <p>策略是「异常放行」：接口超时、返回未知错误码、没配 appSecret，都当作通过并记日志。
 * 内容安全属于附加风控，宁可漏掉一条违规文本，也不能因为微信接口抖动让用户改不了昵称。
 */
@Service
public class ContentSecurityService {

    private static final Logger log = LoggerFactory.getLogger(ContentSecurityService.class);

    private final WxApiClient wxApiClient;

    public ContentSecurityService(WxApiClient wxApiClient) {
        this.wxApiClient = wxApiClient;
    }

    /**
     * 检测一段用户输入是否放行。
     *
     * @param openid  提交人 openid，用于微信侧判定（可空）
     * @param content 待检测文本
     * @return true = 放行（干净，或检测接口不可用）；false = 确认含违规内容
     */
    public boolean pass(String openid, String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        int errcode = wxApiClient.msgSecCheck(content, openid);
        if (errcode == WxApiClient.CONTENT_RISKY) {
            return false;
        }
        if (errcode != 0) {
            log.warn("内容安全检测不可用，按放行处理 errcode={}", errcode);
        }
        return true;
    }
}
