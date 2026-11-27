package com.xxxfhf.meal.role;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.auth.Perms;
import com.xxxfhf.meal.auth.UserContext;
import com.xxxfhf.meal.audit.AuditActions;
import com.xxxfhf.meal.audit.AuditHelper;
import com.xxxfhf.meal.audit.AuditService;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.user.User;
import com.xxxfhf.meal.user.UserMapper;
import com.xxxfhf.meal.user.UserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;

/**
 * 角色与权限分配（网页后台独有模块，需 role.manage）。
 *
 * 三条硬规则：
 * 1. 内置「商家」角色不可删、不可改权限项 —— 否则一次误操作能把店主自己锁死；
 * 2. 写入前把权限项与 Perms 白名单对齐，脏字符串进不了库；
 * 3. 角色被账号引用时不可删，报错要点名是哪些账号在用。
 */
@Service
public class RoleService {

    private static final Logger log = LoggerFactory.getLogger(RoleService.class);

    /** 权限项中文名，顺序与《需求文档》第二章一致；前端勾选面板直接渲染这一份 */
    private static final List<PermissionItem> ITEMS = List.of(
            new PermissionItem(Perms.DISH_MANAGE, "菜品管理", "菜品（原料）增删改、启停用", false),
            new PermissionItem(Perms.COMBO_MANAGE, "盒饭管理", "盒饭新增、编辑组成、定价、上下架", false),
            new PermissionItem(Perms.MENU_MANAGE, "每日菜单", "勾选盒饭、设置每日份数", false),
            new PermissionItem(Perms.USER_AUDIT, "用户审核", "审核用户准入、禁用用户", false),
            new PermissionItem(Perms.USER_VIEW, "用户查看", "只读查看用户列表与审核状态，不能通过 / 驳回", true),
            new PermissionItem(Perms.USER_DELETE, "删除用户", "物理删除用户（含名下地址），不可撤销，与「停用」是两回事", false),
            new PermissionItem(Perms.USER_PHONE_VIEW, "查看完整手机号", "把某个用户的掩码还原成完整号码，每次查看记操作记录", false),
            new PermissionItem(Perms.REGION_MANAGE, "区域管理", "区域新增、改名、排序、停用", false),
            new PermissionItem(Perms.USER_ADDRESS, "地址维护", "维护用户收货地址（含区域归属）", false),
            new PermissionItem(Perms.ORDER_ACCEPT, "订单处理", "接单、拒单、推进订单状态", false),
            new PermissionItem(Perms.ORDER_VIEW, "订单查看", "只读查看订单列表与详情，不能接单 / 改状态", true),
            new PermissionItem(Perms.PREP_VIEW, "备菜统计", "查看备菜统计（菜品汇总 / 区域汇总）", true),
            new PermissionItem(Perms.PREP_EXPORT, "导出", "导出备菜与对账 Excel", false),
            new PermissionItem(Perms.ORDER_PAYMENT, "收款与退款", "确认收款、标记退款", false),
            new PermissionItem(Perms.FINANCE_VIEW, "对账查看", "查看对账视图（应收 / 已收 / 未收 / 已退款）", true),
            new PermissionItem(Perms.CONFIG_MANAGE, "系统配置", "截单时间、配送费、店铺信息", false),
            new PermissionItem(Perms.ROLE_MANAGE, "角色权限", "角色与权限分配", false),
            new PermissionItem(Perms.DICT_MANAGE, "数据字典", "状态与类型的显示名、颜色、排序维护", false),
            new PermissionItem(Perms.AUDIT_VIEW, "操作记录", "查看谁在什么时候改了哪条数据（只读）", true)
    );

    private static final Set<String> VALID = new HashSet<>(List.of(Perms.ALL));

    private final RoleMapper roleMapper;
    private final UserMapper userMapper;
    /** 角色与授权留痕（需求 4.19 / F-J13）：权限是被放大或被削减的，事后必须查得到是谁动的 */
    private final AuditService auditService;

