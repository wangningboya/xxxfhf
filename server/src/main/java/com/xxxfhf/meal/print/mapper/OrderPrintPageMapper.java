package com.xxxfhf.meal.print.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xxxfhf.meal.print.domain.OrderPrintPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface OrderPrintPageMapper extends BaseMapper<OrderPrintPage> {

    /**
     * 批量取「每单打成功了几张、最后一次什么时候」。
     *
     * <p>列表页一次查 20 单，不能逐单 select count —— 那是 20 次查询。
     * 也不在这里算总张数：总张数来自订单明细（Σ qty），属于订单模块的口径，
     * 放在这里会让「应打几张」有两处定义。
     *
     * @return 每行 {orderId, printed, lastAt}；没打过的单不出现在结果里
     */
    @Select("""
            <script>
            SELECT order_id AS orderId, COUNT(*) AS printed, MAX(created_at) AS lastAt
            FROM order_print_page
            WHERE result = 'success'
              AND order_id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY order_id
            </script>
            """)
    List<Map<String, Object>> statsByOrderIds(@Param("ids") List<Long> ids);

    /**
     * 某单的打印记录，按批聚合，最近 10 批。
     *
     * <p>详情页只回答「谁在什么时候用哪台机器打了多少张、成没成」，一张一行太碎，
     * 一次作业一行才看得出节奏。同一批里可能既有成功又有失败（打到第 3 张缺纸），
     * 所以同时给总张数、成功张数与第一条失败的错误码，前端才能把「3 张里成了 2 张」说清楚。
     */
    @Select("""
            SELECT batch_no AS batchNo,
                   MAX(created_at) AS createdAt,
                   MAX(operator_name) AS operatorName,
                   MAX(device_name) AS deviceName,
                   COUNT(*) AS copies,
                   SUM(CASE WHEN result = 'success' THEN 1 ELSE 0 END) AS successCopies,
                   MIN(CASE WHEN result = 'fail' THEN err_code END) AS failErrCode
            FROM order_print_page
            WHERE order_id = #{orderId}
            GROUP BY batch_no
            ORDER BY createdAt DESC
            LIMIT 10
            """)
    List<Map<String, Object>> recentBatches(@Param("orderId") Long orderId);
}
