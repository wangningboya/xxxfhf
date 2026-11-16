<template>
  <div class="login-page">
    <div class="card">
      <div class="title">点餐后台</div>
      <div class="sub">商家账号登录</div>
      <el-form :model="form" @submit.prevent>
        <el-form-item>
          <el-input v-model="form.username" placeholder="账号" size="large" clearable />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            show-password
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="onSubmit">
          登录
        </el-button>
      </el-form>
      <div class="hint">忘记密码请联系店主在系统里重置</div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '../stores/auth';

const router = useRouter();
const auth = useAuthStore();

const form = reactive({ username: '', password: '' });
const loading = ref(false);

async function onSubmit() {
  if (!form.username.trim()) {
    ElMessage.warning('请输入账号');
    return;
  }
  if (!form.password) {
    ElMessage.warning('请输入密码');
    return;
  }
  loading.value = true;
  try {
    const result = await auth.login(form.username.trim(), form.password);
    if (!result.permissions || result.permissions.length === 0) {
      ElMessage.warning('该账号还没有分配角色，登录后看不到任何功能，请联系店主');
    }
    router.push('/');
  } catch (e) {
    // 错误提示已由请求拦截器统一弹出
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2a37 0%, #2f3b4a 100%);
}

.card {
  width: 360px;
  background: #fff;
  border-radius: 12px;
  padding: 32px 28px 24px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.18);
}

.title {
  font-size: 20px;
  font-weight: 700;
  color: #1f2a37;
  text-align: center;
}

.sub {
  font-size: 13px;
  color: #9aa1ab;
  text-align: center;
  margin: 6px 0 24px;
}

.submit {
  width: 100%;
  margin-top: 4px;
}

.hint {
  margin-top: 16px;
  font-size: 12px;
  color: #b0b6bf;
  text-align: center;
}
</style>
