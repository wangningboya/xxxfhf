package com.xxxfhf.meal.auth;

import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.user.AccountDeletionService;
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
    private final AccountDeletionService accountDeletionService;

    public AuthController(AuthService authService, GroupAccessService groupAccessService,
                          AccountDeletionService accountDeletionService) {
        this.authService = authService;
        this.groupAccessService = groupAccessService;
        this.accountDeletionService = accountDeletionService;
    }

    /* ---------------- 登录 ---------------- */

    /** code 换登录态。失败时服务端返回规范化错误，code 不落任何日志明文。 */
    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.code()));
    }

    /**
     * 首次进入：凭邀请码或群卡片登记并登录。
     *
     * 免登录。login 只认已登记的号，没登记就返回 registered=false，前端再带凭证来这里建号。
     * 邀请码与群卡片二选一，两条都给或都不给都会被拒绝。
     */
    @PostMapping("/join")
    public ApiResponse<AuthService.LoginResult> join(@Valid @RequestBody JoinRequest request) {
        return ApiResponse.ok(authService.join(
                request.code(), request.inviteCode(), request.encryptedData(), request.iv()));
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

    /**
     * 手动填写 / 修改手机号。
     *
     * 不接微信 `getPhoneNumber` 一键授权：手机号只在配送单需要，量小、用户也不多，
     * 为省这一次输入去申请手机号快速验证能力（按次计费）不划算。
     */
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

    /**
     * 本人手机号明文，只给改号弹窗与地址预填回填用。
     *
     * <p>不在 /auth/me 里返回明文是刻意的：那是每次启动都会调的接口，
     * 把 11 位号塞进去等于让每个页面都有机会拿到全号。
     */
    @GetMapping("/my-phone")
    public ApiResponse<String> myPhone() {
        return ApiResponse.ok(authService.myPhone(UserContext.userId()));
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

    /* ---------------- 注销 ---------------- */

    /**
     * 注销前先看后果：有几笔订单要保留、几条地址会被删、手机号会不会被抹掉。
     *
     * <p>单独一个接口而不是塞进 {@code /me}：这是低频且破坏性的操作，
     * 只有用户点进「注销」时才需要这些数字。
     */
    @GetMapping("/delete-preview")
    public ApiResponse<AccountDeletionService.DeletionPreview> deletePreview() {
        return ApiResponse.ok(accountDeletionService.preview(UserContext.userId()));
    }

    /**
     * 执行注销。有订单时保留订单、抹掉身份；没订单时整行删除。
     *
     * <p>调用成功后当前令牌立即失效（令牌版本 +1，或用户行已删），前端必须清本地登录态。
     */
    @PostMapping("/delete")
    public ApiResponse<AccountDeletionService.DeletionResult> deleteAccount() {
        return ApiResponse.ok(accountDeletionService.delete(UserContext.userId()));
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

    /**
     * 首次进入的请求体：邀请码与群卡片二选一。
     *
     * <p>两个字段都不加 @NotBlank —— 这里没有「必填的那一个」，
     * 到底缺哪个由服务端判断后给出一句能看懂的话。
     */
    public record JoinRequest(
            @NotBlank(message = "缺少登录 code") String code,
            @Size(max = 32, message = "邀请码过长") String inviteCode,
            @Size(max = 4096, message = "加密数据过长") String encryptedData,
            @Size(max = 128, message = "加密向量过长") String iv) {
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
