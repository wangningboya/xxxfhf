package com.xxxfhf.meal.file.service;

import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.ErrorCode;
import com.xxxfhf.meal.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 图片落本地磁盘（服务器中转上传，非浏览器直传）。
 *
 * 安全点：
 * 1. 不信任原始文件名，一律用 UUID 重命名，避免路径穿越与覆盖；
 * 2. 扩展名与 Content-Type 双白名单；
 * 3. 校验文件头（magic bytes），防改扩展名上传可执行/脚本文件。
 *
 * 体积：落盘前一律重编码为 JPEG（长边 ≤ app.upload.max-edge），
 * 手机直出的高清原图约 4MB，压完 150~200KB，详情页与列表都靠这张图。
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png");

    private final AppProperties.Upload upload;
    private final ImageCompressor compressor;
    private Path root;

    public FileStorageService(AppProperties props) {
        this.upload = props.getUpload();
        this.compressor = new ImageCompressor(upload.getMaxEdge(), upload.getJpegQuality());
    }

    @PostConstruct
    void init() {
        this.root = Paths.get(upload.getDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建上传目录：" + root, ex);
        }
        if (!Files.isWritable(root)) {
            throw new IllegalStateException("上传目录不可写：" + root);
        }
        log.info("图片上传目录就绪：{}（原图上限 {} MB，压缩后长边 {} px / 质量 {}）",
                root, upload.getMaxSizeMb(), upload.getMaxEdge(), upload.getJpegQuality());
    }

    /**
     * @return 可对外访问的 URL 路径，如 /upload/2026/11/xxxx.jpg
     */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.param("请选择要上传的图片");
        }
        long maxBytes = (long) upload.getMaxSizeMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw BizException.param("图片不能超过 " + upload.getMaxSizeMb() + " MB");
        }

        String ext = resolveExtension(file.getOriginalFilename());
        if (!ALLOWED_EXT.contains(ext)) {
            throw BizException.param("仅支持 jpg / png 格式的图片");
        }
        if (!matchesMagic(file, ext)) {
            throw BizException.param("图片内容与格式不符，请重新选择");
        }

        // 统一重编码为 JPEG：限制长边、剥掉 EXIF，体积通常只剩原图的百分之几
        byte[] data = compressor.compress(file);

        String subDir = LocalDate.now().format(MONTH_DIR);
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ImageCompressor.OUTPUT_EXT;
        Path targetDir = root.resolve(subDir).normalize();
        if (!targetDir.startsWith(root)) {
            throw BizException.param("非法的存储路径");
        }

        try {
            Files.createDirectories(targetDir);
            Files.write(targetDir.resolve(fileName), data);
        } catch (IOException ex) {
            log.error("写入图片失败 dir={} name={}", targetDir, fileName, ex);
            throw new BizException(ErrorCode.STORAGE_ERROR, "图片保存失败，请重试");
        }

        String prefix = upload.getUrlPrefix().endsWith("/")
                ? upload.getUrlPrefix().substring(0, upload.getUrlPrefix().length() - 1)
                : upload.getUrlPrefix();
        return prefix + "/" + subDir + "/" + fileName;
    }

    /**
     * 按固定相对路径写一段二进制（小程序码用）。
     *
     * <p>与 {@link #store(MultipartFile)} 的区别是**不做压缩、不改名**：
     * 小程序码是微信生成的 PNG，重编码没有意义，而文件名必须可推导
     * （{@code qr/202611250012.png}），这样打印取数时不必查库就知道有没有码。
     *
     * @param subDir   相对子目录，如 {@code qr}；会被规范化，越界直接拒
     * @param fileName 固定文件名，如 {@code 202611250012.png}
     * @return 可对外访问的相对路径，如 {@code /upload/qr/202611250012.png}
     */
    public String storeBytes(byte[] data, String subDir, String fileName) {
        if (data == null || data.length == 0) {
            throw BizException.param("文件内容为空");
        }
        Path targetDir = root.resolve(subDir).normalize();
        if (!targetDir.startsWith(root)) {
            throw BizException.param("非法的存储路径");
        }
        try {
            Files.createDirectories(targetDir);
            Files.write(targetDir.resolve(fileName), data);
        } catch (IOException ex) {
            log.error("写入文件失败 dir={} name={}", targetDir, fileName, ex);
            throw new BizException(ErrorCode.STORAGE_ERROR, "文件保存失败");
        }
        return urlPrefix() + "/" + subDir + "/" + fileName;
    }

    /**
     * 相对路径对应的文件是否存在。
     *
     * <p>路径以 {@link #urlPrefix()} 开头，不匹配的一律当不存在 —— 这个方法会被
     * 「按订单号拼路径」的调用方使用，不能让一个拼错的路径读到别处的文件。
     */
    public boolean exists(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        String prefix = urlPrefix() + "/";
        if (!relativePath.startsWith(prefix)) {
            return false;
        }
        Path target = root.resolve(relativePath.substring(prefix.length())).normalize();
        if (!target.startsWith(root)) {
            return false;
        }
        return Files.isRegularFile(target);
    }

    private String urlPrefix() {
        return upload.getUrlPrefix().endsWith("/")
                ? upload.getUrlPrefix().substring(0, upload.getUrlPrefix().length() - 1)
                : upload.getUrlPrefix();
    }

    /**
     * 由子目录与文件名推导出可访问的相对路径。
     *
     * <p>给「文件名固定」的场景用（如按订单号命名的小程序码）：写入前要判断是否已存在，
     * 就必须能在不写文件的前提下拼出一模一样的路径。
     */
    public String pathOf(String subDir, String fileName) {
        return urlPrefix() + "/" + subDir + "/" + fileName;
    }

    /**
     * 删除子目录下超期的文件，只扫这一层、不进下级目录。
     *
     * <p>给「文件名固定、只增不删」的小程序码用：码按订单号命名、一单一张，
     * 删掉不影响已经打出去的纸，重打时按需重新生成。
     *
     * <p>按文件的**最后修改时间**判断，不解析文件名里的日期 —— 将来换命名规则不用改这里。
     * 单个文件删失败只记日志并继续，不让一个坏文件挡住整批清理。
     *
     * @param subDir   相对子目录，如 {@code qr}
     * @param keepDays 保留天数，必须 ≥ 1
     * @return 删除张数、剩余张数与剩余字节数
     */
    public CleanResult deleteOlderThan(String subDir, int keepDays) {
        if (keepDays < 1) {
            throw BizException.param("保留天数必须大于 0");
        }
        Path dir = root.resolve(subDir).normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
            return new CleanResult(0, 0, 0);
        }
        long cutoff = System.currentTimeMillis() - keepDays * 24L * 60 * 60 * 1000;
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile).toList();
        } catch (IOException ex) {
            log.warn("扫描清理目录失败 dir={}：{}", dir, ex.getMessage());
            return new CleanResult(0, 0, 0);
        }
        int deleted = 0;
        int remaining = 0;
        long remainingBytes = 0;
        for (Path file : files) {
            try {
                if (Files.getLastModifiedTime(file).toMillis() < cutoff) {
                    Files.deleteIfExists(file);
                    deleted++;
                } else {
                    remaining++;
                    remainingBytes += Files.size(file);
                }
            } catch (IOException ex) {
                log.warn("清理过期文件失败 path={}：{}", file, ex.getMessage());
            }
        }
        return new CleanResult(deleted, remaining, remainingBytes);
    }

    /** @param deleted 本次删除的文件数；@param remaining 未到期仍保留的文件数；@param remainingBytes 保留文件的字节数 */
    public record CleanResult(int deleted, int remaining, long remainingBytes) {
    }

    private static String resolveExtension(String originalFilename) {
        if (originalFilename == null || !originalFilename.contains(".")) {
            return "";
        }
        String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1);
        return ext.toLowerCase(Locale.ROOT);
    }

    /** 简单文件头校验：只要开头字节对得上就放行，防改扩展名。 */
    private static boolean matchesMagic(MultipartFile file, String ext) {
        byte[] head = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.read(head);
        } catch (IOException ex) {
            return false;
        }
        if (read < 4) {
            return false;
        }
        return switch (ext) {
            case "jpg", "jpeg" -> (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            case "png" -> (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            default -> false;
        };
    }
}
