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
      <el-form-item label="未转账释放份数">
        <el-input-number v-model="form.prepayReleaseMinutes" :min="5" :max="1440" :step="5" />
        <span class="muted">分钟。下单后这么久还没点「我已转账」，份数自动释放给别人</span>
      </el-form-item>

      <el-divider content-position="left">收款</el-divider>
      <el-form-item label="配送费（元）">
        <el-input-number v-model="deliveryFeeYuan" :min="0" :precision="2" :step="1" />
        <span class="muted">0 表示免配送费</span>
      </el-form-item>
      <el-form-item label="收款码图片">
        <el-upload :show-file-list="false" :http-request="uploadQr" accept="image/*">
          <el-image v-if="form.payQrCode" :src="form.payQrCode" fit="contain" class="qr" />
          <el-button v-else>上传收款码</el-button>
        </el-upload>
        <el-button v-if="form.payQrCode" link type="danger" @click="form.payQrCode = ''">移除</el-button>
        <div class="muted">展示在用户的下单成功页与订单详情，用于转账</div>
      </el-form-item>

      <el-divider content-position="left">其他</el-divider>
      <el-form-item label="微信手机号授权">
        <el-switch v-model="phoneAuth" />
        <span class="muted">开启后用户可用微信一键授权手机号；关闭则手填</span>
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
  serveTimeText: '',
  prepayReleaseMinutes: 30,
  payQrCode: '',
});
const cutoff = ref('21:00');
const slotsText = ref('');
const deliveryFeeYuan = ref(0);
const phoneAuth = ref(false);

function load() {
  loading.value = true;
  http
    .get('/admin/config')
    .then((config) => {
      const data = config || {};
      form.value = {
        shopName: data.shopName || '',
        shopNotice: data.shopNotice || '',
        serveTimeText: data.serveTimeText || '',
        prepayReleaseMinutes: Number(data.prepayReleaseMinutes || 30),
        payQrCode: data.payQrCode || '',
      };
      cutoff.value = data.cutoffTime || '21:00';
      deliveryFeeYuan.value = Number(data.deliveryFee || 0) / 100;
      phoneAuth.value = data.phoneAuthEnabled === 'true';
      try {
        const slots = JSON.parse(data.pickupSlots || '[]');
        slotsText.value = Array.isArray(slots) ? slots.join('\n') : '';
      } catch (e) {
        slotsText.value = '';
      }
    })
    .catch(() => {})
    .finally(() => {
      loading.value = false;
    });
}

async function uploadQr(options) {
  const data = new FormData();
  data.append('file', options.file);
  const result = await http.post('/upload', data, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  form.value.payQrCode = result.url;
  ElMessage.success('已上传');
  if (options.onSuccess) {
    options.onSuccess(result);
  }
}

function onSave() {
  // 自取时段按行拆，空行丢掉；不合法的时间格式由服务端最终把关
  const slots = slotsText.value
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);

  const payload = {
    shopName: form.value.shopName || '',
    shopNotice: form.value.shopNotice || '',
    serveTimeText: form.value.serveTimeText || '',
    cutoffTime: cutoff.value || '21:00',
    pickupSlots: JSON.stringify(slots),
    deliveryFee: String(Math.round(Number(deliveryFeeYuan.value || 0) * 100)),
    payQrCode: form.value.payQrCode || '',
    phoneAuthEnabled: phoneAuth.value ? 'true' : 'false',
    prepayReleaseMinutes: String(form.value.prepayReleaseMinutes || 30),
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
