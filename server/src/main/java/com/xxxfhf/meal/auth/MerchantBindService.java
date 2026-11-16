package com.xxxfhf.meal.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxxfhf.meal.address.Address;
import com.xxxfhf.meal.address.AddressMapper;
import com.xxxfhf.meal.common.BizException;
import com.xxxfhf.meal.order.Order;
import com.xxxfhf.meal.order.OrderMapper;
import com.xxxfhf.meal.user.AuditStatus;
import com.xxxfhf.meal.user.User;
import com.xxxfhf.meal.user.UserMapper;
import com.xxxfhf.meal.user.UserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商家绑定接收通知的微信。
 *
 * 为什么要绑：商家是账密登录，`user.openid` 是 NULL，微信服务端根本不知道往哪发订阅消息。
 * 商家在小程序里登录后，前端静默 `wx.login` 拿 code，服务端换出 openid 存到自己的账号上，
 * 之后的下单提醒、取消提醒就有明确的接收人了。
 *
 * **同一个微信只有一个 openid**（appid + 微信号决定），所以一个微信只能落在一条 user 记录上。
 * 而商家本人往往也是群成员（本来就在群里点餐），此时她的 openid 已经被那条群成员记录占用。
 * 这不是异常分支，是常态，处理方式是「合并成同一个人」：
 *
 * - 群成员记录下的历史订单与地址，归属改到商家账号（订单是本人下的，不该因为升级身份而丢失）
 * - 商家账号补上群信息（openid / 群 openGId / 昵称 / 头像 / 手机号），缺失才补，不覆盖已有值
 * - 群成员记录断开 openid 并置为 disabled，备注说明去向（不物理删除：留痕比省一行数据重要）
 *
 * 合并完成后，商家用自己的微信打开小程序会命中这条 merchant 记录，直接以商家身份进入，
 * 这就是需求 4.11 说的「openid 白名单直接进」—— 已绑定 openid 的商家账号本身就是白名单。
 */
@Service
public class MerchantBindService {

    private static final Logger log = LoggerFactory.getLogger(MerchantBindService.class);

    private final WxApiClient wxApiClient;
    private final UserMapper userMapper;
    private final OrderMapper orderMapper;
    private final AddressMapper addressMapper;

    public MerchantBindService(WxApiClient wxApiClient, UserMapper userMapper,
                               OrderMapper orderMapper, AddressMapper addressMapper) {
        this.wxApiClient = wxApiClient;
        this.userMapper = userMapper;
        this.orderMapper = orderMapper;
        this.addressMapper = addressMapper;
    }

    /**
     * 绑定当前商家账号的接收通知微信。
     *
     * @param merchantUserId 当前登录的商家账号 ID
     * @param code           小程序 wx.login 拿到的 code（一次性，5 分钟内有效）
     */
    @Transactional
    public BindResult bindOpenid(String merchantUserId, String code) {
        User merchant = merchantUserId == null ? null : userMapper.selectById(merchantUserId);
        if (merchant == null) {
            throw BizException.unauthorized("登录已失效，请重新登录");
        }
        if (!UserType.MERCHANT.equals(merchant.getUserType())) {
            throw BizException.forbidden("只有商家账号才能绑定接收通知的微信");
        }

        WxApiClient.WxSession session = wxApiClient.code2Session(code);
        String openid = session.openid();

        if (openid.equals(merchant.getOpenid())) {
            // 重复点「绑定」不该报错，直接告诉他已经是这个微信了
            return new BindResult(false);
        }

        User occupied = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getOpenid, openid)
                .last("LIMIT 1"));

        if (occupied == null) {
            setOpenid(merchant.getId(), openid);
            log.info("商家绑定微信成功 userId={}（首次绑定）", merchant.getId());
            return new BindResult(false);
        }

        if (UserType.MERCHANT.equals(occupied.getUserType())) {
            // 另一个商家账号已经占了这个微信，不能悄悄抢过来
            throw BizException.conflict("该微信已绑定到商家账号「"
                    + displayName(occupied) + "」，请先用那个账号解绑");
        }

        mergeCustomerIntoMerchant(merchant, occupied, openid);
        log.info("商家绑定微信成功 userId={} 合并群成员记录 userId={}", merchant.getId(), occupied.getId());
        return new BindResult(true);
    }

    /**
     * 把群成员记录并入商家账号。顺序不能反：先腾出 openid 再写进去，否则撞唯一索引。
     */
    private void mergeCustomerIntoMerchant(User merchant, User customer, String openid) {
        // 1. 历史订单与地址跟着人走
        orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getUserId, merchant.getId())
                .eq(Order::getUserId, customer.getId()));
        addressMapper.update(null, Wrappers.<Address>lambdaUpdate()
                .set(Address::getUserId, merchant.getId())
                .eq(Address::getUserId, customer.getId()));

        // 2. 群成员记录断开 openid 并停用（唯一索引允许多个 NULL，所以置空是安全的）
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .set(User::getOpenid, null)
                .set(User::getAuditStatus, AuditStatus.DISABLED)
                .set(User::getRejectReason, "已并入商家账号，微信通知改由商家端接收")
                .eq(User::getId, customer.getId()));

        // 3. 商家账号补全群信息。缺失才补 —— 商家自己在后台填过的昵称不该被微信昵称覆盖
        var patch = Wrappers.<User>lambdaUpdate()
                .set(User::getOpenid, openid)
                .eq(User::getId, merchant.getId());
        boolean touched = false;
        if (isBlank(merchant.getGroupOpenId()) && !isBlank(customer.getGroupOpenId())) {
            patch.set(User::getGroupOpenId, customer.getGroupOpenId());
            touched = true;
        }
        if (isBlank(merchant.getNickName()) && !isBlank(customer.getNickName())) {
            patch.set(User::getNickName, customer.getNickName());
            touched = true;
        }
        if (isBlank(merchant.getAvatarUrl()) && !isBlank(customer.getAvatarUrl())) {
            patch.set(User::getAvatarUrl, customer.getAvatarUrl());
            touched = true;
        }
        if (isBlank(merchant.getPhone()) && !isBlank(customer.getPhone())) {
            patch.set(User::getPhone, customer.getPhone());
            touched = true;
        }
        userMapper.update(null, patch);
        if (!touched) {
            log.debug("群成员记录没有可补充的资料 userId={}", customer.getId());
        }
    }

    private void setOpenid(String userId, String openid) {
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .set(User::getOpenid, openid)
                .eq(User::getId, userId));
    }

    private static String displayName(User user) {
        if (!isBlank(user.getNickName())) {
            return user.getNickName();
        }
        return isBlank(user.getUsername()) ? user.getId() : user.getUsername();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * @param mergedFromCustomer 是否顺带把一条已有的群成员记录并了进来。
     *                           前端据此提示「你之前的点餐记录已经转到商家账号下」
     */
    public record BindResult(boolean mergedFromCustomer) {
    }
}
