package com.xxxfhf.meal.order;

import com.xxxfhf.meal.user.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自取单快照拼装（隐私整改 P0-08）。
 *
 * 自取单不再要求账号手机号：没号也能下单，快照里 phone 为 null，
 * 履约靠 4 位取餐码；联系人仍写昵称，备菜表能对上人。
 */
class OrderServiceSnapshotTest {

    private static User user(String nickName, String phone) {
        User user = new User();
        user.setNickName(nickName);
        user.setPhone(phone);
        return user;
    }

    @Test
    void pickupSnapshotKeepsNickNameAndPhone() {
        AddressSnapshot snapshot = OrderService.pickupSnapshot(user("张三", "13800138000"));

        assertThat(snapshot.contactName()).isEqualTo("张三");
        assertThat(snapshot.phone()).isEqualTo("13800138000");
        assertThat(snapshot.detail()).isNull();
        assertThat(snapshot.regionName()).isNull();
        assertThat(snapshot.tag()).isNull();
    }

    @Test
    void pickupSnapshotAllowsMissingPhone() {
        AddressSnapshot snapshot = OrderService.pickupSnapshot(user("张三", null));

        assertThat(snapshot.contactName()).isEqualTo("张三");
        assertThat(snapshot.phone()).isNull();
    }

    @Test
    void pickupSnapshotTrimsBlankPhoneToNull() {
        AddressSnapshot snapshot = OrderService.pickupSnapshot(user("张三", "   "));

        assertThat(snapshot.phone()).isNull();
    }

    @Test
    void pickupSnapshotOfMissingUserIsNull() {
        assertThat(OrderService.pickupSnapshot(null)).isNull();
    }
}
