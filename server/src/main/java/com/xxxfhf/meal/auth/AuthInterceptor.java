package com.xxxfhf.meal.auth;

import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.role.Role;
import com.xxxfhf.meal.role.RoleMapper;
import com.xxxfhf.meal.user.AuditStatus;
import com.xxxfhf.meal.user.User;
import com.xxxfhf.meal.user.UserMapper;
import com.xxxfhf.meal.user.UserType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 登录态与权限拦截。小程序端与网页后台共用同一套校验，靠 user_type 区分是谁。
 *
 * 每次请求都从库里读用户与角色，而不是把类型和权限放进 token：
 * 审核通过、权限被收回、商家被降级都必须即时生效。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;

    public AuthInterceptor(JwtService jwtService, UserMapper userMapper, RoleMapper roleMapper) {
        this.jwtService = jwtService;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            // 静态资源等非接口请求，交给后续流程
            return true;
        }

        String token = extractToken(request);
        if (token == null) {
            throw BizException.unauthorized("请先登录");
        }

        JwtService.TokenPayload payload = jwtService.parse(token)
                .orElseThrow(() -> BizException.unauthorized("登录已过期，请重新进入小程序"));

        User user = userMapper.selectById(payload.userId());
        if (user == null) {
            throw BizException.unauthorized("登录已失效，请重新进入小程序");
        }

        // 令牌版本比对：库里 token_version 被调大（踢人）后，旧令牌立即失效
        int currentVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        if (payload.tokenVersion() != currentVersion) {
            throw BizException.unauthorized("登录已失效，请重新登录");
        }

        if (AuditStatus.DISABLED.equals(user.getAuditStatus())) {
            throw BizException.forbidden("账号已被禁用，请联系群管理员");
        }

        Set<String> permissions = resolvePermissions(user);
        // 类型一律取自库里，不信任令牌里的值：商家被降级后旧令牌也立刻失去后台权限
        UserContext.set(new CurrentUser(user.getId(), user.getUserType(), user.getAuditStatus(), permissions));

        RequirePerm requirePerm = handlerMethod.getMethodAnnotation(RequirePerm.class);
        if (requirePerm == null) {
            requirePerm = handlerMethod.getBeanType().getAnnotation(RequirePerm.class);
        }
        if (requirePerm != null) {
            if (!UserType.MERCHANT.equals(user.getUserType())) {
                throw BizException.forbidden("该操作仅限商家端");
            }
            if (!hasAny(permissions, requirePerm.value())) {
                throw BizException.forbidden("没有该操作权限");
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 线程池复用线程，必须清理，否则会串到下一个请求
        UserContext.clear();
    }

    private Set<String> resolvePermissions(User user) {
        List<String> roleIds = user.getRoleIds();
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        Set<String> permissions = new HashSet<>();
        for (String roleId : roleIds) {
            Role role = roleMapper.selectById(roleId);
            if (role != null && role.getPermissions() != null) {
                permissions.addAll(role.getPermissions());
            }
        }
        return permissions;
    }

    private static boolean hasAny(Set<String> owned, String[] required) {
        if (required == null || required.length == 0) {
            return true;
        }
        for (String perm : required) {
            if (owned.contains(perm)) {
                return true;
            }
        }
        return false;
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
