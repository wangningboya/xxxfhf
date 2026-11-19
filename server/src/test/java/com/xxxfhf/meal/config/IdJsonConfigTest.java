package com.xxxfhf.meal.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口层 id 的形态：id 与 xxxId / xxxIds 序列化成字符串，其余 long 保持数字。
 *
 * <p>为什么值得单独测：这条规则一旦配错方向，前端要么拿到数字（严格比较全落空），
 * 要么把 total / dayAmount 也拿到字符串（Math.ceil、金额格式化全崩）。两种都只在
 * 真机联调时才暴露，成本很高，所以在纯单元测试里钉死。
 */
class IdJsonConfigTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(IdJsonConfig.idModule())
            .build();

    /** 覆盖四种情况：id、xxxId、id 列表、非 id 的 long（必须保持数字） */
    record Sample(Long id, Long comboId, List<Long> roleIds,
                  long total, long page, long dayAmount, Long orderCount,
                  String orderNo) {
    }

    private JsonNode toJson(Sample sample) {
        return mapper.readTree(mapper.writeValueAsString(sample));
    }

    @Test
    @DisplayName("id 与 xxxId 写成字符串，id 列表写成字符串数组")
    void idsBecomeStrings() {
        JsonNode node = toJson(new Sample(5L, 7L, List.of(1L, 2L), 100L, 1L, 25800L, 3L, "20261118-000001"));

        assertThat(node.get("id").isString()).as("id 必须是字符串").isTrue();
        assertThat(node.get("id").asString()).isEqualTo("5");
        assertThat(node.get("comboId").asString()).isEqualTo("7");
        // 列表要逐项转，不能整体 String.valueOf 成一个 "[1, 2]" 的字符串
        assertThat(node.get("roleIds").isArray()).isTrue();
        assertThat(node.get("roleIds")).hasSize(2);
        assertThat(node.get("roleIds").get(0).asString()).isEqualTo("1");
        assertThat(node.get("roleIds").get(1).asString()).isEqualTo("2");
    }

    @Test
    @DisplayName("分页与金额这类 long 仍是数字，不能被 id 规则误伤")
    void nonIdNumbersStayNumbers() {
        JsonNode node = toJson(new Sample(5L, 7L, List.of(1L), 100L, 1L, 25800L, 3L, "20261118-000001"));

        assertThat(node.get("total").isNumber()).as("total 转成字符串会让前端分页算错").isTrue();
        assertThat(node.get("total").asLong()).isEqualTo(100L);
        assertThat(node.get("page").isNumber()).isTrue();
        assertThat(node.get("dayAmount").asLong()).isEqualTo(25800L);
        // 名字不像 id 的 Long 也不能转
        assertThat(node.get("orderCount").isNumber()).isTrue();
        assertThat(node.get("orderNo").asString()).isEqualTo("20261118-000001");
    }

    @Test
    @DisplayName("id 为 null 时输出 null，不输出 \"null\"")
    void nullIdStaysNull() {
        JsonNode node = toJson(new Sample(null, null, null, 0L, 1L, 0L, null, null));

        assertThat(node.get("id").isNull()).isTrue();
        assertThat(node.get("roleIds").isNull()).isTrue();
    }

    @Test
    @DisplayName("入参仍然是 Long：前端继续传字符串也能反序列化")
    void stringIdsStillDeserialize() {
        // 基本类型 long 字段必须给值：本测试只关心 id 的字符串形态
        Sample sample = mapper.readValue(
                "{\"id\":\"5\",\"comboId\":\"7\",\"roleIds\":[\"1\",\"2\"],"
                        + "\"total\":100,\"page\":1,\"dayAmount\":0,\"orderNo\":\"20261118-000001\"}",
                Sample.class);

        assertThat(sample.id()).isEqualTo(5L);
        assertThat(sample.comboId()).isEqualTo(7L);
        assertThat(sample.roleIds()).containsExactly(1L, 2L);
    }
}
