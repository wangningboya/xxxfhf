package com.xxxfhf.meal.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 主键的对外形态：库里是自增 BIGINT，接口上仍然是字符串。
 *
 * <p>为什么改回字符串：小程序把 id 塞进页面参数（<code>?id=12</code>），取回来必然是
 * 字符串，页面里的 <code>a.id === editId</code> 这类严格比较全部落空；后台也把 id 当
 * key 用。与其在 15 处前端逐个改，不如让接口契约保持不变 —— 契约的稳定比「数字更自然」重要。
 *
 * <p>为什么不能全局配 Long → String：本项目的 long 不止是 id。分页的
 * <code>total/page/pageSize</code>、看板的 <code>dayAmount</code>、注销预览的
 * <code>orderCount</code> 都是数值，转成字符串会让前端 <code>Math.ceil</code> 和
 * 金额格式化直接出错。所以只对「名字像 id、类型是 Long」的属性生效：
 * <ul>
 *   <li>名字匹配 <code>id</code> / <code>xxxId</code> / <code>ids</code> / <code>xxxIds</code></li>
 *   <li>且声明类型是 <code>Long</code>（基本类型 long 不算，天然挡掉 total 之类）</li>
 *   <li>或声明类型是 <code>Collection&lt;Long&gt;</code>（roleIds、specGroupIds 这类）</li>
 * </ul>
 *
 * <p>只影响 HTTP 出参。入参不用做任何事：Jackson 本来就把 <code>"12"</code> 反序列化成
 * <code>Long 12</code>，所以 DTO 里直接写 Long 就能兼容前端继续传字符串。
 *
 * <p>也不影响库里的 JSON 列：MyBatis-Plus 的 Jackson3TypeHandler 用的是它自己的
 * ObjectMapper，改不到这里。
 */
@Configuration
public class IdJsonConfig {

    /** id / xxxId / ids / xxxIds：区分大小写地不敏感，只认「结尾是 id 或 ids」 */
    private static final Pattern ID_PROPERTY = Pattern.compile("(?i)^id$|^ids$|.*ids?$");

    private static final String MODULE_NAME = "id-as-string";

    @Bean
    JsonMapperBuilderCustomizer idAsStringCustomizer() {
        return builder -> builder.addModule(idModule());
    }

    /** 拆出来是为了单元测试能直接建一个干净的 JsonMapper 验证行为 */
    static SimpleModule idModule() {
        SimpleModule module = new SimpleModule(MODULE_NAME);
        module.setSerializerModifier(new IdPropertyModifier());
        return module;
    }

    private static final class IdPropertyModifier extends ValueSerializerModifier {

        @Override
        public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                         BeanDescription.Supplier beanDesc,
                                                         List<BeanPropertyWriter> properties) {
            for (BeanPropertyWriter writer : properties) {
                if (ID_PROPERTY.matcher(writer.getName()).matches() && isIdType(writer.getType())) {
                    writer.assignSerializer(IdAsTextSerializer.INSTANCE);
                }
            }
            return properties;
        }

        private static boolean isIdType(JavaType type) {
            if (type == null) {
                return false;
            }
            if (type.hasRawClass(Long.class)) {
                return true;
            }
            JavaType content = type.getContentType();
            return content != null
                    && content.hasRawClass(Long.class)
                    && (type.isCollectionLikeType() || type.isArrayType());
        }
    }

    /**
     * 单个 id 写成字符串；id 列表写成字符串数组。
     *
     * <p>列表要单独处理：<code>assignSerializer</code> 换的是整个属性的序列化器，
     * 若只写 <code>String.valueOf(collection)</code> 会输出成 <code>[1, 2]</code> 的
     * 字符串，而不是数组。
     */
    private static final class IdAsTextSerializer extends ValueSerializer<Object> {

        private static final IdAsTextSerializer INSTANCE = new IdAsTextSerializer();

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt)
                throws JacksonException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            if (value instanceof Collection<?> items) {
                gen.writeStartArray();
                for (Object item : items) {
                    if (item == null) {
                        gen.writeNull();
                    } else {
                        gen.writeString(String.valueOf(item));
                    }
                }
                gen.writeEndArray();
                return;
            }
            gen.writeString(String.valueOf(value));
        }
    }
}
