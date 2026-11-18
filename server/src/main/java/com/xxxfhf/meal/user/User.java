package com.xxxfhf.meal.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户。对应表 `user`（user 是 MySQL 关键字，SQL 里必须带反引号）。
 *
 * 一张表装两类账号，靠 userType 区分身份（不用角色）：
 * - customer 群成员：openid 有值，username / passwordHash 为 null，走微信登录
 * - merchant 商家  ：username / passwordHash 有值，openid 为 null，走账密登录
 *
 * autoResultMap = true 是 roleIds 这个 JSON 字段能读出来的前提，漏了会安静地读出 null。
 * JSON 字段必须用 Jackson3TypeHandler（Jackson 2 的 JacksonTypeHandler 在 Boot 4 下不可用）。
 */
@TableName(value = "`user`", autoResultMap = true)
public class User {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** customer / merchant，见 UserType */
    private String userType;

    /** 微信 openid；商家为 null */
    private String openid;

    /** 商家登录账号；群成员为 null */
    private String username;

    /** BCrypt 密码哈希；群成员为 null。绝不返回给前端。 */
    private String passwordHash;

    private String nickName;

    private String avatarUrl;

    /** 绑定来源群的 openGId */
    private String groupOpenId;

    /** pending / approved / rejected / disabled */
    private String auditStatus;

    private String rejectReason;

    private String phone;

    /** 绑定角色 ID 列表，JSON 列 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<String> roleIds;

    private LocalDateTime createdAt;

    private LocalDateTime approvedAt;

    /** 账密连续失败次数；登录成功清零，到 3 触发锁定（商家账号才用得到） */
    private Integer loginFailCount;

    /** 锁定截止时间；到期自动解锁，管理员可提前解除 */
    private LocalDateTime lockUntil;

    /** 令牌版本号；踢人 = +1，与令牌内 ver 不一致即 401（见 JwtService） */
    private Integer tokenVersion;

    /* ---------------- getters / setters ---------------- */

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOpenid() {
        return openid;
    }

    public void setOpenid(String openid) {
        this.openid = openid;
    }

    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNickName() {
        return nickName;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getGroupOpenId() {
        return groupOpenId;
    }

    public void setGroupOpenId(String groupOpenId) {
        this.groupOpenId = groupOpenId;
    }

    public String getAuditStatus() {
        return auditStatus;
    }

    public void setAuditStatus(String auditStatus) {
        this.auditStatus = auditStatus;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public List<String> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(List<String> roleIds) {
        this.roleIds = roleIds;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    public Integer getLoginFailCount() {
        return loginFailCount;
    }

    public void setLoginFailCount(Integer loginFailCount) {
        this.loginFailCount = loginFailCount;
    }

    public LocalDateTime getLockUntil() {
        return lockUntil;
    }

    public void setLockUntil(LocalDateTime lockUntil) {
        this.lockUntil = lockUntil;
    }

    public Integer getTokenVersion() {
        return tokenVersion;
    }

    public void setTokenVersion(Integer tokenVersion) {
        this.tokenVersion = tokenVersion;
    }
}
