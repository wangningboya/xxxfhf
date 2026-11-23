<template>
  <div class="page-card">
    <el-form v-loading="loading" :model="form" label-width="130px" style="max-width: 720px">
      <el-divider content-position="left">店铺信息</el-divider>
      <el-form-item label="店铺名称">
        <el-input v-model="form.shopName" maxlength="30" placeholder="首页展示" />
      </el-form-item>
      <el-form-item label="店铺公告">
        <el-input v-model="form.shopNotice" type="textarea" :rows="2" maxlength="120" placeholder="首页滚动展示" />
      </el-form-item>
      <el-form-item label="店铺地址（自取点）">
        <el-input v-model="form.shopAddress" maxlength="120" placeholder="到店取餐的地址，下单成功页与自取订单详情展示" />
      </el-form-item>
      <el-form-item label="商家联系电话">
        <el-input v-model="form.shopPhone" maxlength="20" placeholder="11 位手机号或带区号座机，用户可一键拨打；留空不显示" />
      </el-form-item>

      <el-divider content-position="left">供餐与截单</el-divider>
      <el-form-item label="供餐时段">
        <el-input v-model="form.serveTimeText" maxlength="30" placeholder="如：午饭前 / 11:30 送达" />
      </el-form-item>
      <el-form-item label="截单时间">
        <el-time-picker
          v-model="cutoff"
          format="HH:mm"
          value-format="HH:mm"
          placeholder="选择时间"
          :clearable="false"
        />
        <span class="muted">供餐日的前一天这个点截止下单，全局一套</span>
      </el-form-item>
      <el-form-item label="自取可选时段">
        <el-input
          v-model="slotsText"
          type="textarea"
          :rows="4"
          placeholder="一行一个，如&#10;11:00-11:30&#10;11:30-12:00"
        />
        <div class="muted">用户下自取单时必须选一个；不限每个时段的份数，只做登记与备菜分组</div>
      </el-form-item>
      <el-form-item label="未付款释放份数">
        <el-input-number v-model="form.prepayReleaseMinutes" :min="5" :max="1440" :step="5" />
        <span class="muted">分钟。下单后这么久还没点「我已付款」，份数自动释放给别人</span>
      </el-form-item>
      <el-form-item label="取消申请超时">
        <el-input-number v-model="form.cancelRequestTimeoutHours" :min="0" :max="72" :step="1" />
        <span class="muted">小时。截单后用户申请退订，商家这么久没处理就按同意自动取消。<b>填 0 关闭自动同意</b>，只能人工处理</span>
      </el-form-item>

      <el-divider content-position="left">准入</el-divider>
      <el-form-item label="群白名单">
        <el-input
          v-model="groupIdsText"
          type="textarea"
          :rows="4"
          placeholder="一行一个 groupOpenId，如&#10;Gxxxxxxxxxxxxxxxx"
        />
        <div class="muted">
          只允许这里登记过的微信群进入，改完<b>即时生效</b>，无需重启。
          <b>留空表示不认任何群</b>，此时只能靠邀请码进入 —— 新群启用前记得先把标识贴进来，否则群里的人全部会被挡在引导页
        </div>
      </el-form-item>

      <el-divider content-position="left">配送与收款</el-divider>
      <el-form-item label="配送费（元）">
        <el-input-number v-model="deliveryFeeYuan" :min="0" :precision="2" :step="1" />
        <span class="muted">0 表示免配送费</span>
      </el-form-item>

      <el-divider content-position="left">订阅消息</el-divider>
      <el-form-item label="消息模板">
        <el-input
          v-model="templatesText"
          type="textarea"
          :rows="8"
          placeholder='{&#10;  "newOrder": {&#10;    "templateId": "模板ID",&#10;    "page": "pages/merchant/order-detail?orderId={orderId}",&#10;    "fields": { "character_string1": "orderNo", "thing2": "summary" }&#10;  }&#10;}'
        />
        <div class="muted">
          键为通知类型：<code>newOrder</code> / <code>accepted</code> / <code>rejected</code> /
          <code>ready</code> / <code>cancelRequest</code> / <code>cancelled</code>。
          <b>填 `{}` 表示不发通知</b>，系统照常运行；模板 ID 需先在微信公众平台申请
        </div>
      </el-form-item>

      <el-form-item>
        <el-button type="primary" :loading="saving" @click="onSave">保存配置</el-button>
        <el-button @click="load">重置</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import http from '../../api';

const loading = ref(false);
const saving = ref(false);

