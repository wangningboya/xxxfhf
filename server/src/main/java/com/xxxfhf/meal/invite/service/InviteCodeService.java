package com.xxxfhf.meal.invite.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xxxfhf.meal.audit.domain.AuditActions;
import com.xxxfhf.meal.audit.service.AuditService;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.ErrorCode;
import com.xxxfhf.meal.invite.domain.InviteCode;
import com.xxxfhf.meal.invite.domain.InviteStatus;
import com.xxxfhf.meal.invite.domain.InviteUse;
import com.xxxfhf.meal.invite.mapper.InviteCodeMapper;
import com.xxxfhf.meal.invite.mapper.InviteUseMapper;
import com.xxxfhf.meal.user.domain.User;
import com.xxxfhf.meal.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 邀请码：生成、列表、核销。
 *
 * 邀请码是准入的**正式路径**（不是降级方案）：群卡片被刷走、用户换号、卡片被转给个人时都靠它。
 *
 * <p>一个码可被**多个人各用一次**（次数在生成时定）。所以「谁用过」记在 {@link InviteUse} 明细里，
 * 本表只留计数与最近使用时间。
 */
@Service
public class InviteCodeService {

    private static final Logger log = LoggerFactory.getLogger(InviteCodeService.class);

    /** 码是 6 位纯数字：数字键盘就能输，不用切大小写，也不会出现 0/O 这类手抄易错字符 */
    private static final int CODE_LENGTH = 6;
    private static final int CODE_SPACE = 1_000_000;

    /**
     * 撞码重试次数。6 位数字只有 100 万种组合，比 8 位字母码更容易撞；
     * 连续 20 次都撞说明号段接近用尽，这时候应该报错而不是继续空转。
     */
    private static final int MAX_GENERATE_RETRY = 20;

    /** 有效期上限 30 天，防止「永不过期」的码在群里长期流传 */
    private static final int MAX_EXPIRE_HOURS = 24 * 30;

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final InviteCodeMapper inviteCodeMapper;
    private final InviteUseMapper inviteUseMapper;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    public InviteCodeService(InviteCodeMapper inviteCodeMapper, InviteUseMapper inviteUseMapper,
                             UserMapper userMapper, AuditService auditService) {
        this.inviteCodeMapper = inviteCodeMapper;
        this.inviteUseMapper = inviteUseMapper;
        this.userMapper = userMapper;
        this.auditService = auditService;
    }

    /**
     * 生成一个邀请码。
     *
     * @param uses        可用次数（正整数，不设上限）：这张码能被多少个人各用一次
     * @param expireHours 有效期（小时），1 ~ 720
     * @param createdBy   生成人 userId
     */
    @Transactional
    public InviteCode create(int uses, int expireHours, Long createdBy) {
        if (uses < 1) {
            throw BizException.param("可用次数至少 1 次");
        }
        int hours = expireHours <= 0 ? 24 : expireHours;
        if (hours > MAX_EXPIRE_HOURS) {
            throw BizException.param("有效期最长 " + MAX_EXPIRE_HOURS + " 小时（30 天）");
        }

        for (int attempt = 0; attempt < MAX_GENERATE_RETRY; attempt++) {
            InviteCode code = new InviteCode();
            code.setCode(randomCode());
            code.setStatus(InviteStatus.UNUSED);
            code.setMaxUses(uses);
            code.setUsedCount(0);
            code.setExpireAt(LocalDateTime.now().plusHours(hours));
            code.setCreatedBy(createdBy);
            code.setCreatedAt(LocalDateTime.now());
            try {
                inviteCodeMapper.insert(code);
                // 发码等于放人进来，必须留痕：记到 invite_code 上，操作人/来源由当前请求上下文自动取
                auditService.record(AuditActions.TABLE_INVITE, code.getId(), AuditActions.INVITE_CREATE,
                        "生成邀请码 " + code.getCode() + "（" + uses + " 次，有效期 " + hours + " 小时，至 "
                                + MINUTE.format(code.getExpireAt()) + "）");
                log.info("生成邀请码 code={} 次数={} 有效期={}h 生成人={}",
                        code.getCode(), uses, hours, createdBy);
                return code;
            } catch (DuplicateKeyException ex) {
                // 撞上已经存在的码（含已用完 / 已过期 / 已作废的，号码不复用），换一个再试
                log.warn("邀请码随机冲突，重试第 {} 次", attempt + 1);
            }
        }
        log.error("邀请码连续 {} 次撞码，号段可能接近用尽", MAX_GENERATE_RETRY);
        throw new BizException(ErrorCode.SERVER_ERROR, "邀请码号段接近用尽，请先清理旧码后再试");
    }

