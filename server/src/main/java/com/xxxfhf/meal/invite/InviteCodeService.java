package com.xxxfhf.meal.invite;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xxxfhf.meal.audit.AuditActions;
import com.xxxfhf.meal.audit.AuditService;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.ErrorCode;
import com.xxxfhf.meal.user.User;
import com.xxxfhf.meal.user.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 邀请码：生成、列表、核销。
 *
 * 邀请码是准入的**正式路径**（不是降级方案）：群卡片被刷走、用户换号、卡片被转给个人时都靠它。
 */
@Service
public class InviteCodeService {

    private static final Logger log = LoggerFactory.getLogger(InviteCodeService.class);

    /**
     * 去掉了 0/O/1/I/L 这类手抄易错的字符。用户是从商家那儿口头或文字拿到码再手输，
     * 少一个易混字符就少一批「码没错但提示不存在」的客诉。
     */
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 8;
    private static final int MAX_GENERATE_RETRY = 5;

    /** 有效期上限 30 天，防止「永不过期」的码在群里长期流传 */
    private static final int MAX_EXPIRE_HOURS = 24 * 30;

    /** 一次最多生成 20 张：够一次给一个小群发，又不至于让商家手滑刷出几百张码 */
    private static final int MAX_BATCH = 20;

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final InviteCodeMapper inviteCodeMapper;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    public InviteCodeService(InviteCodeMapper inviteCodeMapper, UserMapper userMapper,
                             AuditService auditService) {
        this.inviteCodeMapper = inviteCodeMapper;
        this.userMapper = userMapper;
        this.auditService = auditService;
    }

    /**
     * 生成一次性邀请码。
     *
     * @param expireHours 有效期（小时），1 ~ 720
     * @param createdBy   生成人 userId
     */
    @Transactional
    public InviteCode create(int expireHours, Long createdBy) {
        int hours = expireHours <= 0 ? 24 : expireHours;
        if (hours > MAX_EXPIRE_HOURS) {
            throw BizException.param("有效期最长 " + MAX_EXPIRE_HOURS + " 小时（30 天）");
        }

        for (int attempt = 0; attempt < MAX_GENERATE_RETRY; attempt++) {
            InviteCode code = new InviteCode();
            code.setCode(randomCode());
            code.setStatus(InviteStatus.UNUSED);
            code.setExpireAt(LocalDateTime.now().plusHours(hours));
            code.setCreatedBy(createdBy);
            code.setCreatedAt(LocalDateTime.now());
            try {
                inviteCodeMapper.insert(code);
                // 发码等于放人进来，必须留痕：记到 invite_code 上，操作人/来源由当前请求上下文自动取
                auditService.record(AuditActions.TABLE_INVITE, code.getId(), AuditActions.INVITE_CREATE,
                        "生成邀请码 " + code.getCode() + "（有效期 " + hours + " 小时，至 "
                                + MINUTE.format(code.getExpireAt()) + "）");
                log.info("生成邀请码 code={} 有效期={}h 生成人={}", code.getCode(), hours, createdBy);
                return code;
            } catch (DuplicateKeyException ex) {
                // 撞上已存在的码，换一个再试。8 位 31 字符组合撞车概率极低，重试 5 次足够。
                log.warn("邀请码随机冲突，重试第 {} 次", attempt + 1);
            }
        }
        throw new BizException(ErrorCode.SERVER_ERROR, "邀请码生成失败，请重试");
    }

    /**
     * 批量生成。单张与批量走同一条 create，审计、撞码重试、有效期校验都只有一份规则。
     *
     * @param count 1 ~ 20，超出直接拒绝，不静默截断
     */
    @Transactional
    public List<InviteCode> createBatch(int count, int expireHours, Long createdBy) {
        if (count < 1 || count > MAX_BATCH) {
            throw BizException.param("一次最多生成 " + MAX_BATCH + " 个邀请码");
        }
        List<InviteCode> codes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            codes.add(create(expireHours, createdBy));
        }
        log.info("批量生成邀请码 {} 个 生成人={}", count, createdBy);
        return codes;
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

