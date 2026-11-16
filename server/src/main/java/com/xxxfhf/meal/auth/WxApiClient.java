package com.xxxfhf.meal.auth;

import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.ErrorCode;
import com.xxxfhf.meal.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信服务端接口调用。
 *
 * 用 JDK 自带 HttpClient 而不是 RestClient/RestTemplate：
 * 这里只有「一个 GET 请求」的需求，JDK 实现零额外依赖、不受 Spring 版本改名影响。
 */
@Component
public class WxApiClient {

    private static final Logger log = LoggerFactory.getLogger(WxApiClient.class);

    private final AppProperties.Wx wx;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** access_token 进程内缓存，见 accessToken()。 */
    private volatile String cachedToken;
    private volatile long tokenExpireAt;

    public WxApiClient(AppProperties props, ObjectMapper objectMapper) {
        this.wx = props.getWx();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(wx.getTimeoutSeconds()))
                .build();
    }

    /**
     * wx.login 的 code 换 openid + session_key。
     *
     * session_key 只在这里返回给服务端内部使用，绝不进入任何响应体。
     */
    public WxSession code2Session(String code) {
        String url = wx.getCode2SessionUrl()
                + "?appid=" + encode(wx.getAppId())
                + "&secret=" + encode(wx.getAppSecret())
                + "&js_code=" + encode(code)
                + "&grant_type=authorization_code";

        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(wx.getTimeoutSeconds()))
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.WX_API_ERROR, "微信登录请求被中断，请重试");
        } catch (Exception ex) {
            // 网络类异常只记日志，对外给可读文案
            log.error("调用 code2Session 失败", ex);
            throw new BizException(ErrorCode.WX_API_ERROR, "微信登录服务暂时不可用，请稍后重试");
        }

        if (response.statusCode() != 200) {
            log.error("code2Session 返回 HTTP {}，body={}", response.statusCode(), response.body());
            throw new BizException(ErrorCode.WX_API_ERROR, "微信登录服务暂时不可用，请稍后重试");
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(response.body());
        } catch (Exception ex) {
            log.error("解析 code2Session 响应失败，body={}", response.body(), ex);
            throw new BizException(ErrorCode.WX_API_ERROR, "微信登录响应异常，请重试");
        }

        int errcode = node.path("errcode").asInt(0);
        if (errcode != 0) {
            // errmsg 可能含英文内部信息，只记日志不回前端
            log.warn("code2Session 业务错误 errcode={}, errmsg={}", errcode, node.path("errmsg").asText(""));
            throw new BizException(ErrorCode.WX_API_ERROR, "微信登录失败（" + errcode + "），请重新进入小程序");
        }

        String openid = node.path("openid").asText("");
        if (openid.isBlank()) {
            log.error("code2Session 未返回 openid，body={}", response.body());
            throw new BizException(ErrorCode.WX_API_ERROR, "微信登录响应异常，请重试");
        }
        return new WxSession(openid, node.path("session_key").asText(""));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /* ==================== 订阅消息 ==================== */

    /**
     * 接口调用凭证。微信有效期 7200 秒，这里提前 5 分钟作废，避免卡在失效临界点。
     *
     * 进程内缓存：access_token 每天只取几百次，但每次发消息都取会很快触发频率限制。
     * 加锁只在「缓存为空或已过期」这条路径上，正常情况下是一次 volatile 读。
     */
    public String accessToken() {
        String token = cachedToken;
        if (token != null && System.currentTimeMillis() < tokenExpireAt) {
            return token;
        }
        synchronized (this) {
            if (cachedToken != null && System.currentTimeMillis() < tokenExpireAt) {
                return cachedToken;
            }
            String url = wx.getTokenUrl()
                    + "?grant_type=client_credential"
                    + "&appid=" + encode(wx.getAppId())
                    + "&secret=" + encode(wx.getAppSecret());
            JsonNode node = getJson(url, "获取 access_token");
            String fresh = node.path("access_token").asText("");
            if (fresh.isBlank()) {
                int errcode = node.path("errcode").asInt(-1);
                log.error("获取 access_token 失败 errcode={} errmsg={}", errcode, node.path("errmsg").asText(""));
                throw new BizException(ErrorCode.WX_API_ERROR, "微信接口凭证获取失败，请稍后重试");
            }
            int expiresIn = node.path("expires_in").asInt(7200);
            cachedToken = fresh;
            tokenExpireAt = System.currentTimeMillis() + Math.max(expiresIn - 300, 60) * 1000L;
            return fresh;
        }
    }

    /**
     * 发送一条订阅消息。
     *
     * 微信侧的失败（用户没授权 / 授权次数用完 / 模板被删）都是**正常业务现象**，
     * 这里只记日志并返回失败，绝不抛异常 —— 通知发不出去不能连累下单、接单这些主流程。
     *
     * @param data 模板关键词 → 值，如 {"thing1": {"value": "红烧肉饭×2"}}
     * @return 是否成功；失败原因看日志
     */
    public boolean sendSubscribe(String openid, String templateId, String page, Map<String, String> data) {
        if (openid == null || openid.isBlank() || templateId == null || templateId.isBlank()) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", openid);
        body.put("template_id", templateId);
        if (page != null && !page.isBlank()) {
            body.put("page", page);
        }
        body.put("lang", "zh_CN");
        Map<String, Object> wrapped = new LinkedHashMap<>();
        data.forEach((key, value) -> wrapped.put(key, Map.of("value", value == null ? "" : value)));
        body.put("data", wrapped);

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            log.error("订阅消息请求体序列化失败 templateId={}", templateId, ex);
            return false;
        }

        try {
            String token = accessToken();
            String url = wx.getSubscribeSendUrl() + "?access_token=" + encode(token);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(wx.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("订阅消息返回 HTTP {}，templateId={}", response.statusCode(), templateId);
                return false;
            }
            JsonNode node = objectMapper.readTree(response.body());
            int errcode = node.path("errcode").asInt(-1);
            if (errcode == 0) {
                return true;
            }
            // 43101 = 用户未订阅/订阅次数已用完，属预期内，用 INFO 不刷 ERROR
            if (errcode == 43101) {
                log.info("用户未授权或订阅次数已用完，跳过本次推送 templateId={}", templateId);
            } else {
                log.warn("订阅消息发送失败 errcode={} errmsg={} templateId={}",
                        errcode, node.path("errmsg").asText(""), templateId);
            }
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            log.warn("订阅消息发送异常 templateId={}", templateId, ex);
            return false;
        }
    }

    /** 统一 GET + 解析 JSON，供凭证类接口复用。 */
    private JsonNode getJson(String url, String action) {
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(wx.getTimeoutSeconds()))
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.WX_API_ERROR, action + "被中断，请重试");
        } catch (Exception ex) {
            log.error("{}失败", action, ex);
            throw new BizException(ErrorCode.WX_API_ERROR, "微信服务暂时不可用，请稍后重试");
        }
        if (response.statusCode() != 200) {
            log.error("{}返回 HTTP {}，body={}", action, response.statusCode(), response.body());
            throw new BizException(ErrorCode.WX_API_ERROR, "微信服务暂时不可用，请稍后重试");
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception ex) {
            log.error("{}响应解析失败，body={}", action, response.body(), ex);
            throw new BizException(ErrorCode.WX_API_ERROR, "微信接口响应异常，请重试");
        }
    }

    public record WxSession(String openid, String sessionKey) {
    }
}
