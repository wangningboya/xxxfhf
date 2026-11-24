package com.xxxfhf.meal.print;

import com.xxxfhf.meal.auth.WxApiClient;
import com.xxxfhf.meal.file.FileStorageService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 标签上的小程序码：按订单号生成一次，之后一直复用。
 *
 * <p><b>为什么预生成而不是打印时生成</b>：{@code getUnlimited} 是外网调用，10 单串行要 3~5 秒，
 * 正好卡在商家最不想等的那一刻；而且微信偶发失败会让整批标签集体没码。
 * 把这件事挪到「订单落库后没人等」的时机，打印时只查文件在不在。
 *
 * <p>路径可推导（{@code qr/{orderNo}.png}），所以不在订单表加字段：
 * 多一个字段就多一处「字段说有、文件没了」的不一致。文件才是事实。
 *
 * <p>单线程池而不是 {@code @Async}：这个应用没开异步，为一个后台任务引线程池配置不划算；
 * 单线程也顺带保证了同一时刻只打一次微信接口，不会把 access_token 的频率用光。
 */
@Service
public class QrCodeService {

    private static final Logger log = LoggerFactory.getLogger(QrCodeService.class);

    /** 落地页路径，必须与 app.json 里注册的 pages/scan/entry 完全一致 */
    private static final String LANDING_PAGE = "pages/scan/entry";
    private static final String SUB_DIR = "qr";
    private static final int WIDTH = 280;

    private final WxApiClient wxApiClient;
    private final FileStorageService storage;
    private final ExecutorService pool = Executors.newSingleThreadExecutor((runnable) -> {
        Thread thread = new Thread(runnable, "qrcode-gen");
        thread.setDaemon(true);
        return thread;
    });
    /** 正在生成的订单号：同一单连点几下、或多处同时触发，只该打一次微信接口 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public QrCodeService(WxApiClient wxApiClient, FileStorageService storage) {
        this.wxApiClient = wxApiClient;
        this.storage = storage;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    /**
     * 已生成则返回相对路径，没有返回空串。
     *
     * <p>只查文件不生成：打印取数要求快，而且「代码里顺手触发一次生成」会让
     * 一批 10 单在最需要快的时候去排队等外网。
     */
    public String cachedPath(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return "";
        }
        String path = storage.pathOf(SUB_DIR, fileName(orderNo));
        return storage.exists(path) ? path : "";
    }

    /**
     * 后台补生成。已有文件、或同一单已在生成中，直接返回。
     *
     * <p>任何失败只记日志、不抛：二维码是标签上的「加分项」，
     * 它失败不能让下单失败，也不能让打印失败。
     */
    public void generateAsync(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return;
        }
        if (storage.exists(storage.pathOf(SUB_DIR, fileName(orderNo)))) {
            return;
        }
        if (!inFlight.add(orderNo)) {
            return;
        }
        pool.submit(() -> {
            try {
                byte[] png = wxApiClient.getUnlimitedQrCode(orderNo, LANDING_PAGE, WIDTH);
                storage.storeBytes(png, SUB_DIR, fileName(orderNo));
                log.info("小程序码已生成 orderNo={} size={}B", orderNo, png.length);
            } catch (Exception ex) {
                // 常见原因：小程序未发布、appid/secret 未配、微信限流。都不影响打印
                log.warn("小程序码生成失败 orderNo={}：{}", orderNo, ex.getMessage());
            } finally {
                inFlight.remove(orderNo);
            }
        });
    }

    private static String fileName(String orderNo) {
        // 订单号只会是数字；仍然过一遍白名单，防止将来换成别的编号规则后拼出穿越路径
        return orderNo.replaceAll("[^A-Za-z0-9_-]", "") + ".png";
    }
}