const form = ref({
  shopName: '',
  shopNotice: '',
  shopAddress: '',
  shopPhone: '',
  serveTimeText: '',
  prepayReleaseMinutes: 30,
  cancelRequestTimeoutHours: 2,
});
const cutoff = ref('21:00');
const slotsText = ref('');
const deliveryFeeYuan = ref(0);
const groupIdsText = ref('');
const templatesText = ref('{}');

function load() {
  loading.value = true;
  http
    .get('/admin/config')
    .then((config) => {
      const data = config || {};
      // 0 是有效值（表示关闭自动同意），所以不能用 `|| 2` 兜底，只有缺键才给默认 2
      const timeout = data.cancelRequestTimeoutHours;
      form.value = {
        shopName: data.shopName || '',
        shopNotice: data.shopNotice || '',
        shopAddress: data.shopAddress || '',
        shopPhone: data.shopPhone || '',
        serveTimeText: data.serveTimeText || '',
        prepayReleaseMinutes: Number(data.prepayReleaseMinutes || 30),
        cancelRequestTimeoutHours:
          timeout === undefined || timeout === null || timeout === '' ? 2 : Number(timeout),
      };
      cutoff.value = data.cutoffTime || '21:00';
      deliveryFeeYuan.value = Number(data.deliveryFee || 0) / 100;
      try {
        const slots = JSON.parse(data.pickupSlots || '[]');
        slotsText.value = Array.isArray(slots) ? slots.join('\n') : '';
      } catch (e) {
        slotsText.value = '';
      }
      // 群白名单与订阅模板都是 JSON，编辑时降级成文本；解析失败就留空，
      // 保存时按 `[]` / `{}` 重写 —— 不能把解析不了的原文原样回写
      try {
        const groups = JSON.parse(data.groupOpenIds || '[]');
        groupIdsText.value = Array.isArray(groups) ? groups.join('\n') : '';
      } catch (e) {
        groupIdsText.value = '';
      }
      templatesText.value = prettyJson(data.subscribeTemplates, {});
    })
    .catch(() => {})
    .finally(() => {
      loading.value = false;
    });
}

/** 配置里存的是压缩过的 JSON，展示时缩进一下，否则一行长串没法改 */
function prettyJson(raw, fallback) {
  if (!raw || !String(raw).trim()) {
    return JSON.stringify(fallback, null, 2);
  }
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch (e) {
    return String(raw);
  }
}

function onSave() {
  // 自取时段按行拆，空行丢掉；不合法的时间格式由服务端最终把关
  const slots = slotsText.value
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);

  // 群白名单同理；空列表也要存成 `[]` 而不是空串，读的一侧才解析得出来
  const groups = groupIdsText.value
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);

  // 订阅模板必须是合法 JSON 对象，格式错了会在服务端被静默当成"没配"，
  // 表现成"通知不发但也不报错"，所以这里先挡一次
  let templates = '{}';
  if (templatesText.value && templatesText.value.trim()) {
    try {
      const parsed = JSON.parse(templatesText.value);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('not object');
      }
      templates = JSON.stringify(parsed);
    } catch (e) {
      ElMessage.error('消息模板不是合法的 JSON 对象，请检查大括号与引号');
      return;
    }
  }

  // 联系电话与服务端 PhoneValidator.requireShopPhone 同源：留空表示不展示拨号入口
  const shopPhone = (form.value.shopPhone || '').trim();
  if (shopPhone && !/^(1[3-9]\d{9}|0\d{2,3}-?\d{7,8})$/.test(shopPhone)) {
    ElMessage.error('联系电话请填 11 位手机号或带区号的座机（如 010-12345678）');
    return;
  }

  const payload = {
    shopName: form.value.shopName || '',
    shopNotice: form.value.shopNotice || '',
    shopAddress: (form.value.shopAddress || '').trim(),
    shopPhone,
    serveTimeText: form.value.serveTimeText || '',
    cutoffTime: cutoff.value || '21:00',
    pickupSlots: JSON.stringify(slots),
    deliveryFee: String(Math.round(Number(deliveryFeeYuan.value || 0) * 100)),
    prepayReleaseMinutes: String(form.value.prepayReleaseMinutes || 30),
    cancelRequestTimeoutHours: String(form.value.cancelRequestTimeoutHours || 0),
    groupOpenIds: JSON.stringify(groups),
    subscribeTemplates: templates,
  };

  saving.value = true;
  http
    .post('/admin/config', payload)
    .then(() => {
      ElMessage.success('已保存');
      load();
    })
    .catch(() => {})
    .finally(() => {
      saving.value = false;
    });
}

onMounted(load);
</script>

<style scoped>
.qr {
  width: 120px;
  height: 120px;
  border-radius: 6px;
  display: block;
  border: 1px solid #eef0f3;
}
</style>
