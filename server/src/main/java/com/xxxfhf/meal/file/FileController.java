package com.xxxfhf.meal.file;

import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.RequirePerm;
import com.xxxfhf.meal.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片上传。服务端中转，不开放浏览器直传。
 *
 * 能上传图片的动作只有三处：盒饭封面、菜品图、收款码，故复用这三类权限。
 */
@RestController
@RequestMapping("/admin/file")
public class FileController {

    private final FileStorageService storageService;

    public FileController(FileStorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping("/upload")
    @RequirePerm({Perms.COMBO_MANAGE, Perms.DISH_MANAGE, Perms.CONFIG_MANAGE})
    public ApiResponse<UploadResult> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(new UploadResult(storageService.store(file)));
    }

    /** @param url 可直接放入 img src 的相对路径，如 /upload/2026/11/abcdef.webp */
    public record UploadResult(String url) {
    }
}
