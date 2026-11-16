package com.xxxfhf.meal.auth;

import com.xxxfhf.meal.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 鉴权与准入入口。
 *
 * 免登录：两个登录接口。
 * 需登录：准入绑定、昵称、手机号、当前状态、重新申请（都作用于当前令牌对应的用户）。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final GroupAccessService groupAccessService;

    public AuthController(AuthService authService, GroupAccessService groupAccessService) {
        this.authService = authService;
        this.groupAccessService = groupAccessService;
    }

    /* ---------------- 登录 ---------------- */

    /** code 换登录态。失败时服务端返回规范化错误，code 不落任何日志明文。 */
    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.code()));
    }

    /**
     * 商家账密登录。账号不存在与密码错误返回同一句提示。
     * 密码不做长度下限校验之外的限制，避免把「密码规则」暴露成可探测信息。
     */
    @PostMapping("/admin/login")
    public ApiResponse<AuthService.AdminLoginResult> adminLogin(
            @Valid @RequestBody AdminLoginRequest request) {
        return ApiResponse.ok(authService.adminLogin(request.username(), request.password()));
    }

    /* ---------------- 准入 ---------------- */

    /**
     * 群卡片进入：提交 shareTicket 派生的加密数据，服务端解出 openGId 并校验白名单。
     *
     * encryptedData / iv 来自 wx.getShareInfo 或 wx.getGroupEnterInfo，前端拿不到 openGId 本身。
     */
    @PostMapping("/bind-group")
    public ApiResponse<GroupAccessService.AccessState> bindGroup(
            @Valid @RequestBody WxEncryptedRequest request) {
        return ApiResponse.ok(groupAccessService.bindByShare(
                UserContext.userId(), request.encryptedData(), request.iv()));
    }

    /** 输入邀请码取得准入资格（一次性、有有效期）。 */
    @PostMapping("/invite")
    public ApiResponse<GroupAccessService.AccessState> bindByInvite(
            @Valid @RequestBody InviteRequest request) {
        return ApiResponse.ok(groupAccessService.bindByInvite(UserContext.userId(), request.code()));
    }

    /* ---------------- 昵称 ---------------- */

    /**
     * 保存昵称。
     *
     * 微信已不再返回真实昵称（wx.getUserProfile 只给「微信用户」），
     * 前端用 `input type="nickname"` 让用户点选微信昵称或手填，拿到明文后提交到本接口。
     */
    @PostMapping("/nickname")
    public ApiResponse<String> updateNickName(@Valid @RequestBody NickNameRequest request) {
        return ApiResponse.ok(groupAccessService.updateNickName(
                UserContext.userId(), request.nickName()));
    }

    /* ---------------- 手机号 ---------------- */

    /** 微信手机号一键授权。受配置项 phoneAuthEnabled 控制，未开启时直接拒绝。 */
    @PostMapping("/phone/wx")
    public ApiResponse<String> bindPhoneByWx(@Valid @RequestBody WxEncryptedRequest request) {
        return ApiResponse.ok(groupAccessService.bindPhoneByWx(
                UserContext.userId(), request.encryptedData(), request.iv()));
    }

    /** 手动填写 / 修改手机号。 */
    @PostMapping("/phone")
    public ApiResponse<String> updatePhone(@Valid @RequestBody PhoneRequest request) {
        return ApiResponse.ok(groupAccessService.updatePhone(UserContext.userId(), request.phone()));
    }

    /* ---------------- 状态 ---------------- */

    /** 当前用户的准入与审核状态。商家审核后用户刷新这一个接口即可看到变化。 */
    @GetMapping("/me")
    public ApiResponse<AuthService.MeResult> me() {
        return ApiResponse.ok(authService.me(UserContext.userId()));
    }

    /** 网页后台刷新页面后恢复登录态：重新聚合权限，权限被改掉即时生效。 */
    @GetMapping("/admin/me")
    public ApiResponse<AuthService.AdminMeResult> adminMe() {
        return ApiResponse.ok(authService.adminMe(UserContext.userId()));
    }

    /** 被驳回后重新提交申请：rejected → pending。 */
    @PostMapping("/reapply")
    public ApiResponse<GroupAccessService.AccessState> reapply() {
        return ApiResponse.ok(groupAccessService.reapply(UserContext.userId()));
    }

    /* ---------------- 请求体 ---------------- */

    public record LoginRequest(@NotBlank(message = "缺少登录 code") String code) {
    }

    public record AdminLoginRequest(
            @NotBlank(message = "请输入账号") String username,
            @NotBlank(message = "请输入密码")
            @Size(max = 64, message = "密码过长") String password) {
    }

    public record WxEncryptedRequest(
            @NotBlank(message = "缺少加密数据") @Size(max = 4096, message = "加密数据过长") String encryptedData,
            @NotBlank(message = "缺少加密向量") @Size(max = 128, message = "加密向量过长") String iv) {
    }

    public record InviteRequest(
            @NotBlank(message = "请输入邀请码")
            @Size(max = 32, message = "邀请码过长") String code) {
    }

    public record NickNameRequest(
            @NotBlank(message = "请填写昵称")
            @Size(max = 32, message = "昵称最多 32 个字") String nickName) {
    }

    public record PhoneRequest(
            @NotBlank(message = "请输入手机号")
            @Size(max = 20, message = "手机号过长") String phone) {
    }
}