    public IPage<InviteCode> list(String status, int page, int pageSize) {
        // 扫描任务是 1 分钟一次，商家点开列表时不该再看到「已过期却显示未使用」的码；
        // 这条 UPDATE 幂等，执行代价可忽略。
        inviteCodeMapper.markExpired();
        Page<InviteCode> pager = new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100));
        return inviteCodeMapper.selectPage(pager, Wrappers.<InviteCode>lambdaQuery()
                .eq(status != null && !status.isBlank(), InviteCode::getStatus, status)
                .orderByDesc(InviteCode::getCreatedAt));
    }

    public InviteCode findByCode(String rawCode) {
        String code = normalize(rawCode);
        if (code.isEmpty()) {
            return null;
        }
        return inviteCodeMapper.selectOne(Wrappers.<InviteCode>query().eq("code", code));
    }

    /**
     * 批量查用户行：生成人、某一页码的使用者名单合并成一次 in 查询，避免逐行查用户（N+1）。
     *
     * @return userId → 用户行；null / 0 这类无效 id 会被过滤，查不到的人（已删除）不在结果里
     */
    public Map<Long, User> usersByIds(List<Long> ids) {
        List<Long> distinct = ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        List<User> users = userMapper.selectBatchIds(distinct);
        Map<Long, User> map = new HashMap<>(users.size());
        for (User user : users) {
            map.put(user.getId(), user);
        }
        return map;
    }

    /** 本页邀请码分别是谁生成的。系统生成的 created_by=0 不会出现在结果里。 */
    public Map<Long, User> usersCreatedBy(List<InviteCode> codes) {
        return usersByIds(codes.stream().map(InviteCode::getCreatedBy).toList());
    }

    /** 某个码的使用明细，按使用时间正序（先用的排前面）。 */
    public List<InviteUse> usesOf(Long inviteCodeId) {
        if (inviteCodeId == null) {
            return List.of();
        }
        return inviteUseMapper.selectList(Wrappers.<InviteUse>lambdaQuery()
                .eq(InviteUse::getInviteCodeId, inviteCodeId)
                .orderByAsc(InviteUse::getUsedAt));
    }

    /**
     * 核销邀请码，成功即返回。
     *
     * 判定权始终在那条带条件的 UPDATE 上：先查后改在并发下会让剩余次数被超发。
     * 扣次数成功后补一条使用明细；两步在同一个事务里，明细写不进去就整笔回滚，不会出现「次数扣了没人」。
     */
    @Transactional
    public void consume(String rawCode, Long userId) {
        String code = normalize(rawCode);
        if (code.isEmpty()) {
            throw BizException.param("请输入邀请码");
        }

        int affected = inviteCodeMapper.consume(code);
        if (affected == 1) {
            // 上面刚更新成功，这一行一定在；取 id 是为了写明细
            InviteCode row = inviteCodeMapper.selectOne(Wrappers.<InviteCode>query().eq("code", code));
            InviteUse use = new InviteUse();
            use.setInviteCodeId(row.getId());
            use.setUserId(userId);
            use.setUsedAt(LocalDateTime.now());
            inviteUseMapper.insert(use);
            log.info("邀请码核销成功 code={} userId={} 已用 {}/{}",
                    code, userId, row.getUsedCount(), row.getMaxUses());
            return;
        }

        InviteCode existing = inviteCodeMapper.selectOne(Wrappers.<InviteCode>query().eq("code", code));
        if (existing == null) {
            throw BizException.notFound("邀请码不存在，请核对后重新输入");
        }
        if (InviteStatus.REVOKED.equals(existing.getStatus())) {
            throw BizException.conflict("该邀请码已被商家作废，请联系商家重新获取");
        }
        if (isExpired(existing)) {
            throw BizException.conflict("该邀请码已过期，请联系商家重新获取");
        }
        if (isUsedUp(existing)) {
            throw BizException.conflict("该邀请码次数已用完，请联系商家重新获取");
        }
        // 走到这里说明条件更新被并发抢先（别人刚把最后一次用掉），按用完提示即可
        throw BizException.conflict("该邀请码刚刚被用完，请联系商家重新获取");
    }

    /**
     * 作废邀请码。
     *
     * 只作废**还有剩余次数**的码（未使用或部分使用）：已经用完的码作废没有意义，
     * 反而会让人误以为那个人的准入被撤销。
     * 用条件更新而不是「先查后改」，避免与同一瞬间的核销打架（核销也是带条件的 UPDATE）。
     */
    @Transactional
    public InviteCode revoke(String rawCode) {
        String code = normalize(rawCode);
        if (code.isEmpty()) {
            throw BizException.param("请选择要作废的邀请码");
        }
        int affected = inviteCodeMapper.update(null, Wrappers.<InviteCode>lambdaUpdate()
                .set(InviteCode::getStatus, InviteStatus.REVOKED)
                .eq(InviteCode::getCode, code)
                .in(InviteCode::getStatus, InviteStatus.UNUSED, InviteStatus.PARTIAL));
        if (affected == 0) {
            InviteCode existing = inviteCodeMapper.selectOne(
                    Wrappers.<InviteCode>lambdaQuery().eq(InviteCode::getCode, code));
            if (existing == null) {
                throw BizException.notFound("邀请码不存在");
            }
            if (isUsedUp(existing)) {
                throw BizException.conflict("该邀请码次数已用完，无需作废（已凭此码进来的人不受影响）");
            }
            throw BizException.conflict("该邀请码已是「" + existing.getStatus() + "」状态，无法作废");
        }
        log.info("邀请码已作废 code={}", code);
        InviteCode revoked = inviteCodeMapper.selectOne(
                Wrappers.<InviteCode>lambdaQuery().eq(InviteCode::getCode, code));
        if (revoked != null) {
            auditService.record(AuditActions.TABLE_INVITE, revoked.getId(), AuditActions.INVITE_REVOKE,
                    "作废邀请码 " + code + "（已用 " + revoked.getUsedCount() + "/"
                            + revoked.getMaxUses() + " 次）");
        }
        return revoked;
    }

    /** 后台红点数：码被用过但商家还没点开过邀请码管理。口径是「码」不是「次」。 */
    public long unreadCount() {
        return inviteCodeMapper.countUnread();
    }

    /** 商家点开邀请码管理后清红点。只改 read_at，不影响核销与状态。 */
    @Transactional
    public int markRead() {
        return inviteCodeMapper.markRead();
    }

    /** 该用户是否曾用邀请码进入过。登录时判准入用，只查索引不扫表。 */
    public boolean hasConsumed(Long userId) {
        if (userId == null) {
            return false;
        }
        Long count = inviteUseMapper.selectCount(
                Wrappers.<InviteUse>query().eq("user_id", userId));
        return count != null && count > 0;
    }

    /** 把过期未用尽的码标成 expired，仅为了让后台列表看起来准。核销判定不依赖这个状态。 */
    @Transactional
    public int markExpired() {
        return inviteCodeMapper.markExpired();
    }

    private boolean isExpired(InviteCode code) {
        LocalDateTime expireAt = code.getExpireAt();
        return expireAt != null && !expireAt.isAfter(LocalDateTime.now());
    }

    private boolean isUsedUp(InviteCode code) {
        Integer used = code.getUsedCount();
        Integer max = code.getMaxUses();
        if (InviteStatus.USED.equals(code.getStatus())) {
            return true;
        }
        return used != null && max != null && used >= max;
    }

    /** 统一去空格。6 位数字没有大小写，不再做大写转换。 */
    private String normalize(String rawCode) {
        return rawCode == null ? "" : rawCode.trim();
    }

    /** 随机 6 位数字，保留前导 0（012345 也是合法码）。 */
    private String randomCode() {
        return String.format("%06d", random.nextInt(CODE_SPACE));
    }
}
