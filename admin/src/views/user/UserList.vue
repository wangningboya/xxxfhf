<template>
  <div class="page-card">
    <!-- 用户管理双页签：微信用户（原审核列表）/ 商家用户（账号管理），与「账号授权」共用接口 -->
    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane label="微信用户" name="customer">
    <div class="toolbar">
      <el-select v-model="auditStatus" placeholder="全部状态" clearable style="width: 140px" @change="reload">
        <el-option
          v-for="item in auditOptions"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="昵称或手机号"
        clearable
        style="width: 200px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-button @click="reload">查询</el-button>
      <div class="toolbar-right">
        <el-button
          v-if="auth.has('user.audit')"
          type="primary"
          :disabled="selected.length === 0"
          @click="onBatchApprove"
        >
          批量通过（{{ selected.length }}）
        </el-button>
        <el-button v-if="auth.has('user.audit')" @click="onCreateInvite">生成邀请码</el-button>
        <!-- 红点：有码被用了但还没点开看过。点开后清零，不影响码本身 -->
        <el-badge
          v-if="auth.has('user.audit')"
          :value="inviteUnread"
          :hidden="!inviteUnread"
          :max="99"
          class="invite-badge"
        >
          <el-button @click="openInvites">邀请码管理</el-button>
        </el-badge>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      border
      size="small"
      style="width: 100%"
      @selection-change="(v) => (selected = v)"
    >
      <el-table-column
        type="selection"
        width="44"
        :selectable="(row) => row.auditStatus === 'pending' && row.canAudit"
      />
      <el-table-column label="用户" min-width="160">
        <template #default="{ row }">
          <div class="user">
            <el-avatar :size="28" :src="row.avatarUrl">{{ (row.nickName || '?').slice(0, 1) }}</el-avatar>
            <div>
              <div>{{ row.nickName || '未填昵称' }}</div>
              <div class="muted">{{ row.userId }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="手机号" width="150">
        <template #default="{ row }">
          <span>{{ row.phone || '未填' }}</span>
          <!-- 代填：老人不会用微信授权、或者手填时打错一位，都靠这里改 -->
          <el-button
            v-if="auth.has('user.audit')"
            link
            type="primary"
            class="phone-edit"
            @click="openPhone(row)"
          >
            改
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="准入方式" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">
            {{ row.accessVia === 'invite' ? '邀请码' : row.accessVia === 'group' ? '群入口' : '—' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.auditStatus)" size="small">{{ statusLabel(row.auditStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="驳回原因" min-width="140">
        <template #default="{ row }">{{ row.rejectReason || '—' }}</template>
      </el-table-column>
      <el-table-column label="申请时间" width="160">
        <template #default="{ row }">{{ (row.createdAt || '').replace('T', ' ').slice(0, 16) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <template v-if="auth.has('user.audit')">
            <!-- 没填昵称的人列表照常出现，但不给通过/驳回：商家认不出是谁，点了也只能靠猜 -->
            <span v-if="needNickName(row)" class="muted">待对方填昵称</span>
            <template v-else>
              <el-button v-if="row.auditStatus === 'pending'" link type="primary" @click="onAudit(row, 'approve')">
                通过
              </el-button>
              <el-button v-if="row.auditStatus === 'pending'" link type="danger" @click="onAudit(row, 'reject')">
                驳回
              </el-button>
              <el-button v-if="row.auditStatus === 'rejected'" link type="primary" @click="onAudit(row, 'approve')">
                重新通过
              </el-button>
            </template>
            <el-button
              v-if="row.auditStatus === 'approved'"
              link
              type="danger"
              @click="onAudit(row, 'disable')"
            >
              停用
            </el-button>
            <el-button v-if="row.auditStatus === 'disabled'" link type="primary" @click="onAudit(row, 'enable')">
              启用
            </el-button>
          </template>
          <!-- 指定为商家：就地升级 customer → merchant（需求 4.17），与日常审核同权限域 -->
          <el-button v-if="auth.has('user.audit')" link type="warning" @click="openPromote(row)">
            指定为商家
          </el-button>
          <!-- 删除是物理删除、不可恢复，单独挂 user.delete，不跟审核混一个权限 -->
          <el-button
            v-if="auth.has('user.delete')"
            link
            type="danger"
            @click="onDelete(row)"
          >
            删除
          </el-button>
          <span v-if="!auth.has('user.audit') && !auth.has('user.delete')" class="muted">只读</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="load"
      />
    </div>
      </el-tab-pane>

      <el-tab-pane label="商家用户" name="merchant">
        <div class="muted merchant-hint">
          与「角色权限 → 账号授权」共用同一套数据与规则；这里与账号授权都支持分配角色、解除锁定。
        </div>
        <el-table v-loading="merchantLoading" :data="merchants" border size="small" style="width: 100%">
          <el-table-column prop="username" label="登录账号" width="150" />
          <el-table-column prop="displayName" label="显示名" min-width="140" />
          <el-table-column label="后台角色" min-width="220">
            <template #default="{ row }">
              <el-tag
                v-for="name in row.roleNames"
                :key="name"
                size="small"
                effect="plain"
                class="role-tag"
              >
                {{ name }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="statusType(row.auditStatus)" size="small">{{ statusLabel(row.auditStatus) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="锁定" width="200">
            <template #default="{ row }">
              <template v-if="row.lockUntil">
                <el-tag size="small" type="danger">锁定至 {{ fmtTime(row.lockUntil) }}</el-tag>
                <el-button
                  v-if="auth.has('user.audit')"
                  link
                  type="primary"
                  class="unlock-btn"
                  @click="onUnlock(row)"
                >
                  解锁
                </el-button>
              </template>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="160">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="170" fixed="right">
            <template #default="{ row }">
              <template v-if="auth.has('user.audit')">
                <el-button link type="primary" @click="openAssign(row)">分配角色</el-button>
                <!-- 改密：这套系统没有短信通道，忘了密码只能由有 user.audit 的人重置 -->
                <el-button link type="primary" @click="openPassword(row)">改密</el-button>
              </template>
              <span v-else class="muted">只读</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 邀请码管理：发错群时能立刻作废，不用等它过期；使用者与生成人都要看得见，否则对不上人 -->
    <el-dialog v-model="inviteVisible" title="邀请码管理" width="900px">
      <div class="invite-head">
        <el-select v-model="inviteStatus" placeholder="全部状态" clearable style="width: 140px" @change="loadInvites">
          <el-option
            v-for="item in inviteOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <span class="muted">作废只对还有剩余次数的码有效；已用完的码作废会让人以为准入被撤销</span>
      </div>
      <el-table v-loading="inviteLoading" :data="invites" border size="small" style="width: 100%">
        <el-table-column prop="code" label="邀请码" width="170">
          <template #default="{ row }">
            <code>{{ row.code }}</code>
            <el-button link type="primary" class="copy-link" @click="onCopyInviteCode(row.code)">
              复制
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="inviteTag(row.status)">{{ inviteLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="已用次数" width="110">
          <template #default="{ row }">
            <span :class="{ muted: !row.usedCount }">{{ row.usedCount }} / {{ row.maxUses }}</span>
          </template>
        </el-table-column>
        <el-table-column label="生成人" width="110">
          <template #default="{ row }">
            <span :class="{ muted: !row.createdByName }">{{ row.createdByName || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="有效期至" width="150">
          <template #default="{ row }">{{ (row.expireAt || '').replace('T', ' ').slice(0, 16) }}</template>
        </el-table-column>
        <el-table-column label="最近使用" width="150">
          <template #default="{ row }">{{ (row.lastUsedAt || '—').replace('T', ' ').slice(0, 16) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button
              v-if="canRevokeInvite(row)"
              link
              type="danger"
              @click="onRevoke(row)"
            >
              作废
            </el-button>
            <el-button
              link
              type="primary"
              :disabled="!row.usedCount"
              @click="openInviteUses(row)"
            >
              使用者
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 生成表单：一个码带次数，填 20 次就是这张码能被 20 个人各用一次 -->
    <el-dialog v-model="inviteFormVisible" title="生成邀请码" width="440px">
      <el-form label-width="80px">
        <el-form-item label="可用次数">
          <el-input-number v-model="inviteForm.uses" :min="1" :step="1" />
          <span class="muted form-hint">这张码可被多少人各用一次</span>
        </el-form-item>
        <el-form-item label="有效期">
          <el-input-number v-model="inviteForm.expireHours" :min="1" :max="720" :step="24" />
          <span class="muted form-hint">小时（1-720）</span>
        </el-form-item>
      </el-form>
      <div class="muted">只生成 1 个码；用户凭码进入后直接通过审核。</div>
      <template #footer>
        <el-button @click="inviteFormVisible = false">取消</el-button>
        <el-button type="primary" :loading="inviteCreating" @click="onSubmitInvites">生成</el-button>
      </template>
    </el-dialog>

    <!-- 生成结果：只有一个码，确认键直接当复制用 -->
    <el-dialog
      v-model="inviteCreatedVisible"
      title="邀请码已生成"
      width="480px"
      @closed="inviteCreatedList = []"
    >
      <div class="invite-created-list">
        <div
          v-for="item in inviteCreatedList"
          :key="item.code"
          class="invite-created-row single"
        >
          <code class="invite-created-code">{{ item.code }}</code>
          <div class="muted invite-created-exp">
            可用 {{ item.maxUses }} 次，已用 {{ item.usedCount }} 次
          </div>
          <div class="muted invite-created-exp">
            有效期至 {{ (item.expireAt || '').replace('T', ' ').slice(0, 16) }}
          </div>
        </div>
      </div>
      <div class="muted">用户凭码进入后直接通过审核；谁用过可在「邀请码管理 → 使用者」里看。</div>
      <template #footer>
        <el-button @click="inviteCreatedVisible = false">关闭</el-button>
        <el-button type="primary" @click="onCopyAllInvites">复制</el-button>
      </template>
    </el-dialog>

    <!-- 使用者名单：一个码对应多人，只在网页后台看，小程序端只显示已用 X/N -->
    <el-dialog
      v-model="inviteUsesVisible"
      :title="'使用者 — ' + inviteUsesCode"
      width="520px"
    >
      <el-table v-loading="inviteUsesLoading" :data="inviteUses" border size="small" style="width: 100%">
        <el-table-column label="使用者" min-width="160">
          <template #default="{ row }">
            <div>{{ row.name }}</div>
            <div v-if="row.phone" class="muted">{{ row.phone }}</div>
          </template>
        </el-table-column>
        <el-table-column label="使用时间" width="160">
          <template #default="{ row }">{{ (row.usedAt || '').replace('T', ' ').slice(0, 16) }}</template>
        </el-table-column>
      </el-table>
      <div class="muted invite-uses-foot">共 {{ inviteUses.length }} 人用过这张码</div>
    </el-dialog>

    <!-- 代填 / 修改手机号：接口早就有了，之前两端都只能看不能改 -->
    <el-dialog v-model="phoneVisible" title="修改手机号" width="420px">
      <el-form label-width="80px">
        <el-form-item label="用户">
          <span>{{ phoneRow ? phoneRow.nickName || phoneRow.userId : '' }}</span>
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="phoneValue" maxlength="20" placeholder="11 位大陆手机号" clearable />
        </el-form-item>
      </el-form>
      <div class="muted">
        仅用于订单联系，不用于营销；用户可在小程序「我的」页查看和修改。下单时会把手机号快照进订单，改这里不影响历史订单。
        保存与查看完整号码都会记一条操作记录。
      </div>
      <template #footer>
        <el-button @click="phoneVisible = false">取消</el-button>
        <el-button type="primary" :loading="phoneSaving" @click="onSavePhone">保存</el-button>
      </template>
    </el-dialog>

    <!-- 指定为商家：就地升级，openid / 昵称 / 订单 / 地址全保留；升级后他从列表消失（不再是顾客） -->
    <el-dialog v-model="promoteVisible" title="指定为商家" width="460px">
      <el-form label-width="90px">
        <el-form-item label="用户">
          <span>{{ promoteRow ? promoteRow.nickName || promoteRow.userId : '' }}</span>
        </el-form-item>
        <el-form-item label="登录账号" required>
          <el-input
            v-model="promoteForm.username"
            maxlength="64"
            placeholder="2 ~ 64 字符，全库唯一"
            clearable
          />
        </el-form-item>
        <el-form-item label="登录密码" required>
          <el-input
            v-model="promoteForm.password"
            type="password"
            show-password
            maxlength="64"
            placeholder="6 ~ 64 位"
            clearable
          />
        </el-form-item>
        <el-form-item label="后台角色" required>
          <el-select
            v-model="promoteForm.roleIds"
            multiple
            placeholder="至少选一个"
            style="width: 100%"
          >
            <el-option v-for="item in roleOptions" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="muted">
        该用户会被就地升级为商家账号：微信身份、昵称、订单、地址全部保留；审核状态一律置为「已通过」。
        升级后他将从本列表消失，用微信打开小程序时「我的」页会出现商家工作台入口。本期不提供「取消商家」。
      </div>
      <template #footer>
        <el-button @click="promoteVisible = false">取消</el-button>
        <el-button type="primary" :loading="promoteSaving" @click="onSubmitPromote">确定</el-button>
      </template>
    </el-dialog>

    <!-- 分配角色：与「账号授权」同一接口同一规则（POST /admin/role/assign） -->
    <el-dialog v-model="assignVisible" title="分配角色" width="460px">
      <el-form label-width="90px">
        <el-form-item label="账号">
          <span>{{ assignRow ? assignRow.username + (assignRow.displayName ? '（' + assignRow.displayName + '）' : '') : '' }}</span>
        </el-form-item>
        <el-form-item label="后台角色" required>
          <el-select v-model="assignRoleIds" multiple placeholder="至少选一个" style="width: 100%">
            <el-option v-for="item in roleOptions" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="muted">
        至少保留一个角色，否则该账号登录后没有任何功能可用；不能修改自己的角色（防自锁）。角色改完即时生效，权限在服务端按库里数据判定。
      </div>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" :loading="assignSaving" @click="onSubmitAssign">保存</el-button>
      </template>
    </el-dialog>

    <!--
      改密（P1-3 / F-J12）：新密码 + 确认新密码，6~64 位。
      服务端 BCrypt 落库、明文不回显不记日志；改密同时解开该账号的登录锁定。
    -->
    <el-dialog v-model="passwordVisible" title="修改登录密码" width="440px">
      <el-form label-width="96px">
        <el-form-item label="账号">
          <span>{{ passwordRow ? passwordRow.username + (passwordRow.displayName ? '（' + passwordRow.displayName + '）' : '') : '' }}</span>
        </el-form-item>
        <el-form-item label="新密码" required>
          <el-input
            v-model="passwordForm.password"
            type="password"
            show-password
            maxlength="64"
            placeholder="6 ~ 64 位"
          />
        </el-form-item>
        <el-form-item label="确认新密码" required>
          <el-input
            v-model="passwordForm.confirm"
            type="password"
            show-password
            maxlength="64"
            placeholder="再输一次"
          />
        </el-form-item>
      </el-form>
      <div class="muted">
        改完立即生效，请当面或单独告知该账号；改密会同时解除该账号的登录锁定。
      </div>
      <template #footer>
        <el-button @click="passwordVisible = false">取消</el-button>
        <el-button type="primary" :loading="passwordSaving" @click="onSubmitPassword">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import http from '../../api';
import { useAuthStore } from '../../stores/auth';
import { useDictStore } from '../../stores/dict';

const auth = useAuthStore();
const dict = useDictStore();

const rows = ref([]);
const total = ref(0);
const page = ref(1);
const pageSize = 20;
const loading = ref(false);
/** 字典没加载出来时的兜底；加载完成后以字典为准 */
const FALLBACK_AUDIT = [
  { value: 'pending', label: '待审核' },
  { value: 'approved', label: '已通过' },
  { value: 'rejected', label: '已驳回' },
  { value: 'disabled', label: '已停用' },
];
const FALLBACK_INVITE = [
  { value: 'unused', label: '未使用' },
  { value: 'partial', label: '部分使用' },
  { value: 'used', label: '已用完' },
  { value: 'expired', label: '已过期' },
  { value: 'revoked', label: '已作废' },
];

const auditOptions = computed(() => dict.options('audit_status', FALLBACK_AUDIT));
const inviteOptions = computed(() => dict.options('invite_status', FALLBACK_INVITE));

const auditStatus = ref('');
const keyword = ref('');
const selected = ref([]);

function statusLabel(value) {
  const fallback =
    { pending: '待审核', approved: '已通过', rejected: '已驳回', disabled: '已停用' }[value] || value;
  return dict.label('audit_status', value, fallback);
}

function statusType(value) {
  return dict.tagType('audit_status', value);
}

function load() {
  loading.value = true;
  http
    .get('/admin/user/list', {
      params: {
        auditStatus: auditStatus.value || undefined,
        keyword: keyword.value || undefined,
        page: page.value,
        pageSize,
      },
    })
    .then((result) => {
      rows.value = (result && result.list) || [];
      total.value = (result && result.total) || 0;
    })
    .catch(() => {})
    .finally(() => {
      loading.value = false;
    });
}

function reload() {
  page.value = 1;
  load();
}

/* ==================== 代填手机号 ==================== */

const phoneVisible = ref(false);
const phoneRow = ref(null);
const phoneValue = ref('');
const phoneSaving = ref(false);

function openPhone(row) {
  phoneRow.value = row;
  // 列表里是掩码（138****1234），回填明文走「查看完整手机号」接口：
  // 该接口独立权限 user.phone.view，且每次调用写一条操作记录
  phoneValue.value = '';
  phoneVisible.value = true;
  http
    .get(`/admin/user/${row.userId}/phone`)
    .then((phone) => {
      // 用户可能在请求返回前换了人，别把 A 的号填进 B 的弹窗
      if (phoneRow.value === row) {
        phoneValue.value = phone || '';
      }
    })
    .catch(() => {
      // 没权限或还没填号：留空，直接输新号也能保存
    });
}

function onSavePhone() {
  const phone = (phoneValue.value || '').trim();
  // 与服务端同一套规则：11 位大陆手机号，不校验归属
  if (!/^1\d{10}$/.test(phone)) {
    ElMessage.error('手机号应为 11 位，以 1 开头');
    return;
  }
  phoneSaving.value = true;
  http
    .post('/admin/user/phone', { userId: phoneRow.value.userId, phone })
    .then(() => {
      ElMessage.success('已保存');
      phoneVisible.value = false;
      load();
    })
    .catch(() => {})
    .finally(() => {
      phoneSaving.value = false;
    });
}

/* ==================== 删除用户 ==================== */

/**
 * 物理删除，不可恢复。服务端还有两道闸：名下有订单会拒删、只接受 customer 类型。
 * 删完如果这页被删空且不在第一页，回退一页，免得停在一张空表上。
 */
function onDelete(row) {
  const name = row.nickName || row.userId;
  ElMessageBox.confirm('将永久删除「' + name + '」及其名下收货地址，不可恢复。', '删除用户', {
    type: 'warning',
    confirmButtonText: '删除',
    confirmButtonClass: 'el-button--danger',
  })
    .then(() => {
      http
        .post('/admin/user/delete', { userId: row.userId })
        .then(() => {
          ElMessage.success('已删除');
          if (rows.value.length === 1 && page.value > 1) {
            page.value -= 1;
          }
          load();
        })
        .catch(() => {});
    })
    .catch(() => {});
}

/** 没填昵称的人不能通过/驳回：审核列表里认不出是谁，先让对方补昵称 */
function needNickName(row) {
  return (row.auditStatus === 'pending' || row.auditStatus === 'rejected') && !row.canAudit;
}

function onAudit(row, action) {
  // 兜底：后端也会拦，这里先给一句人话，省得用户看到红字报错
  if ((action === 'approve' || action === 'reject') && !row.canAudit) {
    ElMessage.warning('对方还没填昵称，暂时不能审核；请让对方先在「我的」页填写昵称');
    return;
  }
  if (action === 'reject') {
    ElMessageBox.prompt('驳回原因会展示给用户，请写清楚', '驳回申请', {
      inputPlaceholder: '如：不是本群同事，请说明身份',
      inputValidator: (value) => (value && value.trim() ? true : '必须填写驳回原因'),
    })
      .then(({ value }) => {
        http
          .post('/admin/user/audit', { userId: row.userId, action, reason: value.trim() })
          .then(() => {
            ElMessage.success('已驳回');
            load();
          })
          .catch(() => {});
      })
      .catch(() => {});
    return;
  }

  const label = { approve: '通过', disable: '停用', enable: '启用' }[action];
  ElMessageBox.confirm('确定' + label + '「' + (row.nickName || row.userId) + '」？', label)
    .then(() => {
      http
        .post('/admin/user/audit', { userId: row.userId, action })
        .then(() => {
          ElMessage.success('已' + label);
          load();
        })
        .catch(() => {});
    })
    .catch(() => {});
}

/** 批量通过：逐条发请求，单条失败不阻塞其余，最后汇总给一个结果 */
async function onBatchApprove() {
  const users = selected.value.filter((row) => row.auditStatus === 'pending' && row.canAudit);
  if (users.length === 0) {
    return;
  }
  try {
    await ElMessageBox.confirm('确定通过选中的 ' + users.length + ' 位用户？', '批量通过');
  } catch (e) {
    // 用户取消，什么都不做
    return;
  }

  let ok = 0;
  const failed = [];
  for (const user of users) {
    try {
      await http.post('/admin/user/audit', { userId: user.userId, action: 'approve' });
      ok += 1;
    } catch (e) {
      failed.push(user.nickName || user.userId);
    }
  }
  ElMessage.success('已通过 ' + ok + ' 位');
  if (failed.length) {
    ElMessageBox.alert('以下用户未通过：' + failed.join('、'), '部分失败', { type: 'warning' })
      // 同生成邀请码的结果提示：点 × 关闭会 reject，需接住避免全局兜底误报
      .catch(() => {});
  }
  load();
}

/* ==================== 邀请码：生成 / 复制 / 红点 ==================== */

const inviteFormVisible = ref(false);
const inviteCreating = ref(false);
const inviteForm = ref({ uses: 1, expireHours: 168 });
const inviteCreatedVisible = ref(false);
const inviteCreatedList = ref([]);
const inviteUnread = ref(0);
let invitePollTimer = null;

function onCreateInvite() {
  inviteForm.value = { uses: 1, expireHours: 168 };
  inviteFormVisible.value = true;
}

function onSubmitInvites() {
  const uses = Number(inviteForm.value.uses);
  const expireHours = Number(inviteForm.value.expireHours);
  // 与服务端同一套边界，先给一句人话，省一次往返
  if (!Number.isInteger(uses) || uses < 1) {
    ElMessage.warning('可用次数需为 1 以上的整数');
    return;
  }
  if (!(expireHours >= 1 && expireHours <= 720)) {
    ElMessage.warning('有效期需在 1 到 720 小时之间');
    return;
  }
  inviteCreating.value = true;
  http
    .post('/admin/invite/create', { uses, expireHours })
    .then((code) => {
      inviteCreatedList.value = code ? [code] : [];
      inviteFormVisible.value = false;
      inviteCreatedVisible.value = true;
    })
    .catch(() => {})
    .finally(() => {
      inviteCreating.value = false;
    });
}

/**
 * 复制文本。navigator.clipboard 只在 https / localhost 下存在，
 * 商家后台常部署在 http 内网，所以保留 textarea + execCommand 兜底。
 */
async function copyText(text) {
  if (!text) {
    return false;
  }
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch (e) {
    // 权限被拒或浏览器不支持，走下面的兜底
  }
  const area = document.createElement('textarea');
  area.value = text;
  area.setAttribute('readonly', '');
  area.style.position = 'fixed';
  area.style.top = '-1000px';
  document.body.appendChild(area);
  area.select();
  let ok = false;
  try {
    ok = document.execCommand('copy');
  } catch (e) {
    ok = false;
  }
  document.body.removeChild(area);
  return ok;
}

async function onCopyInviteCode(code) {
  const ok = await copyText(code);
  if (ok) {
    ElMessage.success('已复制，发给用户即可');
  } else {
    ElMessage.warning('复制失败，请手动选中邀请码复制');
  }
}

async function onCopyAllInvites() {
  // 只有一个码，按钮就叫「复制」；方法名保留，省得再改模板
  const text = inviteCreatedList.value.map((item) => item.code).join('\n');
  const ok = await copyText(text);
  if (ok) {
    ElMessage.success('已复制，发给用户即可');
  } else {
    ElMessage.warning('复制失败，请手动选中邀请码复制');
  }
}

/** 红点：多少张码被用了但还没点开看过。登录后每分钟刷一次。 */
function loadInviteUnread() {
  if (!auth.has('user.audit')) {
    return;
  }
  http
    .get('/admin/invite/unread-count')
    .then((count) => {
      inviteUnread.value = Number(count) || 0;
    })
    .catch(() => {});
}

const inviteVisible = ref(false);
const inviteLoading = ref(false);
const inviteStatus = ref('');
const invites = ref([]);

function openInvites() {
  inviteVisible.value = true;
  loadInvites();
  // 打开列表就等于已经看到提醒：清红点。清失败不打扰商家，下次打开再试
  http
    .post('/admin/invite/mark-read')
    .then(() => {
      inviteUnread.value = 0;
    })
    .catch(() => {});
}

function loadInvites() {
  inviteLoading.value = true;
  http
    .get('/admin/invite/list', {
      params: { status: inviteStatus.value || undefined, page: 1, pageSize: 50 },
    })
    .then((result) => {
      invites.value = (result && result.list) || [];
    })
    .catch(() => {})
    .finally(() => {
      inviteLoading.value = false;
    });
}

function inviteLabel(status) {
  const fallback =
    { unused: '未使用', partial: '部分使用', used: '已用完', expired: '已过期', revoked: '已作废' }[status] || status;
  return dict.label('invite_status', status, fallback);
}

function inviteTag(status) {
  return dict.tagType('invite_status', status);
}

/** 还有剩余次数的码才能作废；服务端同样按这个条件兜底 */
function canRevokeInvite(row) {
  return row.status === 'unused' || row.status === 'partial';
}

const inviteUsesVisible = ref(false);
const inviteUsesLoading = ref(false);
const inviteUsesCode = ref('');
const inviteUses = ref([]);

/** 使用者名单按需拉：不塞进列表接口，否则一页码要连带几百行明细 */
function openInviteUses(row) {
  inviteUsesCode.value = row.code;
  inviteUses.value = [];
  inviteUsesVisible.value = true;
  inviteUsesLoading.value = true;
  http
    .get('/admin/invite/uses', { params: { code: row.code } })
    .then((list) => {
      inviteUses.value = list || [];
    })
    .catch(() => {})
    .finally(() => {
      inviteUsesLoading.value = false;
    });
}

function onRevoke(row) {
  const left = Math.max((row.maxUses || 0) - (row.usedCount || 0), 0);
  ElMessageBox.confirm(
    `作废后邀请码 ${row.code} 剩余 ${left} 次立即失效，已凭它进来的人不受影响，确定吗？`,
    '作废邀请码',
  )
    .then(() => {
      http
        .post('/admin/invite/revoke', { code: row.code })
        .then(() => {
          ElMessage.success('已作废');
          loadInvites();
        })
        .catch(() => {});
    })
    .catch(() => {});
}

/* ==================== 指定为商家 ==================== */

/**
 * 就地升级 customer → merchant（需求 4.17）。
 * 角色选项来自 /admin/user/role-options（挂 user.audit，不要求 role.manage）。
 * 列表只查 customer，升级成功后该行自动消失，刷新即可看到结果。
 */
const promoteVisible = ref(false);
const promoteRow = ref(null);
const promoteForm = ref({ username: '', password: '', roleIds: [] });
const promoteSaving = ref(false);
const roleOptions = ref([]);
let roleOptionsLoaded = false;

/** 角色选项：第一次用到时拉一次，指定为商家与分配角色两个弹窗共用 */
function loadRoleOptions() {
  if (roleOptionsLoaded) {
    return;
  }
  http
    .get('/admin/user/role-options')
    .then((list) => {
      roleOptions.value = list || [];
      roleOptionsLoaded = true;
    })
    .catch(() => {});
}

function openPromote(row) {
  promoteRow.value = row;
  promoteForm.value = { username: '', password: '', roleIds: [] };
  promoteVisible.value = true;
  loadRoleOptions();
}

function onSubmitPromote() {
  const form = promoteForm.value;
  // 与服务端同一套规则，先给一句人话
  const username = (form.username || '').trim();
  if (username.length < 2 || username.length > 64) {
    ElMessage.warning('登录账号长度须在 2 ~ 64 字符之间');
    return;
  }
  const password = form.password || '';
  if (password.length < 6 || password.length > 64) {
    ElMessage.warning('登录密码长度须在 6 ~ 64 位之间');
    return;
  }
  if (!form.roleIds || form.roleIds.length === 0) {
    ElMessage.warning('请至少选择一个后台角色');
    return;
  }
  const name = promoteRow.value.nickName || promoteRow.value.userId;
  promoteSaving.value = true;
  http
    .post('/admin/user/promote', {
      userId: promoteRow.value.userId,
      username,
      password,
      roleIds: form.roleIds,
    })
    .then(() => {
      ElMessage.success('已将「' + name + '」指定为商家');
      promoteVisible.value = false;
      load();
    })
    .catch(() => {})
    .finally(() => {
      promoteSaving.value = false;
    });
}

/* ==================== 商家用户页签 ==================== */

/**
 * 商家账号列表：数据来自 /admin/user/merchants（复用角色域账号聚合）。
 * 懒加载——第一次切到本页签才请求；分配角色与解锁走 /admin/role/*，
 * 权限已放宽为 role.manage / user.audit 任一（D-28）。
 */
const tab = ref('customer');
const merchants = ref([]);
const merchantLoading = ref(false);
let merchantsLoaded = false;

function loadMerchants() {
  merchantLoading.value = true;
  http
    .get('/admin/user/merchants')
    .then((list) => {
      merchants.value = list || [];
      merchantsLoaded = true;
    })
    .catch(() => {})
    .finally(() => {
      merchantLoading.value = false;
    });
}

function onTabChange(name) {
  if (name === 'merchant' && !merchantsLoaded) {
    loadMerchants();
  }
}

function fmtTime(value) {
  return (value || '').replace('T', ' ').slice(0, 16);
}

/* ---------------- 分配角色 ---------------- */

const assignVisible = ref(false);
const assignRow = ref(null);
const assignRoleIds = ref([]);
const assignSaving = ref(false);

function openAssign(row) {
  assignRow.value = row;
  assignRoleIds.value = [...(row.roleIds || [])];
  assignVisible.value = true;
  loadRoleOptions();
}

function onSubmitAssign() {
  if (!assignRoleIds.value || assignRoleIds.value.length === 0) {
    ElMessage.warning('请至少选择一个后台角色');
    return;
  }
  assignSaving.value = true;
  http
    .post('/admin/role/assign', { userId: assignRow.value.userId, roleIds: assignRoleIds.value })
    .then(() => {
      ElMessage.success('已更新「' + (assignRow.value.displayName || assignRow.value.username) + '」的角色');
      assignVisible.value = false;
      loadMerchants();
    })
    .catch(() => {})
    .finally(() => {
      assignSaving.value = false;
    });
}

/* ---------------- 改密 ---------------- */

const passwordVisible = ref(false);
const passwordRow = ref(null);
const passwordForm = ref({ password: '', confirm: '' });
const passwordSaving = ref(false);

function openPassword(row) {
  passwordRow.value = row;
  passwordForm.value = { password: '', confirm: '' };
  passwordVisible.value = true;
}

function onSubmitPassword() {
  const pwd = passwordForm.value.password || '';
  if (pwd.length < 6 || pwd.length > 64) {
    ElMessage.warning('密码长度须在 6 ~ 64 位之间');
    return;
  }
  if (pwd !== passwordForm.value.confirm) {
    ElMessage.warning('两次输入的密码不一致');
    return;
  }
  passwordSaving.value = true;
  http
    .post('/admin/user/password', { userId: passwordRow.value.userId, password: pwd })
    .then(() => {
      ElMessage.success('已修改「' + (passwordRow.value.displayName || passwordRow.value.username) + '」的密码');
      passwordVisible.value = false;
      // 明文不在前端留任何痕迹
      passwordForm.value = { password: '', confirm: '' };
      loadMerchants();
    })
    .catch(() => {})
    .finally(() => {
      passwordSaving.value = false;
    });
}

/* ---------------- 解除锁定 ---------------- */

function onUnlock(row) {
  ElMessageBox.confirm(
    '解除「' + (row.displayName || row.username) + '」的登录锁定？',
    '解除锁定'
  )
    .then(() => http.post('/admin/role/unlock', { userId: row.userId }))
    .then(() => {
      ElMessage.success('已解除锁定');
      loadMerchants();
    })
    .catch(() => {});
}

onMounted(() => {
  load();
  loadInviteUnread();
  // 一分钟刷一次：商家开着后台时，有人用码也能及时看到红点
  invitePollTimer = window.setInterval(loadInviteUnread, 60_000);
});

onUnmounted(() => {
  if (invitePollTimer) {
    window.clearInterval(invitePollTimer);
  }
});
</script>

<style scoped>
.user {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 手机号旁的「改」：贴得太近会看不清是按钮，留一点间距 */
.phone-edit {
  margin-left: 6px;
}

/* 商家页签的说明行与角色标签的间距 */
.merchant-hint {
  margin-bottom: 10px;
}

.role-tag {
  margin-right: 6px;
}

.unlock-btn {
  margin-left: 6px;
}

/* 邀请码管理按钮上的红点，与左边按钮留出间距 */
.invite-badge {
  margin-left: 12px;
}

/* 表格里的复制按钮贴得太近会看不清 */
.copy-link {
  margin-left: 8px;
}

.form-hint {
  margin-left: 8px;
}

/* 生成结果：码可整段选中；多张时列表内部滚动，不撑高弹窗 */
.invite-created-list {
  max-height: 320px;
  overflow-y: auto;
}

.invite-created-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 4px;
  border-bottom: 1px dashed #ebeef5;
}

.invite-created-row:last-child {
  border-bottom: none;
}

.invite-created-code {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 2px;
  user-select: all;
}

/* 只生成一张时放大，方便一眼核对 */
.invite-created-row.single .invite-created-code {
  font-size: 30px;
  letter-spacing: 4px;
}

.invite-created-exp {
  flex: 1;
}

.invite-uses-foot {
  margin-top: 8px;
}
</style>
