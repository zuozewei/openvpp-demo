<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import * as echarts from 'echarts'
import { getSession } from '../../stores/session'

const route = useRoute()
const session = getSession()

const roleName = computed(() => {
  const record = route.matched.find((item) => item.meta.roleName)
  return record ? record.meta.roleName : '工作台'
})

const chainSteps = [
  '平台发布需求响应事件',
  '场站申报可响应容量',
  '平台分配运营商',
  '运营商派单到充电桩',
  '模拟设备执行与遥测上报',
  '响应效果评估',
  '多级分账结算'
]

// 演示图表数据：虚构的近 7 日需求响应事件数，仅用于验证 ECharts 集成
const chartData = {
  days: ['10-02', '10-03', '10-04', '10-05', '10-06', '10-07', '10-08'],
  eventCounts: [3, 5, 2, 6, 4, 7, 5]
}

const chartRef = ref(null)
let chartInstance = null

function renderChart() {
  if (!chartRef.value) {
    return
  }
  chartInstance = echarts.init(chartRef.value)
  chartInstance.setOption({
    title: {
      text: '近 7 日需求响应事件数（演示数据）',
      left: 'center',
      textStyle: { fontSize: 14 }
    },
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, bottom: 30, top: 40 },
    xAxis: {
      type: 'category',
      data: chartData.days
    },
    yAxis: {
      type: 'value',
      minInterval: 1
    },
    series: [
      {
        name: '事件数',
        type: 'bar',
        data: chartData.eventCounts,
        itemStyle: { color: '#409eff' }
      }
    ]
  })
}

function handleResize() {
  if (chartInstance) {
    chartInstance.resize()
  }
}

onMounted(() => {
  renderChart()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
})
</script>

<template>
  <el-row :gutter="16">
    <el-col :span="14">
      <el-card>
        <template #header>欢迎使用{{ roleName }}</template>
        <p>当前登录：{{ session.displayName }}（角色：{{ session.roleName }}）</p>
        <p>业务链路（后续篇目逐段落地）：</p>
        <el-steps :active="chainSteps.length" direction="vertical">
          <el-step v-for="(step, index) in chainSteps" :key="index" :title="step" />
        </el-steps>
      </el-card>
    </el-col>
    <el-col :span="10">
      <el-card>
        <template #header>运营概览</template>
        <div ref="chartRef" class="overview-chart" />
      </el-card>
    </el-col>
  </el-row>
</template>

<style scoped>
.overview-chart {
  height: 320px;
}
</style>
