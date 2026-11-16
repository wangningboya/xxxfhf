package com.xxxfhf.meal.invite;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

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

    private final InviteCodeMapper inviteCodeMapper;
    private final SecureRandom random = new SecureRandom();

    public InviteCodeService(InviteCodeMapper inviteCodeMapper) {
        this.inviteCodeMapper = inviteCodeMapper;
    }

    /**
     * 生成一次性邀请码。
     *
     * @param expireHours 有效期（小时），1 ~ 720
     * @param createdBy   生成人 userId
     */
    @Transactional
    public InviteCode create(int expireHours, String createdBy) {
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
                log.info("生成邀请码 code={} 有效期={}h 生成人={}", code.getCode(), hours, createdBy);
                return code;
            } catch (DuplicateKeyException ex) {
                // 撞上已存在的码，换一个再试。8 位 31 字符组合撞车概率极低，重试 5 次足够。
                log.warn("邀请码随机冲突，重试第 {} 次", attempt + 1);
            }
        }
        throw new BizException(ErrorCode.SERVER_ERROR, "邀请码生成失败，请重试");
    }

    public IPage<InviteCode> list(String status, int page, int pageSize) {
        Page<InviteCode> pager = new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100));
        return inviteCodeMapper.selectPage(pager, Wrappers.<InviteCode>lambdaQuery()
                .eq(status != null && !status.isBlank(), InviteCode::getStatus, status)
                .orderByDesc(InviteCode::getCreatedAt));
    }

    /**
     * 核销邀请码，成功即返回。
     *
     * 失败时再查一次原因——判定权始终在那条带条件的 UPDATE 上，
     * 先查后改在并发下会让同一个码被两个人用掉。
     */
    @Transactional
    public void consume(String rawCode, String userId) {
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
        throw BizException.conflict("该邀请码已过期，请联系商家重新获取");
    }

    /** 该用户是否曾用邀请码进入过。登录时判准入用，只查索引不扫表。 */
    public boolean hasConsumed(String userId) {
        if (userId == null || userId.isBlank()) {
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
