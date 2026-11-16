package com.xxxfhf.meal.file;

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
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 图片落本地磁盘（服务器中转上传，非浏览器直传）。
 *
 * 安全点：
 * 1. 不信任原始文件名，一律用 UUID 重命名，避免路径穿越与覆盖；
 * 2. 扩展名与 Content-Type 双白名单；
 * 3. 校验文件头（magic bytes），防改扩展名上传可执行/脚本文件。
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp");

    private final AppProperties.Upload upload;
    private Path root;

    public FileStorageService(AppProperties props) {
        this.upload = props.getUpload();
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
        log.info("图片上传目录就绪：{}（单图上限 {} MB）", root, upload.getMaxSizeMb());
    }

    /**
     * @return 可对外访问的 URL 路径，如 /upload/2026/11/xxxx.webp
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
            throw BizException.param("仅支持 jpg / png / webp 格式的图片");
        }
        if (!matchesMagic(file, ext)) {
            throw BizException.param("图片内容与格式不符，请重新选择");
        }

        String subDir = LocalDate.now().format(MONTH_DIR);
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path targetDir = root.resolve(subDir).normalize();
        if (!targetDir.startsWith(root)) {
            throw BizException.param("非法的存储路径");
        }

        try {
            Files.createDirectories(targetDir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, targetDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            log.error("写入图片失败 dir={} name={}", targetDir, fileName, ex);
            throw new BizException(ErrorCode.STORAGE_ERROR, "图片保存失败，请重试");
        }

        String prefix = upload.getUrlPrefix().endsWith("/")
                ? upload.getUrlPrefix().substring(0, upload.getUrlPrefix().length() - 1)
                : upload.getUrlPrefix();
        return prefix + "/" + subDir + "/" + fileName;
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
            // RIFF....WEBP
            case "webp" -> read >= 12
                    && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            default -> false;
        };
    }
}
