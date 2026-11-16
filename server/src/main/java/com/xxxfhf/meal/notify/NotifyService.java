package com.xxxfhf.meal.notify;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.auth.WxApiClient;
import com.xxxfhf.meal.config.ConfigKey;
import com.xxxfhf.meal.config.ConfigService;
import com.xxxfhf.meal.order.Order;
import com.xxxfhf.meal.order.OrderItem;
import com.xxxfhf.meal.user.User;
import com.xxxfhf.meal.user.UserMapper;
import com.xxxfhf.meal.user.UserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订阅消息推送。
 *
 * 三条不可动摇的规则（对应 F-I02 / F-I03）：
 *
 * 1. **通知永远只是增强**：任何失败都只记日志，绝不影响下单、接单、拒单这些主流程，
 *    更不允许因为推送失败而回滚事务。用户看到的一切以小程序内的订单状态为准。
 * 2. **在事务提交后才发**：HTTP 请求短则几百毫秒长则几秒，放在事务里会一直占着数据库连接。
 * 3. **模板没配就静默跳过**：模板 ID 要先去小程序后台申请（F-I01），
 *    申请下来之前系统必须能正常跑，不能因为缺配置就报错。
 *
 * 模板字段是可配的：微信各个模板的关键词编号（thing1 / character_string2 / amount3 …）
 * 由申请到的模板决定，写死在代码里意味着换个模板就得改代码重发版。
 * 所以配置项 subscribeTemplates 里存「关键词 → 变量名」的映射，代码只负责提供变量。
 */
