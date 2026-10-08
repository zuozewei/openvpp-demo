<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const moduleTitle = computed(() => route.meta.title || '当前模块')
</script>

<template>
  <el-card>
    <el-empty description="功能建设中">
      <template #default>
        <p class="placeholder-text">「{{ moduleTitle }}」模块建设中，待后端接口联调完成后开放。</p>
      </template>
    </el-empty>
  </el-card>
</template>

<style scoped>
.placeholder-text {
  text-align: center;
  color: #909399;
  font-size: 14px;
}
</style>