    public RoleService(RoleMapper roleMapper, UserMapper userMapper, AuditService auditService) {
        this.roleMapper = roleMapper;
        this.userMapper = userMapper;
        this.auditService = auditService;
    }

    /* ---------------- 权限项 ---------------- */

    public List<PermissionItem> permissionItems() {
        return ITEMS;
    }

    /* ---------------- 角色 ---------------- */

    /**
     * 角色下拉选项（仅 id + 名称），给「指定为商家」弹窗用。
     *
     * <p>为什么不复用 {@link #list()}：那个接口挂 role.manage 权限且带权限项清单，
     * 而能指定商家的人（user.audit）不一定能管角色 —— 指定商家只该挑角色，
     * 不该顺带看到每个角色里有哪些权限项。
     */
    @Transactional(readOnly = true)
    public List<RoleOption> options() {
        List<Role> roles = roleMapper.selectList(Wrappers.<Role>lambdaQuery()
                .orderByDesc(Role::isBuiltin)
                .orderByAsc(Role::getCreatedAt));
        List<RoleOption> options = new ArrayList<>(roles.size());
        for (Role role : roles) {
            options.add(new RoleOption(role.getId(), role.getName()));
        }
        return options;
    }

    @Transactional(readOnly = true)
    public List<RoleView> list() {
        List<Role> roles = roleMapper.selectList(Wrappers.<Role>lambdaQuery()
                .orderByDesc(Role::isBuiltin)
                .orderByAsc(Role::getCreatedAt));
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : roleMapper.countMembers()) {
            Object id = row.get("roleId");
            Object count = row.get("memberCount");
            if (id != null) {
                counts.put(String.valueOf(id), count == null ? 0 : ((Number) count).intValue());
            }
        }
        List<RoleView> views = new ArrayList<>(roles.size());
        for (Role role : roles) {
            views.add(new RoleView(role.getId(), role.getName(), role.isBuiltin(),
                    role.getPermissions() == null ? List.of() : role.getPermissions(),
                    counts.getOrDefault(role.getId(), 0), role.getCreatedAt()));
        }
        return views;
    }

    /**
     * 新增或编辑角色。id 为空是新增。
     *
     * 内置角色只允许改名：权限项被改了就等于把店主权限改小，而这个页面本身要靠 role.manage 才进得来。
     */
    @Transactional
    public RoleView save(Long operatorId, Long id, String name, List<String> permissions) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw BizException.param("请填写角色名称");
        }
        if (trimmed.length() > 64) {
            throw BizException.param("角色名称最多 64 个字");
        }

        Role role;
        boolean creating = id == null;
        if (creating) {
            role = new Role();
            role.setName(trimmed);
            role.setBuiltin(false);
            role.setPermissions(normalize(permissions));
            try {
                roleMapper.insert(role);
            } catch (DuplicateKeyException ex) {
                throw BizException.conflict("角色名称「" + trimmed + "」已存在");
            }
        } else {
            role = roleMapper.selectById(id);
            if (role == null) {
                throw BizException.notFound("角色不存在，可能已被删除");
            }
            role.setName(trimmed);
            if (!role.isBuiltin()) {
                role.setPermissions(normalize(permissions));
            }
            try {
                roleMapper.updateById(role);
            } catch (DuplicateKeyException ex) {
                throw BizException.conflict("角色名称「" + trimmed + "」已存在");
            }
        }

        log.info("保存角色 operator={} roleId={} name={} builtin={} 权限数={}",
                operatorId, role.getId(), role.getName(), role.isBuiltin(),
                role.getPermissions() == null ? 0 : role.getPermissions().size());
        auditService.record(AuditActions.TABLE_ROLE, role.getId(),
                creating ? AuditActions.ROLE_CREATE : AuditActions.ROLE_UPDATE,
                (creating ? "新增角色：" : "修改角色：") + role.getName() + "｜权限 "
                        + (role.getPermissions() == null ? 0 : role.getPermissions().size()) + " 项"
                        + (role.isBuiltin() ? "（内置角色，只改名称）" : ""),
                operatorId, AuditHelper.source());
        return new RoleView(role.getId(), role.getName(), role.isBuiltin(),
                role.getPermissions() == null ? List.of() : role.getPermissions(), 0, role.getCreatedAt());
    }

    @Transactional
    public void delete(Long operatorId, Long id) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw BizException.notFound("角色不存在，可能已被删除");
        }
        if (role.isBuiltin()) {
            throw BizException.conflict("「" + role.getName() + "」是内置角色，不能删除");
        }

        List<String> owners = userMapper.selectList(Wrappers.<User>lambdaQuery()
                        .eq(User::getUserType, UserType.MERCHANT))
                .stream()
                .filter(u -> u.getRoleIds() != null && u.getRoleIds().contains(id))
                .map(User::getUsername)
                .toList();
        if (!owners.isEmpty()) {
            throw BizException.conflict("角色「" + role.getName() + "」还有 " + owners.size()
                    + " 个账号在用：" + String.join("、", owners) + "，请先改掉这些账号的角色");
        }

        roleMapper.deleteById(id);
        log.info("删除角色 operator={} roleId={} name={}", operatorId, id, role.getName());
        auditService.record(AuditActions.TABLE_ROLE, id, AuditActions.ROLE_DELETE,
                "删除角色：" + role.getName(), operatorId, AuditHelper.source());
    }

    /* ---------------- 账号与角色绑定 ---------------- */

    /** 商家账号列表，附带当前角色名，供分配角色时勾选。 */
    @Transactional(readOnly = true)
    public List<AccountRow> accounts() {
        List<Role> roles = roleMapper.selectList(null);
        Map<Long, String> roleNames = new HashMap<>();
        for (Role role : roles) {
            roleNames.put(role.getId(), role.getName());
        }

        List<User> merchants = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .eq(User::getUserType, UserType.MERCHANT)
                .orderByAsc(User::getCreatedAt));

        LocalDateTime now = LocalDateTime.now();
        List<AccountRow> rows = new ArrayList<>(merchants.size());
        for (User user : merchants) {
            List<Long> ids = user.getRoleIds() == null ? List.of() : user.getRoleIds();
            List<String> names = new ArrayList<>(ids.size());
            for (Long roleId : ids) {
                names.add(roleNames.getOrDefault(roleId, "已删除的角色"));
            }
            String display = user.getNickName() == null || user.getNickName().isBlank()
                    ? user.getUsername() : user.getNickName();
            // 锁定中的才算 locked：过期了的锁不算，避免前端出现「已解锁还能点」的死按钮
            boolean locked = user.getLockUntil() != null && user.getLockUntil().isAfter(now);
            rows.add(new AccountRow(user.getId(), user.getUsername(), display,
                    ids, names, user.getAuditStatus(), user.getCreatedAt(),
                    locked ? user.getLockUntil() : null,
                    locked ? 0 : (user.getLoginFailCount() == null ? 0 : user.getLoginFailCount())));
        }
        return rows;
    }

    /**
     * 给商家账号分配角色。
     *
     * 允许把角色全部清空吗？不允许 —— 清空的账号登进去是白屏且自己改不回来。
     * 唯一例外是操作人不给自己改（避免 self-lock），这层由 Controller 先挡一次。
     */
    @Transactional
    public AccountRow assign(Long operatorId, Long userId, List<Long> roleIds) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("账号不存在");
        }
        if (!UserType.MERCHANT.equals(user.getUserType())) {
            throw BizException.param("只有商家账号可以分配后台角色");
        }

        List<Long> cleaned = roleIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(roleIds));
        if (cleaned.isEmpty()) {
            throw BizException.param("至少要保留一个角色，否则该账号登录后没有任何功能可用");
        }
        for (Long roleId : cleaned) {
            if (roleId == null || roleMapper.selectById(roleId) == null) {
                throw BizException.param("角色不存在，请刷新后重试");
            }
        }
        if (operatorId != null && operatorId.equals(userId)) {
            throw BizException.conflict("不能修改自己的角色，请让另一个商家账号操作");
        }

        user.setRoleIds(cleaned);
        userMapper.updateById(user);
        log.info("分配角色 operator={} userId={} username={} roles={}",
                operatorId, userId, user.getUsername(), cleaned);
        auditService.record(AuditActions.TABLE_ROLE, userId, AuditActions.ROLE_ASSIGN,
                "给账号「" + user.getUsername() + "」分配角色：" + roleNames(cleaned),
                operatorId, AuditHelper.source());
        return accounts().stream()
                .filter(row -> row.userId().equals(userId))
                .findFirst()
                .orElseThrow(() -> BizException.notFound("账号不存在"));
    }

    /**
     * 解除商家账号的登录锁定。
     *
     * 场景：某个商家账号被暴力试密码锁了 30 分钟，等不起，由持有 role.manage 的
     * 其他账号在这里手动解锁。清失败计数与锁定时间，一步到位。
     */
    @Transactional
    public AccountRow unlock(Long operatorId, Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("账号不存在");
        }
        if (!UserType.MERCHANT.equals(user.getUserType())) {
            throw BizException.param("只有商家账号存在登录锁定");
        }

        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getLoginFailCount, 0)
                .set(User::getLockUntil, null));
        log.info("解除登录锁定 operator={} userId={} username={}", operatorId, userId, user.getUsername());
        auditService.record(AuditActions.TABLE_ROLE, userId, AuditActions.ROLE_UNLOCK,
                "解除账号「" + user.getUsername() + "」的登录锁定",
                operatorId, AuditHelper.source());

        return accounts().stream()
                .filter(row -> row.userId().equals(userId))
                .findFirst()
                .orElseThrow(() -> BizException.notFound("账号不存在"));
    }

    /** 角色名拼接，操作记录摘要里写 id 等于没写 */
    private String roleNames(List<Long> ids) {
        List<String> names = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Role role = roleMapper.selectById(id);
            names.add(role == null ? String.valueOf(id) : role.getName());
        }
        return String.join("、", names);
    }

    /* ---------------- 内部 ---------------- */

    /** 与 Perms 白名单对齐：未知权限项直接报错，不静默丢弃。 */
    private List<String> normalize(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return List.of();
        }
        Set<String> kept = new LinkedHashSet<>();
        for (String perm : permissions) {
            if (perm == null || perm.isBlank()) {
                continue;
            }
            if (!VALID.contains(perm)) {
                throw BizException.param("未知的权限项：" + perm);
            }
            kept.add(perm);
        }
        return new ArrayList<>(kept);
    }

    /* ---------------- 视图 ---------------- */

    /**
     * @param builtin     内置角色不可删、权限项不可改
     * @param memberCount 有多少商家账号在用这个角色
     */
    public record RoleView(Long id, String name, boolean builtin,
                           List<String> permissions, int memberCount,
                           java.time.LocalDateTime createdAt) {
    }

    public record PermissionItem(String code, String name, String desc, boolean readOnly) {
    }

    /** 角色下拉选项：只有 id 与名称，不含权限项清单（见 {@link #options()}）。 */
    public record RoleOption(Long id, String name) {
    }

    public record AccountRow(Long userId, String username, String displayName,
                             List<Long> roleIds, List<String> roleNames,
                             String auditStatus, java.time.LocalDateTime createdAt,
                             /** 锁定截止时间；未锁定为 null（过期的锁不出现在响应里） */
                             java.time.LocalDateTime lockedUntil,
                             /** 当前连续失败次数（未锁定时有意义，下次失败即累计） */
                             int loginFailCount) {
    }
}
