package com.xxxfhf.meal.menu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xxxfhf.meal.menu.domain.DailyMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

@Mapper
public interface DailyMenuMapper extends BaseMapper<DailyMenu> {

    /**
     * **原子占份**（技术文档 4.3）。返回受影响行数：1 = 占到，0 = 已售罄。
     *
     * 判断放在 WHERE 里，而不是「先查剩余再更新」—— 两个人同时下最后一单时，
     * 先查再改会让两份都通过，这是超卖的唯一来源。
     *
     * limit_qty IS NULL 表示不限量，此时不参与比较。
     */
    @Update("""
            UPDATE daily_menu
               SET sold_qty = sold_qty + #{qty}
             WHERE serve_date = #{serveDate}
               AND combo_id = #{comboId}
               AND (limit_qty IS NULL OR sold_qty + #{qty} <= limit_qty)
            """)
    int occupy(@Param("serveDate") LocalDate serveDate,
               @Param("comboId") Long comboId,
               @Param("qty") int qty);

    /**
     * 释放份数。拒单、用户取消、超时未付款三个入口共用。
     *
     * GREATEST(..., 0) 是防御：重复释放绝不能把 sold_qty 压成负数，
     * 否则剩余份数会凭空多出来，等于白送。
     */
    @Update("""
            UPDATE daily_menu
               SET sold_qty = GREATEST(sold_qty - #{qty}, 0)
             WHERE serve_date = #{serveDate}
               AND combo_id = #{comboId}
            """)
    int release(@Param("serveDate") LocalDate serveDate,
                @Param("comboId") Long comboId,
                @Param("qty") int qty);
}