@Service
public class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final WxApiClient wxApiClient;
    private final ConfigService configService;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    public NotifyService(WxApiClient wxApiClient, ConfigService configService,
                         UserMapper userMapper, ObjectMapper objectMapper) {
        this.wxApiClient = wxApiClient;
        this.configService = configService;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    /* ==================== 对外：五类通知 ==================== */

    /** 用户下单成功 → 商家 */
    public void newOrder(Order order) {
        fire(NotifyType.NEW_ORDER, order);
    }

    /** 商家接单 → 用户 */
    public void accepted(Order order) {
        fire(NotifyType.ACCEPTED, order);
    }

    /** 商家拒单 → 用户 */
    public void rejected(Order order) {
        fire(NotifyType.REJECTED, order);
    }

    /** 订单可取餐 → 用户 */
    public void ready(Order order) {
        fire(NotifyType.READY, order);
    }

    /** 用户取消（截单前自撤 / 取消申请被同意）→ 商家 */
    public void cancelled(Order order) {
        fire(NotifyType.CANCELLED, order);
    }

    /* ==================== 内部 ==================== */

    /**
     * 事务内调用时推迟到提交后执行；没有事务（如定时任务外的直接调用）就立即发。
     *
     * 为什么必须等提交：订单还没落库就推「已接单」，用户点进小程序可能查不到这单；
     * 更糟的是事务后续回滚了，通知却已经发出去了。
     */
    private void fire(NotifyType type, Order order) {
        if (order == null) {
            return;
        }
        Runnable task = () -> {
            try {
                doFire(type, order);
            } catch (Exception ex) {
                // 兜底：这里再漏出去就会影响主流程
                log.warn("推送 {} 失败 orderNo={}", type.code(), order.getOrderNo(), ex);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private void doFire(NotifyType type, Order order) {
        Template template = templates().get(type.code());
        if (template == null || template.templateId() == null || template.templateId().isBlank()) {
            // 模板还没申请下来，静默跳过（F-I01 未完成时系统也要能跑）
            log.debug("订阅消息模板未配置，跳过 type={}", type.code());
            return;
        }

        List<String> openids = resolveOpenids(type.recipient(), order);
        if (openids.isEmpty()) {
            log.debug("没有可推送的接收人 type={} orderNo={}", type.code(), order.getOrderNo());
            return;
        }

        Map<String, String> vars = buildVars(order);
        String page = fillPage(template.page(), order);

        int ok = 0;
        for (String openid : openids) {
            Map<String, String> data = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : template.fields().entrySet()) {
                String raw = vars.get(entry.getValue());
                data.put(entry.getKey(), clamp(entry.getKey(), raw));
            }
            if (wxApiClient.sendSubscribe(openid, template.templateId(), page, data)) {
                ok++;
            }
        }
        if (ok > 0) {
            log.info("推送 {} 成功 orderNo={} 接收人={}", type.code(), order.getOrderNo(), ok);
        }
    }

    /**
     * 收件人 openid。
     *
     * 商家侧取「user_type = merchant 且绑定了 openid」的全部账号：
     * 商家可能不止一个人，也不该由代码假定只有主账号收通知。
     */
    private List<String> resolveOpenids(NotifyType.Recipient recipient, Order order) {
        if (recipient == NotifyType.Recipient.MERCHANT) {
            List<User> merchants = userMapper.selectList(Wrappers.<User>lambdaQuery()
                    .select(User::getId, User::getOpenid)
                    .eq(User::getUserType, UserType.MERCHANT)
                    .isNotNull(User::getOpenid));
            List<String> openids = new ArrayList<>();
            for (User merchant : merchants) {
                if (merchant.getOpenid() != null && !merchant.getOpenid().isBlank()) {
                    openids.add(merchant.getOpenid());
                }
            }
            return openids;
        }
        User user = order.getUserId() == null ? null : userMapper.selectById(order.getUserId());
        if (user == null || user.getOpenid() == null || user.getOpenid().isBlank()) {
            return List.of();
        }
        return List.of(user.getOpenid());
    }

    /**
     * 模板可用的变量。字段映射里写变量名，不写业务逻辑。
     *
     * 变量清单（改代码前先看一眼文档，前端与运维都按这份清单配模板）：
     * shopName 店铺名 / orderNo 订单号 / summary 盒饭摘要 / qty 总份数 / amount 金额（元）
     * serveDate 供餐日 / pickupCode 取餐码 / pickupSlot 取餐时段 / deliveryType 取送方式
     * status 状态文案 / reason 拒单原因 / nickname 下单人昵称 / remark 备注 / time 当前时间
     */
    private Map<String, String> buildVars(Order order) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("shopName", configService.get(ConfigKey.SHOP_NAME, "本店"));
        vars.put("orderNo", nvl(order.getOrderNo()));
        vars.put("summary", summaryOf(order));
        vars.put("qty", String.valueOf(totalQty(order)));
        vars.put("amount", yuan(order.getTotalAmount()));
        vars.put("serveDate", order.getServeDate() == null ? "" : order.getServeDate().toString());
        vars.put("pickupCode", nvl(order.getPickupCode()));
        vars.put("pickupSlot", nvl(order.getPickupSlot()));
        vars.put("deliveryType", "delivery".equals(order.getDeliveryType()) ? "配送" : "自取");
        vars.put("status", order.getStatus() == null ? "" : order.getStatus());
        vars.put("reason", nvl(order.getRejectReason()));
        vars.put("remark", nvl(order.getRemark()));
        vars.put("time", LocalDateTime.now().format(TIME_FMT));
        vars.put("serveDateDay", serveDateDay(order.getServeDate()));

        User user = order.getUserId() == null ? null : userMapper.selectById(order.getUserId());
        vars.put("nickname", user == null || user.getNickName() == null || user.getNickName().isBlank()
                ? "群成员" : user.getNickName());
        return vars;
    }

    /** 「红烧肉饭×2、素菜拼盘×1」。超长由 clamp 统一截断。 */
    private static String summaryOf(Order order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(order.getItems().size());
        for (OrderItem item : order.getItems()) {
            parts.add(nvl(item.name()) + "×" + item.qtyOrZero());
        }
        return String.join("、", parts);
    }

    private static int totalQty(Order order) {
        if (order.getItems() == null) {
            return 0;
        }
        return order.getItems().stream().mapToInt(OrderItem::qtyOrZero).sum();
    }

    private static String yuan(Integer cents) {
        int value = cents == null ? 0 : cents;
        return String.format("%d.%02d", value / 100, Math.abs(value % 100));
    }

    private static String serveDateDay(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    /** 页面路径里的 {orderId} 换成真实订单 ID，省得每个模板都写一段拼接逻辑。 */
    private static String fillPage(String page, Order order) {
        if (page == null || page.isBlank()) {
            return null;
        }
        return page.replace("{orderId}", order.getId() == null ? "" : order.getId());
    }

    /**
     * 按微信的长度规则截断。
     *
     * 不截断的后果不是「显示不全」，是**整条消息发送失败**（47003 参数超限），
     * 所以宁可截一半也不能发不出去。规则：thing 20 / phrase 5 / character_string 32 / 其余 20。
     */
    private static String clamp(String key, String value) {
        String text = value == null ? "" : value.trim();
        int max = 20;
        if (key.startsWith("thing")) {
            max = 20;
        } else if (key.startsWith("phrase")) {
            max = 5;
        } else if (key.startsWith("character_string")) {
            max = 32;
        } else if (key.startsWith("amount")) {
            max = 10;
        } else if (key.startsWith("time") || key.startsWith("date")) {
            max = 20;
        }
        if (text.isEmpty()) {
            // 空值会让微信判为非法，给一个占位符
            return "-";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /* ---------------- 模板配置 ---------------- */

    /**
     * 解析配置项 subscribeTemplates。结构：
     * <pre>
     * {
     *   "newOrder": {
     *     "templateId": "模板 ID",
     *     "page": "pages/merchant/order/detail?id={orderId}",
     *     "fields": { "character_string1": "orderNo", "thing2": "summary", "amount3": "amount" }
     *   }
     * }
     * </pre>
     * 解析失败一律返回空表 —— 没有模板就是不发通知，这是安全的一侧。
     */
    private Map<String, Template> templates() {
        String raw = configService.get(ConfigKey.SUBSCRIBE_TEMPLATES);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = objectMapper.readValue(raw, Map.class);
            Map<String, Template> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : root.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> node)) {
                    continue;
                }
                Map<String, String> fields = new LinkedHashMap<>();
                if (node.get("fields") instanceof Map<?, ?> fieldMap) {
                    for (Map.Entry<?, ?> f : fieldMap.entrySet()) {
                        fields.put(String.valueOf(f.getKey()),
                                f.getValue() == null ? "" : String.valueOf(f.getValue()));
                    }
                }
                result.put(entry.getKey(), new Template(
                        str(node.get("templateId")),
                        str(node.get("page")),
                        fields));
            }
            return result;
        } catch (Exception ex) {
            log.error("配置项 {} 不是合法 JSON，按未配置处理", ConfigKey.SUBSCRIBE_TEMPLATES, ex);
            return Map.of();
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * @param templateId 微信后台申请到的模板 ID
     * @param page       点击消息后跳转的小程序页面，可含 {orderId}
     * @param fields     模板关键词 → 变量名
     */
    private record Template(String templateId, String page, Map<String, String> fields) {
    }
}
