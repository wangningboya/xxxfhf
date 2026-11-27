package com.xxxfhf.meal.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code msg_sec_check} 2.0 返回体解析（技术文档 4.18）。
 *
 * <p>这段是曾经的缺陷点：请求体是 2.0（version = 2），判定却按 1.0 只认 errcode = 87014，
 * 而 2.0 的违规结论在 {@code result.suggest} 里、errcode 恒为 0，于是所有违规内容都被放行。
 *
 * <p>{@link ContentSecurityServiceTest} 把 {@link WxApiClient} 整个 mock 掉了，验的是
 * 「错误码 → 放行/拒绝」，覆盖不到这里；本类专门盯「微信返回体 → 错误码」这一层。
 */
class WxApiClientSecCheckTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private int judge(String json) {
        return WxApiClient.judgeSecCheck(mapper.readTree(json));
    }

    @Test
    void riskySuggestIsRejected() {
        // 官方文档的返回示例：违规时 errcode 仍是 0，结论在 result.suggest
        String body = """
                {
                  "errcode": 0,
                  "errmsg": "ok",
                  "result": { "suggest": "risky", "label": 20001 },
                  "trace_id": "60ae120f-371d5872-7941a05b"
                }
                """;

        assertThat(judge(body)).isEqualTo(WxApiClient.CONTENT_RISKY);
    }

    @Test
    void reviewSuggestGoesThrough() {
        // 口径：只认 risky，review 放行。这里 detail 里有一条策略判 risky，
        // 但综合 conclusion 是 review —— 只读综合 suggest，不看 detail
        String body = """
                {
                  "errcode": 0,
                  "result": { "suggest": "review", "label": 20012 },
                  "detail": [
                    { "strategy": "keyword", "errcode": 0, "suggest": "risky", "label": 20006, "level": 90 }
                  ]
                }
                """;

        assertThat(judge(body)).isZero();
    }

    @Test
    void passSuggestGoesThrough() {
        String body = """
                { "errcode": 0, "errmsg": "ok", "result": { "suggest": "pass", "label": 100 } }
                """;

        assertThat(judge(body)).isZero();
    }

    @Test
    void wxErrcodeIsPassedThroughForBestEffortPolicy() {
        // 凭证失效等异常：原样把错误码交给上层，按「异常放行」处理，不能当成违规拒绝
        String body = """
                { "errcode": 40001, "errmsg": "invalid credential, access_token is invalid or not latest" }
                """;

        assertThat(judge(body)).isEqualTo(40001);
    }
}
