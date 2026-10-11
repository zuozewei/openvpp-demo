<script setup>
import { dictLabel, dictType } from '../api/dicts'

const props = defineProps({
  dict: { type: Object, required: true },
  code: { type: [String, Boolean, Number], default: '' }
})

const normalized = String(props.code ?? '')
</script>

<template>
  <el-tag :type="dictType(dict, normalized)" disable-transitions>
    {{ dictLabel(dict, normalized) }}
  </el-tag>
</template>
