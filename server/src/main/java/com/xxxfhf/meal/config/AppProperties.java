package com.xxxfhf.meal.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 全部应用配置集中在这里，值来自环境变量（见 .env.example）。
 *
 * 设计原则：启动时集中校验、缺失即失败，不让「配置漏了」拖到运行期才暴露。
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Wx wx = new Wx();
    private final Upload upload = new Upload();
    private final Schedule schedule = new Schedule();
    private final Web web = new Web();
    private final Admin admin = new Admin();
    private final Qr qr = new Qr();
    private final RateLimit rateLimit = new RateLimit();
    private final ContactPhone contactPhone = new ContactPhone();
    private final Invite invite = new Invite();
    private final Auth auth = new Auth();
    private final Message message = new Message();

    @PostConstruct
    void validate() {
        List<String> missing = new ArrayList<>();
        if (jwt.getSecret() == null || jwt.getSecret().isBlank()) {
            missing.add("APP_JWT_SECRET");
        } else if (jwt.getSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "APP_JWT_SECRET 长度不足 32 字节（HS256 要求），当前 "
                            + jwt.getSecret().getBytes(StandardCharsets.UTF_8).length + " 字节");
        }
        if (wx.getAppId() == null || wx.getAppId().isBlank()) {
            missing.add("APP_WX_APP_ID");
        }
        if (wx.getAppSecret() == null || wx.getAppSecret().isBlank()) {
            missing.add("APP_WX_APP_SECRET");
        }
        if (upload.getDir() == null || upload.getDir().isBlank()) {
            missing.add("APP_UPLOAD_DIR");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("缺少必需的环境变量：" + String.join(", ", missing)
                    + "（参考 server/.env.example）");
        }
        if (jwt.getExpireDays() <= 0 || jwt.getExpireDays() > 30) {
            throw new IllegalStateException("APP_JWT_EXPIRE_DAYS 必须在 1~30 之间");
        }
        if (upload.getMaxSizeMb() < 1 || upload.getMaxSizeMb() > 50) {
            throw new IllegalStateException("APP_UPLOAD_MAX_SIZE_MB 必须在 1~50 之间");
        }
        if (upload.getMaxEdge() < 200 || upload.getMaxEdge() > 4000) {
            throw new IllegalStateException("APP_UPLOAD_MAX_EDGE 必须在 200~4000 之间");
        }
        if (upload.getJpegQuality() <= 0.1f || upload.getJpegQuality() > 1.0f) {
            throw new IllegalStateException("APP_UPLOAD_JPEG_QUALITY 必须在 0.1~1.0 之间");
        }
        if (qr.getKeepDays() < 1) {
            throw new IllegalStateException("APP_QR_KEEP_DAYS 必须 ≥ 1（0 或负数会让清理任务删掉刚生成的码）");
        }
        if (rateLimit.getLimit() < 1 || rateLimit.getLoginLimit() < 1) {
            throw new IllegalStateException("限流阈值必须 ≥ 1（APP_RATE_LIMIT_LIMIT / APP_RATE_LIMIT_LOGIN_LIMIT）");
        }
        if (rateLimit.getWindowSeconds() < 1) {
            throw new IllegalStateException("APP_RATE_LIMIT_WINDOW_SECONDS 必须 ≥ 1");
        }
        if (rateLimit.getLoginLimit() > rateLimit.getLimit()) {
            // 免登录入口比登录用户还宽松等于没限：这两个入口会写库、会消耗微信接口配额
            throw new IllegalStateException("APP_RATE_LIMIT_LOGIN_LIMIT 不能大于 APP_RATE_LIMIT_LIMIT");
        }
        if (rateLimit.getJoinLimit() < 1) {
            throw new IllegalStateException("APP_RATE_LIMIT_JOIN_LIMIT 必须 ≥ 1（0 会让所有新用户都进不来）");
        }
        if (rateLimit.getJoinLimit() > rateLimit.getLimit()) {
            throw new IllegalStateException("APP_RATE_LIMIT_JOIN_LIMIT 不能大于 APP_RATE_LIMIT_LIMIT");
        }
        // 联系顾客：限次是隐私合规的一部分（号码按需、限次、留痕），配错等于把闸门拆了
        if (contactPhone.getMaxPerOrder24h() < 1) {
            throw new IllegalStateException("APP_CONTACT_PHONE_MAX_PER_ORDER_24H 必须 ≥ 1");
        }
        if (contactPhone.getMaxPerOperatorDay() < 1) {
            throw new IllegalStateException("APP_CONTACT_PHONE_MAX_PER_OPERATOR_DAY 必须 ≥ 1");
        }
        if (contactPhone.getWindowDays() < 0) {
            // 0 是合法值：表示订单一结束就不可再调取，只在营业时段内可联系
            throw new IllegalStateException("APP_CONTACT_PHONE_WINDOW_DAYS 必须 ≥ 0");
        }
        if (invite.getLock().getMaxFailures() < 1 || invite.getLock().getMinutes() < 1) {
            throw new IllegalStateException(
                    "APP_INVITE_LOCK_MAX_FAILURES / APP_INVITE_LOCK_MINUTES 必须 ≥ 1");
        }
        if (auth.getLoginLock().getMaxFailures() < 1 || auth.getLoginLock().getMinutes() < 1) {
            throw new IllegalStateException(
                    "APP_AUTH_LOGIN_LOCK_MAX_FAILURES / APP_AUTH_LOGIN_LOCK_MINUTES 必须 ≥ 1");
        }
        if (message.getHomeUnreadLimit() < 1) {
            throw new IllegalStateException("APP_MESSAGE_HOME_UNREAD_LIMIT 必须 ≥ 1");
        }
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Wx getWx() {
        return wx;
    }

    public Upload getUpload() {
        return upload;
    }

    public Schedule getSchedule() {
        return schedule;
    }

    public Web getWeb() {
        return web;
    }

    public Admin getAdmin() {
        return admin;
    }

    public Qr getQr() {
        return qr;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public ContactPhone getContactPhone() {
        return contactPhone;
    }

    public Invite getInvite() {
        return invite;
    }

    public Auth getAuth() {
        return auth;
    }

    public Message getMessage() {
        return message;
    }

    /* ------------------------------------------------------------------ */

    /** 标签小程序码的存储策略。 */
    public static class Qr {
        /**
         * 保留天数：超过这个天数的 {@code qr/*.png} 由定时任务删除。
         *
         * <p>删掉不丢东西：码的内容只有订单号，重打时会再调一次微信接口拿回同一张码，
         * 代价仅是那一张标签走一次无码降级布局。
         */
        private int keepDays = 180;

        public int getKeepDays() {
            return keepDays;
        }

        public void setKeepDays(int keepDays) {
            this.keepDays = keepDays;
        }
    }

    public static class Jwt {
        /** HS256 密钥，至少 32 字节。生产必须换成随机值，且不入库不入代码库。 */
        private String secret = "";
        /** 登录态有效期（天）。小程序 401 后静默重新 wx.login。 */
        private int expireDays = 7;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public int getExpireDays() {
            return expireDays;
        }

        public void setExpireDays(int expireDays) {
            this.expireDays = expireDays;
        }
    }

    public static class Wx {
        private String appId = "";
        private String appSecret = "";
        /** code2Session 地址。单独抽出来是为了出问题时能替换网关地址，不改代码。 */
        private String code2SessionUrl = "https://api.weixin.qq.com/sns/jscode2session";
        /** 取接口调用凭证 access_token 的地址。 */
        private String tokenUrl = "https://api.weixin.qq.com/cgi-bin/token";
        /** 发送订阅消息的地址。 */
        private String subscribeSendUrl = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send";
        /** 内容安全检测（msg_sec_check）的地址。 */
        private String msgSecCheckUrl = "https://api.weixin.qq.com/wxa/msg_sec_check";
        /** 生成小程序码（getUnlimited，不限数量）的地址。标签上用。 */
        private String qrCodeUrl = "https://api.weixin.qq.com/wxa/getwxacodeunlimit";
        private int timeoutSeconds = 5;

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getAppSecret() {
            return appSecret;
        }

        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }

        public String getCode2SessionUrl() {
            return code2SessionUrl;
        }

        public void setCode2SessionUrl(String code2SessionUrl) {
            this.code2SessionUrl = code2SessionUrl;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getTokenUrl() {
            return tokenUrl;
        }

        public void setTokenUrl(String tokenUrl) {
            this.tokenUrl = tokenUrl;
        }

        public String getSubscribeSendUrl() {
            return subscribeSendUrl;
        }

        public void setSubscribeSendUrl(String subscribeSendUrl) {
            this.subscribeSendUrl = subscribeSendUrl;
        }

        public String getMsgSecCheckUrl() {
            return msgSecCheckUrl;
        }

        public void setMsgSecCheckUrl(String msgSecCheckUrl) {
            this.msgSecCheckUrl = msgSecCheckUrl;
        }

        public String getQrCodeUrl() {
            return qrCodeUrl;
        }

        public void setQrCodeUrl(String qrCodeUrl) {
            this.qrCodeUrl = qrCodeUrl;
        }
    }

    public static class Upload {
        /** 本地磁盘目录。容器里挂 /data/upload，Nginx 以只读方式挂同一个卷直出图片。 */
        private String dir = "";
        /** 对外访问前缀，与 Nginx 的 location 保持一致。 */
        private String urlPrefix = "/upload";
        /** 单图大小上限（MB）。压缩在服务端做，这里卡的是原图，要容纳手机直出的高清图。 */
        private int maxSizeMb = 15;
        /** 压缩后长边上限（px）。小程序最大展示位 3x 屏约 1035px，1200 足够。 */
        private int maxEdge = 1200;
        /** JPEG 输出质量（0~1）。0.82 在 1200px 尺寸下肉眼与原图无差。 */
        private float jpegQuality = 0.82f;

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }

        public String getUrlPrefix() {
            return urlPrefix;
        }

        public void setUrlPrefix(String urlPrefix) {
            this.urlPrefix = urlPrefix;
        }

        public int getMaxSizeMb() {
            return maxSizeMb;
        }

        public void setMaxSizeMb(int maxSizeMb) {
            this.maxSizeMb = maxSizeMb;
        }

        public int getMaxEdge() {
            return maxEdge;
        }

        public void setMaxEdge(int maxEdge) {
            this.maxEdge = maxEdge;
        }

        public float getJpegQuality() {
            return jpegQuality;
        }

        public void setJpegQuality(float jpegQuality) {
            this.jpegQuality = jpegQuality;
        }
    }

    public static class Schedule {
        /** 超时未付款扫描任务。默认每 5 分钟；要更精确可改 "0 * * * * *"（每分钟）。 */
        private String prepayReleaseCron = "0 */5 * * * *";
        private boolean enabled = true;

        public String getPrepayReleaseCron() {
            return prepayReleaseCron;
        }

        public void setPrepayReleaseCron(String prepayReleaseCron) {
            this.prepayReleaseCron = prepayReleaseCron;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 应用层限流。补 nginx 按来源 IP 之外的「按人」维度，规则见 {@code 限流需求.md}。
     *
     * <p>阈值改完要重启才生效：配置是启动时绑定的，没有接配置中心。
     */
    public static class RateLimit {
        /**
         * 观察模式：算配额、超限只记日志，不返回 429。
         *
         * <p>默认开。60 次/分钟是按请求画像推的、没实测过，先跑几天看日志确认没冤枉正常人，
         * 再改成 false 重启，那时才开始真正拦截。
         */
        private boolean dryRun = true;

        /** 登录用户的额度（次/窗口）。取不到身份时退化为按 IP 计数，共用这一个额度。 */
        private int limit = 60;

        /**
         * 窗口长度（秒）。不能按秒限流：小程序进页面时 onShow 会在同一秒并发发 3 个请求，
         * 按秒必误伤。登录用户的额度与免登录入口共用这个窗口。
         */
        private int windowSeconds = 60;

        /** 免登录的登录入口（/auth/login、/auth/admin/login）按 IP 的额度（次/窗口）。 */
        private int loginLimit = 10;

        /**
         * 首次登记入口 {@code /auth/join} 按 IP 的独立额度（次/窗口）。
         *
         * <p>为什么不和 {@link #loginLimit} 共用一个桶：一次进入要打 1 次 {@code login} + 1 次
         * {@code join}，共用一个桶等于把额度对半砍。店里同一 WiFi 下 10 个顾客同时进来就是 20 次请求，
         * 后 10 个会被 429 挡在门外——而开业当天恰恰全是这种情况。
         */
        private int joinLimit = 30;

        public boolean isDryRun() {
            return dryRun;
        }

        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public int getLoginLimit() {
            return loginLimit;
        }

        public void setLoginLimit(int loginLimit) {
            this.loginLimit = loginLimit;
        }

        public int getJoinLimit() {
            return joinLimit;
        }

        public void setJoinLimit(int joinLimit) {
            this.joinLimit = joinLimit;
        }
    }

    public static class Web {
        /**
         * 允许跨域的来源，仅网页后台开发期需要（生产同域反代，可不配）。
         * 绝不使用通配符。
         */
        private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:5173"));

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    /**
     * 商家初始账号。仅在库里不存在该 username 时创建一次，已存在则不覆盖（改密码不会被启动清掉）。
     *
     * 默认 admin/admin 是研发阶段的约定值，上线前必须通过 APP_ADMIN_PASSWORD 覆盖。
     */
    public static class Admin {
        private String initUsername = "admin";
        private String initPassword = "admin";
        /** 关掉后启动不再自动建账号，用于生产（账号已建好的情况）。 */
        private boolean initEnabled = true;

        public String getInitUsername() {
            return initUsername;
        }

        public void setInitUsername(String initUsername) {
            this.initUsername = initUsername;
        }

        public String getInitPassword() {
            return initPassword;
        }

        public void setInitPassword(String initPassword) {
            this.initPassword = initPassword;
        }

        public boolean isInitEnabled() {
            return initEnabled;
        }

        public void setInitEnabled(boolean initEnabled) {
            this.initEnabled = initEnabled;
        }
    }

    /**
     * 联系顾客（调取完整号码拨号）的限次策略。
     *
     * <p>这三个数是**隐私合规的一部分**，不是体验参数：号码平时一律掩码，只有真要打电话时才
     * 按需调取一次并留痕。限次防的不是用户，是页面上无意识地反复点开把号码摊开。
     * 所以放在环境变量而不是后台配置页 —— 商家不该能自己放宽。
     */
    public static class ContactPhone {
        /** 同一笔订单 24 小时内最多调取几次 */
        private int maxPerOrder24h = 5;
        /** 同一个操作人一天最多调取几次 */
        private int maxPerOperatorDay = 100;
        /** 订单结束之后仍可调取的天数，留出售后与退款沟通的窗口；0 表示一结束就不可调 */
        private int windowDays = 7;

        public int getMaxPerOrder24h() {
            return maxPerOrder24h;
        }

        public void setMaxPerOrder24h(int maxPerOrder24h) {
            this.maxPerOrder24h = maxPerOrder24h;
        }

        public int getMaxPerOperatorDay() {
            return maxPerOperatorDay;
        }

        public void setMaxPerOperatorDay(int maxPerOperatorDay) {
            this.maxPerOperatorDay = maxPerOperatorDay;
        }

        public int getWindowDays() {
            return windowDays;
        }

        public void setWindowDays(int windowDays) {
            this.windowDays = windowDays;
        }
    }

    /** 邀请码相关策略。 */
    public static class Invite {

        private final Lock lock = new Lock();

        public Lock getLock() {
            return lock;
        }

        /**
         * 核销失败锁定：连错 N 次锁 M 分钟。
         *
         * <p>放环境变量的理由同 {@link ContactPhone}：这是防爆破的闸门，
         * 6 位纯数字码只有 100 万种组合，阈值放宽一倍等于把爆破成本砍一半。
         */
        public static class Lock {
            /** 连续错几次触发锁定 */
            private int maxFailures = 10;
            /** 锁定时长（分钟） */
            private int minutes = 30;

            public int getMaxFailures() {
                return maxFailures;
            }

            public void setMaxFailures(int maxFailures) {
                this.maxFailures = maxFailures;
            }

            public int getMinutes() {
                return minutes;
            }

            public void setMinutes(int minutes) {
                this.minutes = minutes;
            }
        }
    }

    /** 商家后台登录策略。 */
    public static class Auth {

        private final LoginLock loginLock = new LoginLock();

        public LoginLock getLoginLock() {
            return loginLock;
        }

        /**
         * 登录失败锁定：连错 N 次锁 M 分钟。
         *
         * <p>次数存在 {@code user.login_fail_count} 列，改阈值不动表结构；
         * 已在锁定中的账号仍按原 {@code lock_until} 到期，不会被这次改动提前解锁。
         */
        public static class LoginLock {
            private int maxFailures = 3;
            private int minutes = 30;

            public int getMaxFailures() {
                return maxFailures;
            }

            public void setMaxFailures(int maxFailures) {
                this.maxFailures = maxFailures;
            }

            public int getMinutes() {
                return minutes;
            }

            public void setMinutes(int minutes) {
                this.minutes = minutes;
            }
        }
    }

    /** 站内消息。 */
    public static class Message {
        /**
         * 首页通知横栏最多带几条未读。
         *
         * <p>只影响首页那一行滚动条，消息中心列表不受影响（那边是分页）。
         * 取值大了横栏会撑高，通常不需要改。
         */
        private int homeUnreadLimit = 3;

        public int getHomeUnreadLimit() {
            return homeUnreadLimit;
        }

        public void setHomeUnreadLimit(int homeUnreadLimit) {
            this.homeUnreadLimit = homeUnreadLimit;
        }
    }
}
