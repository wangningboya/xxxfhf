package com.xxxfhf.meal.message;

import com.xxxfhf.meal.auth.CurrentUser;
import com.xxxfhf.meal.auth.UserContext;
import com.xxxfhf.meal.common.ApiResponse;
import com.xxxfhf.meal.common.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内消息。小程序两侧与网页后台共用同一组接口。
 *
 * <p><b>不接受前端传 userId</b>：一律按当前登录人查。可见范围分两种：
 * 用户侧是「{@code user_id = 我} 且 {@code user_type = customer}」，
 * 商家侧是「{@code user_type = merchant}」的**全店共享**收件箱 ——
 * 前端只能选看哪一侧，绕不过归属校验。
 *
 * <p>不校验用户准入（不像下单那样 {@code requireAccess}）：商家账号未必是群成员，
 * 但他们的新订单消息必须能读到。
 */
@RestController
@RequestMapping("/message")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * 首页横栏与铃铛用。
     *
     * @param side user 用户侧（缺省）/ merchant 商家侧
     */
    @GetMapping("/summary")
    public ApiResponse<MessageService.Summary> summary(
            @RequestParam(required = false) String side) {
        return ApiResponse.ok(messageService.summary(UserContext.userId(), side));
    }

    /**
     * 消息中心列表，按时间倒序分页。
     *
     * @param side 同 {@link #summary(String)}
     */
    @GetMapping("/list")
    public ApiResponse<PageResult<MessageService.Row>> list(
            @RequestParam(required = false) String side,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(messageService.page(
                UserContext.userId(), side, page, pageSize));
    }

    /** 单条标已读。用户侧校验归属，商家侧是共享收件箱、任何商家账号可标，别人的消息一律 403。 */
    @PostMapping("/{id}/read")
    public ApiResponse<Void> read(@PathVariable Long id) {
        CurrentUser me = UserContext.require();
        messageService.markRead(me.userId(), me.userType(), id);
        return ApiResponse.ok();
    }

    /**
     * 扫把：当前这一侧全部未读标已读。
     *
     * @return 本次标已读的条数
     */
    @PostMapping("/read-all")
    public ApiResponse<Integer> readAll(@RequestParam(required = false) String side) {
        CurrentUser me = UserContext.require();
        return ApiResponse.ok(messageService.markAllRead(me.userId(), me.userType(), side));
    }
}