    /**
     * 批量查用户行：一页邀请码里的使用者、生成人合并成一次 in 查询，避免列表逐行查用户（N+1）。
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

    /** 本页邀请码里被谁用了。未使用的码不会出现在结果里，使用者已被删除的也查不到。 */
    public Map<Long, User> usersUsedBy(List<InviteCode> codes) {
        return usersByIds(codes.stream().map(InviteCode::getUsedBy).toList());
    }

    /** 本页邀请码分别是谁生成的。系统生成的 created_by=0 不会出现在结果里。 */
    public Map<Long, User> usersCreatedBy(List<InviteCode> codes) {
        return usersByIds(codes.stream().map(InviteCode::getCreatedBy).toList());
    }

    /**
     * 核销邀请码，成功即返回。
     *
     * 失败时再查一次原因——判定权始终在那条带条件的 UPDATE 上，
     * 先查后改在并发下会让同一个码被两个人用掉。
     */
    @Transactional
    public void consume(String rawCode, Long userId) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase();
        if (code.isEmpty()) {
            throw BizException.param("请输入邀请码");
        }

        int affected = inviteCodeMapper.consume(code, userId);
        if (affected == 1) {
            log.info("邀请码核销成功 code={} userId={}", code, userId);
            return;
        }

        InviteCode existing = inviteCodeMapper.selectOne(
                Wrappers.<InviteCode>query().eq("code", code));
        if (existing == null) {
            throw BizException.notFound("邀请码不存在，请核对后重新输入");
        }
        if (InviteStatus.USED.equals(existing.getStatus())) {
            throw BizException.conflict("该邀请码已被使用，请联系商家重新获取");
        }
        if (InviteStatus.REVOKED.equals(existing.getStatus())) {
            throw BizException.conflict("该邀请码已被商家作废，请联系商家重新获取");
        }
        throw BizException.conflict("该邀请码已过期，请联系商家重新获取");
    }

    /**
     * 作废邀请码。
     *
     * 只作废未使用的码：已经被人用掉的码作废没有意义，反而会让人误以为那个人的准入被撤销。
     * 用条件更新而不是「先查后改」，避免与同一瞬间的核销打架（核销也是带条件的 UPDATE）。
     */
    @Transactional
    public InviteCode revoke(String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase();
        if (code.isEmpty()) {
            throw BizException.param("请选择要作废的邀请码");
        }
        int affected = inviteCodeMapper.update(null, Wrappers.<InviteCode>lambdaUpdate()
                .set(InviteCode::getStatus, InviteStatus.REVOKED)
                .eq(InviteCode::getCode, code)
                .eq(InviteCode::getStatus, InviteStatus.UNUSED));
        if (affected == 0) {
            InviteCode existing = inviteCodeMapper.selectOne(
                    Wrappers.<InviteCode>lambdaQuery().eq(InviteCode::getCode, code));
            if (existing == null) {
                throw BizException.notFound("邀请码不存在");
            }
            if (InviteStatus.USED.equals(existing.getStatus())) {
                throw BizException.conflict("该邀请码已被使用，作废会让人误以为取消了他的准入，已阻止");
            }
            throw BizException.conflict("该邀请码已是「" + existing.getStatus() + "」状态，无需重复作废");
        }
        log.info("邀请码已作废 code={}", code);
        InviteCode revoked = inviteCodeMapper.selectOne(
                Wrappers.<InviteCode>lambdaQuery().eq(InviteCode::getCode, code));
        if (revoked != null) {
            auditService.record(AuditActions.TABLE_INVITE, revoked.getId(), AuditActions.INVITE_REVOKE,
                    "作废邀请码 " + code + "（原状态：未使用）");
        }
        return revoked;
    }

    /** 后台红点数：码被用了但商家还没点开过邀请码管理。 */
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
        Long count = inviteCodeMapper.selectCount(
                Wrappers.<InviteCode>query().eq("used_by", userId));
        return count != null && count > 0;
    }

    /** 把过期未用的码标成 expired，仅为了让后台列表看起来准。核销判定不依赖这个状态。 */
    @Transactional
    public int markExpired() {
        return inviteCodeMapper.markExpired();
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
