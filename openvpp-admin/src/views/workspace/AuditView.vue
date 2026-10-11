<script setup>
import { onMounted, ref } from 'vue'
import { listAuditLogs } from '../../api'
import { fmtMs } from '../../utils/format'

const loading = ref(false)
const auditLogs = ref([])

async function loadData() {
  loading.value = true
  try {
    auditLogs.value = await listAuditLogs()
  } catch {
    // 统一错误提示已在请求层处理
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>

<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span>操作审计（平台侧运营能力）</span>
        <el-button size="small" @click="loadData">刷新</el-button>
      </div>
    </template>

    <el-alert type="info" :closable="false" class="page-tip">
      每次写操作留痕：账号/角色/动作/对象/版本/结果，SUCCESS 与 REJECTED 分列；本入口仅平台角色可见（服务端拦截，越角色 401）。
    </el-alert>

    <el-table v-loading="loading" :data="auditLogs" style="width: 100%">
      <el-table-column label="时间" min-width="155">
        <template #default="{ row }">{{ fmtMs(row.createdMs) }}</template>
      </el-table-column>
      <el-table-column prop="accountId" label="账号" width="130" show-overflow-tooltip />
      <el-table-column prop="role" label="角色" width="100" />
      <el-table-column prop="action" label="动作" width="180" show-overflow-tooltip />
      <el-table-column prop="targetType" label="对象类型" width="150" show-overflow-tooltip />
      <el-table-column prop="targetId" label="对象标识" min-width="150" show-overflow-tooltip />
      <el-table-column prop="targetVersion" label="版本" width="80" />
      <el-table-column label="结果" width="100">
        <template #default="{ row }">
          <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" size="small">
            {{ row.result === 'SUCCESS' ? '成功' : '拒绝' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="detail" label="明细" min-width="220" show-overflow-tooltip />
      <template #empty>
        <el-empty description="暂无审计记录" />
      </template>
    </el-table>
  </el-card>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-tip {
  margin-bottom: 12px;
}
</style>
